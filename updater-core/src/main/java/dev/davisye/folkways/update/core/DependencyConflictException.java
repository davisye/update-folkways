package dev.davisye.folkways.update.core;

import java.io.IOException;

/** Safe structured data for the settings screen; no filesystem paths or server response text. */
public final class DependencyConflictException extends IOException {
    public final String id, version, requiredRange;
    DependencyConflictException(String id, String version, String requiredRange) {
        super("Installed dependency " + id + " " + version + " does not satisfy " + requiredRange + "; it will not be overwritten");
        this.id = id; this.version = version; this.requiredRange = requiredRange;
    }
}
