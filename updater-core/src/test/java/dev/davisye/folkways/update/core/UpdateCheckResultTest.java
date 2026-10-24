package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckResultTest {
    @TempDir Path root;
    @Test void officialDownloadAndCacheHitKeepOneKeyAcrossNewObjectsAndOtherChannelErrors() throws Exception {
        GitHubFolkwaysTest fixture = new GitHubFolkwaysTest(); fixture.game = root; fixture.fixture(true);
        UpdateCheckResult downloaded = fixture.updater.checkAndStageResult(); assertNotNull(downloaded.reminderKey());
        UpdateCheckResult cached = fixture.updater.checkAndStageResult(); assertNotEquals(downloaded.message(), cached.message());
        assertEquals(downloaded.reminderKey(), cached.reminderKey());
        Updater restarted = new Updater(root, "21.1.233"); restarted.githubSource = fixture.source();
        assertEquals(downloaded.reminderKey(), restarted.checkAndStageResult().reminderKey());
        fixture.config.channel = "release"; fixture.save(); assertNull(restarted.checkAndStageResult().reminderKey());
        fixture.config.channel = "nightly"; fixture.save(); fixture.offline = true; assertThrows(IOException.class, restarted::checkAndStageResult);
        fixture.offline = false; assertEquals(downloaded.reminderKey(), restarted.checkAndStageResult().reminderKey());
        fixture.publish(3); assertNotEquals(downloaded.reminderKey(), restarted.checkAndStageResult().reminderKey());
        restarted.applyPending(); assertNull(restarted.checkAndStageResult().reminderKey());
    }
    @Test void officialRepositoriesChannelsAndModuleSelectionsHaveIndependentKeys() throws Exception {
        GitHubFolkwaysTest fixture = new GitHubFolkwaysTest(); fixture.game = root; fixture.fixture(true);
        String upstream = fixture.updater.checkAndStageResult().reminderKey();
        fixture.config.repository = "davisye/folkways"; fixture.save(); fixture.publish(2);
        String fork = fixture.updater.checkAndStageResult().reminderKey(); assertNotEquals(upstream, fork);
        fixture.config.channel = "release"; fixture.save(); fixture.release.tag_name = "v0.1.0"; fixture.release.prerelease = false;
        for (var asset : fixture.release.assets) {
            byte[] bytes = fixture.downloads.get(asset.browser_download_url); asset.browser_download_url = asset.browser_download_url.replace("/nightly/", "/v0.1.0/"); fixture.downloads.put(asset.browser_download_url, bytes);
        }
        String release = fixture.updater.checkAndStageResult().reminderKey(); assertNotNull(release); assertNotEquals(fork, release);
        fixture.config.modules = new ArrayList<>(List.of("core", "vanilla", "cookery")); fixture.save();
        String expanded = fixture.updater.checkAndStageResult().reminderKey(); assertNotEquals(release, expanded);
        fixture.config.modules = new ArrayList<>(List.of("vanilla", "core")); fixture.save();
        assertEquals(release, fixture.updater.checkAndStageResult().reminderKey());
    }
    @Test void signedManifestKeysAreStableAndIndependentAcrossSourceAndChannel() throws Exception {
        UpdaterTest fixture = new UpdaterTest(); fixture.game = root; fixture.fixture();
        String nightly = fixture.updater.checkAndStageResult().reminderKey(); assertNotNull(nightly);
        assertEquals(nightly, fixture.updater.checkAndStageResult().reminderKey());
        fixture.config.channel = "beta"; fixture.saveConfig(); fixture.release(2);
        String beta = fixture.updater.checkAndStageResult().reminderKey(); assertNotEquals(nightly, beta);
        fixture.config.repository = "davisye/folkways"; fixture.saveConfig(); fixture.release(2);
        assertNotEquals(beta, fixture.updater.checkAndStageResult().reminderKey());
        fixture.config.repository = "IzakyL/folkways"; fixture.config.channel = "nightly"; fixture.saveConfig(); fixture.release(2);
        assertEquals(nightly, fixture.updater.checkAndStageResult().reminderKey());
        fixture.updater.applyPending(); assertNull(fixture.updater.checkAndStageResult().reminderKey());
        fixture.release(3); assertNotEquals(nightly, fixture.updater.checkAndStageResult().reminderKey());
    }
    @Test void protectedLocalUnconfiguredAndDisabledChecksHaveNoReminder() throws Exception {
        GitHubFolkwaysTest fixture = new GitHubFolkwaysTest(); fixture.game = root; fixture.fixture(true);
        Files.write(root.resolve("mods/manual.jar"), GitHubFolkwaysTest.jar("folkways_core", "0.1.0", ""));
        assertNull(fixture.updater.checkAndStageResult().reminderKey());
        fixture.config.localDevelopment = true; fixture.save(); assertNull(fixture.updater.checkAndStageResult().reminderKey());
        fixture.config.localDevelopment = false; fixture.save(); assertNull(new Updater(root).checkAndStageResult().reminderKey());
    }
    @Test void dropInKeysIgnoreFolkwaysSourceLocalFlagAndTransientVerificationStatus() throws Exception {
        DropInUpdaterTest fixture = new DropInUpdaterTest(); fixture.game = root; fixture.fixture();
        UpdateCheckResult downloaded = fixture.updater.checkAndStageResult(); String key = downloaded.reminderKey(); assertNotNull(key);
        assertEquals(key, fixture.updater.checkAndStageResult().reminderKey());
        Config.edit(root, c -> { c.repository = "davisye/folkways"; c.channel = "beta"; c.localDevelopment = true; });
        assertEquals(key, fixture.updater.checkAndStageResult().reminderKey());
        fixture.upstream.offline = true; UpdateCheckResult unavailable = fixture.updater.checkAndStageResult();
        assertTrue(unavailable.message().startsWith("Updater verification temporarily unavailable")); assertNull(unavailable.reminderKey());
        fixture.upstream.offline = false; assertEquals(key, fixture.updater.checkAndStageResult().reminderKey());
        fixture.publish(3); assertNotEquals(key, fixture.updater.checkAndStageResult().reminderKey());
        Config.edit(root, c -> c.selfUpdateEnabled = false); assertNull(fixture.updater.checkAndStageResult().reminderKey());
    }
    @Test void producerDoesNotConsumeDisplayHistoryAndChangedTextDoesNotChangeReminderIdentity() throws Exception {
        GitHubFolkwaysTest fixture = new GitHubFolkwaysTest(); fixture.game = root; fixture.fixture(true);
        UpdateCheckResult first = fixture.updater.checkAndStageResult(), repeated = fixture.updater.checkAndStageResult();
        Path historyPath = root.resolve(".folkways-updater/notified-updates.json"); assertFalse(Files.exists(historyPath));
        UpdateReminderHistory history = new UpdateReminderHistory(root, error -> fail(error));
        assertTrue(history.first(repeated.reminderKey())); assertFalse(history.first(first.reminderKey()));
        UpdateCheckResult translated = new UpdateCheckResult("另一种提示文字", first.reminderKey());
        assertFalse(new UpdateReminderHistory(root, error -> fail(error)).first(translated.reminderKey()));
        fixture.config.repository = "davisye/folkways"; fixture.save(); fixture.publish(2);
        assertTrue(history.first(fixture.updater.checkAndStageResult().reminderKey()));
    }
}
