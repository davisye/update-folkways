package dev.davisye.folkways.update.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UpdateReminderHistoryTest {
    @TempDir Path game;
    Path file() { return game.resolve(".folkways-updater/notified-updates.json"); }
    @Test void nullDoesNotTouchDiskAndTwoNamespacesPersistAcrossInstances() throws Exception {
        UpdateReminderHistory history = new UpdateReminderHistory(game, error -> fail(error));
        assertFalse(history.first(null)); assertFalse(Files.exists(file().getParent()));
        assertTrue(history.first("folkways-github:nightly-a")); assertFalse(history.first("folkways-github:nightly-a"));
        assertTrue(history.first("updater-self:nightly-a")); assertTrue(history.first("folkways-github:release-a"));
        history = new UpdateReminderHistory(game, error -> fail(error));
        assertFalse(history.first("folkways-github:nightly-a")); assertFalse(history.first("updater-self:nightly-a"));
        assertTrue(history.first("folkways-github:nightly-b"));
        String content = Files.readString(file()); assertFalse(content.contains("nightly")); assertFalse(content.contains("folkways"));
        assertEquals(4, JsonParser.parseString(content).getAsJsonObject().getAsJsonArray("hashes").size());
    }
    @Test void storesOnlyTheLast256HashesButKeepsAllJvmRemindersDeduplicated() throws Exception {
        UpdateReminderHistory history = new UpdateReminderHistory(game, error -> fail(error));
        for (int index = 0; index < 260; index++) assertTrue(history.first("update-" + index));
        assertFalse(history.first("update-0"));
        var hashes = JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonArray("hashes");
        assertEquals(256, hashes.size()); assertEquals(UpdateCheckResult.hash("update-4"), hashes.get(0).getAsString());
        assertEquals(UpdateCheckResult.hash("update-259"), hashes.get(255).getAsString());
        UpdateReminderHistory restarted = new UpdateReminderHistory(game, error -> fail(error));
        assertFalse(restarted.first("update-259")); assertTrue(restarted.first("update-0"));
        try (var entries = Files.list(file().getParent())) { assertEquals(List.of("notified-updates.json"), entries.map(p -> p.getFileName().toString()).toList()); }
    }
    @Test void malformedOversizedOrUnrecognizedHistoryIsRetainedAndReportsOnlyOnce() throws Exception {
        List<String> invalid = List.of("not JSON", "null", "{\"schema\":2,\"hashes\":[]}", "{\"schema\":1,\"hashes\":[\"not-a-hash\"]}",
            "{\"schema\":1,\"schema\":1,\"hashes\":[]}", "{schema:1,hashes:[]}", "{\"schema\":1,\"hashes\":[]} trailing",
            Updater.JSON.toJson(Map.of("schema", 1, "hashes", Collections.nCopies(257, "a".repeat(64)))), "x".repeat(32769));
        Files.createDirectories(file().getParent());
        for (String original : invalid) {
            Files.writeString(file(), original); AtomicInteger errors = new AtomicInteger(); UpdateReminderHistory history = new UpdateReminderHistory(game, error -> errors.incrementAndGet());
            assertTrue(history.first("one")); assertFalse(history.first("one")); assertTrue(history.first("two"));
            assertEquals(1, errors.get()); assertEquals(original, Files.readString(file()));
        }
    }
    @Test void persistenceFailureDoesNotInterruptDisplayOrInJvmDeduplication() throws Exception {
        AtomicInteger errors = new AtomicInteger(); UpdateReminderHistory history = new UpdateReminderHistory(game, error -> errors.incrementAndGet());
        assertTrue(history.first("before")); Files.delete(file()); Files.createDirectory(file());
        assertTrue(history.first("after")); assertFalse(history.first("after")); assertTrue(history.first("another")); assertEquals(1, errors.get());
        assertTrue(Files.isDirectory(file()));
        try (var files = Files.list(file().getParent())) { assertEquals(1, files.count()); }
    }
    @Test void unsafeLinkNeverReadsOrWritesTheTarget() throws Exception {
        Files.createDirectories(file().getParent()); Path outside = game.resolve("external-history.json"); Files.writeString(outside, "do not modify");
        try { Files.createSymbolicLink(file(), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) { Assumptions.assumeTrue(false, "Symbolic links unavailable"); }
        AtomicInteger errors = new AtomicInteger(); UpdateReminderHistory history = new UpdateReminderHistory(game, error -> errors.incrementAndGet());
        assertTrue(history.first("a")); assertFalse(history.first("a")); assertTrue(history.first("b")); assertEquals(1, errors.get());
        assertEquals("do not modify", Files.readString(outside)); assertTrue(Files.isSymbolicLink(file()));
    }
    @Test void doesNotAcquireDownloadOrInstallLockAndCallbackFailureCannotEscape() throws Exception {
        Files.createDirectories(file().getParent());
        try (FileChannel channel = FileChannel.open(file().resolveSibling("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            UpdateReminderHistory history = new UpdateReminderHistory(game, error -> fail(error));
            assertTrue(history.first("independent")); assertFalse(history.first("independent"));
        }
        Files.delete(file()); Files.createDirectory(file());
        UpdateReminderHistory broken = new UpdateReminderHistory(game, error -> { throw new IllegalStateException("consumer failed"); });
        assertTrue(broken.first("a")); assertFalse(broken.first("a"));
    }
}
