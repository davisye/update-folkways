package dev.davisye.folkways.update.mod;

import dev.davisye.folkways.update.core.Config;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Edits the shared JSON configuration; advanced trust/restart settings are preserved on save. */
public final class UpdaterConfigScreen extends Screen {
    private static final Path GAME = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    private final Screen parent;
    private final Config draft;
    private final boolean readable;
    private boolean advanced;
    private Component feedback;
    private long feedbackUntil = Long.MAX_VALUE;
    private Button checkButton;

    public UpdaterConfigScreen(Screen parent) {
        super(t("title"));
        this.parent = parent;
        Config loaded;
        boolean success;
        try { loaded = Config.load(GAME); success = true; }
        catch (Exception error) { loaded = new Config(); success = false; }
        draft = loaded;
        readable = success;
        if (!success) feedback = t("load_error");
    }

    private static Component t(String key, Object... arguments) {
        return Component.translatable("folkways_updater.config." + key, arguments);
    }
    private static Component toggle(boolean enabled) { return t(enabled ? "on" : "off"); }
    private Button button(String key, int x, int y, int width, Runnable press, Object... args) {
        Button value = Button.builder(t(key, args), ignored -> press.run()).bounds(x, y, width, 20).build();
        addRenderableWidget(value);
        return value;
    }
    private void changed() { feedback = t("unsaved"); feedbackUntil = Long.MAX_VALUE; rebuildWidgets(); }

    @Override protected void init() {
        int content = Math.min(width - 24, 460), x = (width - content) / 2;
        int half = (content - 6) / 2, third = (content - 12) / 3, y = 56;
        if (!advanced) {
            button("repository", x, y, content, () -> {
                draft.repository = draft.repository.equals("IzakyL/folkways") ? "davisye/folkways" : "IzakyL/folkways";
                changed();
            }, draft.repository);
            y += 24;
            button("channel", x, y, half, () -> {
                List<String> channels = List.of("nightly", "release", "rc", "beta", "alpha");
                draft.channel = channels.get((channels.indexOf(draft.channel) + 1) % channels.size()); changed();
            }, draft.channel);
            button("local", x + half + 6, y, content - half - 6, () -> {
                draft.localDevelopment = !draft.localDevelopment; changed();
            }, toggle(draft.localDevelopment)).setTooltip(Tooltip.create(t("local_help")));
            y += 24;
            module("create", "Create", "create", "Create 6.0.0+", x, y, third);
            module("modulargolems", "ModularGolems", "modulargolems", "ModularGolems 3.1.43+", x + third + 6, y, third);
            module("cookery", "Cookery", "kaleidoscope_cookery", "KaleidoscopeCookery 1.6.x", x + 2 * (third + 6), y, content - 2 * (third + 6));
            y += 24;
            button("network", x, y, half, () -> {
                List<String> policies = List.of("mirror-first", "direct-first", "region");
                draft.networkPolicy = policies.get((policies.indexOf(draft.networkPolicy) + 1) % policies.size()); changed();
            }, t("policy." + draft.networkPolicy)).setTooltip(Tooltip.create(t("network_help")));
            button("self", x + half + 6, y, content - half - 6, () -> {
                draft.selfUpdateEnabled = !draft.selfUpdateEnabled; changed();
            }, toggle(draft.selfUpdateEnabled)).setTooltip(Tooltip.create(t("self_help")));
            y += 24;
            button("commands", x, y, half, () -> { draft.commandsEnabled = !draft.commandsEnabled; changed(); }, toggle(draft.commandsEnabled))
                    .setTooltip(Tooltip.create(t("commands_help")));
            button("more", x + half + 6, y, content - half - 6, () -> { advanced = true; rebuildWidgets(); });
        } else {
            button("poll", x, y, content, () -> {
                List<Integer> times = List.of(60, 300, 900, 1800, 3600);
                draft.pollSeconds = times.get((times.indexOf(draft.pollSeconds) + 1) % times.size()); changed();
            }, draft.pollSeconds);
            y += 24;
            button("self_channel", x, y, content, () -> {
                draft.selfUpdateChannel = draft.selfUpdateChannel.equals("nightly") ? "release" : "nightly"; changed();
            }, draft.selfUpdateChannel);
            y += 24;
            button("region", x, y, content, () -> {
                draft.regionHint = "CN".equals(draft.regionHint) ? "" : "CN"; changed();
            }, t("CN".equals(draft.regionHint) ? "region_cn" : "region_unset"))
                    .setTooltip(Tooltip.create(t("region_help")));
            y += 24;
            Button takeover = button("takeover", x, y, content, this::confirmTakeover);
            takeover.active = readable && !draft.localDevelopment && draft.manifestUrl.isBlank();
            takeover.setTooltip(Tooltip.create(t(draft.localDevelopment ? "takeover_local"
                    : !draft.manifestUrl.isBlank() ? "takeover_signed" : "takeover_help")));
            y += 24;
            button("folder", x, y, half, () -> Util.getPlatform().openFile(GAME.resolve(".folkways-updater").toFile()));
            button("back_basic", x + half + 6, y, content - half - 6, () -> { advanced = false; rebuildWidgets(); });
        }
        int footer = height - 28;
        button("save", x, footer, third, this::save).active = readable;
        checkButton = button("check", x + third + 6, footer, third, this::save);
        checkButton.active = readable;
        checkButton.setTooltip(Tooltip.create(t("check_help")));
        button("back", x + 2 * (third + 6), footer, content - 2 * (third + 6), this::onClose);
    }

