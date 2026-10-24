package dev.davisye.folkways.update.core;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Offline-safe staging and an externally supervised, recoverable install transaction. */
public final class Updater {
    @FunctionalInterface public interface Fetcher { byte[] fetch(String url, long maximum) throws Exception; }
    @FunctionalInterface public interface PendingCommit { void run() throws Exception; }
    @FunctionalInterface public interface CommitAuthorization { void commit(PendingCommit action) throws Exception; }
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final long MAX_BUNDLE = 128L * 1024 * 1024;
    static final Set<String> MODULES = Set.of("core", "vanilla", "create", "modulargolems", "cookery");
    final Path game, home, mods;
    private final Fetcher injected;
    final String loaderVersion;
    GitHubFolkways.Source githubSource;
    ApprovedDependencies.Catalog approvedDependencies; // Package-private fixture injection; production always uses the immutable catalogue.
    interface InstallGate { void check() throws Exception; void committed() throws Exception; }
    InstallGate installGate;
    private void installCheck() throws Exception { if (installGate != null) installGate.check(); }
    private void installCommitted() throws Exception { if (installGate != null) installGate.committed(); }

    public Updater(Path gameDir) throws IOException { this(gameDir, (Fetcher)null); }
    public Updater(Path gameDir, String loaderVersion) throws IOException { this(gameDir, null, loaderVersion); }
    Updater(Path gameDir, Fetcher injected) throws IOException {
        this(gameDir, injected, null);
    }
    private Updater(Path gameDir, Fetcher injected, String loaderVersion) throws IOException {
        game = gameDir.toAbsolutePath().normalize().toRealPath();
        home = game.resolve(".folkways-updater"); mods = game.resolve("mods"); this.injected = injected;
        safe(home); safe(mods);
        this.loaderVersion = loaderVersion;
    }

