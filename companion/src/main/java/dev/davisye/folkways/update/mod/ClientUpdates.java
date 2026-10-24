package dev.davisye.folkways.update.mod;

import dev.davisye.folkways.update.core.Config;
import dev.davisye.folkways.update.core.DependencyConflictException;
import dev.davisye.folkways.update.core.TakeoverRejectedException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private static final LatestCheckQueue<ClientCheckRequest, Component> CHECKS = new LatestCheckQueue<>(WORKER,
            ClientUpdates::check, request -> {
                Config config = request.settings();
                status = Component.translatable("folkways_updater.status." +
                        (request.takeOverExisting() ? "checking_takeover" : "checking_source"), config.repository, config.channel);
                queuedNotice = null;
            }, result -> {
                status = result;
                if (!result.getString().equals(lastNotice)) {
                    lastNotice = result.getString();
                    queuedNotice = result;
                }
            }, ClientCheckRequest::identity);
    public static boolean checking() { return CHECKS.running(); }

    /** Saving always requests the latest settings, including while an older check is running. */
    public static boolean settingsSaved() { return requestCheck(); }
    public static void settingsSaving() { CHECKS.invalidate(); }

    /** Only the saved selection explicitly confirmed by the user receives this authorization. */
    public static synchronized boolean requestTakeover(Config confirmed) throws java.io.IOException {
        ClientCheckRequest request = new ClientCheckRequest(confirmed, true);
        nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(confirmed.pollSeconds);
        return CHECKS.request(request);
    }

    public static synchronized boolean requestCheck() {
        try {
            Config current = Config.load(GAME_DIR);
            nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(current.pollSeconds);
            return CHECKS.request(new ClientCheckRequest(current, false));
        } catch (Exception error) {
            status = text("config_error");
            nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            return false;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!initialized) {
            initialized = true;
            nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            WORKER.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() < nextPoll) return;
                if (!checking()) requestCheck();
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

    private static Component check(ClientCheckRequest request, LatestCheckQueue.Ticket current) {
        Config requested = request.settings();
        try {
            String folkways;
            try {
                if (!current.getAsBoolean()) return null;
                folkways = requested.isLocalDevelopment(GAME_DIR) ? text("local").getString()
                        : UpdaterMod.folkwaysUpdater().checkAndStage(requested, request.takeOverExisting(), current,
                                action -> current.commit(action::run));
            }
            catch (Exception error) {
                folkways = failure(error);
                LOG.warn("Folkways {} / {} check unavailable ({}): {}", requested.repository, requested.channel,
                        error.getClass().getSimpleName(), folkways);
            }
            if (!current.getAsBoolean()) return null;
            String self;
            try { self = UpdaterMod.checkOwnUpdate(); }
            catch (Exception error) {
                LOG.warn("Updater self-check unavailable ({})", error.getClass().getSimpleName());
                self = text("self_error").getString();
            }
            return Component.translatable("folkways_updater.status.source_result", requested.repository,
                    requested.channel, readable(folkways, requested), readable(self, requested));
        } catch (Exception error) {
            LOG.warn("Client update check failed ({})", error.getClass().getSimpleName());
            return text("config_error");
        }
    }

    private static String failure(Exception error) {
        if (error instanceof TakeoverRejectedException rejected) {
            String key = switch (rejected.reason) {
                case UNSELECTED_MODULE -> "takeover_unselected";
                case COMPOSITE_OR_NESTED -> "takeover_composite";
                case UNSUPPORTED_CHANNEL -> "takeover_signed";
            };
            return Component.translatable("folkways_updater.status." + key, rejected.id).getString();
        }
        if (error instanceof DependencyConflictException conflict)
            return Component.translatable("folkways_updater.status.dependency_conflict", dependencyName(conflict.id),
                    conflict.version, conflict.requiredRange).getString();
        String reason = error.getMessage();
        String override = "Cannot prove the version of a top-level bundled-library override: ";
        if (reason != null && reason.startsWith(override)) {
            String id = reason.substring(override.length());
            if (id.matches("[a-z0-9_]{1,64}"))
                return Component.translatable("folkways_updater.status.unproven_override", dependencyName(id)).getString();
        }
        if ("No successful official Folkways push CI exists for this release commit".equals(reason))
            return text("source_ci_unavailable").getString();
        if ("Official release changed while downloading; previous files were retained".equals(reason))
            return text("source_changed").getString();
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

    private static String readable(String result, Config current) {
        if (result.startsWith("No official ")) {
            return Component.translatable("folkways_updater.status.no_release", current.repository, current.channel).getString();
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
        if ("folkways_current".equals(key) || "folkways_ready".equals(key)) {
            var commit = java.util.regex.Pattern.compile("(?<![0-9a-f])[0-9a-f]{40}(?![0-9a-f])").matcher(result);
            if (commit.find()) return Component.translatable("folkways_updater.status." + key + "_commit",
                    commit.group().substring(0, 8)).getString();
        }
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
