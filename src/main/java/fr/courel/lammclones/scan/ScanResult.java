package fr.courel.lammclones.scan;

import java.util.List;

public record ScanResult(
    List<DuplicateGroup> duplicates,
    int filesScanned,
    long wastedBytes,
    long durationMillis
) {}