    public static void initialize(Path gameDir) throws IOException {
        Updater u = new Updater(gameDir); u.directory(u.home);
        Path config = u.home.resolve("config.json");
        if (!Files.exists(config)) atomic(config, JSON.toJson(new Config()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String status() throws IOException {
        Config c = Config.load(game);
        return "mode=" + (c.isLocalDevelopment(game) ? "local-development (replacement disabled)" : "managed")
            + ", source=" + c.scope() + (c.manifestUrl.isBlank() ? " (official GitHub)" : " (signed manifest)")
            + ", pending=" + (Files.exists(home.resolve("pending/manifest.json")) || Files.exists(home.resolve("github-folkways-pending/plan.json")))
            + ", recovery=" + (Files.exists(home.resolve("journal.json")) || Files.exists(home.resolve(GitHubFolkways.JOURNAL)));
    }

    /** Explicit ownership baseline; never silently adopts newly copied developer jars. */
    public String adopt() throws Exception {
        Config c = Config.load(game);
        return locked(() -> {
            if (Files.exists(home.resolve("journal.json"))) throw new IOException("Recover transaction before adoption");
            State old = state();
            if (!old.installed.isEmpty()) throw new IOException("Already adopted; refusing to reset local-change protection");
            State s = new State();
            Map<String, List<Path>> found = findManaged();
            if (!found.keySet().equals(new HashSet<>(c.modules))) throw new IOException("Installed modules differ from configured modules: " + found.keySet());
            for (String module : c.modules) {
                List<Path> files = found.get(module);
                if (files.size() != 1) throw new IOException("Duplicate module: " + module);
                Path p = files.getFirst(); byte[] bytes = readBounded(p, MAX_BUNDLE); validateJar(bytes, module);
                s.installed.put(module, new Owned(p.getFileName().toString(), sha(bytes), Files.size(p)));
            }
            saveState(s); return "Adopted " + s.installed.size() + " modules; local development protection remains " + c.isLocalDevelopment(game);
        });
    }

    public String checkAndStage() throws Exception { return checkAndStage(null, false); }
    public String checkAndStage(boolean takeOverExisting) throws Exception { return checkAndStage(null, takeOverExisting); }
    /** The consent applies only to this defensive configuration snapshot, never a subsequently selected source. */
    public String checkAndStage(Config expected, boolean takeOverExisting) throws Exception { return checkAndStage(expected, takeOverExisting, () -> true); }
    public String checkAndStage(Config expected, boolean takeOverExisting, java.util.function.BooleanSupplier stillCurrent) throws Exception {
        return checkAndStage(expected, takeOverExisting, stillCurrent, PendingCommit::run);
    }
    public String checkAndStage(Config expected, boolean takeOverExisting, java.util.function.BooleanSupplier stillCurrent, CommitAuthorization authorization) throws Exception {
        Objects.requireNonNull(stillCurrent); Objects.requireNonNull(authorization);
        Config c = expected == null ? Config.load(game) : JSON.fromJson(JSON.toJson(expected), Config.class);
        c.validate();
        if (c.isLocalDevelopment(game)) return "Local development: update check and replacement disabled";
        if (c.manifestUrl.isBlank()) return new GitHubFolkways(this, stillCurrent, authorization).checkAndStage(c, takeOverExisting);
        if (takeOverExisting) throw new TakeoverRejectedException("", TakeoverRejectedException.Reason.UNSUPPORTED_CHANNEL);
        return locked(() -> {
            requireManaged(c);
            if (Files.exists(home.resolve(GitHubFolkways.JOURNAL))) throw new IOException("Recover the official GitHub transaction before changing channels");
            restorePendingSwap(c);
            if (Files.exists(home.resolve("journal.json"))) throw new IOException("Interrupted transaction requires bootstrap recovery");
            State s = state(); validateOwned(s, c);
            Fetcher fetcher = injected == null ? new Transport(c) : injected;
            byte[] envelope = injected == null
                ? ((Transport)fetcher).fetchValidated(c.manifestUrl, 1024 * 1024, bytes -> manifest(bytes, c))
                : fetcher.fetch(c.manifestUrl, 1024 * 1024);
            Manifest m = manifest(envelope, c);
            long accepted = s.highWater.getOrDefault(c.scope(), 0L);
            if (m.sequence < accepted) throw new IOException("Rejected manifest rollback");
            if (m.sequence == accepted) {
                if (!Objects.equals(s.accepted.get(c.scope()), sha(envelope))) throw new IOException("Sequence reused with different manifest");
                if (isInstalled(m, s, c)) return "Already installed " + m.buildId;
            }
            Path pending = home.resolve("pending");
            if (Files.exists(pending.resolve("manifest.json"))) {
                byte[] prior = readBounded(pending.resolve("manifest.json"), 1024 * 1024);
                try {
                    Manifest pm = manifest(prior, c);
                    if (pm.sequence > m.sequence) throw new IOException("Rejected downgrade of pending update");
                    if (pm.sequence == m.sequence) {
                        if (!sha(prior).equals(sha(envelope))) throw new IOException("Pending sequence reused");
                        verifyPending(pending, pm, c); return "Update already downloaded: " + pm.buildId;
                    }
                } catch (ScopeMismatch ignored) { /* Explicit source/channel selection discards old pending only after success. */ }
            }
            Path candidate = home.resolve("candidate"); clear(candidate); directory(candidate);
            try {
                if (m.bundle != null) {
                    byte[] bundle = download(fetcher, m.bundle.url, m.bundle.mirrors, m.bundle.sha256, m.bundle.size, c);
                    unpack(bundle, m, candidate);
                } else {
                    for (String module : c.modules) {
                        requireManaged(c);
                        Artifact a = artifact(m, module);
                        byte[] jar = download(fetcher, a.url, a.mirrors, a.sha256, a.size, c);
                        validateJar(jar, module);
                        requireManaged(c);
                        atomic(candidate.resolve(a.file), jar);
                    }
                }
                atomic(candidate.resolve("manifest.json"), envelope);
                requireManaged(c); validateOwned(state(), c);
                commitPending(candidate, pending, c);
            } finally { clear(candidate); }
            return "Downloaded " + m.buildId + "; installs before the next supervised launch";
        });
    }

    /** Package-private: only Bootstrap, while holding its lifetime session lock, calls this. */
    String applyPending() throws Exception {
        Config c = Config.load(game);
        if (c.manifestUrl.isBlank()) return new GitHubFolkways(this).apply(c);
        if (Files.exists(home.resolve(GitHubFolkways.JOURNAL))) throw new IOException("An official GitHub installation requires recovery before switching to a signed manifest");
        if (c.isLocalDevelopment(game)) {
            if (Files.exists(home.resolve("journal.json"))) throw new IOException("Local-development mode blocks recovery writes; resolve interrupted transaction manually");
            return "Local development: replacement disabled";
        }
        return locked(() -> {
            installCheck(); requireManaged(c); recover();
            restorePendingSwap(c);
            Path pending = home.resolve("pending");
            if (!Files.exists(pending.resolve("manifest.json"))) return "No pending update";
            byte[] envelope = readBounded(pending.resolve("manifest.json"), 1024 * 1024);
            Manifest m;
            try { m = manifest(envelope, c); }
            catch (ScopeMismatch e) { clear(pending); return "Discarded pending update for previous source/channel selection"; }
            verifyPending(pending, m, c);
            State before = state(); validateOwned(before, c);
            long installedSequence = before.highWater.getOrDefault(c.scope(), 0L);
            if (m.sequence == installedSequence && sha(envelope).equals(before.accepted.get(c.scope())) && isInstalled(m, before, c)) {
                // Crash after state/journal commit but before cache cleanup: installation already completed.
                for (String module : c.modules) {
                    Artifact a = artifact(m, module);
                    Owned current = before.installed.get(module);
                    if (!a.sha256.equals(current.sha256) || a.size != current.size) throw new IOException("Committed installation differs from pending update");
                }
                clear(pending); return "Already installed " + m.buildId + "; cleaned committed pending cache";
            }
            if (m.sequence < installedSequence || (m.sequence == installedSequence && !sha(envelope).equals(before.accepted.get(c.scope())))) throw new IOException("Pending update is not newer or reuses a sequence");
            Path backup = home.resolve("backup"); clear(backup); directory(backup);
            for (Map.Entry<String, Owned> e : before.installed.entrySet()) {
                byte[] b = readBounded(mods.resolve(e.getValue().file), MAX_BUNDLE);
                verify(b, e.getValue().sha256, e.getValue().size);
                atomic(backup.resolve(e.getKey() + ".jar"), b);
            }
            Journal journal = new Journal(); journal.before = before;
            for (String module : c.modules) {
                Artifact a = artifact(m, module);
                journal.after.put(module, new Owned(before.installed.get(module).file, a.sha256, a.size));
            }
            installCheck(); requireManaged(c); validateOwned(before, c);
            atomic(home.resolve("journal.json"), JSON.toJson(journal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                for (String module : c.modules) {
                    installCheck(); requireManaged(c);
                    Artifact a = artifact(m, module);
                    atomic(mods.resolve(before.installed.get(module).file), readBounded(pending.resolve(a.file), MAX_BUNDLE));
                }
                State after = new State(); after.installed.putAll(journal.after);
                after.highWater.putAll(before.highWater); after.accepted.putAll(before.accepted);
                after.highWater.put(c.scope(), m.sequence); after.accepted.put(c.scope(), sha(envelope)); after.buildId = m.buildId; after.installedScope = c.scope();
                installCheck(); saveState(after); installCommitted(); Files.delete(home.resolve("journal.json")); clear(pending);
                return "Installed " + m.buildId;
            } catch (Exception e) {
                // A changed development flag blocks further replacement, including automatic rollback.
                if (!Config.load(game).isLocalDevelopment(game)) recover();
                throw e;
            }
        });
    }

    void recover() throws Exception {
        if (Files.exists(home.resolve(GitHubFolkways.JOURNAL))) throw new IOException("Official GitHub recovery must run through its matching source");
        Path p = home.resolve("journal.json");
        if (!Files.exists(p)) return;
        installCheck();
        if (Config.load(game).isLocalDevelopment(game)) throw new IOException("Recovery disabled in local development");
        Journal j = JSON.fromJson(new String(readBounded(p, 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8), Journal.class);
        if (j == null || j.before == null || j.before.installed.isEmpty() || !j.after.keySet().equals(j.before.installed.keySet())) throw new IOException("Invalid recovery journal");
        for (var e : j.before.installed.entrySet()) {
            String module = e.getKey(); if (!MODULES.contains(module)) throw new IOException("Invalid journal module");
            Owned old = e.getValue(), next = j.after.get(module); fileName(old.file); fileName(next.file);
            if (!old.file.equals(next.file)) throw new IOException("Invalid journal path");
            byte[] backup = readBounded(home.resolve("backup/" + module + ".jar"), MAX_BUNDLE); verify(backup, old.sha256, old.size);
            Path current = mods.resolve(old.file); safe(current);
            if (!Files.exists(current)) throw new IOException("Managed file disappeared during recovery");
            String h = sha(readBounded(current, MAX_BUNDLE));
            if (!h.equals(old.sha256) && !h.equals(next.sha256)) throw new IOException("Local edit blocks recovery: " + old.file);
        }
        for (var e : j.before.installed.entrySet()) {
            installCheck();
            if (Config.load(game).isLocalDevelopment(game)) throw new IOException("Local-development protection enabled during recovery");
            atomic(mods.resolve(e.getValue().file), readBounded(home.resolve("backup/" + e.getKey() + ".jar"), MAX_BUNDLE));
        }
        installCheck(); saveState(j.before); installCommitted(); Files.delete(p);
    }

    private void requireManaged(Config expected) throws IOException {
        Config now = Config.load(game);
        if (now.isLocalDevelopment(game)) throw new IOException("Local development blocks mutation");
        if (!now.scope().equals(expected.scope()) || !now.modules.equals(expected.modules) || !now.publicKeys.equals(expected.publicKeys)
            || !now.manifestUrl.equals(expected.manifestUrl)) throw new IOException("Configuration changed during update");
    }

    private void validateOwned(State s, Config c) throws Exception {
        if (!s.installed.keySet().equals(new HashSet<>(c.modules))) throw new IOException("Run explicit adopt first; module selection must match installed set");
        Map<String, List<Path>> found = findManaged();
        if (!found.keySet().equals(s.installed.keySet())) throw new IOException("Unmanaged Folkways JAR present or owned module missing");
        for (var e : s.installed.entrySet()) {
            Owned o = e.getValue(); fileName(o.file);
            if (found.get(e.getKey()).size() != 1 || !found.get(e.getKey()).getFirst().getFileName().toString().equals(o.file)) throw new IOException("Duplicate/unmanaged Folkways module");
            verify(readBounded(mods.resolve(o.file), MAX_BUNDLE), o.sha256, o.size);
        }
    }

    private Map<String, List<Path>> findManaged() throws IOException {
        Map<String, List<Path>> found = new TreeMap<>(); directory(mods);
        try (var paths = Files.list(mods)) {
            for (Path p : paths.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".jar")) continue;
                safe(p);
                Set<String> ids = new HashSet<>();
                try (ZipFile zip = new ZipFile(p.toFile())) {
                    ZipEntry metadata = zip.getEntry("META-INF/neoforge.mods.toml");
                    if (metadata != null) try (InputStream input = zip.getInputStream(metadata)) {
                        byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                        if (bytes.length > 1024 * 1024) throw new IOException("Mod metadata exceeds limit");
                        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        ids.addAll(ownModules(text));
                    }
                } catch (ZipException e) {
                    if (name.startsWith("folkways-")) throw new IOException("Invalid Folkways JAR", e);
                }
                for (String module : MODULES) if (name.startsWith("folkways-" + module + "-") || ids.contains(module)) {
                    found.computeIfAbsent(module, ignored -> new ArrayList<>()).add(p);
                }
            }
        }
        return found;
    }

    Manifest manifest(byte[] envelope, Config c) throws Exception {
        try { return verifiedManifest(envelope, c); }
        catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid signed manifest", e); }
    }

    private Manifest verifiedManifest(byte[] envelope, Config c) throws Exception {
        if (envelope.length > 1024 * 1024) throw new IOException("Manifest too large");
        Envelope e = JSON.fromJson(new String(envelope, java.nio.charset.StandardCharsets.UTF_8), Envelope.class);
        if (e == null || e.keyId == null || !c.publicKeys.containsKey(e.keyId)) throw new IOException("Manifest key is not trusted");
        byte[] payload = Base64.getDecoder().decode(e.payload);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(c.publicKeys.get(e.keyId)))));
        verifier.update(payload);
        if (!verifier.verify(Base64.getDecoder().decode(e.signature))) throw new IOException("Invalid manifest signature");
        Manifest m = JSON.fromJson(new String(payload, java.nio.charset.StandardCharsets.UTF_8), Manifest.class);
        if (m == null || m.schema != 1 || !Objects.equals(m.repository, c.repository) || !Objects.equals(m.minecraft, c.minecraft)
            || !Objects.equals(m.channel, c.channel) || !"neoforge".equals(m.loader)) throw new ScopeMismatch();
        if (m.sequence <= 0 || m.buildId == null || !m.buildId.matches("[0-9a-f]{40}") || m.components == null) throw new IOException("Invalid manifest fields");
        if (m.bundle != null) {
            if (m.bundle.size < 1 || m.bundle.size > MAX_BUNDLE || !hash(m.bundle.sha256)) throw new IOException("Invalid bundle metadata");
            validateRoutes(m.bundle.url, m.bundle.mirrors);
        }
        Set<String> seen = new HashSet<>(), files = new HashSet<>(); long total = 0;
        for (Artifact a : m.components) {
            if (a == null || a.file == null || !MODULES.contains(a.module) || !seen.add(a.module) || !files.add(a.file.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate/unknown component");
            fileName(a.file);
            if (!a.file.startsWith("folkways-" + a.module + "-") || !hash(a.sha256) || a.size <= 0 || a.size > MAX_BUNDLE) throw new IOException("Invalid component metadata");
            if (m.bundle == null || a.url != null || a.mirrors != null) validateRoutes(a.url, a.mirrors);
            total = Math.addExact(total, a.size);
        }
        if (total > MAX_BUNDLE || !seen.equals(MODULES)) throw new IOException("Manifest must describe all five modules within the total size limit");
        return m;
    }

    private static void validateRoutes(String url, List<String> mirrors) throws IOException {
        if (url == null || url.isBlank()) throw new IOException("Component download URL is required without a bundle");
        Transport.safeUri(url);
        if (mirrors != null) {
            if (mirrors.size() > 4) throw new IOException("Too many mirrors");
            for (String mirror : mirrors) Transport.safeUri(mirror);
        }
    }

    private byte[] download(Fetcher fetcher, String url, List<String> mirrors, String digest, long size, Config config) throws Exception {
        List<String> routes = new ArrayList<>();
        boolean preferMirrors = config.networkPolicy.equals("mirror-first")
            || (config.networkPolicy.equals("region") && "CN".equalsIgnoreCase(config.regionHint));
        if (preferMirrors && mirrors != null) routes.addAll(mirrors);
        routes.add(url);
        if (!preferMirrors && mirrors != null) routes.addAll(mirrors);
        Exception last = null;
        for (String route : routes) {
            try {
                byte[] data = injected == null
                    ? ((Transport)fetcher).fetchValidated(route, size, bytes -> verify(bytes, digest, size))
                    : fetcher.fetch(route, size);
                verify(data, digest, size); return data;
            } catch (Exception e) { last = e; }
        }
        throw new IOException("No verified artifact route", last);
    }

    /** Directory renames preserve the preceding complete pending set until the new one is committed. */
    private void commitPending(Path candidate, Path pending, Config config) throws IOException {
        Path previous = home.resolve("pending.previous");
        requireManaged(config);
        if (Files.exists(pending)) Files.move(pending, previous, StandardCopyOption.ATOMIC_MOVE);
        try { requireManaged(config); Files.move(candidate, pending, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) {
            if (!Files.exists(pending) && Files.exists(previous)) Files.move(previous, pending, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
        clear(previous);
    }

    /** Recover a stopped cache commit before checking or applying; live mod files were never involved. */
    private void restorePendingSwap(Config config) throws IOException {
        Path pending = home.resolve("pending"), previous = home.resolve("pending.previous");
        safe(previous); safe(pending);
        if (!Files.exists(previous)) return;
        if (!Files.exists(pending)) Files.move(previous, pending, StandardCopyOption.ATOMIC_MOVE);
        else {
            try {
                Manifest committed = manifest(readBounded(pending.resolve("manifest.json"), 1024 * 1024), config);
                verifyPending(pending, committed, config);
            } catch (Exception e) {
                throw new IOException("New pending cache is incomplete or does not match this selection; preserved both pending caches for recovery", e);
            }
            clear(previous);
        }
    }

    private void unpack(byte[] bundle, Manifest m, Path target) throws Exception {
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                fileName(e.getName());
                if (e.isDirectory() || !seen.add(e.getName())) throw new IOException("Invalid ZIP entry");
                String entryName = e.getName();
                Artifact a = m.components.stream().filter(x -> x.file.equals(entryName)).findFirst().orElseThrow(() -> new IOException("Unexpected ZIP entry"));
                byte[] b = zip.readNBytes((int)a.size + 1); verify(b, a.sha256, a.size); validateJar(b, a.module);
                atomic(target.resolve(a.file), b);
            }
        }
        if (seen.size() != m.components.size()) throw new IOException("Incomplete ZIP bundle");
    }

    private void verifyPending(Path pending, Manifest m, Config c) throws Exception {
        for (String module : c.modules) {
            Artifact a = artifact(m, module); byte[] b = readBounded(pending.resolve(a.file), MAX_BUNDLE); verify(b, a.sha256, a.size); validateJar(b, module);
        }
    }
    static void validateJar(byte[] b, String module) throws Exception {
        boolean metadata = false; long expanded = 0; int entries = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++entries > 50000) throw new IOException("JAR has too many entries");
                byte[] content = z.readNBytes(8 * 1024 * 1024 + 1); expanded += content.length;
                if (content.length > 8 * 1024 * 1024 || expanded > 256L * 1024 * 1024) throw new IOException("JAR expansion exceeds limit");
                if (e.getName().equals("META-INF/neoforge.mods.toml")) {
                    String toml = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                    metadata = ownModules(toml).equals(Set.of(module));
                }
            }
        }
        if (!metadata) throw new IOException("Unexpected NeoForge mod ID for " + module);
    }

