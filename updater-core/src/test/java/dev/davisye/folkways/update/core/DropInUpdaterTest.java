package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class DropInUpdaterTest {
    @TempDir Path game;
    SelfUpdaterTest upstream;
    DropInUpdater updater;
    byte[] original;
    static final String MAIN = "dev.davisye.folkways.update.core.DropInUpdater$Helper";
    static final String RESOURCE = "META-INF/folkways-updater/helper.jar";
    Path mod() { return game.resolve("mods/folkways-updater-installed.jar"); }
    Path state() { return game.resolve(".folkways-updater/dropin-state.json"); }
    Path queued() { return game.resolve(".folkways-updater/dropin-pending"); }
    void fixture() throws Exception {
        Files.createDirectories(game.resolve("mods")); upstream = new SelfUpdaterTest();
        original = companion(upstream.metadata(1)); Files.write(mod(), original);
        publish(2); updater = DropInUpdater.initialize(game, mod(), upstream.source());
    }
    void publish(long run) throws Exception {
        upstream.publish(run);
        upstream.asset(upstream.metadata.artifacts.get(1).fileName, companion(upstream.metadata));
    }
    void deadGame() throws Exception { Files.deleteIfExists(game.resolve(".folkways-updater/game-leases/" + ProcessHandle.current().pid() + ".json")); }
    void local(boolean value) throws Exception {
        Config config = Config.load(game); config.localDevelopment = value;
        Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(config));
    }
    static byte[] companion(SelfUpdater.Metadata build) throws Exception {
        Map<String,byte[]> outer = entries(SelfUpdaterTest.jar(build, "companion")); outer.put(RESOURCE, helper(build)); return zip(outer);
    }
    static byte[] helper(SelfUpdater.Metadata build) throws Exception {
        Map<String,byte[]> result = new LinkedHashMap<>(entries(SelfUpdaterTest.jar(build, "bootstrap")));
        Path classes = Path.of(DropInUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var files = Files.walk(classes)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String relative = classes.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".class")) result.put(relative, Files.readAllBytes(path));
            }
        }
        Path gson = Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (var item : entries(Files.readAllBytes(gson)).entrySet()) {
            if (!item.getKey().startsWith("META-INF/") && !item.getKey().equals("module-info.class")) result.putIfAbsent(item.getKey(), item.getValue());
        }
        return zip(result);
    }
    static Map<String,byte[]> entries(byte[] bytes) throws Exception {
        Map<String,byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) if (!entry.isDirectory()) result.put(entry.getName(), zip.readAllBytes());
        } return result;
    }
    static byte[] zip(Map<String,byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var item : entries.entrySet()) { ZipEntry entry = new ZipEntry(item.getKey()); entry.setTime(0); zip.putNextEntry(entry); zip.write(item.getValue()); zip.closeEntry(); }
        } return output.toByteArray();
    }
    @Test void firstOfficialInstallUsesManagedDefaultsAndReplacesOnlyAfterExit() throws Exception {
        fixture(); assertFalse(Config.load(game).localDevelopment); assertTrue(Config.load(game).selfUpdateEnabled);
        assertFalse(Files.exists(game.resolve(".folkways-updater/bin")));
        assertTrue(updater.checkAndStage().contains("downloaded")); assertArrayEquals(original, Files.readAllBytes(mod()));
        assertThrows(RuntimeLease.ActiveGameException.class, updater::applyOffline);
        deadGame(); assertTrue(updater.applyOffline().contains("completed"));
        assertArrayEquals(upstream.downloads.get(upstream.metadata.artifacts.get(1).url), Files.readAllBytes(mod()));
        assertFalse(Files.exists(game.resolve(".folkways-updater/dropin-journal.json")));
        assertTrue(Files.exists(game.resolve(".folkways-updater/last-install.json")));
        assertThrows(IOException.class, () -> DropInUpdater.guardGame(game)); // This JVM predates the installation.
    }
    @Test void existingConfigIsPreservedAndLocalCiIdentityProtectsBeforeNetwork() throws Exception {
        Files.createDirectories(game.resolve("mods")); Updater.initialize(game);
        Config config = Config.load(game); config.commandsEnabled = true; Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(config));
        byte[] configBefore = Files.readAllBytes(game.resolve(".folkways-updater/config.json"));
        upstream = new SelfUpdaterTest(); SelfUpdater.Metadata build = upstream.metadata(1); build.runId = ""; build.runNumber = 0; build.runAttempt = 0; build.sequence = 0; build.commit = "local"; build.channel = "local";
        Files.write(mod(), companion(build)); upstream.publish(2);
        updater = DropInUpdater.initialize(game, mod(), upstream.source());
        assertArrayEquals(configBefore, Files.readAllBytes(game.resolve(".folkways-updater/config.json")));
        local(false); assertTrue(updater.checkAndStage().contains("Locally compiled")); assertEquals(0, upstream.officialCalls);
    }
    @Test void freshLocalBuildDefaultsToProtectedModeEvenWithoutAnyExistingConfiguration() throws Exception {
        Files.createDirectories(game.resolve("mods")); upstream = new SelfUpdaterTest(); SelfUpdater.Metadata build = upstream.metadata(1);
        build.runId = ""; build.runNumber = 0; build.runAttempt = 0; build.sequence = 0; build.commit = "local"; build.channel = "local";
        Files.write(mod(), companion(build)); upstream.publish(2);
        updater = DropInUpdater.initialize(game, mod(), upstream.source());
        assertTrue(Config.load(game).localDevelopment); assertTrue(updater.checkAndStage().contains("Local development")); assertEquals(0, upstream.officialCalls);
    }
    @Test void badDigestFailedCiDuplicateAssetAndDowngradeNeverChangeLiveOrOldQueue() throws Exception {
        fixture(); updater.checkAndStage(); byte[] proof = Files.readAllBytes(queued().resolve("proof.json"));
        publish(3); upstream.corruptCompanion = true; assertThrows(IOException.class, updater::checkAndStage);
        assertArrayEquals(proof, Files.readAllBytes(queued().resolve("proof.json")));
        upstream.corruptCompanion = false; upstream.run.event = "pull_request"; assertThrows(IOException.class, updater::checkAndStage);
        publish(3); upstream.release.assets.add(upstream.release.assets.stream().filter(a -> a.name.startsWith("folkways-updater-neoforge-")).findFirst().orElseThrow());
        assertThrows(IOException.class, updater::checkAndStage);
        publish(1); Map<String,byte[]> reused = entries(upstream.downloads.get(upstream.metadata.artifacts.get(1).url));
        reused.put("reused-sequence.txt", new byte[]{1}); upstream.asset(upstream.metadata.artifacts.get(1).fileName, zip(reused));
        assertThrows(IOException.class, updater::checkAndStage);
        assertArrayEquals(proof, Files.readAllBytes(queued().resolve("proof.json"))); assertArrayEquals(original, Files.readAllBytes(mod()));
    }
    @Test void localModeToggledDuringDownloadPreservesOldQueueAndBlocksOfflineReplacement() throws Exception {
        fixture(); updater.checkAndStage(); byte[] proof = Files.readAllBytes(queued().resolve("proof.json")); publish(3);
        upstream.beforeAsset = ignored -> { try { local(true); } catch (Exception e) { throw new RuntimeException(e); } };
        assertThrows(IOException.class, updater::checkAndStage); assertArrayEquals(proof, Files.readAllBytes(queued().resolve("proof.json")));
        deadGame(); assertTrue(updater.applyOffline().contains("Local development")); assertArrayEquals(original, Files.readAllBytes(mod()));
    }
    @Test void detectsLocalReplacementAndRenamedDuplicateRatherThanSilentlyTakingOwnership() throws Exception {
        fixture(); Files.write(game.resolve("mods/renamed-local.jar"), original);
        assertThrows(IOException.class, updater::checkAndStage); Files.delete(game.resolve("mods/renamed-local.jar"));
        byte[] changed = original.clone(); changed[10] ^= 1; Files.write(mod(), changed);
        assertThrows(IOException.class, updater::checkAndStage);
        DropInUpdater manual = DropInUpdater.initialize(game, mod(), upstream.source());
        assertTrue(manual.checkAndStage().contains("Manual/local"));
    }
    @Test void anExistingManagedInstallCanRunAnExplicitLocalRebuildWithoutResettingOwnership() throws Exception {
        fixture(); byte[] baseline = Files.readAllBytes(state()); local(true);
        SelfUpdater.Metadata local = upstream.metadata(1); local.commit = "local"; local.runId = ""; local.runNumber = 0; local.sequence = 0; local.runAttempt = 0; local.channel = "local";
        byte[] replacement = companion(local); Files.write(mod(), replacement);
        DropInUpdater development = DropInUpdater.initialize(game, mod(), upstream.source());
        assertArrayEquals(baseline, Files.readAllBytes(state())); assertTrue(development.checkAndStage().contains("Local development"));
        assertTrue(Files.exists(game.resolve(".folkways-updater/dropin-unmanaged.json")));
        deadGame(); assertTrue(development.applyOffline().contains("files retained")); assertArrayEquals(replacement, Files.readAllBytes(mod()));
    }
    @Test void manualOfficialVersionFilenameCanRunProtectedAndOriginalBaselineCanBeRestored() throws Exception {
        fixture(); byte[] baseline = Files.readAllBytes(state()); Path replacement = game.resolve("mods/folkways-updater-new-official.jar");
        Files.delete(mod()); byte[] newer = companion(upstream.metadata); Files.write(replacement, newer);
        DropInUpdater manual = DropInUpdater.initialize(game, replacement, upstream.source());
        assertArrayEquals(baseline, Files.readAllBytes(state())); assertTrue(manual.checkAndStage().contains("Manual/local"));
        deadGame(); manual.applyOffline(); assertArrayEquals(newer, Files.readAllBytes(replacement));
        Files.delete(replacement); Files.write(mod(), original);
        DropInUpdater restored = DropInUpdater.initialize(game, mod(), upstream.source());
        assertFalse(Files.exists(game.resolve(".folkways-updater/dropin-unmanaged.json"))); assertTrue(restored.checkAndStage().contains("downloaded"));
    }
    @Test void hiddenHelperMustMatchTheOuterBuildAndCannotBeSwapped() throws Exception {
        fixture(); Map<String,byte[]> bad = entries(companion(upstream.metadata)); bad.put(RESOURCE, helper(upstream.metadata(4)));
        upstream.asset(upstream.metadata.artifacts.get(1).fileName, zip(bad));
        assertThrows(IOException.class, updater::checkAndStage); assertFalse(Files.exists(queued())); assertArrayEquals(original, Files.readAllBytes(mod()));
    }
    @Test void unconfiguredFolkwaysNeedsNoUnsignedManifestOrExitNetwork() throws Exception {
        fixture(); updater.checkAndStage(); upstream.offline = true; deadGame();
        updater.applyOffline(); assertFalse(Files.exists(game.resolve(".folkways-updater/dropin-journal.json")));
        assertArrayEquals(upstream.downloads.get(upstream.metadata.artifacts.get(1).url), Files.readAllBytes(mod()));
    }
    @Test void legacyTwoJarJournalPreventsAdvancingTheCompanionIntoAThirdBuild() throws Exception {
        fixture(); updater.checkAndStage(); byte[] proof = Files.readAllBytes(queued().resolve("proof.json")); deadGame();
        Files.writeString(game.resolve(".folkways-updater/self-journal.json"), "{\"legacy\":true}");
        IOException failure = assertThrows(IOException.class, updater::applyOffline);
        assertTrue(failure.getMessage().contains("legacy two-JAR")); assertArrayEquals(original, Files.readAllBytes(mod()));
        assertArrayEquals(proof, Files.readAllBytes(queued().resolve("proof.json")));
        assertThrows(IOException.class, () -> DropInUpdater.guardGame(game));
        assertFalse(Files.exists(game.resolve(".folkways-updater/dropin-runtime")), "Legacy recovery must not repeatedly spawn an unsupported helper");
    }
    @Test void ideMarkerProtectsAHelperThatWasAlreadyQueued() throws Exception {
        fixture(); updater.checkAndStage(); DropInUpdater.guardGame(game, true); deadGame();
        assertTrue(updater.applyOffline().contains("Local development")); assertArrayEquals(original, Files.readAllBytes(mod()));
    }
    @Test void incompleteTransactionRejectsThisLoadButStartsRecoveryWaitingForThisJvm() throws Exception {
        fixture(); updater.checkAndStage();
        DropInUpdater.State before = Updater.JSON.fromJson(Files.readString(state()), DropInUpdater.State.class);
        DropInUpdater.Journal journal = new DropInUpdater.Journal(); journal.before = before;
        journal.after = Updater.JSON.fromJson(Updater.JSON.toJson(before), DropInUpdater.State.class);
        byte[] next = Files.readAllBytes(queued().resolve("mod.jar")); journal.after.owned = new SelfUpdater.Owned(before.owned.path, Updater.sha(next), next.length);
        journal.after.build = SelfUpdater.jar(next, "companion", null); journal.after.pending = null; journal.after.highest = journal.after.build.sequence;
        Files.write(game.resolve(".folkways-updater/dropin-backup.jar"), original);
        Files.writeString(game.resolve(".folkways-updater/dropin-journal.json"), Updater.JSON.toJson(journal)); Files.write(mod(), next);
        try {
            assertThrows(IOException.class, () -> DropInUpdater.guardGame(game));
            assertTrue(updater.helperAlive()); assertArrayEquals(next, Files.readAllBytes(mod())); // Parent is still alive.
        } finally { if (updater.helperAlive()) stopHelper(updater); }
    }
    @Test void aNewGameLeaseBetweenFolkwaysFilesStopsFurtherWritesAndAutomaticRollback() throws Exception {
        UpdaterTest folkways = new UpdaterTest(); folkways.game = game; folkways.fixture(); folkways.updater.checkAndStage();
        boolean[] injected = {false};
        folkways.updater.installGate = new Updater.InstallGate() {
            public void check() throws Exception {
                if (!injected[0] && Arrays.equals(folkways.fresh.get("core"), folkways.installed("core"))) {
                    injected[0] = true; RuntimeLease.recordGame(new Updater(game));
                }
                RuntimeLease.ensureNoGame(new Updater(game));
            }
            public void committed() { }
        };
        assertThrows(RuntimeLease.ActiveGameException.class, folkways.updater::applyPending);
        assertTrue(injected[0]); assertArrayEquals(folkways.fresh.get("core"), folkways.installed("core"));
        assertArrayEquals(UpdaterTest.jar("vanilla", "old"), folkways.installed("vanilla"));
        assertTrue(Files.exists(game.resolve(".folkways-updater/journal.json")));
        deadGame(); folkways.updater.applyPending();
        assertArrayEquals(folkways.fresh.get("vanilla"), folkways.installed("vanilla"));
        assertFalse(Files.exists(game.resolve(".folkways-updater/journal.json")));
    }
    @Test void lateConstructorAndCorruptOrRollbackClockMarkersFailClosed() throws Exception {
        fixture(); byte[] loaded = entries(original).get("META-INF/folkways-updater-build.json");
        assertThrows(IOException.class, () -> DropInUpdater.initializeLoaded(game, mod(), loaded, "wrong-scanned-version"));
        byte[] mismatched = entries(SelfUpdaterTest.jar(upstream.metadata(4), "companion")).get("META-INF/folkways-updater-build.json");
        assertThrows(IOException.class, () -> DropInUpdater.initializeLoaded(game, mod(), mismatched, upstream.metadata(1).version));
        Path marker = game.resolve(".folkways-updater/last-install.json");
        Files.writeString(marker, "{\"finishedMillis\":" + System.currentTimeMillis() + ",\"clockAnomaly\":false}");
        assertThrows(IOException.class, () -> DropInUpdater.guardGame(game));
        Files.writeString(marker, "{\"finishedMillis\":1,\"clockAnomaly\":true}"); assertThrows(IOException.class, () -> DropInUpdater.guardGame(game));
        Files.writeString(marker, "{}"); assertThrows(IOException.class, () -> DropInUpdater.guardGame(game));
    }
    @Test void actualIndependentHelperWaitsForItsExactParentThenWritesSuccessAndIsPruned() throws Exception {
        Files.createDirectories(game.resolve("mods")); SelfUpdaterTest factory = new SelfUpdaterTest(); byte[] jar = companion(factory.metadata(1)); Files.write(mod(), jar);
        Path bootstrap = game.resolve("probe-bootstrap.jar"); Files.write(bootstrap, helper(factory.metadata(1)));
        Map<?,?> first = probe(bootstrap); Path firstRuntime = game.resolve((String)first.get("runtime"));
        assertTrue(Config.load(game).selfUpdateEnabled); assertFalse(Config.load(game).localDevelopment);
        assertArrayEquals(jar, Files.readAllBytes(mod()));
        Map<?,?> second = probe(bootstrap); assertFalse(Files.exists(firstRuntime));
        assertNotEquals(first.get("helperPid"), second.get("helperPid"));
        try (var runtimes = Files.list(game.resolve(".folkways-updater/dropin-runtime"))) { assertEquals(1, runtimes.count()); }
    }
    @Test void actualExitHelperConsumesTheTrustedQueueWithoutExitNetwork() throws Exception {
        fixture(); updater.checkAndStage(); upstream.offline = true; deadGame();
        Path bootstrap = game.resolve("probe-bootstrap.jar"); Files.write(bootstrap, helper(upstream.metadata(1)));
        probe(bootstrap);
        assertArrayEquals(upstream.downloads.get(upstream.metadata.artifacts.get(1).url), Files.readAllBytes(mod()));
        assertFalse(Files.exists(queued())); assertFalse(Files.exists(game.resolve(".folkways-updater/dropin-journal.json")));
        assertTrue(Files.exists(game.resolve(".folkways-updater/last-install.json")));
    }
    @Test void actualJvmLocalPropertyAlsoProtectsExistingAndNewExitHelpers() throws Exception {
        fixture(); updater.checkAndStage(); deadGame();
        Path bootstrap = game.resolve("probe-bootstrap.jar"); Files.write(bootstrap, helper(upstream.metadata(1)));
        probe(bootstrap, true); assertArrayEquals(original, Files.readAllBytes(mod()));
        assertTrue(Files.exists(queued())); assertTrue(Files.exists(game.resolve(".folkways-updater/LOCAL_DEVELOPMENT")));
        assertTrue(updater.localProtection());
    }
    Map<?,?> probe(Path bootstrap) throws Exception { return probe(bootstrap, false); }
    Map<?,?> probe(Path bootstrap, boolean property) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java)); if (property) command.add("-Dfolkways.localDevelopment=true");
        Path testClasses = Path.of(ProbeParent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        command.addAll(List.of("-cp", bootstrap + File.pathSeparator + testClasses, ProbeParent.class.getName(), game.toString(), mod().toString()));
        Process parent = new ProcessBuilder(command).redirectErrorStream(true).start();
        BufferedReader output = parent.inputReader(StandardCharsets.UTF_8);
        String text = output.readLine(); assertNotNull(text);
        Map<?,?> value = Updater.JSON.fromJson(text, Map.class); assertEquals(true, value.get("ready"));
        assertEquals(parent.pid(), ((Number)value.get("parentPid")).longValue()); assertNotEquals(parent.pid(), ((Number)value.get("helperPid")).longValue());
        // Capture the exact helper while its parent is definitely alive. A result file is not a process exit signal.
        ProcessHandle child = ProcessHandle.of(((Number)value.get("helperPid")).longValue()).orElseThrow();
        assertTrue(parent.isAlive()); assertTrue(child.isAlive());
        assertEquals(value.get("helperStart"), child.info().startInstant().orElseThrow().toString());
        var exited = child.onExit();
        parent.getOutputStream().write(1); parent.getOutputStream().close();
        assertTrue(parent.waitFor(30, TimeUnit.SECONDS), text); assertEquals(0, parent.exitValue(), text + output.lines().toList());
        exited.get(30, TimeUnit.SECONDS); assertFalse(child.isAlive());
        Path result = game.resolve((String)value.get("runtime")).resolve("result.json");
        assertTrue(Files.exists(result), "Exit helper did not complete: " + result);
        DropInUpdater.Result completed = Updater.JSON.fromJson(Files.readString(result), DropInUpdater.Result.class);
        assertTrue(completed.success, completed.message); assertEquals(parent.pid(), completed.parentPid);
        assertEquals(child.pid(), completed.helperPid);
        return value;
    }
    public static final class ProbeParent {
        public static void main(String[] args) throws Exception {
            Path game = Path.of(args[0]); DropInUpdater updater = DropInUpdater.initialize(game, Path.of(args[1])); updater.startHelper();
            DropInUpdater.HelperOwner owner = Updater.JSON.fromJson(Files.readString(game.resolve(".folkways-updater/dropin-helper.json")), DropInUpdater.HelperOwner.class);
            Map<String,Object> value = new LinkedHashMap<>(); value.put("helperPid", owner.pid); value.put("helperStart", owner.start);
            value.put("parentPid", ProcessHandle.current().pid()); value.put("runtime", owner.runtime); value.put("ready", updater.helperAlive());
            System.out.println(new com.google.gson.Gson().toJson(value)); System.out.flush();
            if (System.in.read() != 1) throw new IOException("Test parent was not released");
        }
    }
    static void stopHelper(DropInUpdater updater) throws Exception {
        Path game = updaterGame(updater); DropInUpdater.HelperOwner owner = Updater.JSON.fromJson(Files.readString(game.resolve(".folkways-updater/dropin-helper.json")), DropInUpdater.HelperOwner.class);
        ProcessHandle handle = ProcessHandle.of(owner.pid).orElseThrow(); assertEquals(owner.start, handle.info().startInstant().orElseThrow().toString()); handle.destroy(); handle.onExit().get(10, TimeUnit.SECONDS);
    }
    static Path updaterGame(DropInUpdater updater) throws Exception {
        var field = DropInUpdater.class.getDeclaredField("u"); field.setAccessible(true); return ((Updater)field.get(updater)).game;
    }
}
