package dev.davisye.folkways.update.core;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Launch this external process before the launcher/JVM is allowed to open mod JARs. */
public final class Bootstrap {
    private Bootstrap() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !args[1].equals("--game-dir")) {
            System.err.println("Usage: bootstrap <init|adopt|check|status|self-adopt|self-check|self-prepare|launch> --game-dir <dir> [--companion-jar <existing-mod.jar>] [--dedicated] [-- <foreground-executable> <args>...]");
            System.exit(2); return;
        }
        Path game = Path.of(args[2]); Updater.initialize(game); Updater updater = new Updater(game);
        RuntimeLease.record(updater);
        switch (args[0]) {
            case "init" -> System.out.println(updater.status());
            case "adopt" -> System.out.println(updater.adopt());
            case "check" -> System.out.println(updater.checkAndStage());
            case "status" -> System.out.println(updater.status() + " | " + new SelfUpdater(game).status());
            case "self-adopt" -> {
                if (args.length != 5 || !args[3].equals("--companion-jar")) throw new IOException("self-adopt requires one explicit existing companion JAR");
                System.out.println(new SelfUpdater(game).adopt(Path.of(args[4])));
            }
            case "self-check" -> System.out.println(new SelfUpdater(game).checkAndStage());
            case "self-prepare" -> {
                RuntimeLease.pruneDead(updater);
                System.out.println(new SelfUpdater(game).prepare());
            }
            case "launch" -> {
                int delimiter = Arrays.asList(args).indexOf("--");
                if (delimiter < 3 || delimiter + 1 >= args.length) throw new IOException("Launch requires an explicit executable argument array");
                boolean dedicated = false;
                for (int i = 3; i < delimiter; i++) {
                    if (args[i].equals("--dedicated")) dedicated = true;
                    else throw new IOException("Unknown launch option: " + args[i]);
                }
                int result = supervise(updater, Arrays.asList(args).subList(delimiter + 1, args.length), dedicated);
                System.exit(result);
            }
            default -> throw new IOException("Unknown bootstrap operation");
        }
    }

    static int supervise(Updater u, List<String> command, boolean dedicated) throws Exception {
        u.directory(u.home); u.safe(u.home.resolve("session.lock"));
        try (FileChannel channel = FileChannel.open(u.home.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("A supervised instance is already running");
            guardPreviousChild(u);
            int restarts = 0;
            if ("1".equals(System.getenv("FOLKWAYS_SEED_PROTOCOL"))) {
                try { restarts = Integer.parseInt(Objects.requireNonNullElse(System.getenv("FOLKWAYS_SEED_RESTART_COUNT"), "0")); }
                catch (NumberFormatException e) { throw new IOException("Invalid stable-seed restart count", e); }
                if (restarts < 0 || restarts > 50) throw new IOException("Stable-seed restart count exceeds the hard limit");
            }
            while (true) {
                RuntimeLease.ensureNoGame(u);
                if (!"1".equals(System.getenv("FOLKWAYS_SEED_PROTOCOL"))) {
                    try { System.out.println(new SelfUpdater(u.game).checkAndStage()); }
                    catch (Exception e) { System.err.println("Updater self-check failed; current updater retained (" + e.getClass().getSimpleName() + ")"); }
                    System.out.println("Updater self-replacement requires launch.ps1 or launch.sh; direct JAR launch only stages files");
                }
                // Network failure does not prevent a known installed instance from launching offline.
                try { System.out.println(u.checkAndStage()); }
                catch (Exception e) { System.err.println("Update check failed; keeping installed files: " + e.getMessage()); }
                System.out.println(u.applyPending());
                Path session = u.home.resolve("session"); u.clear(session); u.directory(session);
                String token = token();
                ProcessBuilder builder = new ProcessBuilder(command).directory(u.game.toFile()).inheritIO();
                builder.environment().put("FOLKWAYS_SUPERVISOR_TOKEN", token);
                builder.environment().put("FOLKWAYS_SUPERVISOR_DIR", session.toString());
                builder.environment().put("FOLKWAYS_SUPERVISOR_KIND", dedicated ? "dedicated" : "client");
                builder.environment().put("FOLKWAYS_SUPERVISOR_PID", Long.toString(ProcessHandle.current().pid()));
                builder.environment().put("FOLKWAYS_SUPERVISOR_START", ProcessHandle.current().info().startInstant().orElseThrow().toString());
                Child child = new Child(); child.phase = "launching";
                Path barrier = u.home.resolve("child.json");
                Updater.atomic(barrier, Updater.JSON.toJson(child).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Process process;
                try { process = builder.start(); }
                catch (IOException e) { Files.delete(barrier); throw e; }
                recordChildIdentity(process, child, barrier);
                // On interruption, do not orphan an invisible restarting supervisor.
                int exit;
                try { exit = process.waitFor(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Supervisor interrupted; child still owns world shutdown", e); }
                Files.delete(barrier);
                boolean requested = dedicated && exit == 0 && validRequest(session, token);
                u.clear(session);
                if (!requested) return "1".equals(System.getenv("FOLKWAYS_SEED_PROTOCOL")) && exit == 75 ? 1 : exit;
                Config config = Config.load(u.game);
                if (++restarts > config.restartLimit) throw new IOException("Restart budget exhausted; refusing a restart loop");
                System.out.println("Server exited normally; restarting in " + config.restartCooldownSeconds + "s (" + restarts + "/" + config.restartLimit + ")");
                Thread.sleep(config.restartCooldownSeconds * 1000L);
                if ("1".equals(System.getenv("FOLKWAYS_SEED_PROTOCOL"))) return 75;
            }
        } catch (OverlappingFileLockException e) { throw new IOException("A supervised instance is already running", e); }
    }

    static void recordChildIdentity(Process process, Child child, Path barrier) throws IOException {
        Optional<java.time.Instant> start = process.info().startInstant();
        if (start.isEmpty()) {
            // A foreground JVM can finish before its first process-info snapshot.
            // Only an already exited child may proceed without a persistent identity.
            if (process.isAlive()) throw new IOException("Cannot identify live child start time; launch barrier retained");
            return;
        }
        child.phase = "running"; child.pid = process.pid(); child.start = start.get().toString();
        Updater.atomic(barrier, Updater.JSON.toJson(child).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void guardPreviousChild(Updater u) throws IOException {
        Path p = u.home.resolve("child.json"); u.safe(p);
        if (!Files.exists(p)) return;
        if (Files.size(p) > 4096) throw new IOException("Invalid child launch barrier; verify no game is running before manual recovery");
        Child child;
        try { child = Updater.JSON.fromJson(Files.readString(p), Child.class); }
        catch (RuntimeException e) { throw new IOException("Invalid child launch barrier; manual recovery required", e); }
        if (child == null || !"running".equals(child.phase) || child.pid <= 0 || child.start == null) {
            throw new IOException("Previous supervisor stopped during child launch; verify the game has exited, then remove .folkways-updater/child.json manually");
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(child.pid);
        if (handle.isPresent() && handle.get().isAlive()) {
            Optional<java.time.Instant> start = handle.get().info().startInstant();
            if (start.isEmpty() || start.get().toString().equals(child.start)) throw new IOException("Previous child JVM is still running; replacement refused");
        }
        Files.delete(p); // PID absent or reused: the recorded child has exited.
    }

    static boolean validRequest(Path session, String token) throws IOException {
        Path p = session.resolve("restart-request.json");
        if (!Files.isRegularFile(p) || Files.isSymbolicLink(p) || Files.size(p) > 4096) return false;
        try {
            Request request = Updater.JSON.fromJson(Files.readString(p), Request.class);
            return request != null && "restart".equals(request.action) && token.equals(request.token);
        } catch (RuntimeException e) { return false; }
    }
    private static String token() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return HexFormat.of().formatHex(b); }
    static final class Request { String token, action; }
    static final class Child { String phase, start; long pid; }
}
