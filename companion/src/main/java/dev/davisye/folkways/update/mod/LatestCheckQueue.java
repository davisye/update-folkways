package dev.davisye.folkways.update.mod;

import java.util.concurrent.Executor;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One active check; repeated requests coalesce into a check of the latest saved settings. */
final class LatestCheckQueue<S, R> {
    private final Executor executor;
    @FunctionalInterface interface CommitAction { void run() throws Exception; }
    interface Ticket extends BooleanSupplier { void commit(CommitAction action) throws Exception; }
    private final BiFunction<S, Ticket, R> check;
    private final Consumer<S> requested;
    private final Consumer<R> publish;
    private final Function<S, ?> identity;
    private long revision;
    private boolean running;
    private S latest;

    LatestCheckQueue(Executor executor, BiFunction<S, Ticket, R> check,
                     Consumer<S> requested, Consumer<R> publish) {
        this(executor, check, requested, publish, Function.identity());
    }

    LatestCheckQueue(Executor executor, BiFunction<S, Ticket, R> check,
                     Consumer<S> requested, Consumer<R> publish, Function<S, ?> identity) {
        this.executor = executor; this.check = check; this.requested = requested; this.publish = publish;
        this.identity = identity;
    }

    synchronized boolean request(S settings) {
        Objects.requireNonNull(settings);
        if (running && latest != null && Objects.equals(identity.apply(latest), identity.apply(settings))) return false;
        latest = settings;
        revision++;
        requested.accept(settings);
        if (running) return false;
        running = true;
        executor.execute(this::run);
        return true;
    }

    synchronized boolean running() { return running; }
    /** Revoke before saving, even when that save later fails. Never restart the old authorization. */
    synchronized void invalidate() { latest = null; revision++; }
    private synchronized boolean current(long ticket) { return revision == ticket; }
    private synchronized void commit(long ticket, CommitAction action) throws Exception {
        if (revision != ticket) throw new IllegalStateException("Updater check request was superseded by newer settings");
        action.run();
    }

    private void run() {
        S settings; long ticket;
        synchronized (this) {
            settings = latest; ticket = revision;
            if (settings == null) { running = false; return; }
        }
        try {
            R result = check.apply(settings, new Ticket() {
                public boolean getAsBoolean() { return current(ticket); }
                public void commit(CommitAction action) throws Exception { LatestCheckQueue.this.commit(ticket, action); }
            });
            synchronized (this) { if (ticket == revision && result != null) publish.accept(result); }
        } finally {
            synchronized (this) {
                if (ticket != revision && latest != null) executor.execute(this::run);
                else running = false;
            }
        }
    }
}
