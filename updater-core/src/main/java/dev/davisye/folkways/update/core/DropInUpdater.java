package dev.davisye.folkways.update.core;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** A manually installed Mod owns only its own JAR; a separate JVM installs verified downloads after exit. */
public final class DropInUpdater {
    private static final long LIMIT = 64L * 1024 * 1024;
    private static final String PAYLOAD = "META-INF/folkways-updater/helper.jar";
    private static final String API = "https://api.github.com/repos/" + SelfUpdater.REPOSITORY;
    private final Updater u;
    private final SelfUpdater.Source injected;
    private long observedClockMillis = System.currentTimeMillis();
    private boolean clockRollback;
    private static final String STATE = "dropin-state.json", JOURNAL = "dropin-journal.json";

    private DropInUpdater(Path game, SelfUpdater.Source source) throws IOException { u = new Updater(game); injected = source; }

    /** Call before any optional initialization, including IDE runs with no installed Mod JAR. */
    public static void guardGame(Path game) throws Exception { guardGame(game, false); }
    public static void guardGame(Path game, boolean developmentEnvironment) throws Exception {
        Updater u = new Updater(game);
        // Deliberately outside update.lock: a helper already holding the mutation lock must see this JVM.
        RuntimeLease.recordGame(u);
        if (developmentEnvironment || Boolean.getBoolean("folkways.localDevelopment") || "true".equalsIgnoreCase(System.getenv("FOLKWAYS_LOCAL_DEVELOPMENT"))) {
            // Sticky by design: an older already-running helper must also see a newer JVM's local protection.
            Updater.atomic(u.home.resolve("LOCAL_DEVELOPMENT"), "IDE/JVM/environment local-development protection\n".getBytes(StandardCharsets.UTF_8));
        }
        String conflict = u.locked(() -> {
            if (System.currentTimeMillis() < currentStart().toEpochMilli()) throw new IOException("Clock moved behind this JVM's start; loading refused");
            Path legacy = u.home.resolve("self-journal.json"); u.safe(legacy);
            if (Files.exists(legacy)) throw new IOException("An unfinished legacy two-JAR transaction requires recovery with launch.ps1/launch.sh; this game load is refused");
            for (String name : List.of(JOURNAL, "journal.json")) {
                u.safe(u.home.resolve(name));
                if (Files.exists(u.home.resolve(name))) return "An interrupted updater installation blocks this game load; let the exit helper recover first";
            }
            Path marker = u.home.resolve("last-install.json");
            if (Files.exists(marker)) {
                InstallMark mark = read(u, marker, InstallMark.class);
                // Compare at millisecond precision on both sides; equal timestamps are conservatively rejected.
                if (mark == null || mark.finishedMillis <= 0 || mark.clockAnomaly || mark.finishedMillis >= currentStart().toEpochMilli())
                    throw new IOException("Mod files changed after this JVM started; close it and start a fresh game JVM");
            }
            return null;
        });
        if (conflict != null) {
            IOException failure = new IOException(conflict);
            try { new DropInUpdater(game, null).startHelper(true); }
            catch (Exception e) { failure.addSuppressed(e); }
            throw failure;
        }
    }

