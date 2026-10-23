package dev.davisye.folkways.update.mod;

import dev.davisye.folkways.update.core.Config;
import dev.davisye.folkways.update.core.Updater;
import dev.davisye.folkways.update.core.SelfUpdater;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-only class: remote multiplayer clients and the menu also stage their own updates. */
@EventBusSubscriber(modid = "folkways_updater", value = Dist.CLIENT)
public final class ClientUpdates {
    private static final Logger LOG = LoggerFactory.getLogger(ClientUpdates.class);
    private static final Path GAME_DIR = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    private static boolean initialized;
    private static volatile String queuedNotice;
    private static String lastNotice = "";

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!initialized) {
            initialized = true;
            try {
                Config config = Config.load(GAME_DIR);
                if (!FMLEnvironment.production || config.isLocalDevelopment(GAME_DIR)) return;
                ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "folkways-client-update");
                    thread.setDaemon(true);
                    return thread;
                });
                worker.scheduleWithFixedDelay(ClientUpdates::check, 15,
                        Math.max(60, config.pollSeconds), TimeUnit.SECONDS);
            } catch (Exception e) { LOG.warn("Client update polling unavailable ({})", e.getClass().getSimpleName()); }
        }
        Minecraft client = Minecraft.getInstance();
        String notice = queuedNotice;
        if (notice != null && client.player != null) {
            queuedNotice = null;
            client.player.displayClientMessage(Component.literal("[Folkways updater] " + notice), false);
        }
    }

    private static void check() {
        try {
            if (!FMLEnvironment.production || Config.load(GAME_DIR).isLocalDevelopment(GAME_DIR)) return;
            String self;
            try { self = new SelfUpdater(GAME_DIR).checkAndStage(); }
            catch (Exception e) { self = "Updater self-check unavailable; current updater retained"; }
            String folkways;
            try { folkways = new Updater(GAME_DIR).checkAndStage(); }
            catch (Exception e) { folkways = "Folkways check unavailable; installed Folkways retained"; }
            String notice = self + " | " + folkways;
            if (!notice.equals(lastNotice)) {
                lastNotice = notice;
                queuedNotice = notice;
            }
        } catch (Exception e) { LOG.warn("Client update check failed ({})", e.getClass().getSimpleName()); }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        if (UpdaterMod.quitClientRequested && !event.getServer().isDedicatedServer()) {
            UpdaterMod.quitClientRequested = false;
            Minecraft client = Minecraft.getInstance();
            client.execute(client::stop);
        }
    }
}
