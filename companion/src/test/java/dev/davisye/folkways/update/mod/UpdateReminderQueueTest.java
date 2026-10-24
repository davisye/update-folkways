package dev.davisye.folkways.update.mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UpdateReminderQueueTest {
    private static UpdateReminderQueue.Notice<String> notice(String key, String message) {
        return new UpdateReminderQueue.Notice<>(key, message);
    }
    @Test void wordingAndOtherChannelChangesDoNotRepeatTheSameUpdate() {
        var queue = new UpdateReminderQueue<String>(); var seen = new HashSet<String>();
        queue.replace(List.of(notice("folkways-A", "Downloaded Folkways"), notice("self-A", "Updater downloaded")));
        assertEquals(List.of("Downloaded Folkways", "Updater downloaded"), queue.takeNew(seen::add));
        queue.replace(List.of(notice("folkways-A", "Already downloaded Folkways")));
        assertTrue(queue.takeNew(seen::add).isEmpty());
        queue.replace(List.of(notice("folkways-A", "Folkways 已准备好"), notice("self-A", "Updater ready again after network recovery")));
        assertTrue(queue.takeNew(seen::add).isEmpty());
        queue.replace(List.of(notice("folkways-A", "Already downloaded"), notice("self-B", "A different updater build")));
        assertEquals(List.of("A different updater build"), queue.takeNew(seen::add));
    }
    @Test void replacingOrCancellingAnUndisplayedCheckConsumesNoReminder() {
        var queue = new UpdateReminderQueue<String>(); var seen = new HashSet<String>();
        queue.replace(List.of(notice("old", "old source"))); queue.clear();
        assertTrue(queue.takeNew(seen::add).isEmpty()); assertTrue(seen.isEmpty());
        queue.replace(List.of(notice("old", "old source")));
        queue.replace(List.of(notice("new", "latest source")));
        assertEquals(List.of("latest source"), queue.takeNew(seen::add));
        assertEquals(java.util.Set.of("new"), seen);
        queue.replace(List.of(notice("old", "old source selected again")));
        assertEquals(List.of("old source selected again"), queue.takeNew(seen::add));
    }
    @Test void oneBatchKeepsBothChannelsAndDrainsOnlyOnce() {
        var queue = new UpdateReminderQueue<String>(); var seen = new HashSet<String>();
        queue.replace(List.of(notice("folkways", "Folkways ready"), notice("self", "Updater ready")));
        assertEquals(List.of("Folkways ready", "Updater ready"), queue.takeNew(seen::add));
        assertTrue(queue.takeNew(seen::add).isEmpty());
    }
    @Test void aConcurrentReplacementDuringDrainRemainsForTheNextTick() {
        var queue = new UpdateReminderQueue<String>(); var seen = new HashSet<String>();
        queue.replace(List.of(notice("first", "first")));
        assertEquals(List.of("first"), queue.takeNew(key -> {
            queue.replace(List.of(notice("second", "second"))); return seen.add(key);
        }));
        assertEquals(List.of("second"), queue.takeNew(seen::add));
    }
    @Test void noUpdateStatusProducesNoPopupOrHistoryWrite() {
        var queue = new UpdateReminderQueue<String>(); var calls = new ArrayList<String>();
        queue.replace(List.of());
        assertTrue(queue.takeNew(key -> { calls.add(key); return true; }).isEmpty());
        assertTrue(calls.isEmpty());
    }
    @Test void callerCannotChangeTheQueuedBatchAfterPublishing() {
        var queue = new UpdateReminderQueue<String>(); var source = new ArrayList<UpdateReminderQueue.Notice<String>>();
        source.add(notice("first", "first")); queue.replace(source); source.clear();
        assertEquals(List.of("first"), queue.takeNew(ignored -> true));
    }
}
