package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SelfUpdaterTest {
    @TempDir Path game;
    Config config;
    SelfUpdater.Metadata metadata;
    SelfUpdater.ApiRelease release;
    SelfUpdater.Run run;
    Map<String, byte[]> downloads;
    SelfUpdater updater;
    byte[] oldBootstrap, oldCompanion;
    int officialCalls, releaseReads;
    boolean offline, corruptCompanion, changeRelease;
    Consumer<String> beforeAsset = ignored -> {};
    final String api = "https://api.github.com/repos/davisye/update-folkways";

    void fixture() throws Exception {
        Updater.initialize(game); config = new Config(); config.localDevelopment = false; config.restartCooldownSeconds = 1; saveConfig();
        Files.createDirectories(game.resolve(".folkways-updater/bin")); Files.createDirectories(game.resolve("mods"));
        SelfUpdater.Metadata old = metadata(1); oldBootstrap = jar(old, "bootstrap"); oldCompanion = jar(old, "companion");
        Files.write(bin(), oldBootstrap); Files.write(companion(), oldCompanion);
        publish(2);
        updater = new SelfUpdater(game, source(), game.resolve(".folkways-updater/runtime/launch-test/runner.jar"));
        updater.adopt(companion());
    }
    Path bin() { return game.resolve(".folkways-updater/bin/folkways-bootstrap.jar"); }
    Path companion() { return game.resolve("mods/folkways-updater-installed.jar"); }
    Path pending() { return game.resolve(".folkways-updater/self-pending"); }
    void saveConfig() throws Exception { Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(config)); }
    SelfUpdater.Metadata metadata(long number) {
        SelfUpdater.Metadata value = new SelfUpdater.Metadata(); value.schema = 1; value.repository = SelfUpdater.REPOSITORY;
        value.minecraft = "1.21.1"; value.channel = "nightly"; value.version = "0.1.0-nightly." + number + ".1";
        value.commit = Long.toHexString(number).repeat(40).substring(0, 40); value.runId = Long.toString(9000 - number);
        value.runNumber = number; value.runAttempt = 1; value.sequence = number * 1000 + 1; value.artifacts = new ArrayList<>(); return value;
    }
    void publish(long number) throws Exception {
        metadata = metadata(number); downloads = new HashMap<>();
        release = new SelfUpdater.ApiRelease(); release.id = 20; release.url = api + "/releases/20"; release.tag_name = "nightly"; release.prerelease = true;
        release.target_commitish = metadata.commit; release.assets = new ArrayList<>();
        for (String component : List.of("bootstrap", "companion")) {
            byte[] bytes = jar(metadata, component);
            SelfUpdater.Artifact artifact = new SelfUpdater.Artifact(); artifact.component = component;
            artifact.fileName = component.equals("bootstrap") ? "folkways-bootstrap-" + metadata.version + "-standalone.jar" : "folkways-updater-neoforge-1.21.1-" + metadata.version + ".jar";
            artifact.sha256 = Updater.sha(bytes); artifact.size = bytes.length;
            artifact.url = "https://github.com/davisye/update-folkways/releases/download/nightly/" + artifact.fileName;
            metadata.artifacts.add(artifact); asset(artifact.fileName, bytes);
        }
        asset("update-metadata.json", json(metadata));
        run = new SelfUpdater.Run(); run.id = Long.parseLong(metadata.runId); run.run_number = metadata.runNumber; run.run_attempt = 1;
        run.status = "completed"; run.conclusion = "success"; run.event = "push"; run.path = ".github/workflows/ci.yml";
        run.head_sha = metadata.commit; run.head_branch = "main"; run.repository = new SelfUpdater.Repo(); run.repository.full_name = SelfUpdater.REPOSITORY;
        run.head_repository = new SelfUpdater.Repo(); run.head_repository.full_name = SelfUpdater.REPOSITORY;
    }
    void asset(String name, byte[] bytes) throws Exception {
        SelfUpdater.Asset asset = new SelfUpdater.Asset(); asset.id = 100 + release.assets.size(); asset.name = name;
        asset.size = bytes.length; asset.state = "uploaded"; asset.digest = "sha256:" + Updater.sha(bytes);
        asset.browser_download_url = "https://github.com/davisye/update-folkways/releases/download/nightly/" + name;
        release.assets.removeIf(value -> value.name.equals(name)); release.assets.add(asset); downloads.put(asset.browser_download_url, bytes);
    }
    SelfUpdater.Source source() {
        return new SelfUpdater.Source() {
            public byte[] official(String url, long maximum) throws Exception {
                officialCalls++; assertTrue(url.startsWith(api + "/"));
                if (offline) throw new IOException("official API unavailable");
                if (url.contains("/actions/runs/")) return json(run);
                releaseReads++;
                if (changeRelease && releaseReads > 1) release.id++;
                return json(release);
            }
            public byte[] asset(String url, long maximum, String digest) throws Exception {
                beforeAsset.accept(url);
                byte[] bytes = downloads.get(url).clone();
                if (corruptCompanion && url.contains("folkways-updater-neoforge")) bytes[0] ^= 1;
                return bytes;
            }
        };
    }
    static byte[] json(Object value) { return Updater.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    static byte[] jar(SelfUpdater.Metadata metadata, String component) throws Exception {
        SelfUpdater.BuildInfo build = new SelfUpdater.BuildInfo(); build.schema = 1; build.repository = metadata.repository;
        build.component = component; build.version = metadata.version; build.commit = metadata.commit; build.runId = metadata.runId;
        build.runNumber = metadata.runNumber; build.runAttempt = metadata.runAttempt; build.sequence = metadata.sequence;
        build.channel = metadata.channel; build.minecraft = metadata.minecraft;
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\nMain-Class: dev.davisye.folkways.update.core.Bootstrap\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/folkways-updater-build.json", json(build));
        entries.put("dev/davisye/folkways/update/core/Bootstrap.class", new byte[]{(byte)0xca, (byte)0xfe, (byte)0xba, (byte)0xbe, 0, 0, 0, 65});
        if (component.equals("companion")) entries.put("META-INF/neoforge.mods.toml", ("[[mods]]\nmodId=\"folkways_updater\"\nversion=\"" + metadata.version + "\"\n[[dependencies.folkways_updater]]\nmodId=\"minecraft\"\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(result)) {
            for (var entry : entries.entrySet()) { ZipEntry item = new ZipEntry(entry.getKey()); item.setTime(0); zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return result.toByteArray();
    }
    void assertOld() throws Exception { assertArrayEquals(oldBootstrap, Files.readAllBytes(bin())); assertArrayEquals(oldCompanion, Files.readAllBytes(companion())); }

    @Test void copiedHelperStagesThenActuallyReplacesBothUpdaterJarsBeforeLaunch() throws Exception {
        fixture(); updater.checkAndStage(); assertOld(); assertTrue(Files.exists(pending()));
        assertTrue(updater.prepare().contains("Updater installed"));
        for (var artifact : metadata.artifacts) assertArrayEquals(downloads.get(artifact.url), Files.readAllBytes(artifact.component.equals("bootstrap") ? bin() : companion()));
        assertFalse(Files.exists(pending())); assertFalse(Files.exists(game.resolve(".folkways-updater/self-journal.json")));
        assertTrue(updater.status().contains(metadata.version)); assertTrue(officialCalls > 0);
    }
    @Test void officialApiOfflineKeepsCurrentAndAllowsTheNextGameLaunch() throws Exception {
        fixture(); offline = true; assertTrue(updater.prepare().contains("current files retained")); assertOld(); assertFalse(Files.exists(pending()));
    }
    @Test void selfUpdateStagesWithoutAnyFolkwaysManifestOrPublicKeys() throws Exception {
        fixture(); assertTrue(config.manifestUrl.isBlank()); assertTrue(config.publicKeys.isEmpty());
        assertTrue(new Updater(game).checkAndStage().contains("current NeoForge version"));
        assertTrue(updater.checkAndStage().contains("downloaded")); assertTrue(Files.exists(pending().resolve("metadata.json"))); assertOld();
    }
    @Test void localConfigMarkerAndLocalBuildProtectBeforeNetwork() throws Exception {
        fixture(); config.localDevelopment = true; saveConfig(); assertTrue(updater.checkAndStage().contains("Local development")); assertEquals(0, officialCalls);
        config.localDevelopment = false; saveConfig(); Files.writeString(game.resolve(".folkways-updater/LOCAL_DEVELOPMENT"), "");
        assertTrue(updater.prepare().contains("retained")); assertEquals(0, officialCalls); assertOld();
    }
    @Test void localCiIdentityNeverGetsAutomaticallyReplaced() throws Exception {
        Updater.initialize(game); config = new Config(); config.localDevelopment = false; saveConfig();
        Files.createDirectories(game.resolve(".folkways-updater/bin")); Files.createDirectories(game.resolve("mods"));
        SelfUpdater.Metadata local = metadata(1); local.sequence = 0; local.runNumber = 0; local.runId = ""; local.runAttempt = 0; local.channel = "local"; local.commit = "local";
        Files.write(bin(), jar(local, "bootstrap")); Files.write(companion(), jar(local, "companion"));
        publish(2); updater = new SelfUpdater(game, source(), game.resolve("copied-runner.jar")); updater.adopt(companion());
        assertTrue(updater.checkAndStage().contains("Locally compiled")); assertEquals(0, officialCalls);
    }
    @Test void runningInstalledBootstrapCannotOverwriteItself() throws Exception {
        fixture(); SelfUpdater direct = new SelfUpdater(game, source(), bin());
        assertThrows(IOException.class, direct::prepare); assertOld(); assertEquals(0, officialCalls);
    }
    @Test void liveGameLeaseAndSupervisorLockBothBlockTheHelper() throws Exception {
        fixture(); SelfUpdater.recordGameProcess(game); assertThrows(IOException.class, updater::prepare); assertOld();
        Files.delete(game.resolve(".folkways-updater/game-leases/" + ProcessHandle.current().pid() + ".json"));
        try (FileChannel channel = FileChannel.open(game.resolve(".folkways-updater/session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            assertThrows(IOException.class, updater::prepare); assertOld();
        }
    }
    @Test void partialDownloadOrMidDownloadLocalModeKeepsOldCompletePending() throws Exception {
        fixture(); updater.checkAndStage(); byte[] old = Files.readAllBytes(pending().resolve("metadata.json"));
        publish(3); corruptCompanion = true; assertThrows(IOException.class, updater::checkAndStage);
        assertArrayEquals(old, Files.readAllBytes(pending().resolve("metadata.json"))); assertOld();
        corruptCompanion = false; beforeAsset = url -> { if (url.contains("folkways-updater-neoforge")) { config.localDevelopment = true; try { saveConfig(); } catch (Exception e) { throw new RuntimeException(e); } } };
        assertThrows(IOException.class, updater::checkAndStage); assertArrayEquals(old, Files.readAllBytes(pending().resolve("metadata.json"))); assertOld();
    }
    @Test void refusesMissingOfficialDigestWrongCiAndChangingReleases() throws Exception {
        fixture(); release.assets.getFirst().digest = null; assertThrows(IOException.class, updater::checkAndStage); assertOld();
        publish(2); run.conclusion = "failure"; assertThrows(IOException.class, updater::checkAndStage);
        run.conclusion = "success"; run.path = ".github/workflows/evil.yml"; assertThrows(IOException.class, updater::checkAndStage);
        run.path = ".github/workflows/ci.yml"; run.event = "pull_request"; assertThrows(IOException.class, updater::checkAndStage);
        run.event = "push"; changeRelease = true; releaseReads = 0; assertThrows(IOException.class, updater::checkAndStage); assertFalse(Files.exists(pending()));
    }
    @Test void runNumberControlsSequenceRatherThanUniquenessOnlyRunId() throws Exception {
        fixture(); assertEquals(8998, Long.parseLong(metadata.runId)); updater.prepare();
        publish(3); assertEquals(8997, Long.parseLong(metadata.runId)); assertTrue(updater.prepare().contains("installed"));
        publish(2); assertThrows(IOException.class, updater::checkAndStage);
    }
    @Test void rejectsSameVersionMixedBuildAndRenamedDuplicateCompanion() throws Exception {
        fixture(); Files.delete(game.resolve(".folkways-updater/self-state.json"));
        SelfUpdater.Metadata mixed = metadata(1); mixed.commit = "f".repeat(40); Files.write(companion(), jar(mixed, "companion"));
        assertThrows(IOException.class, () -> updater.adopt(companion()));
        Files.write(companion(), oldCompanion); updater.adopt(companion());
        Files.write(game.resolve("mods/renamed-local.jar"), oldCompanion);
        assertThrows(IOException.class, updater::checkAndStage); assertOld();
    }
    @Test void adoptsAndUpdatesThroughAnExternalGameRootAlias(@TempDir Path aliases) throws Exception {
        fixture(); Files.delete(game.resolve(".folkways-updater/self-state.json"));
        Path alias = aliases.resolve("game-alias"); directoryAlias(alias, game.toRealPath());
        try {
            SelfUpdater aliased = new SelfUpdater(alias, source(), alias.resolve("copied-runner.jar"));
            assertTrue(aliased.adopt(alias.resolve("mods/folkways-updater-installed.jar")).contains("ownership recorded"));
            assertTrue(aliased.prepare().contains("Updater installed"));
            assertArrayEquals(downloads.get(metadata.artifacts.get(1).url), Files.readAllBytes(companion()));
        } finally { Files.delete(alias); }
    }
    @Test void rootAliasCannotHideAnInternalLinkBackToTheGame(@TempDir Path aliases) throws Exception {
        fixture(); Files.delete(game.resolve(".folkways-updater/self-state.json"));
        Path alias = aliases.resolve("game-alias"), inside = game.resolve("inside-link");
        directoryAlias(alias, game.toRealPath()); directoryAlias(inside, game.toRealPath());
        try {
            SelfUpdater aliased = new SelfUpdater(alias, source(), alias.resolve("copied-runner.jar"));
            assertThrows(IOException.class, () -> aliased.adopt(alias.resolve("inside-link/mods/folkways-updater-installed.jar")));
            assertFalse(Files.exists(game.resolve(".folkways-updater/self-state.json"))); assertOld();
        } finally { Files.delete(inside); Files.delete(alias); }
    }
    @Test void managedModsDirectoryLinkIsStillRefused() throws Exception {
        fixture(); Path actual = game.resolve("actual-mods"); Files.move(game.resolve("mods"), actual);
        Path linked = game.resolve("mods"); directoryAlias(linked, actual.toRealPath());
        try { assertThrows(IOException.class, () -> new SelfUpdater(game)); }
        finally { Files.delete(linked); Files.move(actual, linked); }
    }
    @Test void companionFileLinkIsRefusedBeforeAdoption() throws Exception {
        fixture(); Files.delete(game.resolve(".folkways-updater/self-state.json"));
        Path actual = game.resolve("actual-companion.jar"); Files.move(companion(), actual);
        try {
            try { Files.createSymbolicLink(companion(), actual.toRealPath()); }
            catch (FileSystemException | UnsupportedOperationException e) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "File symlinks unavailable: " + e.getClass().getSimpleName());
            }
            assertThrows(IOException.class, () -> updater.adopt(companion()));
            assertFalse(Files.exists(game.resolve(".folkways-updater/self-state.json")));
        } finally { Files.deleteIfExists(companion()); Files.move(actual, companion()); }
    }
    @Test void sameNamedCompanionOutsideGameCannotBeAdopted(@TempDir Path outside) throws Exception {
        fixture(); Files.delete(game.resolve(".folkways-updater/self-state.json"));
        Path other = outside.resolve(companion().getFileName()); Files.write(other, oldCompanion);
        assertThrows(IOException.class, () -> updater.adopt(other)); assertOld();
    }
    @Test void adoptsThroughWindowsShortGameRootWhenAvailable(@TempDir Path parent) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        game = Files.createDirectory(parent.resolve("updater game root with long names")); fixture();
        Files.delete(game.resolve(".folkways-updater/self-state.json"));
        Process command = new ProcessBuilder("cmd.exe", "/d", "/c", "for %I in (\"" + game + "\") do @echo %~sI").redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, command.waitFor(), output); Path shortGame = Path.of(output);
        org.junit.jupiter.api.Assumptions.assumeFalse(shortGame.equals(game.toRealPath()), "This volume does not expose an 8.3 root alias");
        assertTrue(Files.isSameFile(shortGame, game));
        SelfUpdater aliased = new SelfUpdater(shortGame, source(), shortGame.resolve("copied-runner.jar"));
        assertTrue(aliased.adopt(shortGame.resolve("mods/folkways-updater-installed.jar")).contains("ownership recorded"));
        assertTrue(aliased.prepare().contains("Updater installed"));
    }
    static void directoryAlias(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) { Files.createSymbolicLink(link, target); return; }
        Process command = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, command.waitFor(), output); assertTrue(Files.isSameFile(link, target));
    }
    @Test void localRecompilationAfterAdoptionIsNeverOverwritten() throws Exception {
        fixture(); updater.checkAndStage(); byte[] local = oldBootstrap.clone(); local[0] ^= 1; Files.write(bin(), local);
        assertTrue(updater.prepare().contains("current files retained")); assertArrayEquals(local, Files.readAllBytes(bin()));
    }
    @Test void interruptedTwoJarTransactionRestoresBothBeforeAnyNewJvm() throws Exception {
        fixture(); updater.checkAndStage();
        SelfUpdater.State before = Updater.JSON.fromJson(Files.readString(game.resolve(".folkways-updater/self-state.json")), SelfUpdater.State.class);
        SelfUpdater.State after = new SelfUpdater.State(); after.version = metadata.version; after.highest = metadata.sequence;
        for (var artifact : metadata.artifacts) after.owned.put(artifact.component, new SelfUpdater.Owned(before.owned.get(artifact.component).path, artifact.sha256, artifact.size));
        Files.createDirectories(game.resolve(".folkways-updater/self-backup"));
        Files.write(game.resolve(".folkways-updater/self-backup/bootstrap.jar"), oldBootstrap); Files.write(game.resolve(".folkways-updater/self-backup/companion.jar"), oldCompanion);
        SelfUpdater.Journal journal = new SelfUpdater.Journal(); journal.before = before; journal.after = after;
        Files.write(game.resolve(".folkways-updater/self-journal.json"), json(journal));
        Files.write(bin(), downloads.get(metadata.artifacts.getFirst().url)); updater.recover(config); assertOld();
        assertFalse(Files.exists(game.resolve(".folkways-updater/self-journal.json")));
    }
    @Test void deadRuntimeCleanupRetainsAliveAndAmbiguousRunners() throws Exception {
        fixture(); Path root = game.resolve(".folkways-updater/runtime"); Files.createDirectories(root);
        for (String name : List.of("launch-dead", "launch-live", "launch-unknown")) { Files.createDirectory(root.resolve(name)); Files.writeString(root.resolve(name + "/runner.jar"), "data"); }
        RuntimeLease.Lease dead = new RuntimeLease.Lease(); dead.pid = Long.MAX_VALUE; dead.start = "2000-01-01T00:00:00Z";
        RuntimeLease.Lease live = new RuntimeLease.Lease(); live.pid = ProcessHandle.current().pid(); live.start = ProcessHandle.current().info().startInstant().orElseThrow().toString();
        Files.write(root.resolve("launch-dead/owner.json"), json(dead)); Files.write(root.resolve("launch-live/owner.json"), json(live));
        RuntimeLease.pruneDead(new Updater(game)); assertFalse(Files.exists(root.resolve("launch-dead")));
        assertTrue(Files.exists(root.resolve("launch-live/runner.jar"))); assertTrue(Files.exists(root.resolve("launch-unknown/runner.jar")));
    }
    @Test void seedDoesNotBounceOrdinaryChildExit75AndCarriesItsRestartBudget() throws Exception {
        fixture(); config.localDevelopment = true; config.restartLimit = 1; saveConfig();
        assertEquals(1, seedChild("75", "0"));
        assertEquals(75, seedChild("request", "0"));
        assertEquals(1, seedChild("request", "1"));
    }
    @Test void repeatedlySupervisesActualFastJavaExitsWithoutLeavingLaunchBarriers() throws Exception {
        fixture(); config.localDevelopment = true; saveConfig();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        for (int attempt = 0; attempt < 16; attempt++) {
            assertEquals(0, Bootstrap.supervise(new Updater(game), List.of(java.toString(), "-version"), false), "fast JVM attempt " + attempt);
            assertFalse(Files.exists(game.resolve(".folkways-updater/child.json")), "barrier retained after exited JVM " + attempt);
        }
    }
    @Test void missingIdentityOfConfirmedExitedChildMayReachNormalExitCollection() throws Exception {
        Updater.initialize(game); Path barrier = game.resolve(".folkways-updater/child.json");
        Files.writeString(barrier, "{\"phase\":\"launching\"}");
        Bootstrap.Child child = new Bootstrap.Child(); child.phase = "launching";
        assertDoesNotThrow(() -> Bootstrap.recordChildIdentity(new NoIdentityProcess(false), child, barrier));
        assertEquals("launching", child.phase);
    }
    @Test void missingIdentityOfStillAliveChildRetainsTheFailClosedLaunchBarrier() throws Exception {
        Updater.initialize(game); Path barrier = game.resolve(".folkways-updater/child.json");
        String launching = "{\"phase\":\"launching\"}"; Files.writeString(barrier, launching);
        Bootstrap.Child child = new Bootstrap.Child(); child.phase = "launching";
        assertThrows(IOException.class, () -> Bootstrap.recordChildIdentity(new NoIdentityProcess(true), child, barrier));
        assertEquals(launching, Files.readString(barrier)); assertEquals("launching", child.phase);
    }
    static final class NoIdentityProcess extends Process {
        private final boolean alive;
        NoIdentityProcess(boolean alive) { this.alive = alive; }
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { return exitValue(); }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public void destroy() { }
        public boolean isAlive() { return alive; }
        public long pid() { return 123; }
        public ProcessHandle.Info info() {
            return new ProcessHandle.Info() {
                public Optional<String> command() { return Optional.empty(); }
                public Optional<String> commandLine() { return Optional.empty(); }
                public Optional<String[]> arguments() { return Optional.empty(); }
                public Optional<java.time.Instant> startInstant() { return Optional.empty(); }
                public Optional<java.time.Duration> totalCpuDuration() { return Optional.empty(); }
                public Optional<String> user() { return Optional.empty(); }
            };
        }
    }
    int seedChild(String mode, String used) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(java))) java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = String.join(File.pathSeparator,
            Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Path.of(SelfUpdaterTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        ProcessBuilder process = new ProcessBuilder(java, "-cp", classpath, Bootstrap.class.getName(), "launch", "--game-dir", game.toString(), "--dedicated", "--", java, "-cp", classpath, ExitChild.class.getName(), mode);
        process.environment().put("FOLKWAYS_SEED_PROTOCOL", "1"); process.environment().put("FOLKWAYS_SEED_RESTART_COUNT", used);
        process.redirectErrorStream(true); Process child = process.start(); String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int result = child.waitFor(); assertFalse(output.contains("ClassNotFoundException"), output); return result;
    }
    public static final class ExitChild {
        public static void main(String[] arguments) throws Exception {
            if (arguments[0].equals("75")) { System.exit(75); return; }
            Path session = Path.of(System.getenv("FOLKWAYS_SUPERVISOR_DIR"));
            Files.writeString(session.resolve("restart-request.json"), "{\"action\":\"restart\",\"token\":\"" + System.getenv("FOLKWAYS_SUPERVISOR_TOKEN") + "\"}");
        }
    }
}
