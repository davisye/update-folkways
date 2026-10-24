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
        String conflict = u.locked(() -> {
            if (System.currentTimeMillis() < currentStart().toEpochMilli()) throw new IOException("Clock moved behind this JVM's start; loading refused");
            Path legacy = u.home.resolve("self-journal.json"); u.safe(legacy);
            if (Files.exists(legacy)) throw new IOException("An unfinished legacy two-JAR transaction requires recovery with launch.ps1/launch.sh; this game load is refused");
            for (String name : List.of(JOURNAL, "journal.json", GitHubFolkways.JOURNAL)) {
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
                Config config = new Config(); config.localDevelopment = false;
                if (SelfUpdater.publishedBuild(build)) config.selfUpdateChannel = build.channel;
                Updater.atomic(configPath, json(config));
            }
            result.unique(jar);
            State state = result.state();
            if (state.owned == null) {
                state.owned = new SelfUpdater.Owned(result.u.game.relativize(jar).toString().replace('\\', '/'), Updater.sha(bytes), bytes.length);
                state.build = build; state.localBuild = !SelfUpdater.publishedBuild(build); state.highest = state.localBuild ? 0 : Math.max(0, build.sequence);
                state.highestSha256 = state.localBuild ? null : state.owned.sha256;
                result.save(state);
            } else {
                boolean sameBytes = state.owned.size == bytes.length && state.owned.sha256.equals(Updater.sha(bytes)) && SelfUpdater.sameBuild(state.build, build);
                boolean samePath = result.target(state).equals(jar);
                boolean matches = sameBytes && (samePath || !Files.exists(result.target(state)));
                Path protection = result.u.home.resolve("dropin-unmanaged.json"); result.u.safe(protection);
                if (matches) {
                    // A rename of exactly the recorded bytes changes no trust or anti-rollback state.
                    if (!samePath) { state.owned.path = result.u.game.relativize(jar).toString().replace('\\', '/'); result.save(state); }
                    Files.deleteIfExists(protection);
                } else {
                    // Observe manual bytes for a race-safe official replacement; this is not a new owned baseline.
                    Unmanaged value = new Unmanaged(); value.path = result.u.game.relativize(jar).toString().replace('\\', '/');
                    value.sha256 = Updater.sha(bytes); value.size = bytes.length; value.build = build;
                    Updater.atomic(protection, json(value));
                }
            }
            result.pruneHelpers();
            return null;
        });
        return result;
    }

    public String status() throws Exception {
        State state = state(); Config config = Config.load(u.game);
        return "drop-in updater=" + (state.build == null ? "unadopted" : state.build.version)
            + ", pending=" + (state.pending != null) + ", Folkways local=" + config.isLocalDevelopment(u.game)
            + ", self-update=" + config.selfUpdateEnabled + ", manual verification pending=" + unmanaged() + ", helper=" + helperAlive();
    }
    public boolean localProtection() throws Exception { return Config.load(u.game).isLocalDevelopment(u.game); }
    public String checkAndStage() throws Exception { return checkAndStageResult().message(); }
    public UpdateCheckResult checkAndStageResult() throws Exception {
        Config config = Config.load(u.game);
        if (!config.selfUpdateEnabled) return UpdateCheckResult.status("Updater self-update disabled");
        try { return u.locked(() -> {
            rejectLegacyTransaction();
            if (Files.exists(u.home.resolve(JOURNAL))) throw new IOException("An unfinished updater transaction requires exit recovery before checking updates");
            State state = state();
            if (state.owned == null) return UpdateCheckResult.status("Updater ownership not initialized");
            managed(config); Unmanaged observed = actual(state); restorePending(state);
            SelfUpdater.Source source = source(config);
            if (observed != null && SelfUpdater.publishedBuild(observed.build)) {
                State reconciled = null;
                try { reconciled = reconcileOfficial(state, observed, source); }
                catch (VerificationUnavailable ignored) { /* Historical nightly assets may be gone; verify the selected latest target independently. */ }
                if (reconciled != null) { state = reconciled; observed = null; }
            }
            SelfUpdater.ApiRelease release = SelfUpdater.officialRelease(source, config);
            SelfUpdater.Asset asset = selectedAsset(release);
            String digest = SelfUpdater.hashOf(asset);
            if (asset.size > LIMIT) throw new IOException("Updater asset exceeds limit");
            if (observed == null && digest.equals(state.owned.sha256)) { cleanCommittedPending(state); return UpdateCheckResult.status("Updater already current: " + state.build.version); }
            if (state.pending != null) {
                Proof previous = pending(state);
                if (digest.equals(SelfUpdater.hashOf(previous.asset)) && sameObserved(observed, previous.observed)) return reminder("Updater already downloaded; installs after the game JVM exits", previous);
            }
            byte[] bytes = source.asset(asset.browser_download_url, asset.size, digest);
            Updater.verify(bytes, digest, asset.size);
            SelfUpdater.BuildInfo build = SelfUpdater.jar(bytes, "companion", null);
            validateBuild(build, asset, release, config);
            byte[] runBytes = source.official(API + "/actions/runs/" + build.runId, 2 * 1024 * 1024);
            if (runBytes.length > 2 * 1024 * 1024) throw new IOException("CI response exceeds limit");
            SelfUpdater.Run run = decode(runBytes, SelfUpdater.Run.class);
            verifyRun(build, release, run);
            if (build.sequence < state.highest || build.sequence == state.highest && !digest.equals(highestDigest(state))) throw new IOException("Updater is older or reuses an accepted sequence");
            if (state.pending != null) {
                Proof previous = pending(state);
                if (build.sequence < previous.build.sequence || build.sequence == previous.build.sequence && !digest.equals(SelfUpdater.hashOf(previous.asset))) throw new IOException("Updater does not advance the queued build");
            }
            // Also bind the hidden executable payload to this exact outer JAR's complete build identity.
            helperPayload(bytes, build);
            SelfUpdater.ApiRelease after = SelfUpdater.officialRelease(source, config);
            if (!SelfUpdater.releaseFingerprint(release).equals(SelfUpdater.releaseFingerprint(after))) throw new IOException("Updater release changed during download");
            Proof proof = new Proof(); proof.asset = asset; proof.release = release; proof.run = run; proof.build = build; proof.observed = observed;
            byte[] proofBytes = json(proof);
            Path candidate = u.home.resolve("dropin-candidate"), queued = u.home.resolve("dropin-pending"), previous = u.home.resolve("dropin-pending.previous");
            u.clear(candidate); u.directory(candidate);
            try {
                Updater.atomic(candidate.resolve("mod.jar"), bytes); Updater.atomic(candidate.resolve("proof.json"), proofBytes);
                managed(config); verifyActual(state(), proof.observed);
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
            return reminder("Updater downloaded: " + build.version + "; installs after the game JVM exits", proof);
        }); } catch (VerificationUnavailable e) { return UpdateCheckResult.status("Updater verification temporarily unavailable; current updater retained and Folkways checks remain independent"); }
    }
    private UpdateCheckResult reminder(String message, Proof proof) throws IOException {
        return UpdateCheckResult.pending(message, "updater-self", proof.build.repository, proof.build.minecraft, proof.build.channel,
            proof.build.sequence, proof.build.commit, SelfUpdater.hashOf(proof.asset), proof.asset.size);
    }
    private SelfUpdater.Source source(Config config) {
        Transport transport = new Transport(config);
        return new SelfUpdater.Source() {
            public byte[] official(String url, long maximum) throws Exception {
                try { return injected == null ? transport.fetchOfficialApi(url, maximum) : injected.official(url, maximum); }
                catch (IOException e) { throw new VerificationUnavailable(e); }
            }
            public byte[] asset(String url, long maximum, String digest) throws Exception { return injected == null ? transport.fetchValidated(url, maximum, bytes -> Updater.verify(bytes, digest, maximum)) : injected.asset(url, maximum, digest); }
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
                if (alive(old) && !releaseOldHelper(old)) return "An earlier exit helper is still finishing; updater checks remain available";
            }
            pruneHelpers(); State state = state();
            Path installed = target(state); SelfUpdater.BuildInfo build = state.build;
            if (recovery && Files.exists(u.home.resolve(JOURNAL))) {
                Journal journal = read(u, u.home.resolve(JOURNAL), Journal.class);
                if (journal == null || journal.before == null || journal.after == null) throw new IOException("Invalid recovery ownership");
                SelfUpdater.Owned old = journal.actualBefore == null ? journal.before.owned : journal.actualBefore;
                State temporary = new State(); temporary.owned = old; installed = target(temporary);
                if (!target(journal.after).equals(installed)) throw new IOException("Invalid recovery target");
                unique(installed); byte[] current = u.readBounded(installed, LIMIT); String hash = Updater.sha(current);
                if (!hash.equals(old.sha256) && !hash.equals(journal.after.owned.sha256)) throw new IOException("Local changes block recovery helper");
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
            intent.installed = snapshot(installed, build);
            intent.runtime = u.game.relativize(runtime).toString().replace('\\', '/');
            Updater.atomic(runtime.resolve("intent.json"), json(intent));
            List<String> command = new ArrayList<>(List.of(javaExecutable().toString()));
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
        mutationCheck();
        u.locked(() -> { recover(); return null; });
        String selfReport = "";
        if (config.selfUpdateEnabled) {
            try { u.locked(() -> { applySelf(config); return null; }); }
            catch (RuntimeLease.ActiveGameException e) { throw e; }
            catch (Exception e) {
                if (Files.exists(u.home.resolve(JOURNAL)) || clockRollback) throw e;
                selfReport = "Updater installation deferred (" + e.getClass().getSimpleName() + "); ";
            }
        }
        // A configured signed Folkways channel remains independent of self-update publication.
        u.installGate = new Updater.InstallGate() {
            public void check() throws Exception { mutationCheck(); }
            public void committed() throws Exception { markInstalled(u, false); }
        };
        if (Files.exists(u.home.resolve("journal.json")) || Files.exists(u.home.resolve(GitHubFolkways.JOURNAL))
            || Files.exists(u.home.resolve("github-folkways-pending/plan.json")) || (!config.manifestUrl.isBlank() && Files.exists(u.home.resolve("pending/manifest.json")))) u.applyPending();
        return selfReport + "Exit helper completed; verified pending files installed or current files retained";
    }
    private void applySelf(Config config) throws Exception {
        State state = state(); restorePending(state);
        if (state.pending == null) return;
        mutationCheck(); managed(config);
        Proof proof = pending(state);
        verifyActual(state, proof.observed);
        String digest = SelfUpdater.hashOf(proof.asset);
        if (proof.build.sequence < state.highest || proof.build.sequence == state.highest && !digest.equals(highestDigest(state))) throw new IOException("Queued updater is not newer");
        Path target = proof.observed == null ? target(state) : observedTarget(proof.observed), queued = u.home.resolve("dropin-pending/mod.jar");
        byte[] next = u.readBounded(queued, LIMIT); Updater.verify(next, SelfUpdater.hashOf(proof.asset), proof.asset.size);
        Updater.atomic(u.home.resolve("dropin-backup.jar"), u.readBounded(target, LIMIT));
        Journal journal = new Journal(); journal.before = state;
        journal.actualBefore = proof.observed == null ? state.owned : new SelfUpdater.Owned(proof.observed.path, proof.observed.sha256, proof.observed.size);
        journal.after = decode(json(state), State.class); journal.after.build = proof.build; journal.after.highest = Math.max(state.highest, proof.build.sequence); journal.after.highestSha256 = digest;
        journal.after.localBuild = false;
        journal.after.owned = new SelfUpdater.Owned(journal.actualBefore.path, digest, proof.asset.size); journal.after.pending = null;
        mutationCheck(); managed(config); verifyActual(state, proof.observed); Updater.atomic(u.home.resolve(JOURNAL), json(journal));
        mutationCheck(); managed(config); verifyActual(state, proof.observed); Updater.atomic(target, next);
        mutationCheck(); managed(config); Updater.verify(u.readBounded(target, LIMIT), digest, proof.asset.size); save(journal.after);
        markInstalled(u, false); Files.delete(u.home.resolve(JOURNAL)); Files.deleteIfExists(u.home.resolve("dropin-unmanaged.json")); u.clear(u.home.resolve("dropin-pending"));
    }
    private void recover() throws Exception {
        Path path = u.home.resolve(JOURNAL); if (!Files.exists(path)) return;
        Config config = Config.load(u.game); mutationCheck(); managed(config);
        Journal journal = read(u, path, Journal.class);
        if (journal == null || journal.before == null || journal.after == null || journal.before.owned == null || journal.after.owned == null
            ) throw new IOException("Invalid drop-in recovery journal");
        SelfUpdater.Owned actualBefore = journal.actualBefore == null ? journal.before.owned : journal.actualBefore;
        if (!Objects.equals(actualBefore.path, journal.after.owned.path)) throw new IOException("Invalid recovery path");
        State actual = new State(); actual.owned = actualBefore; Path target = target(actual); unique(target);
        byte[] backup = u.readBounded(u.home.resolve("dropin-backup.jar"), LIMIT);
        Updater.verify(backup, actualBefore.sha256, actualBefore.size);
        String current = Updater.sha(u.readBounded(target, LIMIT));
        if (!current.equals(actualBefore.sha256) && !current.equals(journal.after.owned.sha256)) throw new IOException("Local file changes block updater recovery");
        mutationCheck(); managed(config);
        current = Updater.sha(u.readBounded(target, LIMIT));
        if (!current.equals(actualBefore.sha256) && !current.equals(journal.after.owned.sha256)) throw new IOException("Local file changes appeared during updater recovery");
        Updater.atomic(target, backup);
        State restored = decode(json(journal.before), State.class);
        if (journal.after.highest > restored.highest) { restored.highest = journal.after.highest; restored.highestSha256 = highestDigest(journal.after); }
        if (!actualBefore.path.equals(restored.owned.path) || !actualBefore.sha256.equals(restored.owned.sha256)) {
            Unmanaged observed = new Unmanaged(); observed.path = actualBefore.path; observed.sha256 = actualBefore.sha256; observed.size = actualBefore.size;
            observed.build = SelfUpdater.jar(backup, "companion", null); Updater.atomic(u.home.resolve("dropin-unmanaged.json"), json(observed));
        }
        mutationCheck(); managed(config); save(restored); markInstalled(u, false); Files.delete(path);
    }

    private void managed(Config expected) throws Exception {
        Config now = Config.load(u.game);
        if (!now.selfUpdateEnabled || !now.selfUpdateChannel.equals(expected.selfUpdateChannel)) throw new IOException("Self-update is disabled or its channel changed");
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
    private Unmanaged actual(State state) throws Exception {
        if (!unmanaged()) { owned(state); return null; }
        Unmanaged observed = read(u, u.home.resolve("dropin-unmanaged.json"), Unmanaged.class); verifyObserved(observed); return observed;
    }
    private Path observedTarget(Unmanaged observed) throws IOException {
        if (observed == null) throw new IOException("Missing observed updater installation");
        State temporary = new State(); temporary.owned = new SelfUpdater.Owned(observed.path, observed.sha256, observed.size); return target(temporary);
    }
    private void verifyObserved(Unmanaged observed) throws Exception {
        Path path = observedTarget(observed); unique(path); byte[] bytes = u.readBounded(path, LIMIT); Updater.verify(bytes, observed.sha256, observed.size);
        if (observed.build == null || !SelfUpdater.sameBuild(SelfUpdater.jar(bytes, "companion", null), observed.build)) throw new IOException("The manually installed updater changed after it was observed");
    }
    private void verifyActual(State state, Unmanaged observed) throws Exception { if (observed == null) owned(state); else verifyObserved(observed); }
    private Unmanaged snapshot(Path installed, SelfUpdater.BuildInfo build) throws Exception {
        byte[] bytes = u.readBounded(installed, LIMIT); Unmanaged snapshot = new Unmanaged(); snapshot.path = u.game.relativize(installed).toString().replace('\\', '/');
        snapshot.sha256 = Updater.sha(bytes); snapshot.size = bytes.length; snapshot.build = build; return snapshot;
    }
    private static boolean sameObserved(Unmanaged a, Unmanaged b) { return a == null ? b == null : b != null && Objects.equals(a.path, b.path) && Objects.equals(a.sha256, b.sha256) && a.size == b.size && SelfUpdater.sameBuild(a.build, b.build); }
    private static String highestDigest(State state) {
        if (state.highestSha256 != null) return state.highestSha256;
        return state.build != null && state.build.sequence == state.highest && state.owned != null ? state.owned.sha256 : null;
    }
    private State reconcileOfficial(State state, Unmanaged observed, SelfUpdater.Source source) throws Exception {
        Config manual = new Config(); manual.selfUpdateChannel = observed.build.channel;
        SelfUpdater.ApiRelease release = SelfUpdater.officialRelease(source, manual);
        SelfUpdater.Asset asset = selectedAsset(release);
        if (!asset.name.equals("folkways-updater-neoforge-1.21.1-" + observed.build.version + ".jar") || asset.size != observed.size || !SelfUpdater.hashOf(asset).equals(observed.sha256)) return null;
        validateBuild(observed.build, asset, release, manual);
        byte[] response = source.official(API + "/actions/runs/" + observed.build.runId, 2 * 1024 * 1024);
        if (response.length > 2 * 1024 * 1024) throw new IOException("CI response exceeds limit");
        verifyRun(observed.build, release, decode(response, SelfUpdater.Run.class));
        if (!SelfUpdater.releaseFingerprint(release).equals(SelfUpdater.releaseFingerprint(SelfUpdater.officialRelease(source, manual)))) throw new IOException("Manual updater release changed during verification");
        verifyObserved(observed);
        String highest = highestDigest(state);
        if (observed.build.sequence == state.highest && highest != null && !highest.equals(observed.sha256)) throw new IOException("Manual updater reuses an accepted sequence with different bytes");
        state.highestSha256 = observed.build.sequence >= state.highest ? observed.sha256 : highest;
        state.highest = Math.max(state.highest, observed.build.sequence); state.build = observed.build; state.localBuild = false;
        state.owned = new SelfUpdater.Owned(observed.path, observed.sha256, observed.size); save(state); Files.deleteIfExists(u.home.resolve("dropin-unmanaged.json")); return state;
    }
    private void cleanCommittedPending(State state) throws Exception {
        if (state.pending == null) return;
        Proof proof = pending(state);
        if (!SelfUpdater.hashOf(proof.asset).equals(state.owned.sha256) || !SelfUpdater.sameBuild(proof.build, state.build)) return;
        state.pending = null; save(state); u.clear(u.home.resolve("dropin-pending"));
    }
    private boolean releaseOldHelper(HelperOwner old) throws Exception {
        Optional<ProcessHandle> parent = ProcessHandle.of(old.parentPid);
        if (parent.isPresent() && parent.get().isAlive() && parent.get().info().startInstant().map(i -> i.toString().equals(old.parentStart)).orElse(true)) return false;
        // A new game lease and update.lock already exclude installation. Request voluntary exit; never kill a process.
        Path runtime = u.game.resolve(old.runtime).normalize(); u.safe(runtime);
        if (!Objects.equals(runtime.getParent(), u.home.resolve("dropin-runtime")) || !runtime.getFileName().toString().matches("helper-[A-Za-z0-9-]+")) throw new IOException("Invalid earlier helper runtime");
        HelperOwner owner = read(u, runtime.resolve("owner.json"), HelperOwner.class);
        HelperOwner intent = read(u, runtime.resolve("intent.json"), HelperOwner.class);
        HelperOwner launched = read(u, runtime.resolve("launched.json"), HelperOwner.class);
        if (!sameHelper(old, owner) || !sameHelper(old, launched) || intent == null || intent.parentPid != old.parentPid
            || !Objects.equals(intent.parentStart, old.parentStart) || !Objects.equals(intent.runtime, old.runtime) || !Objects.equals(intent.token, old.token))
            throw new IOException("Earlier helper capability does not match its runtime; leave it to finish after this game exits");
        Updater.atomic(runtime.resolve("cancel.json"), json(old));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (alive(old) && System.nanoTime() < deadline) Thread.sleep(25);
        return !alive(old);
    }
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

    static final class State { SelfUpdater.Owned owned; SelfUpdater.BuildInfo build; boolean localBuild; long highest; String highestSha256, pending; }
    static final class Unmanaged { String path, sha256; long size; SelfUpdater.BuildInfo build; }
    static final class Proof { SelfUpdater.Asset asset; SelfUpdater.ApiRelease release; SelfUpdater.Run run; SelfUpdater.BuildInfo build; Unmanaged observed; }
    static final class Journal { State before, after; SelfUpdater.Owned actualBefore; }
    static final class InstallMark { long finishedMillis; boolean clockAnomaly; }
    static final class HelperOwner { long pid, parentPid; String start, parentStart, token, runtime; Unmanaged installed; }
    static final class VerificationUnavailable extends IOException { VerificationUnavailable(IOException cause) { super("Official updater verification is unavailable", cause); } }
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
                waitForExactParent(parentPid, parentStart, updater, intent, runtime);
                cancelled(u, runtime, intent);
                if (intent.installed != null) updater.verifyObserved(intent.installed);
                try (FileChannel session = FileChannel.open(u.home.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock sessionLock = session.tryLock()) {
                    if (sessionLock == null) throw new IOException("A legacy supervisor is active; exit installation refused");
                    while (true) {
                        cancelled(u, runtime, intent);
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
        private static void waitForExactParent(long pid, String expected, DropInUpdater updater, HelperOwner intent, Path runtime) throws Exception {
            Optional<ProcessHandle> parent = ProcessHandle.of(pid);
            if (parent.isEmpty() || !parent.get().isAlive()) return;
            Optional<Instant> start = parent.get().info().startInstant();
            if (start.isEmpty()) throw new IOException("Parent is alive but identity is unknown; replacement refused");
            if (!expected.equals(start.get().toString())) return; // PID reuse proves the original parent has exited.
            var exited = parent.get().onExit();
            while (true) {
                cancelled(updater.u, runtime, intent);
                updater.observeClock();
                try { exited.get(200, TimeUnit.MILLISECONDS); updater.observeClock(); return; }
                catch (java.util.concurrent.TimeoutException e) { /* Observe clock rollback while awaiting the exact process. */ }
            }
        }
        private static void cancelled(Updater u, Path runtime, HelperOwner intent) throws Exception {
            Path file = runtime.resolve("cancel.json"); u.safe(file);
            if (Files.exists(file) && sameHelper(intent, read(u, file, HelperOwner.class))) throw new IOException("Earlier helper voluntarily cancelled before installation");
        }
    }
}
