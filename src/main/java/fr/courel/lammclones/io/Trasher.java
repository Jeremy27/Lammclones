package fr.courel.lammclones.io;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Déplace des fichiers vers la corbeille du système.
 *
 * <p>Stratégie : essaie d'abord {@link Desktop#moveToTrash(File)} (Mac/Windows
 * et Linux quand l'intégration AWT/GVFS est OK), puis fallback sur {@code gio trash}
 * sous Linux/GNOME — souvent indispensable car le check Java
 * {@code isSupported(MOVE_TO_TRASH)} renvoie {@code false} dans une app JavaFX-only.
 */
public final class Trasher {

    private static final boolean IS_LINUX =
        System.getProperty("os.name", "").toLowerCase().contains("linux");

    private static volatile Boolean gioCached;

    private Trasher() {}

    public static boolean isAvailable() {
        return desktopAvailable() || (IS_LINUX && gioAvailable());
    }

    public static boolean moveToTrash(File file) {
        if (desktopAvailable()) {
            try {
                if (Desktop.getDesktop().moveToTrash(file)) {
                    return true;
                }
            } catch (Exception ignored) {
                // fall through to gio
            }
        }
        if (IS_LINUX && gioAvailable()) {
            return runGioTrash(file);
        }
        return false;
    }

    private static boolean desktopAvailable() {
        try {
            return Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean gioAvailable() {
        Boolean cached = gioCached;
        if (cached != null) return cached;
        boolean ok;
        try {
            var process = new ProcessBuilder("gio", "version")
                .redirectErrorStream(true).start();
            ok = process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            ok = false;
        }
        gioCached = ok;
        return ok;
    }

    private static boolean runGioTrash(File file) {
        try {
            var process = new ProcessBuilder("gio", "trash", file.getAbsolutePath())
                .redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