    /** Read IDs only in [[mods]] tables, never [[dependencies.*]] declarations. */
    static Set<String> ownModules(String toml) {
        Set<String> ids = new HashSet<>(); boolean inMods = false; String multiline = null;
        Pattern id = Pattern.compile("^modId\\s*=\\s*([\"'])folkways_([a-z]+)\\1\\s*(?:#.*)?$");
        for (String original : toml.split("\\R")) {
            String line = original.strip();
            if (multiline != null) {
                if (line.contains(multiline)) multiline = null;
                continue;
            }
            if (line.startsWith("#")) continue;
            for (String quote : List.of("\"\"\"", "'''")) {
                int start = line.indexOf(quote);
                if (start >= 0 && line.indexOf(quote, start + 3) < 0) multiline = quote;
            }
            if (line.startsWith("[")) inMods = line.matches("\\[\\[\\s*mods\\s*\\]\\]\\s*(?:#.*)?$");
            if (inMods) {
                var match = id.matcher(line);
                if (match.matches() && MODULES.contains(match.group(2))) ids.add(match.group(2));
            }
        }
        return ids;
    }

    private static Artifact artifact(Manifest m, String module) { return m.components.stream().filter(a -> a.module.equals(module)).findFirst().orElseThrow(); }
    private static boolean isInstalled(Manifest m, State s, Config c) {
        if (!c.scope().equals(s.installedScope)) return false;
        for (String module : c.modules) {
            Artifact a = artifact(m, module); Owned o = s.installed.get(module);
            if (o == null || !a.sha256.equals(o.sha256) || a.size != o.size) return false;
        }
        return true;
    }
    static boolean hash(String s) { return s != null && s.matches("[0-9a-f]{64}"); }
    static String sha(byte[] b) throws NoSuchAlgorithmException { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
    static void verify(byte[] b, String digest, long size) throws Exception { if (b.length != size || !sha(b).equals(digest)) throw new IOException("Size/SHA-256 mismatch; preserving local files"); }
    static void fileName(String name) throws IOException { if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*\\.jar")) throw new IOException("Unsafe JAR filename"); }

    /** Re-anchor an existing CLI input through a game-root alias, without resolving links inside the game. */
    Path existingInput(Path path) throws IOException {
        Path input = path.toAbsolutePath().normalize(), root = null;
        for (Path ancestor = input; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(ancestor, game)) root = ancestor;
        }
        if (root == null) throw new IOException("Path escapes game directory");
        // Use the outermost match: a link inside the game back to its root must remain a checked component.
        Path anchored = game.resolve(root.relativize(input));
        safe(anchored);
        Path canonical = anchored.toRealPath(LinkOption.NOFOLLOW_LINKS);
        safe(canonical);
        return canonical;
    }

    void safe(Path path) throws IOException {
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(game)) throw new IOException("Path escapes game directory");
        for (Path q = p; q != null && q.startsWith(game); q = q.getParent()) if (unsafeLink(q)) throw new IOException("Link/reparse path refused: " + q.getFileName());
    }
    void directory(Path path) throws IOException { safe(path); Files.createDirectories(path); }
    void clear(Path path) throws IOException {
        safe(path); if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) { safe(p); Files.delete(p); }
        }
    }
    byte[] readBounded(Path p, long max) throws IOException { safe(p); if (Files.size(p) > max) throw new IOException("File exceeds limit"); return Files.readAllBytes(p); }
    static void atomic(Path path, byte[] bytes) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        if (unsafeLink(path) || unsafeLink(temp)) throw new IOException("Link/reparse path refused");
        try (FileChannel c = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) c.write(buffer);
            c.force(true);
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static boolean unsafeLink(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // Windows directory junctions are isOther(), even where isSymbolicLink() is false.
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private State state() throws IOException {
        Path p = home.resolve("state.json"); if (!Files.exists(p)) return new State();
        State s = JSON.fromJson(new String(readBounded(p, 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8), State.class);
        if (s == null || s.installed == null || s.highWater == null || s.accepted == null) throw new IOException("Invalid state"); return s;
    }
    private void saveState(State s) throws IOException { atomic(home.resolve("state.json"), JSON.toJson(s).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    @FunctionalInterface interface Operation<T> { T run() throws Exception; }
    <T> T locked(Operation<T> op) throws Exception {
        directory(home); safe(home.resolve("update.lock"));
        try (FileChannel channel = FileChannel.open(home.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Updater is busy"); return op.run();
        } catch (OverlappingFileLockException e) { throw new IOException("Updater is busy", e); }
    }

    static final class Envelope { String keyId, payload, signature; }
    static final class Manifest { int schema; String repository, minecraft, loader, channel, buildId, version, createdAt; long sequence; Bundle bundle; List<Artifact> components; }
    static final class Bundle { String url, sha256; long size; List<String> mirrors; }
    static final class Artifact { String module, file, sha256, url; long size; List<String> mirrors; }
    static final class Owned { String file, sha256; long size; Owned(String f, String h, long s) { file = f; sha256 = h; size = s; } }
    static final class State { Map<String, Owned> installed = new TreeMap<>(); Map<String, Long> highWater = new TreeMap<>(); Map<String, String> accepted = new TreeMap<>(); String buildId, installedScope; }
    static final class Journal { State before; Map<String, Owned> after = new TreeMap<>(); }
    static final class ScopeMismatch extends IOException { ScopeMismatch() { super("Manifest does not match selected repository/game/loader/channel"); } }
}
