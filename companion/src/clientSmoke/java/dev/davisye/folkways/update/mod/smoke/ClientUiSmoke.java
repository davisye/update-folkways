package dev.davisye.folkways.update.mod.smoke;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import dev.davisye.folkways.update.core.Config;
import dev.davisye.folkways.update.mod.ClientUpdates;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只由 -PclientUiSmoke 加载，绝不写入正式 Mod JAR。 */
@EventBusSubscriber(modid = "folkways_updater", value = Dist.CLIENT)
public final class ClientUiSmoke {
    private static final Logger LOG = LoggerFactory.getLogger(ClientUiSmoke.class);
    private static final String PREFIX = "folkways_updater.config.";
    private static final String OWNER = "update-folkways-client-ui-smoke-v1";
    private static final long TIMEOUT_SECONDS = 90;
    private static final Map<String, Object> RESULT = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile boolean enabled, finished;
    private static boolean initialized;
    private static Path game;
    private static long started;
    private static Screen parent;
    private static String expectedRepository;
    private static boolean expectedLocal;
    private static int frameCount;
    private static long checkDeadline;
    private static long captureAfter;
    private static Phase phase = Phase.WAITING_FOR_TITLE;

    private enum Phase { WAITING_FOR_TITLE, REQUEST_CHECK, WAITING_FOR_CHECK, BASIC_CAPTURE, ADVANCED_CAPTURE, COMPLETE }

    private ClientUiSmoke() {}