    private void module(String module, String label, String dependency, String requirement, int x, int y, int size) {
        boolean selected = draft.modules.contains(module);
        boolean installed = ModList.get().isLoaded(dependency);
        Button control = button("module", x, y, size, () -> {
            if (draft.modules.contains(module)) draft.modules.remove(module); else draft.modules.add(module);
            changed();
        }, selected ? "+" : "-", label);
        control.active = readable;
        control.setTooltip(Tooltip.create(t(installed ? "module_help" : "module_missing", requirement)));
    }

    private void save() {
        if (!readable) return;
        try {
            persistDraft(draft, false);
            feedback = t(ClientUpdates.settingsSaved() ? "requested" : "busy");
            feedbackUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        } catch (Exception error) { feedback = t("save_error"); feedbackUntil = Long.MAX_VALUE; }
    }

    private Config persistDraft(Config selection, boolean takeover) throws java.io.IOException {
        Config[] saved = new Config[1];
        ClientUpdates.settingsSaving();
        Config.edit(GAME, current -> {
            if (takeover) ClientCheckRequest.requireTakeoverAllowed(selection, current);
            current.repository = selection.repository;
            current.channel = selection.channel;
            current.modules = new ArrayList<>(selection.modules);
            current.localDevelopment = selection.localDevelopment;
            current.networkPolicy = selection.networkPolicy;
            current.selfUpdateEnabled = selection.selfUpdateEnabled;
            current.commandsEnabled = selection.commandsEnabled;
            current.pollSeconds = selection.pollSeconds;
            current.selfUpdateChannel = selection.selfUpdateChannel;
            current.regionHint = selection.regionHint;
            saved[0] = current;
        });
        return saved[0];
    }

    private void confirmTakeover() {
        if (!readable || draft.localDevelopment || !draft.manifestUrl.isBlank()) return;
        try {
            Config confirmed = new ClientCheckRequest(draft, true).settings();
            Component message = t("takeover_message", confirmed.repository, confirmed.channel,
                    String.join(", ", confirmed.modules));
            minecraft.setScreen(new ConfirmScreen(accepted -> {
                minecraft.setScreen(this);
                if (!accepted) return;
                try {
                    Config saved = persistDraft(confirmed, true);
                    feedback = t(ClientUpdates.requestTakeover(saved) ? "takeover_requested" : "takeover_queued");
                    feedbackUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
                } catch (Exception error) {
                    feedback = t("takeover_error"); feedbackUntil = Long.MAX_VALUE;
                }
            }, t("takeover_title"), message, t("takeover_confirm"), t("takeover_cancel")));
        } catch (Exception error) { feedback = t("takeover_error"); feedbackUntil = Long.MAX_VALUE; }
    }

    @Override public void tick() {
        if (checkButton != null) checkButton.active = readable;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        graphics.drawCenteredString(font, t(advanced ? "subtitle_advanced" : "subtitle"), width / 2, 28, 0xBBBBBB);
        graphics.drawCenteredString(font, t("exit_help"), width / 2, 40, 0xBBBBBB);
        int content = Math.min(width - 24, 460), x = (width - content) / 2, y = height - 62;
        Component message = feedback == null || System.nanoTime() > feedbackUntil ? ClientUpdates.status() : feedback;
        if (ClientUpdates.checking()) message = ClientUpdates.status();
        int lines = 0;
        for (var line : font.split(message, content)) {
            if (lines++ == 3) break;
            graphics.drawString(font, line, x, y, 0xDDDDDD);
            y += 9;
        }
    }
}