    public static DropInUpdater initialize(Path game, Path installedModJar) throws Exception {
        return initialize(game, installedModJar, null);
    }
    /** Compare the loader's scan snapshot as well as the class loader's resource identity before adopting anything. */
    public static DropInUpdater initializeLoaded(Path game, Path installedModJar, byte[] loadedBuildInfo, String scannedModVersion) throws Exception {
        guardGame(game);
        if (loadedBuildInfo == null || loadedBuildInfo.length == 0 || loadedBuildInfo.length > 64 * 1024 || scannedModVersion == null) throw new IOException("Loaded updater identity is unavailable");
        return initialize(game, installedModJar, null, loadedBuildInfo, scannedModVersion);
    }
    static DropInUpdater initialize(Path game, Path installedModJar, SelfUpdater.Source source) throws Exception {
        return initialize(game, installedModJar, source, null, null);
    }
    private static DropInUpdater initialize(Path game, Path installedModJar, SelfUpdater.Source source, byte[] loadedBuildInfo, String scannedModVersion) throws Exception {
        guardGame(game);
        DropInUpdater result = new DropInUpdater(game, source);
        result.u.locked(() -> {
            Path jar = result.u.existingInput(installedModJar);
            if (!Objects.equals(jar.getParent(), result.u.mods) || !jar.getFileName().toString().endsWith(".jar")) throw new IOException("The installed updater must be a direct game/mods JAR");
            byte[] bytes = result.u.readBounded(jar, LIMIT);
            SelfUpdater.BuildInfo build = SelfUpdater.jar(bytes, "companion", null);
            if (loadedBuildInfo != null) {
                SelfUpdater.BuildInfo loaded = decode(loadedBuildInfo, SelfUpdater.BuildInfo.class);
                if (loaded == null || loaded.schema != 1 || !SelfUpdater.REPOSITORY.equals(loaded.repository) || !"companion".equals(loaded.component)
                    || !"1.21.1".equals(loaded.minecraft) || !SelfUpdater.sameBuild(build, loaded) || !scannedModVersion.equals(build.version))
                    throw new IOException("Updater JAR changed since its loader scan; this game load is refused");
            }
            Path configPath = result.u.home.resolve("config.json"); result.u.safe(configPath);
            if (!Files.exists(configPath)) {
                Config config = new Config(); config.localDevelopment = !SelfUpdater.publishedBuild(build);
                if (SelfUpdater.publishedBuild(build)) config.selfUpdateChannel = build.channel;
                Updater.atomic(configPath, json(config));
            }
            result.unique(jar);
            State state = result.state();
            if (state.owned == null) {
                state.owned = new SelfUpdater.Owned(result.u.game.relativize(jar).toString().replace('\\', '/'), Updater.sha(bytes), bytes.length);
                state.build = build; state.localBuild = !SelfUpdater.publishedBuild(build); state.highest = Math.max(0, build.sequence);
                result.save(state);
            } else {
                boolean matches = result.target(state).equals(jar) && SelfUpdater.sameBuild(state.build, build);
                if (matches) try { result.owned(state); } catch (IOException e) { matches = false; }
                Path protection = result.u.home.resolve("dropin-unmanaged.json"); result.u.safe(protection);
                if (!matches) {
                    // A manual/local build may run, but never silently resets the previously trusted baseline.
                    Unmanaged value = new Unmanaged(); value.path = result.u.game.relativize(jar).toString().replace('\\', '/');
                    value.sha256 = Updater.sha(bytes); value.size = bytes.length; value.build = build;
                    Updater.atomic(protection, json(value));
                } else Files.deleteIfExists(protection);
            }
            result.pruneHelpers();
            return null;
        });
        return result;
    }