    /** 路径与专用标记都匹配后才有资格写证据、请求退出当前 JVM。 */
    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        if (!Boolean.getBoolean("folkways.updater.clientUiSmoke") || FMLEnvironment.production) return;
        try {
            Path expected = Path.of(System.getProperty("folkways.updater.clientUiSmoke.gameDir")).toRealPath();
            game = FMLPaths.GAMEDIR.get().toRealPath();
            if (!game.equals(expected) || !game.endsWith(Path.of("work", "client-config-smoke"))
                    || !OWNER.equals(Files.readString(game.resolve("client-ui-smoke-owner.txt")).trim())) {
                throw new IllegalStateException("隔离客户端目录或归属标记不匹配");
            }
            enabled = true;
            started = System.nanoTime();
            RESULT.put("fixture", OWNER);
            RESULT.put("started", true);
            Thread.ofVirtual().name("folkways-client-ui-smoke-timeout").start(() -> {
                try { TimeUnit.SECONDS.sleep(TIMEOUT_SECONDS); }
                catch (InterruptedException ignored) { return; }
                if (!finished) finish(false, new IllegalStateException("客户端界面验收超时"));
            });
        } catch (Exception error) {
            // 不匹配的实例从不自动关闭，也不写入其配置目录。
            LOG.error("客户端 UI 烟测未启用：隔离目录验证失败 ({})", error.getClass().getSimpleName());
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Pre event) {
        initialize();
        if (!enabled || finished) return;
        Minecraft mc = Minecraft.getInstance();
        hide(mc);
        try {
            if (phase == Phase.WAITING_FOR_TITLE && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                openAndEdit(mc);
            } else if (phase == Phase.REQUEST_CHECK || phase == Phase.WAITING_FOR_CHECK) {
                awaitIndependentCheck(mc);
            }
        } catch (Exception | AssertionError error) { finish(false, error); }
    }

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (enabled && !finished) hide(Minecraft.getInstance());
    }

    /** 截取已完成渲染的 framebuffer；写完文件后才退出，避免异步截图丢失。 */
    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        if (!enabled || finished || (phase != Phase.BASIC_CAPTURE && phase != Phase.ADVANCED_CAPTURE)) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            requireConfigScreen(mc);
            if (System.nanoTime() < captureAfter) return;
            if (mc.getOverlay() != null || ++frameCount < 3) return;
            if (phase == Phase.BASIC_CAPTURE) {
                RESULT.put("basicLayout", checkBounds(mc.screen));
                requireOptionalControls(mc.screen);
                capture(mc, "config-basic.png");
                click(mc.screen, "more");
                phase = Phase.ADVANCED_CAPTURE;
                frameCount = 0;
            } else if (phase == Phase.ADVANCED_CAPTURE) {
                RESULT.put("advancedLayout", checkBounds(mc.screen));
                button(mc.screen, "back_basic");
                capture(mc, "config-advanced.png");
                click(mc.screen, "back_basic");
                button(mc.screen, "repository");
                verifySaved();
                click(mc.screen, "back");
                require(mc.screen == parent, "配置工厂没有保留返回页面");
                RESULT.put("factoryBackClosesToParent", true);
                phase = Phase.COMPLETE;
                finish(true, null);
            }
        } catch (Exception | AssertionError error) { finish(false, error); }
    }

    private static void hide(Minecraft mc) {
        if (mc != null && mc.getWindow() != null) GLFW.glfwHideWindow(mc.getWindow().getWindow());
    }

    private static void openAndEdit(Minecraft mc) throws Exception {
        var container = ModList.get().getModContainerById("folkways_updater").orElseThrow();
        IConfigScreenFactory factory = container.getCustomExtension(IConfigScreenFactory.class)
                .orElseThrow(() -> new IllegalStateException("Mods 菜单配置入口未注册"));
        RESULT.put("factoryRegistered", true);
        Config initial = Config.load(game);
        require(initial.localDevelopment && initial.selfUpdateEnabled, "隔离实例没有显式启用 Folkways 本地模式和独立自身检查");
        require(initial.modules.equals(List.of("core", "vanilla")), "界面首装验收必须从两个基础模块开始");
        expectedRepository = initial.repository.equals("IzakyL/folkways") ? "davisye/folkways" : "IzakyL/folkways";
        Files.writeString(game.resolve(".folkways-updater/LOCAL_DEVELOPMENT"), "legacy UI smoke marker\n", StandardCharsets.UTF_8);
        expectedLocal = false;
        parent = mc.screen;
        Screen screen = factory.createScreen(container, parent);
        mc.setScreen(screen);
        requireConfigScreen(mc);
        checkBounds(screen);
        requireOptionalControls(screen);
        // 缺少三个前置时仍能实际点击，并将三项扩展一起保存。
        for (int index = 0; index < 3; index++) optionalButton(mc.screen, index).onPress();
        // 取消后重选第一项，验证两方向的实际交互，最终仍全选。
        optionalButton(mc.screen, 0).onPress();
        optionalButton(mc.screen, 0).onPress();
        RESULT.put("allMissingPrerequisiteModulesClicked", true);
        // 模拟用户开着旧草稿时，其他工具修改了不在此界面编辑的字段。
        Config.edit(game, latest -> { latest.restartLimit = 17; latest.restartCooldownSeconds = 23; });
        click(screen, "repository");
        click(mc.screen, "local");
        click(mc.screen, "save");
        RESULT.put("repositoryClicked", true);
        RESULT.put("localClicked", true);
        verifySaved();
        RESULT.put("legacyMarkerIgnoredWhenDisabled", true);
        // 在发起检查前恢复 true；整个烟测均不请求真实 Folkways 网络。
        click(mc.screen, "local");
        click(mc.screen, "save");
        expectedLocal = true;
        verifySaved();
        RESULT.put("explicitLocalModeEnabled", true);
        checkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        phase = Phase.REQUEST_CHECK;
        frameCount = 0;
    }

    private static void verifySaved() throws Exception {
        Config saved = Config.load(game);
        require(expectedRepository.equals(saved.repository), "仓库按钮没有保存到共享配置");
        require(saved.localDevelopment == expectedLocal, "本地开发按钮没有保存到共享配置");
        require(new java.util.HashSet<>(saved.modules).equals(Set.of("core", "vanilla", "create", "modulargolems", "cookery")),
                "缺少前置时三个可选扩展没有全部保存");
        RESULT.put("allOptionalModulesSavedWithoutPrerequisites", true);
        require(saved.restartLimit == 17 && saved.restartCooldownSeconds == 23, "保存覆盖了外部编辑的隐藏字段");
        require(Files.isRegularFile(game.resolve(".folkways-updater/LOCAL_DEVELOPMENT"))
                && saved.isLocalDevelopment(game) == expectedLocal, "旧标记覆盖了显式本地模式开关");
        RESULT.put("saved", true);
        RESULT.put("hiddenEditsPreserved", true);
        RESULT.put("localModeMatchesBoolean", true);
    }

    private static void awaitIndependentCheck(Minecraft mc) throws Exception {
        requireConfigScreen(mc);
        require(Config.load(game).isLocalDevelopment(game), "验收检查前 Folkways 本地模式被关闭");
        require(System.nanoTime() < checkDeadline, "本地模式中的独立自身检查未在五秒内完成");
        if (phase == Phase.REQUEST_CHECK) {
            if (ClientUpdates.checking() || !button(mc.screen, "check").active) return;
            click(mc.screen, "check");
            // 等待“请求已提交”的四秒反馈消失，让截图包含独立检查结果。
            captureAfter = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4200);
            RESULT.put("checkButtonClickedInLocalMode", true);
            phase = Phase.WAITING_FOR_CHECK;
            return;
        }
        if (ClientUpdates.checking()) return;
        String status = ClientUpdates.status().getString();
        String local = Component.translatable("folkways_updater.status.local").getString();
        String noTarget = Component.translatable("folkways_updater.status.self_no_target").getString();
        require(status.contains(local) && status.contains(noTarget) && !status.equals(local),
                "本地模式没有分别返回 Folkways 暂停与 IDE 无自身 JAR 目标");
        RESULT.put("independentSelfCheckCompleted", true);
        RESULT.put("selfCheckHasNoInstalledJarTarget", true);
        RESULT.put("folkwaysCheckSkippedInLocalMode", true);
        RESULT.put("checkStatus", status);
        phase = Phase.BASIC_CAPTURE;
        frameCount = 0;
    }

    private static void requireConfigScreen(Minecraft mc) {
        require(mc.screen != null && mc.screen.getClass().getName()
                .equals("dev.davisye.folkways.update.mod.UpdaterConfigScreen"), "配置工厂没有打开实际配置页面");
    }

    private static AbstractButton button(Screen screen, String key) {
        List<AbstractButton> matching = widgets(screen).stream()
                .filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                .filter(widget -> containsKey(widget.getMessage(), PREFIX + key)).toList();
        require(matching.size() == 1, "按钮翻译键不唯一或缺失：" + key);
        return matching.getFirst();
    }

    private static void click(Screen screen, String key) {
        AbstractButton button = button(screen, key);
        require(button.visible && button.active, "按钮不可交互：" + key);
        button.onPress();
    }

    private static boolean containsKey(Component text, String key) {
        if (text.getContents() instanceof TranslatableContents contents) {
            if (key.equals(contents.getKey())) return true;
            for (Object argument : contents.getArgs())
                if (argument instanceof Component nested && containsKey(nested, key)) return true;
        }
        for (Component sibling : text.getSiblings()) if (containsKey(sibling, key)) return true;
        return false;
    }

    private static List<AbstractWidget> widgets(Screen screen) {
        List<AbstractWidget> result = new ArrayList<>();
        Set<GuiEventListener> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(screen.children(), seen, result);
        return result;
    }

    private static void collect(List<? extends GuiEventListener> children, Set<GuiEventListener> seen, List<AbstractWidget> result) {
        for (GuiEventListener child : children) {
            if (!seen.add(child)) continue;
            if (child instanceof AbstractWidget widget) result.add(widget);
            if (child instanceof ContainerEventHandler container) collect(container.children(), seen, result);
        }
    }

    private static Map<String, Object> checkBounds(Screen screen) {
        require(screen.width >= 320 && screen.height >= 240, "GUI 尺寸小于 320x240");
        List<Map<String, Object>> bounds = new ArrayList<>();
        for (AbstractWidget widget : widgets(screen)) {
            if (!widget.visible) continue;
            require(widget.getWidth() > 0 && widget.getHeight() > 0 && widget.getX() >= 0 && widget.getY() >= 0
                    && widget.getX() + widget.getWidth() <= screen.width
                    && widget.getY() + widget.getHeight() <= screen.height, "可见控件超出屏幕边界");
            bounds.add(Map.of("x", widget.getX(), "y", widget.getY(), "width", widget.getWidth(),
                    "height", widget.getHeight(), "active", widget.active));
        }
        require(!bounds.isEmpty(), "页面没有实际控件");
        return Map.of("width", screen.width, "height", screen.height, "visibleWidgets", bounds);
    }

    private static AbstractButton optionalButton(Screen screen, int index) {
        List<AbstractButton> controls = widgets(screen).stream()
                .filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                .filter(widget -> containsKey(widget.getMessage(), PREFIX + "module")).toList();
        require(controls.size() == 3 && index >= 0 && index < controls.size(), "可选扩展按钮缺失");
        AbstractButton control = controls.get(index);
        require(control.active && control.visible, "缺少前置时不能勾选扩展");
        return control;
    }

    private static void requireOptionalControls(Screen screen) {
        List<AbstractWidget> controls = widgets(screen).stream()
                .filter(widget -> containsKey(widget.getMessage(), PREFIX + "module")).toList();
        require(controls.size() == 3, "基础页没有三个可选扩展控件");
        for (AbstractWidget control : controls) require(control.visible && control.active, "缺少前置的扩展仍应允许选择自动安装");
        require(!ModList.get().isLoaded("create") && !ModList.get().isLoaded("modulargolems")
                && !ModList.get().isLoaded("kaleidoscope_cookery"), "验收实例意外带入了可选扩展前置");
        RESULT.put("missingOptionalPrerequisitesSelectable", true);
    }

    private static void capture(Minecraft mc, String fileName) throws Exception {
        Path directory = game.resolve("screenshots");
        Files.createDirectories(directory);
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(directory.resolve(fileName));
        }
        require(Files.size(directory.resolve(fileName)) > 0, "截图没有写入文件");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static synchronized void finish(boolean passed, Throwable error) {
        if (!enabled || finished) return;
        finished = true;
        RESULT.put("passed", passed);
        RESULT.put("phase", phase.name());
        RESULT.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        if (passed) RESULT.put("screenshots", List.of("screenshots/config-basic.png", "screenshots/config-advanced.png"));
        if (error != null) {
            RESULT.put("errorType", error.getClass().getSimpleName());
            String message = String.valueOf(error.getMessage());
            RESULT.put("error", message.substring(0, Math.min(message.length(), 1000)));
        }
        try {
            Path temporary = game.resolve("ui-smoke-result.json.next");
            Map<String, Object> snapshot;
            synchronized (RESULT) { snapshot = new LinkedHashMap<>(RESULT); }
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(snapshot), StandardCharsets.UTF_8);
            Files.move(temporary, game.resolve("ui-smoke-result.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("客户端 UI 烟测结束：passed={}, phase={}", passed, phase);
        } catch (Exception writeError) { LOG.error("客户端 UI 烟测证据写入失败 ({})", writeError.getClass().getSimpleName()); }
        finally {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(mc::stop);
        }
    }
}
