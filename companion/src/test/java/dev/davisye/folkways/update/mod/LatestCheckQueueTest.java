package dev.davisye.folkways.update.mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LatestCheckQueueTest {
    @Test void requestsBeforeWorkerStartsOnlyCheckLatestSelection() {
        var tasks = new ArrayDeque<Runnable>(); var checked = new ArrayList<String>(); var results = new ArrayList<String>();
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            checked.add(source); return source;
        }, ignored -> {}, results::add);
        assertTrue(queue.request("IzakyL")); assertFalse(queue.request("davisye"));
        assertEquals(1, tasks.size()); tasks.remove().run();
        assertEquals(List.of("davisye"), checked); assertEquals(checked, results); assertFalse(queue.running());
    }

    @Test void busySourceSwitchRunsLatestNextAndSuppressesOldResult() {
        var tasks = new ArrayDeque<Runnable>(); var checked = new ArrayList<String>(); var results = new ArrayList<String>();
        var ref = new AtomicReference<LatestCheckQueue<String, String>>();
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            checked.add(source);
            if (source.equals("IzakyL")) {
                assertTrue(current.getAsBoolean());
                assertFalse(ref.get().request("davisye/intermediate"));
                assertFalse(ref.get().request("davisye/latest"));
                assertFalse(current.getAsBoolean());
            }
            return source;
        }, ignored -> {}, results::add);
        ref.set(queue); queue.request("IzakyL"); tasks.remove().run();
        assertTrue(queue.running()); assertTrue(results.isEmpty()); assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(List.of("IzakyL", "davisye/latest"), checked);
        assertEquals(List.of("davisye/latest"), results); assertFalse(queue.running());
    }

    @Test void repeatedBusyChecksOfSameSettingsReuseActiveRound() {
        var tasks = new ArrayDeque<Runnable>(); var results = new ArrayList<String>();
        var ref = new AtomicReference<LatestCheckQueue<String, String>>();
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            assertFalse(ref.get().request(source)); assertTrue(current.getAsBoolean()); return source;
        }, ignored -> {}, results::add);
        ref.set(queue); queue.request("IzakyL"); tasks.remove().run();
        assertTrue(tasks.isEmpty()); assertEquals(List.of("IzakyL"), results); assertFalse(queue.running());
    }

    @Test void switchingAwayAndBackStillInvalidatesOldRound() {
        var tasks = new ArrayDeque<Runnable>(); var results = new ArrayList<String>();
        var ref = new AtomicReference<LatestCheckQueue<String, String>>(); int[] count = {0};
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            if (++count[0] == 1) { ref.get().request("davisye"); ref.get().request("IzakyL"); }
            return source + count[0];
        }, ignored -> {}, results::add);
        ref.set(queue); queue.request("IzakyL"); tasks.remove().run(); tasks.remove().run();
        assertEquals(List.of("IzakyL2"), results);
    }

    @Test void failedOldRoundStillRunsQueuedRequest() {
        var tasks = new ArrayDeque<Runnable>(); var results = new ArrayList<String>();
        var ref = new AtomicReference<LatestCheckQueue<String, String>>();
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            if (source.equals("old")) { ref.get().request("new"); throw new IllegalStateException("fixture"); }
            return source;
        }, ignored -> {}, results::add);
        ref.set(queue); queue.request("old"); assertThrows(IllegalStateException.class, () -> tasks.remove().run());
        tasks.remove().run(); assertEquals(List.of("new"), results); assertFalse(queue.running());
        queue.request("retry"); tasks.remove().run(); assertFalse(queue.running());
    }

    @Test void savingDuringBlockedNetworkDoesNotWaitForOldCheck() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var published = new CountDownLatch(1);
        var results = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var queue = new LatestCheckQueue<String, String>(executor, (source, current) -> {
                if (source.equals("old")) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }
                return source;
            }, ignored -> {}, value -> { results.add(value); published.countDown(); });
            queue.request("old"); assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertFalse(queue.request("new"))); }
            finally { release.countDown(); }
            assertTrue(published.await(5, TimeUnit.SECONDS)); assertEquals(List.of("new"), results);
        }
    }

    @Test void invalidatingBeforeWorkerStartsNeverExecutesTheRevokedRequest() {
        var tasks = new ArrayDeque<Runnable>(); var results = new ArrayList<String>();
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            fail("Revoked request must not run"); return source;
        }, ignored -> {}, results::add);
        queue.request("force"); queue.invalidate(); tasks.remove().run();
        assertTrue(results.isEmpty()); assertTrue(tasks.isEmpty()); assertFalse(queue.running());
    }

    @Test void failedSaveAfterInvalidationDoesNotRetryTheActiveForceRequest() {
        var tasks = new ArrayDeque<Runnable>(); var results = new ArrayList<String>();
        var ref = new AtomicReference<LatestCheckQueue<String, String>>();
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            ref.get().invalidate();
            assertFalse(current.getAsBoolean());
            assertThrows(IllegalStateException.class, () -> current.commit(() -> fail("Revoked commit ran")));
            return source;
        }, ignored -> {}, results::add);
        ref.set(queue); queue.request("force"); tasks.remove().run();
        assertTrue(results.isEmpty()); assertTrue(tasks.isEmpty()); assertFalse(queue.running());
    }

    @Test void onlyCurrentTicketMayCommitThePreparedOwnership() {
        var tasks = new ArrayDeque<Runnable>(); var committed = new ArrayList<String>();
        var ref = new AtomicReference<LatestCheckQueue<String, String>>();
        var queue = new LatestCheckQueue<String, String>(tasks::add, (source, current) -> {
            try {
                if (source.equals("old")) {
                    ref.get().request("new");
                    assertThrows(IllegalStateException.class, () -> current.commit(() -> committed.add(source)));
                } else current.commit(() -> committed.add(source));
            } catch (Exception error) { throw new AssertionError(error); }
            return source;
        }, ignored -> {}, ignored -> {});
        ref.set(queue); queue.request("old"); tasks.remove().run(); tasks.remove().run();
        assertEquals(List.of("new"), committed);
    }
}