    public String status() throws Exception {
        State state = state(); Config config = Config.load(u.game);
        return "drop-in updater=" + (state.build == null ? "unadopted" : state.build.version)
            + ", pending=" + (state.pending != null) + ", local=" + (state.localBuild || config.isLocalDevelopment(u.game))
            + ", manual/local protection=" + unmanaged() + ", helper=" + helperAlive();
    }
    public boolean localProtection() throws Exception { return Config.load(u.game).isLocalDevelopment(u.game) || state().localBuild || unmanaged(); }
    public String checkAndStage() throws Exception {
        Config config = Config.load(u.game);
        if (config.isLocalDevelopment(u.game)) return "Local development: updater replacement disabled";
        if (unmanaged()) return "Manual/local updater build: replacement disabled; recorded ownership retained";
        if (!config.selfUpdateEnabled) return "Updater self-update disabled";
        return u.locked(() -> {
            State state = state();
            if (state.owned == null) return "Updater ownership not initialized";
            if (state.localBuild) return "Locally compiled updater: replacement disabled";
            managed(config); owned(state); restorePending(state);
            SelfUpdater.Source source = source(config);
            SelfUpdater.ApiRelease release = SelfUpdater.officialRelease(source, config);
            SelfUpdater.Asset asset = selectedAsset(release);
            String digest = SelfUpdater.hashOf(asset);
            if (asset.size > LIMIT) throw new IOException("Updater asset exceeds limit");
            if (digest.equals(state.owned.sha256)) return "Updater already current: " + state.build.version;
            if (state.pending != null) {
                Proof previous = pending(state);
                if (digest.equals(SelfUpdater.hashOf(previous.asset))) return "Updater already downloaded; installs after the game JVM exits";
            }
            byte[] bytes = source.asset(asset.browser_download_url, asset.size, digest);
            Updater.verify(bytes, digest, asset.size);
            SelfUpdater.BuildInfo build = SelfUpdater.jar(bytes, "companion", null);
            validateBuild(build, asset, release, config);
            byte[] runBytes = source.official(API + "/actions/runs/" + build.runId, 2 * 1024 * 1024);
            if (runBytes.length > 2 * 1024 * 1024) throw new IOException("CI response exceeds limit");
            SelfUpdater.Run run = decode(runBytes, SelfUpdater.Run.class);
            verifyRun(build, release, run);
            if (build.sequence <= state.highest) throw new IOException("Updater is older or reuses an accepted sequence");
            if (state.pending != null && build.sequence <= pending(state).build.sequence) throw new IOException("Updater does not advance the queued build");
            // Also bind the hidden executable payload to this exact outer JAR's complete build identity.
            helperPayload(bytes, build);
            SelfUpdater.ApiRelease after = SelfUpdater.officialRelease(source, config);
            if (!SelfUpdater.releaseFingerprint(release).equals(SelfUpdater.releaseFingerprint(after))) throw new IOException("Updater release changed during download");
            Proof proof = new Proof(); proof.asset = asset; proof.release = release; proof.run = run; proof.build = build;
            byte[] proofBytes = json(proof);
            Path candidate = u.home.resolve("dropin-candidate"), queued = u.home.resolve("dropin-pending"), previous = u.home.resolve("dropin-pending.previous");
            u.clear(candidate); u.directory(candidate);
            try {
                Updater.atomic(candidate.resolve("mod.jar"), bytes); Updater.atomic(candidate.resolve("proof.json"), proofBytes);
                managed(config); owned(state());
                if (Files.exists(previous)) throw new IOException("Unresolved previous updater cache");
                if (Files.exists(queued)) Files.move(queued, previous, StandardCopyOption.ATOMIC_MOVE);
                try {
                    managed(config); Files.move(candidate, queued, StandardCopyOption.ATOMIC_MOVE);
                    state.pending = Updater.sha(proofBytes); save(state);
                } catch (Exception e) {
                    if (Files.exists(previous)) { u.clear(queued); Files.move(previous, queued, StandardCopyOption.ATOMIC_MOVE); }
                    throw e;
                }
                u.clear(previous);
            } finally { u.clear(candidate); }
            return "Updater downloaded: " + build.version + "; installs after the game JVM exits";
        });
    }
    private SelfUpdater.Source source(Config config) {
        if (injected != null) return injected;
        Transport transport = new Transport(config);
        return new SelfUpdater.Source() {
            public byte[] official(String url, long maximum) throws Exception { return transport.fetchOfficialApi(url, maximum); }
            public byte[] asset(String url, long maximum, String digest) throws Exception { return transport.fetchValidated(url, maximum, bytes -> Updater.verify(bytes, digest, maximum)); }
        };
    }

