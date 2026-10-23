package dev.davisye.folkways.update.mod;

import dev.davisye.folkways.update.core.Config;
import dev.davisye.folkways.update.core.DependencyConflictException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Polls in the menu as well as in worlds, and shares one worker with the configuration screen. */
@EventBusSubscriber(modid = "folkways_updater", value = Dist.CLIENT)
public final class ClientUpdates {
    private static final Logger LOG = LoggerFactory.getLogger(ClientUpdates.class);
    private static final Path GAME_DIR = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "folkways-client-update");
        thread.setDaemon(true);
        return thread;
    });
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(10000L);
    private static boolean initialized;
    private static volatile long nextPoll;
    private static volatile Component status = text("idle");
    private static volatile Component queuedNotice;
    private static String lastNotice = "";

    private static Component text(String key) { return Component.translatable("folkways_updater.status." + key); }
    public static Component status() { return status; }
    public static boolean checking() { return CHECKING.get(); }

    /** Never keep a previous mode's result visible after its switch has been saved. */
    public static void settingsSaved() {
        nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        if (!CHECKING.get()) status = text("settings_changed");
    }

    /** Saving local protection remains possible while this background operation is running. */
    public static boolean requestCheck() {
        if (!CHECKING.compareAndSet(false, true)) return false;
        status = text("checking");
        WORKER.execute(ClientUpdates::check);
        return true;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!initialized) {
            initialized = true;
            nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            WORKER.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() < nextPoll) return;
                try {
                    Config current = Config.load(GAME_DIR);
                    nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(current.pollSeconds);
                    requestCheck();
                } catch (Exception error) {
                    status = text("config_error");
                    nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                }
            }, 5, 5, TimeUnit.SECONDS);
        }
        Component notice = queuedNotice;
        if (notice != null) {
            queuedNotice = null;
            Minecraft client = Minecraft.getInstance();
            SystemToast.addOrUpdate(client.getToasts(), TOAST,
                    Component.translatable("folkways_updater.config.title"), notice);
            if (client.player != null) client.player.displayClientMessage(notice, false);
        }
    }

    private static void check() {
        try {
            String self;
            try { self = UpdaterMod.checkOwnUpdate(); }
            catch (Exception error) {
                LOG.warn("Updater self-check unavailable ({})", error.getClass().getSimpleName());
                self = text("self_error").getString();
            }
            String folkways;
            try {
                folkways = Config.load(GAME_DIR).isLocalDevelopment(GAME_DIR)
                        ? text("local").getString() : UpdaterMod.folkwaysUpdater().checkAndStage();
            }
            catch (Exception error) {
                LOG.warn("Folkways check unavailable ({})", error.getClass().getSimpleName());
                folkways = failure(error);
            }
            Component result = Component.literal(readable(folkways) + " | " + readable(self));
            status = result;
            if (!result.getString().equals(lastNotice)) {
                lastNotice = result.getString();
                queuedNotice = result;
            }
        } catch (Exception error) {
            status = text("config_error");
            LOG.warn("Client update check failed ({})", error.getClass().getSimpleName());
        } finally { CHECKING.set(false); }
    }

    private static String failure(Exception error) {
        if (error instanceof DependencyConflictException conflict)
            return Component.translatable("folkways_updater.status.dependency_conflict", dependencyName(conflict.id),
                    conflict.version, conflict.requiredRange).getString();
        String reason = error.getMessage();
        if (reason != null && reason.startsWith("Official verification is temporarily unavailable"))
            return text("official_unavailable").getString();
        if (reason != null && reason.length() <= 240 && !reason.contains("://") && !reason.contains("\\")
                && (reason.startsWith("Folkways requires NeoForge ") || reason.startsWith("Required dependency ")
                || reason.startsWith("Installed dependency ") || reason.startsWith("Local mode or updater configuration changed")))
            return reason;
        return text("folkways_error").getString();
    }

    private static String dependencyName(String id) {
        return switch (id) {
            case "create" -> "Create";
            case "modulargolems" -> "Modular Golems";
            case "kaleidoscope_cookery" -> "Kaleidoscope Cookery";
            case "ldlib2" -> "LDLib2";
            case "l2library" -> "L2Library";
            default -> id;
        };
    }

    private static String readable(String result) {
        if (result.startsWith("No official ")) {
            try {
                Config current = Config.load(GAME_DIR);
                return Component.translatable("folkways_updater.status.no_release", current.repository, current.channel).getString();
            } catch (Exception ignored) { return result; }
        }
        String key = null;
        if (result.startsWith("Existing Folkways JARs")) key = "existing_protected";
        else if (result.startsWith("Existing signed-channel")) key = "signed_protected";
        else if (result.startsWith("Already installed official Folkways")) key = "folkways_current";
        else if (result.startsWith("Downloaded official Folkways") || result.startsWith("Official Folkways is already downloaded")) key = "folkways_ready";
        else if (result.startsWith("Updater already current")) key = "self_current";
        else if (result.startsWith("Updater downloaded:") || result.startsWith("Updater already downloaded")) key = "self_ready";
        else if (result.equals("Updater self-update disabled")) key = "self_disabled";
        else if (result.equals("No installed updater JAR target in this IDE run")) key = "self_no_target";
        else if (result.startsWith("Updater verification temporarily unavailable")) key = "self_verification_unavailable";
        return key == null ? result : text(key).getString();
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
