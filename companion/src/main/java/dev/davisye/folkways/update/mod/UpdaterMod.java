package dev.davisye.folkways.update.mod;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.davisye.folkways.update.core.Config;
import dev.davisye.folkways.update.core.Updater;
import dev.davisye.folkways.update.core.SelfUpdater;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("folkways_updater")
public final class UpdaterMod {
    private static final Logger LOG = LoggerFactory.getLogger(UpdaterMod.class);
    static volatile boolean quitClientRequested;
    private final LifecycleRequest lifecycle = new LifecycleRequest();
    private final AtomicBoolean checking = new AtomicBoolean();
    private final Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    private ScheduledExecutorService polling;
    private volatile boolean stopping;
    private boolean shutdownIssued;
    private boolean restartRequested;
    private String lastNotice = "";

    public UpdaterMod() {
        try { SelfUpdater.recordGameProcess(gameDir); }
        catch (Exception e) { LOG.warn("Could not record whole-JVM updater lease ({})", e.getClass().getSimpleName()); }
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        stopping = false;
        shutdownIssued = false;
        restartRequested = false;
        lifecycle.cancel();
        if (!event.getServer().isDedicatedServer()) return;
        try {
            Config config = Config.load(gameDir);
            if (!FMLEnvironment.production || config.isLocalDevelopment(gameDir)) {
                LOG.info("Local development: automatic update polling and replacement are disabled");
                return;
            }
            polling = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "folkways-update-poll");
                thread.setDaemon(true);
                return thread;
            });
            polling.scheduleWithFixedDelay(() -> check(event.getServer()), 15,
                    Math.max(60, config.pollSeconds), TimeUnit.SECONDS);
        } catch (Exception e) { LOG.warn("Updater polling unavailable: {}", e.getClass().getSimpleName()); }
    }

    private void check(MinecraftServer server) {
        if (stopping || !checking.compareAndSet(false, true)) return;
        try {
            if (!FMLEnvironment.production || Config.load(gameDir).isLocalDevelopment(gameDir)) return;
            String self;
            try { self = new SelfUpdater(gameDir).checkAndStage(); }
            catch (Exception e) { self = "Updater self-check unavailable; current updater retained"; }
            String folkways;
            try { folkways = new Updater(gameDir).checkAndStage(); }
            catch (Exception e) { folkways = "Folkways check unavailable; installed Folkways retained"; }
            String notice = self + " | " + folkways;
            if (!stopping && !notice.equals(lastNotice)) {
                lastNotice = notice;
                server.execute(() -> {
                    if (!stopping) server.getPlayerList().broadcastSystemMessage(
                            Component.literal("[Folkways updater] " + notice), false);
                });
            }
        } catch (Exception e) {
            // Do not log download URLs, launcher arguments, or credentials.
            LOG.warn("Update check failed ({}); installed files were retained", e.getClass().getSimpleName());
        } finally { checking.set(false); }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("folkwaysupdate")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("status").executes(context -> {
                    try {
                        String status = new Updater(gameDir).status() + " | " + new SelfUpdater(gameDir).status();
                        context.getSource().sendSuccess(() -> Component.literal(status), false);
                        return 1;
                    } catch (Exception e) { return fail(context.getSource(), "无法读取更新状态。"); }
                }))
                .then(Commands.literal("check").executes(context -> {
                    try {
                        if (!FMLEnvironment.production || Config.load(gameDir).isLocalDevelopment(gameDir)) {
                            return fail(context.getSource(), "本地开发模式，更新已禁用。");
                        }
                    } catch (Exception e) { return fail(context.getSource(), "无法读取更新配置。"); }
                    if (stopping) return fail(context.getSource(), "正在关闭，无法安排检查。");
                    Thread.ofPlatform().daemon(true).name("folkways-manual-check")
                            .start(() -> check(context.getSource().getServer()));
                    context.getSource().sendSuccess(() -> Component.literal("已安排后台检查；通过校验后仅预下载。"), false);
                    return 1;
                })));
        registerAction(dispatcher, "shutdown", LifecycleRequest.Action.SHUTDOWN);
        registerAction(dispatcher, "restart", LifecycleRequest.Action.RESTART);
    }

    private void registerAction(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
                                String name, LifecycleRequest.Action action) {
        if (dispatcher.getRoot().getChild(name) != null) {
            LOG.warn("/{} already exists; updater will not register over it", name);
            return;
        }
        dispatcher.register(Commands.literal(name).requires(this::canControl)
                .executes(context -> schedule(context.getSource(), action, 10))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 300))
                        .executes(context -> schedule(context.getSource(), action,
                                IntegerArgumentType.getInteger(context, "seconds"))))
                .then(Commands.literal("cancel").executes(context -> {
                    if (shutdownIssued || !lifecycle.cancel()) return fail(context.getSource(), "当前没有可取消的倒计时。");
                    context.getSource().sendSuccess(() -> Component.literal("已取消关闭/重启倒计时。"), true);
                    return 1;
                })));
    }

    private boolean canControl(CommandSourceStack source) {
        try { return source.hasPermission(4) && Config.load(gameDir).commandsEnabled; }
        catch (Exception e) { return false; }
    }

    private int schedule(CommandSourceStack source, LifecycleRequest.Action action, int seconds) {
        if (!canControl(source)) return fail(source, "需要权限等级 4，且配置中启用开发命令。");
        if (action == LifecycleRequest.Action.RESTART && !source.getServer().isDedicatedServer()) {
            return fail(source, "客户端完整重启尚未接入启动器；不能通过重启内置服务器代替。");
        }
        if (!lifecycle.schedule(action, System.nanoTime(), seconds, this::hasSupervisor)) {
            return fail(source, "已有关闭请求，或未通过 Bootstrap 启动，无法可靠重启。");
        }
        source.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                "[Folkways updater] " + seconds + " 秒后" + (action == LifecycleRequest.Action.RESTART ? "保存并重启。" : "保存并关闭。")
                        + " 可用 /" + (action == LifecycleRequest.Action.RESTART ? "restart" : "shutdown") + " cancel 取消。"), false);
        return 1;
    }

    private boolean hasSupervisor() {
        String token = System.getenv("FOLKWAYS_SUPERVISOR_TOKEN");
        String dir = System.getenv("FOLKWAYS_SUPERVISOR_DIR");
        boolean active = false;
        try {
            ProcessHandle handle = ProcessHandle.of(Long.parseLong(System.getenv("FOLKWAYS_SUPERVISOR_PID"))).orElse(null);
            active = handle != null && handle.isAlive() && handle.info().startInstant()
                    .map(time -> time.toString().equals(System.getenv("FOLKWAYS_SUPERVISOR_START"))).orElse(false);
        } catch (Exception ignored) { }
        return active && token != null && token.length() >= 32 && dir != null
                && "dedicated".equals(System.getenv("FOLKWAYS_SUPERVISOR_KIND"))
                && Files.isDirectory(Path.of(dir))
                && Path.of(dir).toAbsolutePath().normalize().startsWith(gameDir.resolve(".folkways-updater"));
    }

    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        if (shutdownIssued || stopping) return;
        var action = lifecycle.due(System.nanoTime());
        if (action == null) return;
        if (action == LifecycleRequest.Action.RESTART && !hasSupervisor()) {
            lifecycle.cancel();
            event.getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("[Folkways updater] supervisor 已失联，重启已取消。"), false);
            return;
        }
        // Both paths use Minecraft's normal stop/save sequence. Never System.exit/halt the JVM.
        shutdownIssued = true;
        restartRequested = action == LifecycleRequest.Action.RESTART;
        quitClientRequested = !event.getServer().isDedicatedServer();
        event.getServer().halt(false);
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        stopping = true;
        if (polling != null) polling.shutdownNow();
    }

    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) {
        if (!restartRequested || !event.getServer().isDedicatedServer() || !hasSupervisor()) return;
        try {
            Path dir = Path.of(System.getenv("FOLKWAYS_SUPERVISOR_DIR"));
            JsonObject request = new JsonObject();
            request.addProperty("token", System.getenv("FOLKWAYS_SUPERVISOR_TOKEN"));
            request.addProperty("action", "restart");
            Path temp = Files.createTempFile(dir, "restart-", ".tmp");
            Files.writeString(temp, request.toString(), StandardCharsets.UTF_8);
            Files.move(temp, dir.resolve("restart-request.json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { LOG.error("Could not record restart request; supervisor must leave the server stopped"); }
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
