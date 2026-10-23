package dev.davisye.folkways.update.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Shared disk config; defaults deliberately protect source builds. */
public final class Config {
    public boolean localDevelopment = true;
    public boolean commandsEnabled = false;
    public int pollSeconds = 900;
    public String repository = "IzakyL/folkways";
    public String minecraft = "1.21.1";
    public String channel = "nightly";
    public List<String> modules = new ArrayList<>(List.of("core", "vanilla"));
    public String manifestUrl = "";
    public Map<String, String> publicKeys = new HashMap<>();
    public String networkPolicy = "mirror-first";
    public String regionHint = "";
    public int restartLimit = 5;
    public int restartCooldownSeconds = 10;
    public boolean selfUpdateEnabled = true;
    public String selfUpdateChannel = "nightly";

    public static Config load(Path gameDir) throws IOException {
        Path p = gameDir.resolve(".folkways-updater/config.json");
        if (!Files.exists(p)) return new Config();
        if (Files.isSymbolicLink(p) || Files.size(p) > 1024 * 1024) throw new IOException("Unsafe config file");
        try {
            Config c = new Gson().fromJson(Files.readString(p), Config.class);
            if (c == null) throw new IOException("Config is empty");
            c.validate();
            return c;
        } catch (RuntimeException e) { throw new IOException("Invalid updater config", e); }
    }

    public boolean isLocalDevelopment(Path gameDir) {
        return localDevelopment || Boolean.getBoolean("folkways.localDevelopment")
            || "true".equalsIgnoreCase(System.getenv("FOLKWAYS_LOCAL_DEVELOPMENT"))
            || Files.exists(gameDir.resolve(".folkways-updater/LOCAL_DEVELOPMENT"));
    }

    public void validate() throws IOException {
        if (!Set.of("IzakyL/folkways", "davisye/folkways").contains(repository)) throw new IOException("Unsupported repository");
        if (!"1.21.1".equals(minecraft)) throw new IOException("Only Minecraft 1.21.1 is supported");
        if (!Set.of("release", "rc", "beta", "alpha", "nightly").contains(channel)) throw new IOException("Invalid channel");
        if (modules == null || !modules.containsAll(List.of("core", "vanilla")) || new HashSet<>(modules).size() != modules.size()
            || !Set.of("core", "vanilla", "create", "modulargolems", "cookery").containsAll(modules)) throw new IOException("Invalid module selection");
        if (publicKeys == null || manifestUrl == null || !Set.of("direct-first", "mirror-first", "region").contains(networkPolicy)) throw new IOException("Invalid network/key configuration");
        if (pollSeconds < 60 || pollSeconds > 86400 || restartLimit < 0 || restartLimit > 50 || restartCooldownSeconds < 1 || restartCooldownSeconds > 600) throw new IOException("Invalid timing limits");
        if (!Set.of("nightly", "release").contains(selfUpdateChannel)) throw new IOException("Invalid updater self-update channel");
    }

    public String scope() { return repository + "/" + minecraft + "/" + channel; }
}
