package dev.davisye.folkways.update.mod;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LifecycleRequestTest {
    @Test void restartNeedsSupervisor() {
        var request = new LifecycleRequest();
        assertFalse(request.schedule(LifecycleRequest.Action.RESTART, 0, 0, () -> false));
        assertNull(request.due(Long.MAX_VALUE));
    }
    @Test void gracePeriodCancellationAndSingleRequest() {
        var request = new LifecycleRequest();
        assertTrue(request.schedule(LifecycleRequest.Action.SHUTDOWN, 100, 10, () -> false));
        assertFalse(request.schedule(LifecycleRequest.Action.RESTART, 100, 0, () -> true));
        assertNull(request.due(10_000_000_099L));
        assertEquals(LifecycleRequest.Action.SHUTDOWN, request.due(10_000_000_100L));
        assertTrue(request.cancel());
        assertNull(request.due(Long.MAX_VALUE));
        assertTrue(request.schedule(LifecycleRequest.Action.RESTART, 200, 0, () -> true));
        assertEquals(LifecycleRequest.Action.RESTART, request.due(200));
    }
}
