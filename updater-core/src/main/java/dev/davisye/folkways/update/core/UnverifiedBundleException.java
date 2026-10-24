package dev.davisye.folkways.update.core;
import java.io.IOException;

/** Safe structured diagnostic: no filesystem paths, download URLs or remote response text. */
public final class UnverifiedBundleException extends IOException {
    public final String id, version;
    UnverifiedBundleException(String id, String version) {
        super("Installed dependency cannot be verified against the bundled artifact: " + id + " " + version);
        this.id = id; this.version = version;
    }
}
