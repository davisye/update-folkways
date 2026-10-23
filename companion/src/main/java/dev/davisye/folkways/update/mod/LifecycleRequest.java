package dev.davisye.folkways.update.mod;

import java.util.function.BooleanSupplier;

/** One graceful lifecycle action at a time; evaluated on the server thread. */
public final class LifecycleRequest {
    public enum Action { SHUTDOWN, RESTART }
    private Action pending;
    private long deadline;

    public synchronized boolean schedule(Action action, long now, int seconds, BooleanSupplier restartAvailable) {
        if (pending != null || seconds < 0 || seconds > 300) return false;
        if (action == Action.RESTART && !restartAvailable.getAsBoolean()) return false;
        pending = action;
        deadline = Math.addExact(now, seconds * 1_000_000_000L);
        return true;
    }

    public synchronized Action due(long now) {
        return pending != null && now >= deadline ? pending : null;
    }

    public synchronized boolean cancel() {
        boolean existed = pending != null;
        pending = null;
        return existed;
    }
}
