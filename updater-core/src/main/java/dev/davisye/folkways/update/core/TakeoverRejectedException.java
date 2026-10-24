package dev.davisye.folkways.update.core;

import java.io.IOException;

/** Fixed, path-free data for a one-time takeover confirmation error. */
public final class TakeoverRejectedException extends IOException {
    public enum Reason { UNSELECTED_MODULE, COMPOSITE_OR_NESTED, UNSUPPORTED_CHANNEL }
    public final String id;
    public final Reason reason;
    TakeoverRejectedException(String id, Reason reason) {
        super("One-time takeover rejected: " + reason + (id.isEmpty() ? "" : " (" + id + ")"));
        this.id = id; this.reason = reason;
    }
}
