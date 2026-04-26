package fr.courel.lammclones.scan;

import java.nio.file.Path;
import java.util.List;

public record DuplicateGroup(long sizeBytes, List<Path> files) {

    public long wastedBytes() {
        return sizeBytes * (files.size() - 1);
    }
}
