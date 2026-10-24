package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class FolkwaysTakeoverTest {
    @TempDir Path root;
    GitHubFolkwaysTest base;
    final Map<String,byte[]> originals = new TreeMap<>();
    void fixture() throws Exception {
        base = new GitHubFolkwaysTest(); base.game = root; base.fixture(true);
        manual("core", "hand-built-core.jar"); manual("vanilla", "renamed-vanilla.jar");
    }
    void manual(String module, String filename) throws Exception {
        byte[] bytes = GitHubFolkwaysTest.jar("folkways_" + module, "0.1.0", "# manually installed " + filename);
        Files.write(root.resolve("mods/" + filename), bytes); originals.put(filename, bytes);
    }
    Path statePath() { return root.resolve(".folkways-updater/github-folkways-state.json"); }
    Config snapshot() { return Updater.JSON.fromJson(Updater.JSON.toJson(base.config), Config.class); }
    void assertOriginals() throws Exception {
        for (var file : originals.entrySet()) assertArrayEquals(file.getValue(), Files.readAllBytes(root.resolve("mods/" + file.getKey())));
    }
    @Test void ordinaryCheckStillProtectsRenamedLocalFolkwaysBeforeNetworking() throws Exception {
        fixture(); assertTrue(base.updater.checkAndStage().contains("Existing Folkways JARs are protected"));
        assertEquals(0, base.calls); assertFalse(Files.exists(statePath())); assertFalse(Files.exists(base.pending())); assertOriginals();
    }
    @Test void explicitTakeoverCommitsOnlyVerifiedBaselineThenExitReplacementKeepsOriginalBackup() throws Exception {
        fixture(); byte[] config = Files.readAllBytes(root.resolve(".folkways-updater/config.json"));
        byte[] dependency = Files.readAllBytes(root.resolve("mods/user-ldlib.jar"));
        assertTrue(base.updater.checkAndStage(snapshot(), true).contains("one-time takeover staged")); assertOriginals();
        assertEquals(Set.of("folkways_core", "folkways_vanilla"), base.state().owned.keySet());
        assertEquals("hand-built-core.jar", base.state().owned.get("folkways_core").file);
        assertEquals(Updater.sha(originals.get("hand-built-core.jar")), base.state().owned.get("folkways_core").sha256);
        assertArrayEquals(config, Files.readAllBytes(root.resolve(".folkways-updater/config.json")));
        assertTrue(base.updater.applyPending().contains("Installed official"));
        assertArrayEquals(originals.get("hand-built-core.jar"), Files.readAllBytes(root.resolve(".folkways-updater/github-folkways-backup/folkways_core.jar")));
        assertArrayEquals(originals.get("renamed-vanilla.jar"), Files.readAllBytes(root.resolve(".folkways-updater/github-folkways-backup/folkways_vanilla.jar")));
        assertArrayEquals(dependency, Files.readAllBytes(root.resolve("mods/user-ldlib.jar"))); assertFalse(base.state().owned.containsKey("ldlib2"));
        assertEquals(2001L, base.state().highWater.get(base.config.scope())); assertFalse(Files.exists(base.pending()));
        assertNotEquals(Updater.sha(originals.get("hand-built-core.jar")), Updater.sha(Files.readAllBytes(root.resolve("mods/hand-built-core.jar"))));
    }
    @Test void failedOfficialCiDigestOrDownloadNeverTakesOwnership() throws Exception {
        fixture(); base.run.conclusion = "failure"; assertThrows(IOException.class, () -> base.updater.checkAndStage(true));
        base.publish(2); base.release.assets.getFirst().digest = null; assertThrows(IOException.class, () -> base.updater.checkAndStage(true));
        base.publish(2); String url = base.release.assets.getFirst().browser_download_url; byte[] bad = base.downloads.get(url).clone(); bad[bad.length - 1] ^= 1; base.downloads.put(url, bad);
        assertThrows(IOException.class, () -> base.updater.checkAndStage(true)); assertOriginals();
        assertFalse(Files.exists(statePath())); assertFalse(Files.exists(base.pending()));
    }
    @Test void localModeAndSourceChangeDuringDownloadPreserveManualFilesAndNoOwnership() throws Exception {
        fixture(); Config expected = snapshot();
        base.duringDownload = ignored -> { base.config.localDevelopment = true; try { base.save(); } catch (Exception e) { throw new RuntimeException(e); } };
        assertThrows(IOException.class, () -> base.updater.checkAndStage(expected, true)); assertFalse(Files.exists(statePath())); assertOriginals();
        assertTrue(base.updater.checkAndStage(true).contains("Local development"));
        base.config.localDevelopment = false; base.save(); base.duringDownload = ignored -> { base.config.repository = "davisye/folkways"; try { base.save(); } catch (Exception e) { throw new RuntimeException(e); } };
        assertThrows(IOException.class, () -> base.updater.checkAndStage(expected, true)); assertFalse(Files.exists(statePath())); assertFalse(Files.exists(base.pending())); assertOriginals();
    }
    @Test void authorizationSnapshotCannotBeRedirectedToNewRepository() throws Exception {
        fixture(); Config expected = snapshot(); base.config.repository = "davisye/folkways"; base.save();
        assertThrows(IOException.class, () -> base.updater.checkAndStage(expected, true)); assertEquals(0, base.calls); assertFalse(Files.exists(statePath())); assertOriginals();
    }
    @Test void aToBToAConfigurationDoesNotReviveRevokedConsent() throws Exception {
        fixture(); Config expected = snapshot(); AtomicBoolean current = new AtomicBoolean(true);
        base.duringDownload = ignored -> {
            current.set(false);
            try { base.config.repository = "davisye/folkways"; base.save(); base.config.repository = expected.repository; base.save(); }
            catch (Exception e) { throw new RuntimeException(e); }
        };
        assertThrows(IOException.class, () -> base.updater.checkAndStage(expected, true, current::get)); assertFalse(Files.exists(statePath())); assertFalse(Files.exists(base.pending())); assertOriginals();
    }
    @Test void finalConsentRevocationRestoresPriorCompleteQueueAndOwnership() throws Exception {
        base = new GitHubFolkwaysTest(); base.game = root; base.fixture(true); base.updater.checkAndStage();
        byte[] previousState = Files.readAllBytes(statePath()), previousPlan = Files.readAllBytes(base.pending().resolve("plan.json"));
        manual("core", "hand-built-core.jar"); manual("vanilla", "renamed-vanilla.jar"); base.publish(3); AtomicBoolean current = new AtomicBoolean(true);
        assertThrows(IOException.class, () -> base.updater.checkAndStage(snapshot(), true, current::get, action -> { current.set(false); action.run(); }));
        assertArrayEquals(previousState, Files.readAllBytes(statePath())); assertArrayEquals(previousPlan, Files.readAllBytes(base.pending().resolve("plan.json"))); assertOriginals();
        assertTrue(base.state().owned.isEmpty()); assertFalse(Files.exists(root.resolve(".folkways-updater/github-folkways-pending.previous")));
    }
    @Test void authorizationThatDoesNotConsumeCannotLeaveNewOwnershipOrPending() throws Exception {
        fixture(); assertThrows(IOException.class, () -> base.updater.checkAndStage(snapshot(), true, () -> true, action -> {}));
        assertFalse(Files.exists(statePath())); assertFalse(Files.exists(base.pending())); assertOriginals();
    }
    @Test void repeatingTheExplicitCheckUsesTheSameCompleteQueue() throws Exception {
        fixture(); base.updater.checkAndStage(true); byte[] before = Files.readAllBytes(statePath()), plan = Files.readAllBytes(base.pending().resolve("plan.json"));
        assertTrue(base.updater.checkAndStage(true).contains("already downloaded"));
        assertArrayEquals(before, Files.readAllBytes(statePath())); assertArrayEquals(plan, Files.readAllBytes(base.pending().resolve("plan.json"))); assertOriginals();
    }
    @Test void manualFileChangedAfterConsentCannotBeRebasedByAnotherForceOrInstalled() throws Exception {
        fixture(); base.updater.checkAndStage(true); byte[] before = Files.readAllBytes(statePath());
        byte[] changed = GitHubFolkwaysTest.jar("folkways_core", "0.1.0", "# changed after takeover"); Files.write(root.resolve("mods/hand-built-core.jar"), changed);
        assertThrows(IOException.class, () -> base.updater.checkAndStage(true)); assertThrows(IOException.class, base.updater::applyPending);
        assertArrayEquals(changed, Files.readAllBytes(root.resolve("mods/hand-built-core.jar"))); assertArrayEquals(before, Files.readAllBytes(statePath()));
        assertFalse(Files.exists(root.resolve(".folkways-updater/github-folkways-backup")));
    }
    @Test void unselectedAndCompoundOrDuplicateManualJarsCannotBeTakenOver() throws Exception {
        fixture(); manual("cookery", "extra.jar");
        var unselected = assertThrows(TakeoverRejectedException.class, () -> base.updater.checkAndStage(true));
        assertEquals(TakeoverRejectedException.Reason.UNSELECTED_MODULE, unselected.reason); assertEquals("folkways_cookery", unselected.id);
        Files.delete(root.resolve("mods/extra.jar"));
        Files.write(root.resolve("mods/hand-built-core.jar"), GitHubFolkwaysTest.jar("folkways_core", "0.1.0", "\n[[mods]]\nmodId=\"unrelated_mod\"\nversion=\"1.0\"\n"));
        assertEquals(TakeoverRejectedException.Reason.COMPOSITE_OR_NESTED, assertThrows(TakeoverRejectedException.class, () -> base.updater.checkAndStage(true)).reason);
        Files.write(root.resolve("mods/hand-built-core.jar"), originals.get("hand-built-core.jar")); manual("core", "duplicate.jar");
        assertThrows(IOException.class, () -> base.updater.checkAndStage(true)); assertFalse(Files.exists(statePath())); assertEquals(0, base.calls);
    }
    @Test void unknownWrapperNestedFolkwaysIsProtectedForBothDefaultAndTakeover() throws Exception {
        base = new GitHubFolkwaysTest(); base.game = root; base.fixture(true);
        byte[] wrapper = ApprovedDependenciesTest.nested(GitHubFolkwaysTest.jar("unrelated_wrapper", "1.0", GitHubFolkwaysTest.dependency("unrelated_wrapper", "folkways_core", "[0.0.1]")),
            "folkways_core", "0.0.1", GitHubFolkwaysTest.jar("folkways_core", "0.0.1", ""));
        Path path = root.resolve("mods/user-wrapper.jar"); Files.write(path, wrapper);
        assertTrue(base.updater.checkAndStage().contains("Existing Folkways JARs are protected"));
        assertEquals(TakeoverRejectedException.Reason.COMPOSITE_OR_NESTED, assertThrows(TakeoverRejectedException.class, () -> base.updater.checkAndStage(true)).reason);
        assertEquals(0, base.calls); assertFalse(Files.exists(statePath())); assertArrayEquals(wrapper, Files.readAllBytes(path));
    }
    @Test void unrelatedJarJarDoesNotMakeUnknownDependencySyntaxAnInstallationBlocker() throws Exception {
        base = new GitHubFolkwaysTest(); base.game = root; base.fixture(true);
        byte[] child = GitHubFolkwaysTest.jar("unrelated_child", "1.0", "\n[[dependencies.\"unknown-owner\"]]\nmodId=\"another_mod\"\n");
        byte[] wrapper = ApprovedDependenciesTest.nested(GitHubFolkwaysTest.jar("unrelated_wrapper", "1.0", ""), "unrelated_child", "1.0", child);
        Map<String,byte[]> entries = DropInUpdaterTest.entries(wrapper); entries.put("large-unrelated-resource.bin", new byte[17 * 1024 * 1024]);
        byte[] original = DropInUpdaterTest.zip(entries); Path path = root.resolve("mods/user-wrapper.jar"); Files.write(path, original);
        assertTrue(base.updater.checkAndStage().contains("Downloaded official")); assertArrayEquals(original, Files.readAllBytes(path)); assertTrue(base.state().owned.isEmpty());
    }
    @Test void partialOwnershipCanMergeOnlyNewManualModulesAndPreservesSourceHighWater() throws Exception {
        base = new GitHubFolkwaysTest(); base.game = root; base.fixture(true); base.publish(114); base.updater.checkAndStage(); base.updater.applyPending();
        String upstream = base.config.scope(); manual("cookery", "my-cookery.jar");
        base.config.modules = new ArrayList<>(List.of("core", "vanilla", "cookery")); base.config.repository = "davisye/folkways"; base.save(); base.publish(14);
        assertThrows(IOException.class, base.updater::checkAndStage);
        assertTrue(base.updater.checkAndStage(snapshot(), true).contains("one-time takeover staged")); assertEquals(114001L, base.state().highWater.get(upstream));
        assertFalse(base.state().highWater.containsKey(base.config.scope())); base.updater.applyPending();
        assertEquals(Set.of("folkways_core", "folkways_vanilla", "folkways_cookery"), base.state().owned.keySet());
        assertEquals(114001L, base.state().highWater.get(upstream)); assertEquals(14001L, base.state().highWater.get(base.config.scope()));
        assertTrue(Files.exists(root.resolve("mods/my-cookery.jar"))); assertFalse(base.state().owned.containsKey("ldlib2"));
        base.publish(13); assertThrows(IOException.class, () -> base.updater.checkAndStage(true));
    }
    @Test void interruptedTakeoverReplacementRollsBackToTheOriginalManualBytes() throws Exception {
        fixture(); base.updater.checkAndStage(true); GitHubFolkways.State before = base.state();
        var plan = Updater.JSON.fromJson(Files.readString(base.pending().resolve("plan.json")), GitHubFolkways.Plan.class);
        GitHubFolkways.Journal transaction = new GitHubFolkways.Journal(); transaction.before = before;
        transaction.after = Updater.JSON.fromJson(Updater.JSON.toJson(before), GitHubFolkways.State.class);
        Path backup = root.resolve(".folkways-updater/github-folkways-backup"); Files.createDirectory(backup);
        for (var old : before.owned.entrySet()) Files.write(backup.resolve(old.getKey() + ".jar"), Files.readAllBytes(root.resolve("mods/" + old.getValue().file)));
        for (var file : plan.files) transaction.after.owned.put(file.id, new GitHubFolkways.Owned(file.target, file.sha256, file.size, file.version));
        Files.writeString(base.journal(), Updater.JSON.toJson(transaction)); var first = plan.files.getFirst();
        Files.write(root.resolve("mods/" + first.target), Files.readAllBytes(base.pending().resolve(first.id + ".jar")));
        base.config.repository = "davisye/folkways"; base.save(); assertTrue(base.updater.applyPending().contains("different source"));
        assertOriginals(); assertFalse(Files.exists(base.journal())); assertEquals(Updater.JSON.toJson(before), Updater.JSON.toJson(base.state()));
    }
    @Test void signedManifestTakeoverIsNeverAnUnsignedBypass() throws Exception {
        fixture(); base.config.manifestUrl = "https://pan.davisye.cn/manifest.json"; base.save();
        assertEquals(TakeoverRejectedException.Reason.UNSUPPORTED_CHANNEL, assertThrows(TakeoverRejectedException.class, () -> base.updater.checkAndStage(true)).reason);
        assertEquals(0, base.calls); assertFalse(Files.exists(statePath())); assertOriginals();
    }
}