    /** Starts early, while this exact parent JVM is alive. It never installs files while a game lease is alive. */
    public String startHelper() throws Exception { return startHelper(false); }
    private String startHelper(boolean recovery) throws Exception {
        return u.locked(() -> {
            if (helperAlive()) return "Exit helper already running";
            Path pointer = u.home.resolve("dropin-helper.json");
            if (Files.exists(pointer)) {
                HelperOwner old = read(u, pointer, HelperOwner.class);
                if (alive(old)) throw new IOException("Another game owns the active exit helper");
            }
            pruneHelpers(); State state = state();
            Path installed = target(state); SelfUpdater.BuildInfo build = state.build;
            if (recovery && Files.exists(u.home.resolve(JOURNAL))) {
                Journal journal = read(u, u.home.resolve(JOURNAL), Journal.class);
                if (journal == null || journal.before == null || journal.after == null || !target(journal.before).equals(installed) || !target(journal.after).equals(installed)) throw new IOException("Invalid recovery ownership");
                unique(installed); byte[] current = u.readBounded(installed, LIMIT); String hash = Updater.sha(current);
                if (!hash.equals(journal.before.owned.sha256) && !hash.equals(journal.after.owned.sha256)) throw new IOException("Local changes block recovery helper");
                build = SelfUpdater.jar(current, "companion", null);
            } else if (unmanaged()) {
                Unmanaged manual = read(u, u.home.resolve("dropin-unmanaged.json"), Unmanaged.class);
                State temporary = new State(); temporary.owned = new SelfUpdater.Owned(manual.path, manual.sha256, manual.size);
                installed = target(temporary); unique(installed); byte[] bytes = u.readBounded(installed, LIMIT);
                Updater.verify(bytes, manual.sha256, manual.size); build = SelfUpdater.jar(bytes, "companion", null);
                if (!SelfUpdater.sameBuild(build, manual.build)) throw new IOException("Manual updater changed after initialization");
            } else owned(state);
            byte[] payload = helperPayload(u.readBounded(installed, LIMIT), build);
            Path runtime = u.home.resolve("dropin-runtime/helper-" + UUID.randomUUID()); u.directory(runtime);
            Updater.atomic(runtime.resolve("helper.jar"), payload);
            HelperOwner intent = new HelperOwner(); intent.parentPid = ProcessHandle.current().pid(); intent.parentStart = currentStart().toString(); intent.token = UUID.randomUUID().toString();
            intent.runtime = u.game.relativize(runtime).toString().replace('\\', '/');
            Updater.atomic(runtime.resolve("intent.json"), json(intent));
            List<String> command = new ArrayList<>(List.of(javaExecutable().toString()));
            if (Boolean.getBoolean("folkways.localDevelopment")) command.add("-Dfolkways.localDevelopment=true");
            // Java 21's Windows launcher converts main arguments through the active ANSI code page.
            // Keep the internal command ASCII and let the JVM resolve the relative class path from its Unicode cwd.
            command.addAll(List.of("-cp", "helper.jar", Helper.class.getName(),
                "--wait-b64", encodePath(u.game), Long.toString(intent.parentPid), intent.parentStart, encodePath(runtime), intent.token));
            ProcessBuilder builder = new ProcessBuilder(command).directory(runtime.toFile());
            builder.redirectInput(ProcessBuilder.Redirect.PIPE); builder.redirectOutput(runtime.resolve("helper.log").toFile()); builder.redirectErrorStream(true);
            Process process = builder.start();
            Optional<Instant> start = process.info().startInstant();
            if (start.isEmpty()) throw new IOException("Cannot identify spawned helper; runtime retained for inspection");
            intent.pid = process.pid(); intent.start = start.get().toString();
            Updater.atomic(runtime.resolve("launched.json"), json(intent));
            // Child setup never takes update.lock, so this bounded handshake cannot deadlock a mutation.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                Path ready = runtime.resolve("owner.json");
                if (Files.exists(ready)) {
                    HelperOwner owner = read(u, ready, HelperOwner.class);
                    if (sameHelper(intent, owner) && alive(owner)) {
                        Updater.atomic(pointer, json(owner)); return "Exit helper ready";
                    }
                }
                if (!process.isAlive()) throw new IOException("Exit helper failed to start; inspect helper.log");
                Thread.sleep(25);
            }
            throw new IOException("Exit helper handshake timed out; helper runtime retained");
        });
    }
    public boolean helperAlive() {
        try {
            HelperOwner owner = read(u, u.home.resolve("dropin-helper.json"), HelperOwner.class);
            Optional<ProcessHandle> handle = owner == null ? Optional.empty() : ProcessHandle.of(owner.pid);
            return owner != null && owner.parentPid == ProcessHandle.current().pid() && currentStart().toString().equals(owner.parentStart)
                && handle.isPresent() && handle.get().isAlive() && handle.get().info().startInstant().map(i -> i.toString().equals(owner.start)).orElse(false);
        } catch (Exception e) { return false; }
    }

    /** Offline only. The helper's process-wide session lock excludes all other helpers/legacy supervisors. */
    String applyOffline() throws Exception {
        u.locked(() -> { rejectLegacyTransaction(); return null; });
        Config config = Config.load(u.game);
        if (config.isLocalDevelopment(u.game) || unmanaged() || state().localBuild) {
            if (Files.exists(u.home.resolve(JOURNAL)) || Files.exists(u.home.resolve("journal.json"))) throw new IOException("Local mode blocks interrupted installation recovery");
            return "Local development/manual build: files retained";
        }
        mutationCheck();
        u.locked(() -> { recover(); return null; });
        if (config.selfUpdateEnabled && !state().localBuild) u.locked(() -> { applySelf(config); return null; });
        // A configured signed Folkways channel remains independent of self-update publication.
        u.installGate = new Updater.InstallGate() {
            public void check() throws Exception { mutationCheck(); }
            public void committed() throws Exception { markInstalled(u, false); }
        };
        if (Files.exists(u.home.resolve("journal.json")) || (!config.manifestUrl.isBlank() && Files.exists(u.home.resolve("pending/manifest.json")))) u.applyPending();
        return "Exit helper completed; verified pending files installed or current files retained";
    }
    private void applySelf(Config config) throws Exception {
        State state = state(); restorePending(state);
        if (state.pending == null) return;
        managed(config); mutationCheck(); owned(state);
        Proof proof = pending(state);
        if (proof.build.sequence <= state.highest) throw new IOException("Queued updater is not newer");
        Path target = target(state), queued = u.home.resolve("dropin-pending/mod.jar");
        byte[] next = u.readBounded(queued, LIMIT); Updater.verify(next, SelfUpdater.hashOf(proof.asset), proof.asset.size);
        Updater.atomic(u.home.resolve("dropin-backup.jar"), u.readBounded(target, LIMIT));
        Journal journal = new Journal(); journal.before = state;
        journal.after = decode(json(state), State.class); journal.after.build = proof.build; journal.after.highest = proof.build.sequence;
        journal.after.owned = new SelfUpdater.Owned(state.owned.path, SelfUpdater.hashOf(proof.asset), proof.asset.size); journal.after.pending = null;
        managed(config); mutationCheck(); Updater.atomic(u.home.resolve(JOURNAL), json(journal));
        managed(config); mutationCheck(); Updater.atomic(target, next);
        managed(config); mutationCheck(); save(journal.after);
        markInstalled(u, false); Files.delete(u.home.resolve(JOURNAL)); u.clear(u.home.resolve("dropin-pending"));
    }
    private void recover() throws Exception {
        Path path = u.home.resolve(JOURNAL); if (!Files.exists(path)) return;
        Config config = Config.load(u.game); managed(config); mutationCheck();
        Journal journal = read(u, path, Journal.class);
        if (journal == null || journal.before == null || journal.after == null || journal.before.owned == null || journal.after.owned == null
            || !Objects.equals(journal.before.owned.path, journal.after.owned.path)) throw new IOException("Invalid drop-in recovery journal");
        Path target = target(journal.before); unique(target);
        byte[] backup = u.readBounded(u.home.resolve("dropin-backup.jar"), LIMIT);
        Updater.verify(backup, journal.before.owned.sha256, journal.before.owned.size);
        String current = Updater.sha(u.readBounded(target, LIMIT));
        if (!current.equals(journal.before.owned.sha256) && !current.equals(journal.after.owned.sha256)) throw new IOException("Local file changes block updater recovery");
        managed(config); mutationCheck(); Updater.atomic(target, backup);
        managed(config); mutationCheck(); save(journal.before); markInstalled(u, false); Files.delete(path);
    }

    private void managed(Config expected) throws Exception {
        Config now = Config.load(u.game);
        if (now.isLocalDevelopment(u.game) || !now.selfUpdateEnabled || !now.selfUpdateChannel.equals(expected.selfUpdateChannel)) throw new IOException("Local/disabled mode or configuration change blocks updater mutation");
        if (state().localBuild) throw new IOException("Locally compiled updater cannot be replaced");
    }
    private void mutationCheck() throws Exception {
        rejectLegacyTransaction();
        RuntimeLease.ensureNoGame(u);
        observeClock();
        if (clockRollback) {
            markInstalled(u, true); throw new IOException("Clock rollback detected; installation requires manual inspection");
        }
    }
    private void rejectLegacyTransaction() throws IOException {
        Path journal = u.home.resolve("self-journal.json"); u.safe(journal);
        if (Files.exists(journal)) throw new IOException("An unfinished legacy two-JAR transaction requires recovery with launch.ps1/launch.sh; exit replacement refused");
    }
    private void observeClock() {
        long now = System.currentTimeMillis();
        if (now < observedClockMillis) clockRollback = true;
        observedClockMillis = Math.max(observedClockMillis, now);
    }
    private boolean unmanaged() throws IOException { Path path = u.home.resolve("dropin-unmanaged.json"); u.safe(path); return Files.exists(path); }
    private State state() throws IOException {
        Path path = u.home.resolve(STATE); if (!Files.exists(path)) return new State();
        State result = read(u, path, State.class);
        if (result == null || (result.owned != null && (result.build == null || result.highest < 0))) throw new IOException("Invalid drop-in ownership state");
        return result;
    }
    private void save(State state) throws IOException { Updater.atomic(u.home.resolve(STATE), json(state)); }
    private Path target(State state) throws IOException {
        if (state.owned == null || state.owned.path == null || !state.owned.path.startsWith("mods/") || state.owned.path.substring(5).contains("/")) throw new IOException("Invalid updater ownership path");
        Updater.fileName(state.owned.path.substring(5)); Path target = u.game.resolve(state.owned.path); u.safe(target); return target;
    }
    private void unique(Path target) throws Exception {
        List<Path> files = new SelfUpdater(u.game).companions();
        if (files.size() != 1 || !Files.isSameFile(files.getFirst(), target)) throw new IOException("Duplicate or unrecorded updater Mod JAR");
    }
    private void owned(State state) throws Exception { Path target = target(state); unique(target); Updater.verify(u.readBounded(target, LIMIT), state.owned.sha256, state.owned.size); }
    private Proof pending(State state) throws Exception { return pendingAt(u.home.resolve("dropin-pending"), state); }
    private Proof pendingAt(Path directory, State state) throws Exception {
        byte[] proofBytes = u.readBounded(directory.resolve("proof.json"), 4 * 1024 * 1024);
        if (state.pending == null || !Updater.sha(proofBytes).equals(state.pending)) throw new IOException("Uncommitted updater pending proof");
        Proof proof = decode(proofBytes, Proof.class); Config config = Config.load(u.game);
        if (proof == null || proof.asset == null || proof.release == null || proof.build == null || proof.run == null) throw new IOException("Invalid updater pending proof");
        validateBuild(proof.build, proof.asset, proof.release, config); verifyRun(proof.build, proof.release, proof.run);
        SelfUpdater.Asset selected = selectedAsset(proof.release);
        if (!Updater.JSON.toJson(selected).equals(Updater.JSON.toJson(proof.asset))) throw new IOException("Pending asset differs from trusted release");
        byte[] jar = u.readBounded(directory.resolve("mod.jar"), LIMIT); Updater.verify(jar, SelfUpdater.hashOf(proof.asset), proof.asset.size);
        SelfUpdater.jar(jar, "companion", metadata(proof.build)); helperPayload(jar, proof.build); return proof;
    }
    private void restorePending(State state) throws Exception {
        Path previous = u.home.resolve("dropin-pending.previous"), pending = u.home.resolve("dropin-pending"); u.safe(previous); u.safe(pending);
        if (!Files.exists(previous)) return;
        try { pendingAt(pending, state); u.clear(previous); }
        catch (Exception e) { pendingAt(previous, state); u.clear(pending); Files.move(previous, pending, StandardCopyOption.ATOMIC_MOVE); }
    }
    private static SelfUpdater.Asset selectedAsset(SelfUpdater.ApiRelease release) throws IOException {
        if (release == null || release.assets == null) throw new IOException("Missing official release");
        List<SelfUpdater.Asset> assets = release.assets.stream().filter(a -> a != null && a.name != null
            && a.name.matches("folkways-updater-neoforge-1\\.21\\.1-[A-Za-z0-9._+\\-]+\\.jar")).toList();
        if (assets.size() != 1) throw new IOException("Official release must contain exactly one updater Mod JAR");
        SelfUpdater.hashOf(assets.getFirst()); return assets.getFirst();
    }
    private static void validateBuild(SelfUpdater.BuildInfo build, SelfUpdater.Asset asset, SelfUpdater.ApiRelease release, Config config) throws Exception {
        if (!SelfUpdater.publishedBuild(build) || !SelfUpdater.REPOSITORY.equals(build.repository) || !"companion".equals(build.component)
            || !"1.21.1".equals(build.minecraft) || !config.selfUpdateChannel.equals(build.channel) || build.version == null || !build.version.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*")
            || !Objects.equals(release.target_commitish, build.commit) || release.draft
            || !Objects.equals(release.url, API + "/releases/" + release.id) || release.id <= 0
            || (build.channel.equals("nightly") ? !"nightly".equals(release.tag_name) : release.prerelease || !("v" + build.version).equals(release.tag_name))) throw new IOException("Invalid updater JAR release identity");
        String name = "folkways-updater-neoforge-1.21.1-" + build.version + ".jar";
        if (!name.equals(asset.name) || asset.id <= 0 || asset.size <= 0 || asset.size > LIMIT || !"uploaded".equals(asset.state)
            || !Objects.equals(asset.browser_download_url, "https://github.com/" + SelfUpdater.REPOSITORY + "/releases/download/" + release.tag_name + "/" + name)) throw new IOException("Invalid official updater JAR asset");
        SelfUpdater.hashOf(asset);
    }
    private static SelfUpdater.Metadata metadata(SelfUpdater.BuildInfo build) {
        SelfUpdater.Metadata m = new SelfUpdater.Metadata(); m.schema = 1; m.repository = build.repository; m.minecraft = build.minecraft;
        m.channel = build.channel; m.version = build.version; m.commit = build.commit; m.runId = build.runId; m.runNumber = build.runNumber; m.runAttempt = build.runAttempt; m.sequence = build.sequence; return m;
    }
    private static void verifyRun(SelfUpdater.BuildInfo build, SelfUpdater.ApiRelease release, SelfUpdater.Run run) throws Exception {
        SelfUpdater.verifyRun(new SelfUpdater.Source() {
            public byte[] official(String url, long maximum) throws Exception {
                if (!url.equals(API + "/actions/runs/" + build.runId)) throw new IOException("Unexpected CI lookup"); return json(run);
            }
            public byte[] asset(String url, long maximum, String digest) throws IOException { throw new IOException("No network access in exit helper"); }
        }, metadata(build), release);
    }
    private static byte[] helperPayload(byte[] jar, SelfUpdater.BuildInfo outer) throws Exception {
        byte[] payload = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(PAYLOAD)) {
                    if (payload != null) throw new IOException("Duplicate helper payload");
                    payload = zip.readNBytes((int)LIMIT + 1); if (payload.length > LIMIT) throw new IOException("Helper payload exceeds limit");
                }
            }
        }
        if (payload == null) throw new IOException("This updater JAR has no embedded exit helper");
        SelfUpdater.BuildInfo inner = SelfUpdater.jar(payload, "bootstrap", null);
        if (!SelfUpdater.sameBuild(outer, inner)) throw new IOException("Embedded helper belongs to a different build"); return payload;
    }

    private void pruneHelpers() throws Exception {
        Path root = u.home.resolve("dropin-runtime"); u.safe(root); if (!Files.exists(root)) return;
        int unknown = 0;
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                u.safe(child); if (!child.getFileName().toString().matches("helper-[A-Za-z0-9-]+")) throw new IOException("Unknown helper runtime");
                Path owner = child.resolve("owner.json"); if (!Files.exists(owner)) owner = child.resolve("launched.json");
                if (!Files.exists(owner)) { unknown++; continue; }
                HelperOwner lease = read(u, owner, HelperOwner.class);
                if (lease == null || lease.pid <= 0 || lease.start == null) { unknown++; continue; }
                if (!alive(lease)) u.clear(child);
            }
        }
        if (unknown > 1) throw new IOException("Ambiguous helper launches require manual inspection; refusing unbounded caches");
    }
    private static boolean alive(HelperOwner owner) throws IOException {
        if (owner == null || owner.pid <= 0 || owner.start == null) return false;
        Optional<ProcessHandle> handle = ProcessHandle.of(owner.pid);
        return handle.isPresent() && handle.get().isAlive() && (handle.get().info().startInstant().isEmpty() || owner.start.equals(handle.get().info().startInstant().orElseThrow().toString()));
    }
    private static boolean sameHelper(HelperOwner a, HelperOwner b) {
        return b != null && a.pid == b.pid && Objects.equals(a.start, b.start) && a.parentPid == b.parentPid
            && Objects.equals(a.parentStart, b.parentStart) && Objects.equals(a.token, b.token) && Objects.equals(a.runtime, b.runtime);
    }
    private static void markInstalled(Updater u, boolean anomaly) throws Exception {
        RuntimeLease.ensureNoGame(u); InstallMark mark = new InstallMark(); mark.finishedMillis = System.currentTimeMillis(); mark.clockAnomaly = anomaly;
        Updater.atomic(u.home.resolve("last-install.json"), json(mark));
    }
    private static Instant currentStart() throws IOException { return ProcessHandle.current().info().startInstant().orElseThrow(() -> new IOException("Cannot identify this JVM start time")); }
    private static String encodePath(Path path) { return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8)); }
    private static Path decodePath(String encoded) throws IOException {
        try {
            if (encoded.isEmpty() || encoded.length() > 131072 || !encoded.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid encoded helper path");
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return Path.of(decoded);
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) { throw new IOException("Invalid UTF-8 helper path", e); }
    }
    private static Path javaExecutable() { Path path = Path.of(System.getProperty("java.home"), "bin", "java.exe"); return Files.exists(path) ? path : path.resolveSibling("java"); }
    private static byte[] json(Object value) { return Updater.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    private static <T> T decode(byte[] bytes, Class<T> type) throws IOException {
        try { return Updater.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type); }
        catch (RuntimeException e) { throw new IOException("Invalid updater JSON", e); }
    }
    private static <T> T read(Updater u, Path path, Class<T> type) throws IOException { return decode(u.readBounded(path, 4 * 1024 * 1024), type); }

    static final class State { SelfUpdater.Owned owned; SelfUpdater.BuildInfo build; boolean localBuild; long highest; String pending; }
    static final class Unmanaged { String path, sha256; long size; SelfUpdater.BuildInfo build; }
    static final class Proof { SelfUpdater.Asset asset; SelfUpdater.ApiRelease release; SelfUpdater.Run run; SelfUpdater.BuildInfo build; }
    static final class Journal { State before, after; }
    static final class InstallMark { long finishedMillis; boolean clockAnomaly; }
    static final class HelperOwner { long pid, parentPid; String start, parentStart, token, runtime; }
    static final class Result { boolean success; String message; long helperPid, parentPid, finishedMillis; String parentStart; }

    /** Hidden fat-JAR entrypoint; no remote lookup is permitted from this process. */
    public static final class Helper {
        public static void main(String[] args) throws Exception {
            if (args.length == 3 && (args[0].equals("--probe") || args[0].equals("--probe-b64"))) {
                boolean encoded = args[0].equals("--probe-b64");
                DropInUpdater updater = initialize(encoded ? decodePath(args[1]) : Path.of(args[1]), encoded ? decodePath(args[2]) : Path.of(args[2]));
                updater.startHelper(); HelperOwner owner = read(updater.u, updater.u.home.resolve("dropin-helper.json"), HelperOwner.class);
                Map<String,Object> result = new LinkedHashMap<>(); result.put("helperPid", owner.pid); result.put("helperStart", owner.start); result.put("runtime", owner.runtime);
                result.put("parentPid", ProcessHandle.current().pid()); result.put("parentStart", currentStart().toString()); result.put("ready", updater.helperAlive());
                System.out.println(Updater.JSON.toJson(result)); return;
            }
            if (args.length != 6 || !args[0].equals("--wait-b64")) throw new IOException("Internal helper usage: --wait-b64 GAME_B64 PARENT_PID PARENT_START RUNTIME_B64 TOKEN");
            DropInUpdater updater = new DropInUpdater(decodePath(args[1]), null); Updater u = updater.u;
            long parentPid = Long.parseLong(args[2]); String parentStart = Instant.parse(args[3]).toString();
            Path runtime = u.existingInput(decodePath(args[4]));
            if (!Objects.equals(runtime.getParent(), u.home.resolve("dropin-runtime")) || !runtime.getFileName().toString().matches("helper-[A-Za-z0-9-]+")) throw new IOException("Invalid helper runtime path");
            Path executing = Path.of(Helper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!executing.equals(runtime.resolve("helper.jar"))) throw new IOException("The exit helper must run from its unique runtime copy");
            HelperOwner intent = read(u, runtime.resolve("intent.json"), HelperOwner.class);
            if (intent == null || intent.parentPid != parentPid || !parentStart.equals(intent.parentStart) || !args[5].equals(intent.token)) throw new IOException("Helper launch identity mismatch");
            u.safe(u.home.resolve("dropin-helper.lock"));
            Result result = new Result(); result.parentPid = parentPid; result.parentStart = parentStart; result.helperPid = ProcessHandle.current().pid();
            try (FileChannel channel = FileChannel.open(u.home.resolve("dropin-helper.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another exit helper is active");
                intent.pid = ProcessHandle.current().pid(); intent.start = currentStart().toString(); Updater.atomic(runtime.resolve("owner.json"), json(intent));
                waitForExactParent(parentPid, parentStart, updater);
                try (FileChannel session = FileChannel.open(u.home.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock sessionLock = session.tryLock()) {
                    if (sessionLock == null) throw new IOException("A legacy supervisor is active; exit installation refused");
                    while (true) {
                        try { result.message = updater.applyOffline(); break; }
                        catch (RuntimeLease.ActiveGameException e) { Thread.sleep(200); }
                    }
                }
                RuntimeLease.ensureNoGame(u);
                AutomaticRestart.afterExit(u.game, parentPid, parentStart);
                result.success = true;
            } catch (Exception e) {
                result.message = e.getClass().getSimpleName() + ": " + e.getMessage(); System.err.println(result.message);
            } finally { result.finishedMillis = System.currentTimeMillis(); Updater.atomic(runtime.resolve("result.json"), json(result)); }
            if (!result.success) System.exit(1);
        }
        private static void waitForExactParent(long pid, String expected, DropInUpdater updater) throws Exception {
            Optional<ProcessHandle> parent = ProcessHandle.of(pid);
            if (parent.isEmpty() || !parent.get().isAlive()) return;
            Optional<Instant> start = parent.get().info().startInstant();
            if (start.isEmpty()) throw new IOException("Parent is alive but identity is unknown; replacement refused");
            if (!expected.equals(start.get().toString())) return; // PID reuse proves the original parent has exited.
            var exited = parent.get().onExit();
            while (true) {
                updater.observeClock();
                try { exited.get(200, TimeUnit.MILLISECONDS); updater.observeClock(); return; }
                catch (java.util.concurrent.TimeoutException e) { /* Observe clock rollback while awaiting the exact process. */ }
            }
        }
    }
}
