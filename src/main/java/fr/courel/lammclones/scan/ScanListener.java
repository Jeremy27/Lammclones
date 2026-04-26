package fr.courel.lammclones.scan;

public interface ScanListener {

    /** Progress fraction in [0,1], or -1 for indeterminate. */
    void onProgress(double fraction, String message);

    /** Called when a file can't be read. Implementations may log or ignore. */
    default void onFileError(String path, Throwable error) {}

    /** Polled by the scanner between files; returning true aborts the scan. */
    default boolean isCancelled() {
        return false;
    }
}
