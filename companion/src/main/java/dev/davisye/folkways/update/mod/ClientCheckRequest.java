package dev.davisye.folkways.update.mod;

import com.google.gson.Gson;
import dev.davisye.folkways.update.core.Config;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

/** A saved selection and its one-round ownership authorization, never a persistent setting. */
final class ClientCheckRequest {
    private static final Gson JSON = new Gson();
    private final Config settings;
    private final boolean takeOverExisting;
    private final Object identity;

    ClientCheckRequest(Config settings, boolean takeOverExisting) throws IOException {
        this.settings = copy(settings);
        this.settings.validate();
        this.takeOverExisting = takeOverExisting;
        this.identity = Collections.unmodifiableList(Arrays.asList(this.settings.scope(),
                Collections.unmodifiableList(new ArrayList<>(this.settings.modules)),
                this.settings.localDevelopment, this.settings.networkPolicy, this.settings.regionHint,
                this.settings.manifestUrl, Collections.unmodifiableMap(new HashMap<>(this.settings.publicKeys)),
                this.settings.selfUpdateEnabled, this.settings.selfUpdateChannel, takeOverExisting));
    }

    Config settings() { return copy(settings); }
    boolean takeOverExisting() { return takeOverExisting; }
    Object identity() { return identity; }
    static void requireTakeoverAllowed(Config confirmed, Config current) {
        if (confirmed.localDevelopment || current.localDevelopment || !confirmed.manifestUrl.isBlank()
                || !current.manifestUrl.equals(confirmed.manifestUrl))
            throw new IllegalStateException("Takeover selection is no longer available");
    }
    private static Config copy(Config settings) { return JSON.fromJson(JSON.toJson(settings), Config.class); }
}
