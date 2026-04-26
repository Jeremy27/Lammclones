package fr.courel.lammclones.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DuplicateScanner {

    private static final int PARTIAL_HASH_BYTES = 64 * 1024;
    private static final int READ_BUFFER = 64 * 1024;
    private static final String HASH_ALGO = "SHA-256";

    public ScanResult scan(Path root, ScanListener listener) {
        return scan(List.of(root), listener);
    }

    public ScanResult scan(List<Path> roots, ScanListener listener) {
        long startNs = System.nanoTime();

        listener.onProgress(-1, "Parcours des dossiers…");
        Map<Long, List<Path>> bySize = new HashMap<>();
        int filesScanned = walkAndGroupBySize(roots, listener, bySize);

        if (listener.isCancelled()) return null;

        var sizeCandidates = flattenGroups(bySize);
        Map<String, List<Path>> byPartial = new HashMap<>();
        hashCandidates(sizeCandidates, byPartial, PARTIAL_HASH_BYTES,
            "Hash partiel", 0.0, 0.5, listener);

        if (listener.isCancelled()) return null;

        var partialCandidates = flattenGroups(byPartial);
        Map<String, List<Path>> byFull = new HashMap<>();
        hashCandidates(partialCandidates, byFull, Long.MAX_VALUE,
            "Hash complet", 0.5, 1.0, listener);

        if (listener.isCancelled()) return null;

        var duplicates = buildGroups(byFull, listener);
        long wasted = duplicates.stream().mapToLong(DuplicateGroup::wastedBytes).sum();
        long durationMs = (System.nanoTime() - startNs) / 1_000_000;
        listener.onProgress(1.0, "Terminé.");
        return new ScanResult(duplicates, filesScanned, wasted, durationMs);
    }

    private int walkAndGroupBySize(List<Path> roots, ScanListener listener, Map<Long, List<Path>> bySize) {
        int count = 0;
        Set<Path> seen = new HashSet<>();
        for (Path root : roots) {
            if (listener.isCancelled()) return count;
            try (var stream = Files.walk(root)) {
                for (var iter = stream.iterator(); iter.hasNext(); ) {
                    if (listener.isCancelled()) return count;
                    Path p = iter.next();
                    try {
                        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                        Path canonical = p.toAbsolutePath().normalize();
                        if (!seen.add(canonical)) continue;
                        long size = Files.size(p);
                        if (size <= 0) continue;
                        bySize.computeIfAbsent(size, k -> new ArrayList<>()).add(p);
                        count++;
                        if (count % 200 == 0) {
                            listener.onProgress(-1, count + " fichiers découverts…");
                        }
                    } catch (IOException e) {
                        listener.onFileError(p.toString(), e);
                    }
                }
            } catch (IOException e) {
                listener.onFileError(root.toString(), e);
            }
        }
        return count;
    }

    private List<Path> flattenGroups(Map<?, List<Path>> groups) {
        var out = new ArrayList<Path>();
        for (var list : groups.values()) {
            if (list.size() >= 2) out.addAll(list);
        }
        return out;
    }

    private void hashCandidates(List<Path> candidates, Map<String, List<Path>> out,
                                long maxBytes, String phaseLabel,
                                double fromFraction, double toFraction,
                                ScanListener listener) {
        int total = candidates.size();
        if (total == 0) return;
        int done = 0;
        for (Path p : candidates) {
            if (listener.isCancelled()) return;
            try {
                String hash = hash(p, maxBytes);
                out.computeIfAbsent(hash, k -> new ArrayList<>()).add(p);
            } catch (IOException | NoSuchAlgorithmException e) {
                listener.onFileError(p.toString(), e);
            }
            done++;
            double frac = fromFraction + (toFraction - fromFraction) * ((double) done / total);
            listener.onProgress(frac, phaseLabel + " " + done + "/" + total);
        }
    }

    private String hash(Path file, long maxBytes) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance(HASH_ALGO);
        byte[] buf = new byte[READ_BUFFER];
        long remaining = maxBytes;
        try (InputStream in = Files.newInputStream(file)) {
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, toRead);
                if (n <= 0) break;
                digest.update(buf, 0, n);
                remaining -= n;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<DuplicateGroup> buildGroups(Map<String, List<Path>> byFull, ScanListener listener) {
        var out = new ArrayList<DuplicateGroup>();
        for (var entry : byFull.entrySet()) {
            var files = entry.getValue();
            if (files.size() < 2) continue;
            try {
                long size = Files.size(files.get(0));
                out.add(new DuplicateGroup(size, List.copyOf(files)));
            } catch (IOException e) {
                listener.onFileError(files.get(0).toString(), e);
            }
        }
        out.sort((a, b) -> Long.compare(b.wastedBytes(), a.wastedBytes()));
        return out;
    }
}
