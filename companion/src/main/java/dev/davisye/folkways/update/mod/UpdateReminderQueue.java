package dev.davisye.folkways.update.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Only the latest completed check may reach the display thread; replacing it consumes no history. */
final class UpdateReminderQueue<M> {
    record Notice<M>(String key, M message) {
        Notice { Objects.requireNonNull(key); Objects.requireNonNull(message); }
    }
    private final AtomicReference<List<Notice<M>>> pending = new AtomicReference<>(List.of());

    void replace(List<Notice<M>> notices) { pending.set(List.copyOf(notices)); }
    void clear() { pending.set(List.of()); }
    List<M> takeNew(Predicate<String> first) {
        List<M> fresh = new ArrayList<>();
        for (Notice<M> notice : pending.getAndSet(List.of())) {
            if (first.test(notice.key())) fresh.add(notice.message());
        }
        return List.copyOf(fresh);
    }
}
