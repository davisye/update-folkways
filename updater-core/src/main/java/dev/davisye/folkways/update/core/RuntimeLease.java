package dev.davisye.folkways.update.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Unique copied runners have a PID lease; only a proven dead runtime may be pruned. */
final class RuntimeLease {
    private RuntimeLease() {}
    static void record(Updater u) throws Exception {
        Path jar = Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize().toRealPath();
        Path root = u.home.resolve("runtime");
        if (!jar.startsWith(root) || !jar.getFileName().toString().equals("runner.jar") || jar.getParent() == null
            || !Objects.equals(jar.getParent().getParent(), root) || !jar.getParent().getFileName().toString().matches("launch-[A-Za-z0-9-]+")) return;
        u.safe(jar);
        Lease lease = new Lease(); lease.pid = ProcessHandle.current().pid(); lease.start = ProcessHandle.current().info().startInstant().orElseThrow().toString();
        Updater.atomic(jar.getParent().resolve("owner.json"), Updater.JSON.toJson(lease).getBytes(StandardCharsets.UTF_8));
    }
    static void pruneDead(Updater u) throws Exception {
        Path root = u.home.resolve("runtime"); u.safe(root); if (!Files.exists(root)) return;
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                if (!path.getFileName().toString().matches("launch-[A-Za-z0-9-]+")) continue;
                u.safe(path); Path owner = path.resolve("owner.json"); u.safe(owner);
                if (!Files.isRegularFile(owner) || Files.size(owner) > 4096) continue; // Unknown launch phase requires manual inspection.
                Lease lease;
                try { lease = Updater.JSON.fromJson(Files.readString(owner), Lease.class); }
                catch (RuntimeException e) { continue; }
                if (lease == null || lease.pid <= 0 || lease.start == null) continue;
                Optional<ProcessHandle> process = ProcessHandle.of(lease.pid);
                if (process.isPresent() && process.get().isAlive()) {
                    Optional<java.time.Instant> start = process.get().info().startInstant();
                    if (start.isEmpty() || start.get().toString().equals(lease.start)) continue;
                }
                u.clear(path);
            }
        }
    }
    static void recordGame(Updater u) throws Exception {
        Path root = u.home.resolve("game-leases"); u.directory(root);
        Lease lease = new Lease(); lease.pid = ProcessHandle.current().pid(); lease.start = ProcessHandle.current().info().startInstant().orElseThrow().toString();
        Updater.atomic(root.resolve(lease.pid + ".json"), Updater.JSON.toJson(lease).getBytes(StandardCharsets.UTF_8));
    }
    static void ensureNoGame(Updater u) throws Exception {
        Path root = u.home.resolve("game-leases"); u.safe(root); if (!Files.exists(root)) return;
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                u.safe(path);
                if (!Files.isRegularFile(path) || Files.size(path) > 4096) throw new IOException("Invalid game JVM lease; replacement refused");
                Lease lease;
                try { lease = Updater.JSON.fromJson(Files.readString(path), Lease.class); }
                catch (RuntimeException e) { throw new IOException("Invalid game JVM lease; replacement refused", e); }
                if (lease == null || lease.pid <= 0 || lease.start == null) throw new IOException("Unknown game JVM lease; replacement refused");
                Optional<ProcessHandle> process = ProcessHandle.of(lease.pid);
                if (process.isPresent() && process.get().isAlive()) {
                    Optional<java.time.Instant> start = process.get().info().startInstant();
                    if (start.isEmpty() || start.get().toString().equals(lease.start)) throw new ActiveGameException();
                }
                Files.delete(path);
            }
        }
    }
    static final class Lease { long pid; String start; }
    static final class ActiveGameException extends IOException {
        ActiveGameException() { super("A game JVM is still alive; replacement refused"); }
    }
}
