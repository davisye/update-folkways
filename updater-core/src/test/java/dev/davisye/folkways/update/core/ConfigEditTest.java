package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigEditTest {
    @TempDir Path game;
    @Test void visibleEditsPreserveLatestAdvancedTrustAndRestartSettings() throws Exception {
        Updater.initialize(game);
        Config.edit(game, c -> {
            c.manifestUrl = "https://pan.davisye.cn/d/folkways/test.json";
            c.publicKeys = Map.of("test", "do-not-erase");
            c.restartLimit = 17;
            c.restartCooldownSeconds = 23;
        });
        Config.edit(game, c -> { c.repository = "davisye/folkways"; c.localDevelopment = false; });
        Config saved = Config.load(game);
        assertEquals("davisye/folkways", saved.repository);
        assertFalse(saved.localDevelopment);
        assertEquals(17, saved.restartLimit);
        assertEquals(23, saved.restartCooldownSeconds);
        assertEquals("https://pan.davisye.cn/d/folkways/test.json", saved.manifestUrl);
        assertEquals(Map.of("test", "do-not-erase"), saved.publicKeys);
    }
    @Test void invalidEditRetainsExactOriginalFile() throws Exception {
        Updater.initialize(game);
        Path file = game.resolve(".folkways-updater/config.json");
        byte[] before = Files.readAllBytes(file);
        assertThrows(java.io.IOException.class, () -> Config.edit(game, c -> c.pollSeconds = 0));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertFalse(Files.exists(file.resolveSibling("config.json.tmp")));
    }
    @Test void localModeCanBeChangedDuringDownloadAndLegacyMarkersDoNotOverrideItsSwitch() throws Exception {
        Updater.initialize(game);
        Config.edit(game, c -> c.localDevelopment = false);
        Path home = game.resolve(".folkways-updater");
        try (var channel = FileChannel.open(home.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            Config.edit(game, c -> c.localDevelopment = true);
            assertTrue(Config.load(game).isLocalDevelopment(game));
        }
        Files.writeString(home.resolve("LOCAL_DEVELOPMENT"), "explicit developer protection");
        Config.edit(game, c -> c.localDevelopment = false);
        assertFalse(Config.load(game).isLocalDevelopment(game));
        assertTrue(Files.exists(home.resolve("LOCAL_DEVELOPMENT")));
    }
    @Test void legacyJvmFlagCannotSilentlyEnableLocalMode() throws Exception {
        Updater.initialize(game);
        String previous = System.getProperty("folkways.localDevelopment");
        try {
            System.setProperty("folkways.localDevelopment", "true");
            assertFalse(Config.load(game).isLocalDevelopment(game));
            Config.edit(game, c -> c.localDevelopment = true);
            assertTrue(Config.load(game).isLocalDevelopment(game));
        } finally {
            if (previous == null) System.clearProperty("folkways.localDevelopment");
            else System.setProperty("folkways.localDevelopment", previous);
        }
    }

}
