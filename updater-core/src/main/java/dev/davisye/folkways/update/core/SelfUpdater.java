package dev.davisye.folkways.update.core;

import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Updates only this fixed repository's two updater JARs, through the external seed gate. */
public final class SelfUpdater {
    public static final String REPOSITORY = "davisye/update-folkways";
    private static final String API = "https://api.github.com/repos/" + REPOSITORY;
    private static final String BOOTSTRAP = ".folkways-updater/bin/folkways-bootstrap.jar";
    private static final String BUILD_INFO = "META-INF/folkways-updater-build.json";
    private static final long LIMIT = 64L * 1024 * 1024;
    interface Source {
        byte[] official(String url, long maximum) throws Exception;
        byte[] asset(String url, long maximum, String digest) throws Exception;
    }
    private final Updater u;
    private final Source injected;
    private final Path executing;
    private Trusted trusted;

    public SelfUpdater(Path gameDir) throws IOException { this(gameDir, null, executingLocation()); }
    public static void recordGameProcess(Path gameDir) throws Exception { RuntimeLease.recordGame(new Updater(gameDir)); }
    SelfUpdater(Path gameDir, Source source, Path executing) throws IOException {
        u = new Updater(gameDir); injected = source; this.executing = executing.toAbsolutePath().normalize();
    }
    private static Path executingLocation() throws IOException {
        try { return Path.of(SelfUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception e) { throw new IOException("Cannot identify updater runtime location", e); }
    }
    private Source source(Config config) {
        if (injected != null) return injected;
        Transport transport = new Transport(config);
        return new Source() {
            public byte[] official(String url, long maximum) throws Exception { return transport.fetchOfficialApi(url, maximum); }
            public byte[] asset(String url, long maximum, String digest) throws Exception {
                return transport.fetchValidated(url, maximum, bytes -> Updater.verify(bytes, digest, maximum));
            }
        };
    }
    public String status() throws IOException {
        State s = state(); Config config = Config.load(u.game);
        return "self-source=" + REPOSITORY + ", channel=" + config.selfUpdateChannel + ", version=" + (s.version == null ? "unadopted" : s.version)
            + ", pending=" + Files.exists(u.home.resolve("self-pending/metadata.json"))
            + ", local=" + (config.isLocalDevelopment(u.game) || s.localBuild);
    }
    /** Initial trust is explicit: the seed names an existing companion file and never adopts replacements later. */
    public String adopt(Path companion) throws Exception {
        return u.locked(() -> {
            if (!state().owned.isEmpty()) throw new IOException("Updater ownership is already recorded; local-change protection will not be reset");
            Path target = u.existingInput(companion);
            if (!Objects.equals(target.getParent(), u.mods) || !target.getFileName().toString().endsWith(".jar")) throw new IOException("Companion must be one existing game/mods JAR");
            List<Path> companions = companions();
            if (companions.size() != 1 || !companions.getFirst().equals(target)) throw new IOException("Duplicate or unrecorded updater companion JAR");
            State state = new State();
            BuildInfo first = null;
            for (String component : List.of("bootstrap", "companion")) {
                Path path = component.equals("bootstrap") ? u.game.resolve(BOOTSTRAP) : target;
                byte[] data = u.readBounded(path, LIMIT);
                BuildInfo info = jar(data, component, null);
                if (first != null && !sameBuild(first, info)) throw new IOException("Bootstrap and companion must be from the same complete updater build");
                first = info;
                state.version = info.version;
                state.localBuild |= !publishedBuild(info);
                state.highest = Math.max(state.highest, info.sequence);
                state.owned.put(component, new Owned(u.game.relativize(path).toString().replace('\\', '/'), Updater.sha(data), data.length));
            }
            save(state); return "Updater ownership recorded; local-build protection=" + state.localBuild;
        });
    }
    public String checkAndStage() throws Exception {
        Config config = Config.load(u.game);
        if (config.isLocalDevelopment(u.game)) return "Local development: updater self-update disabled";
        if (!config.selfUpdateEnabled) return "Updater self-update disabled";
        return u.locked(() -> {
            requireManaged(config);
            State state = state();
            if (state.owned.isEmpty()) return "Updater self-update requires the stable launch seed and explicit initial ownership";
            if (state.localBuild) return "Locally compiled updater: self-replacement disabled";
            owned(state);
            if (Files.exists(u.home.resolve("self-journal.json"))) throw new IOException("Updater transaction requires stable-seed recovery");
            Source source = source(config);
            Trusted next = discover(source, config); trusted = next;
            if (next.metadata.sequence < state.highest) throw new IOException("Rejected updater downgrade");
            if (next.metadata.sequence == state.highest && next.metadata.version.equals(state.version)) {
                if (state.accepted != null && !state.accepted.equals(next.metadataAsset.digest)) throw new IOException("Updater sequence was reused");
                return "Updater already current: " + state.version;
            }
            Path pending = u.home.resolve("self-pending"); restorePending(config);
            if (Files.exists(pending.resolve("metadata.json"))) {
                Metadata old = metadata(u.readBounded(pending.resolve("metadata.json"), 1024 * 1024), config);
                if (old.sequence > next.metadata.sequence) throw new IOException("Rejected pending updater downgrade");
                if (old.sequence == next.metadata.sequence) {
                    if (!Updater.sha(u.readBounded(pending.resolve("metadata.json"), 1024 * 1024)).equals(hashOf(next.metadataAsset))) throw new IOException("Pending updater sequence was reused");
                    verifyPending(pending, next.metadata); return "Updater already downloaded; stable seed applies it before the next JVM";
                }
            }
            Path candidate = u.home.resolve("self-candidate"); u.clear(candidate); u.directory(candidate);
            try {
                for (Artifact artifact : next.metadata.artifacts) {
                    requireManaged(config);
                    byte[] data = source.asset(artifact.url, artifact.size, artifact.sha256);
                    Updater.verify(data, artifact.sha256, artifact.size); jar(data, artifact.component, next.metadata);
                    requireManaged(config); Updater.atomic(candidate.resolve(artifact.component + ".jar"), data);
                }
                ApiRelease after = officialRelease(source, config);
                if (!releaseFingerprint(next.release).equals(releaseFingerprint(after))) throw new IOException("Updater release changed during download");
                verifyRun(source, next.metadata, next.release);
                Updater.atomic(candidate.resolve("metadata.json"), next.bytes);
                requireManaged(config); owned(state());
                Path previous = u.home.resolve("self-pending.previous");
                if (Files.exists(pending)) Files.move(pending, previous, StandardCopyOption.ATOMIC_MOVE);
                try { requireManaged(config); Files.move(candidate, pending, StandardCopyOption.ATOMIC_MOVE); }
                catch (Exception e) { if (!Files.exists(pending) && Files.exists(previous)) Files.move(previous, pending, StandardCopyOption.ATOMIC_MOVE); throw e; }
                u.clear(previous);
            } finally { u.clear(candidate); }
            return "Updater downloaded: " + next.metadata.version + "; stable seed required before replacement";
        });
    }
    /** Called only by a copied runner, under the same lifetime lock used by the game supervisor. */
    public String prepare() throws Exception {
        Config config = Config.load(u.game); u.directory(u.home); u.safe(u.home.resolve("session.lock"));
        try (FileChannel channel = FileChannel.open(u.home.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Game supervisor is active; updater replacement refused");
            Bootstrap.guardPreviousChild(u);
            RuntimeLease.ensureNoGame(u);
            if (config.isLocalDevelopment(u.game) || !config.selfUpdateEnabled) {
                if (Files.exists(u.home.resolve("self-journal.json"))) throw new IOException("Local/disabled mode blocks an interrupted updater transaction");
                return "Local or disabled mode: updater files retained";
            }
            separateRuntime(state());
            u.locked(() -> { recover(config); return null; });
            String stage;
            try { stage = checkAndStage(); }
            catch (Exception e) { return "Updater check unavailable; current files retained (" + e.getClass().getSimpleName() + ")"; }
            if (trusted == null) return stage;
            return u.locked(() -> apply(config, trusted));
        } catch (OverlappingFileLockException e) { throw new IOException("Game supervisor is active; updater replacement refused", e); }
    }
    String apply(Config config, Trusted next) throws Exception {
        requireManaged(config); separateRuntime(state());
        Path pending = u.home.resolve("self-pending"); restorePending(config);
        if (!Files.exists(pending.resolve("metadata.json"))) return "Updater already current";
        byte[] bytes = u.readBounded(pending.resolve("metadata.json"), 1024 * 1024);
        if (!Updater.sha(bytes).equals(hashOf(next.metadataAsset))) throw new IOException("Pending updater is not the officially verified release");
        Metadata metadata = metadata(bytes, config); verifyPending(pending, metadata);
        State before = state(); owned(before);
        if (metadata.sequence < before.highest) throw new IOException("Rejected updater downgrade");
        if (metadata.sequence == before.highest && metadata.version.equals(before.version) && Objects.equals(before.accepted, next.metadataAsset.digest)) {
            u.clear(pending); return "Updater already installed; cleared committed cache";
        }
        if (metadata.sequence == before.highest) throw new IOException("Updater sequence was reused");
        Path backup = u.home.resolve("self-backup"); u.clear(backup); u.directory(backup);
        State after = new State(); after.version = metadata.version; after.highest = metadata.sequence; after.accepted = next.metadataAsset.digest;
        for (Artifact artifact : metadata.artifacts) {
            Owned old = before.owned.get(artifact.component);
            Updater.atomic(backup.resolve(artifact.component + ".jar"), u.readBounded(target(artifact.component, old), LIMIT));
            after.owned.put(artifact.component, new Owned(old.path, artifact.sha256, artifact.size));
        }
        Journal journal = new Journal(); journal.before = before; journal.after = after;
        requireManaged(config); owned(before);
        Updater.atomic(u.home.resolve("self-journal.json"), json(journal));
        try {
            for (Artifact artifact : metadata.artifacts) {
                requireManaged(config);
                Updater.atomic(target(artifact.component, before.owned.get(artifact.component)), u.readBounded(pending.resolve(artifact.component + ".jar"), LIMIT));
            }
            save(after); Files.delete(u.home.resolve("self-journal.json")); u.clear(pending);
        } catch (Exception e) {
            if (!Config.load(u.game).isLocalDevelopment(u.game)) recover(config);
            throw e;
        }
        return "Updater installed before JVM launch: " + metadata.version;
    }
    void recover(Config config) throws Exception {
        Path path = u.home.resolve("self-journal.json"); if (!Files.exists(path)) return;
        requireManaged(config);
        Journal journal = Updater.JSON.fromJson(new String(u.readBounded(path, 1024 * 1024), StandardCharsets.UTF_8), Journal.class);
        if (journal == null || journal.before == null || journal.after == null) throw new IOException("Invalid updater recovery journal");
        separateRuntime(journal.before);
        for (String component : List.of("bootstrap", "companion")) {
            Owned old = journal.before.owned.get(component), next = journal.after.owned.get(component);
            Path target = target(component, old);
            if (!old.path.equals(next.path)) throw new IOException("Invalid updater recovery target");
            Updater.verify(u.readBounded(u.home.resolve("self-backup/" + component + ".jar"), LIMIT), old.sha256, old.size);
            String current = Updater.sha(u.readBounded(target, LIMIT));
            if (!current.equals(old.sha256) && !current.equals(next.sha256)) throw new IOException("Local updater edit blocks recovery");
        }
        for (String component : List.of("bootstrap", "companion")) {
            requireManaged(config);
            Updater.atomic(target(component, journal.before.owned.get(component)), u.readBounded(u.home.resolve("self-backup/" + component + ".jar"), LIMIT));
        }
        save(journal.before); Files.delete(path);
    }
    private void restorePending(Config config) throws Exception {
        Path pending = u.home.resolve("self-pending"), previous = u.home.resolve("self-pending.previous");
        u.safe(pending); u.safe(previous); if (!Files.exists(previous)) return;
        if (!Files.exists(pending)) Files.move(previous, pending, StandardCopyOption.ATOMIC_MOVE);
        else { verifyPending(pending, metadata(u.readBounded(pending.resolve("metadata.json"), 1024 * 1024), config)); u.clear(previous); }
    }
    private void separateRuntime(State state) throws IOException {
        Path bin = u.game.resolve(BOOTSTRAP); u.safe(bin);
        if (sameFile(executing, bin)) throw new IOException("Use launch.ps1 or launch.sh: a running installed bootstrap cannot replace itself");
        for (var entry : state.owned.entrySet()) if (sameFile(executing, target(entry.getKey(), entry.getValue()))) throw new IOException("A running updater JAR cannot replace itself");
    }
    private static boolean sameFile(Path left, Path right) throws IOException { return left.equals(right) || (Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right)); }
    private void requireManaged(Config expected) throws IOException {
        Config now = Config.load(u.game);
        if (now.isLocalDevelopment(u.game) || !now.selfUpdateEnabled) throw new IOException("Local/disabled mode blocks updater changes");
        if (!now.selfUpdateChannel.equals(expected.selfUpdateChannel)) throw new IOException("Updater channel changed during download");
    }
    private Path target(String component, Owned owned) throws IOException {
        if (owned == null || owned.path == null) throw new IOException("Missing updater ownership");
        Path path = u.game.resolve(owned.path).normalize(); u.safe(path);
        if (component.equals("bootstrap")) { if (!path.equals(u.game.resolve(BOOTSTRAP))) throw new IOException("Invalid bootstrap target"); }
        else if (!component.equals("companion") || !Objects.equals(path.getParent(), u.mods) || !path.getFileName().toString().endsWith(".jar")) throw new IOException("Invalid companion target");
        return path;
    }
    private void owned(State state) throws Exception {
        if (!state.owned.keySet().equals(Set.of("bootstrap", "companion"))) throw new IOException("Explicit updater ownership is required");
        List<Path> companions = companions();
        if (companions.size() != 1 || !companions.getFirst().equals(target("companion", state.owned.get("companion")))) throw new IOException("Duplicate or unrecorded updater companion JAR");
        for (var entry : state.owned.entrySet()) {
            Owned owned = entry.getValue(); Updater.verify(u.readBounded(target(entry.getKey(), owned), LIMIT), owned.sha256, owned.size);
        }
    }
    List<Path> companions() throws IOException {
        List<Path> result = new ArrayList<>(); u.safe(u.mods);
        if (!Files.exists(u.mods)) return result;
        try (var files = Files.list(u.mods)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().endsWith(".jar")) continue;
                u.safe(file);
                try (ZipFile zip = new ZipFile(file.toFile())) {
                    ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
                    if (entry == null) continue;
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                        if (bytes.length > 1024 * 1024) throw new IOException("Companion metadata exceeds limit");
                        boolean inMods = false;
                        for (String original : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                            String line = original.strip();
                            if (line.startsWith("[")) inMods = line.matches("\\[\\[\\s*mods\\s*\\]\\]\\s*(?:#.*)?");
                            if (inMods && line.matches("modId\\s*=\\s*[\"']folkways_updater[\"']\\s*(?:#.*)?")) { result.add(file); break; }
                        }
                    }
                } catch (ZipException e) {
                    if (file.getFileName().toString().startsWith("folkways-updater-")) throw new IOException("Invalid updater companion JAR", e);
                }
            }
        }
        return result;
    }
    private State state() throws IOException {
        Path path = u.home.resolve("self-state.json"); if (!Files.exists(path)) return new State();
        State state = Updater.JSON.fromJson(new String(u.readBounded(path, 1024 * 1024), StandardCharsets.UTF_8), State.class);
        if (state == null || state.owned == null) throw new IOException("Invalid updater state"); return state;
    }
    private void save(State state) throws IOException { Updater.atomic(u.home.resolve("self-state.json"), json(state)); }
    private static byte[] json(Object object) { return Updater.JSON.toJson(object).getBytes(StandardCharsets.UTF_8); }
    private void verifyPending(Path pending, Metadata metadata) throws Exception {
        for (Artifact artifact : metadata.artifacts) {
            byte[] bytes = u.readBounded(pending.resolve(artifact.component + ".jar"), LIMIT);
            Updater.verify(bytes, artifact.sha256, artifact.size); jar(bytes, artifact.component, metadata);
        }
    }
    private Trusted discover(Source source, Config config) throws Exception {
        ApiRelease release = officialRelease(source, config);
        Asset metadataAsset = asset(release, "update-metadata.json");
        if (metadataAsset.size > 1024 * 1024) throw new IOException("Updater metadata exceeds limit");
        byte[] bytes = source.asset(metadataAsset.browser_download_url, metadataAsset.size, hashOf(metadataAsset));
        Updater.verify(bytes, hashOf(metadataAsset), metadataAsset.size);
        Metadata metadata = metadata(bytes, config);
        if (!metadata.commit.equals(release.target_commitish)) throw new IOException("Release commit does not match updater provenance");
        if (config.selfUpdateChannel.equals("release") && !release.tag_name.equals("v" + metadata.version)) throw new IOException("Updater release tag mismatch");
        for (Artifact artifact : metadata.artifacts) {
            Asset asset = asset(release, artifact.fileName);
            if (!asset.browser_download_url.equals(artifact.url) || asset.size != artifact.size || !hashOf(asset).equals(artifact.sha256)) throw new IOException("Official API asset digest does not match updater metadata");
        }
        verifyRun(source, metadata, release);
        Trusted result = new Trusted(); result.release = release; result.metadata = metadata; result.metadataAsset = metadataAsset; result.bytes = bytes; return result;
    }
    static ApiRelease officialRelease(Source source, Config config) throws Exception {
        String endpoint = config.selfUpdateChannel.equals("nightly") ? "/releases/tags/nightly" : "/releases/latest";
        byte[] bytes = source.official(API + endpoint, 4 * 1024 * 1024);
        if (bytes.length > 4 * 1024 * 1024) throw new IOException("Official release response exceeds limit");
        ApiRelease release = Updater.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), ApiRelease.class);
        if (release == null || release.id <= 0 || release.draft || release.tag_name == null || !release.tag_name.matches("[A-Za-z0-9._+\\-]+") || release.assets == null
            || !Objects.equals(release.url, API + "/releases/" + release.id)
            || (config.selfUpdateChannel.equals("nightly") ? !release.tag_name.equals("nightly") : release.prerelease)) throw new IOException("Invalid official updater release");
        for (Asset asset : release.assets) {
            if (asset == null || asset.name == null || !asset.name.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*") || asset.id <= 0 || asset.size <= 0 || !"uploaded".equals(asset.state)
                || !Objects.equals(asset.browser_download_url, "https://github.com/" + REPOSITORY + "/releases/download/" + release.tag_name + "/" + asset.name)) throw new IOException("Invalid official updater asset");
        }
        return release;
    }
    private static Asset asset(ApiRelease release, String name) throws IOException {
        List<Asset> matches = release.assets.stream().filter(asset -> name.equals(asset.name)).toList();
        if (matches.size() != 1) throw new IOException("Missing or duplicate updater asset: " + name);
        Asset result = matches.getFirst(); hashOf(result); return result;
    }
    static String hashOf(Asset asset) throws IOException {
        if (asset.digest == null || !asset.digest.matches("sha256:[0-9a-f]{64}")) throw new IOException("Official GitHub asset digest is missing");
        return asset.digest.substring(7);
    }
    static String releaseFingerprint(ApiRelease release) {
        return release.id + ":" + release.tag_name + ":" + release.target_commitish + ":" + release.assets.stream()
            .filter(asset -> asset.name.equals("update-metadata.json") || asset.name.endsWith(".jar"))
            .sorted(Comparator.comparing(asset -> asset.name)).map(asset -> asset.id + ":" + asset.name + ":" + asset.digest + ":" + asset.size).toList();
    }
    static void verifyRun(Source source, Metadata metadata, ApiRelease release) throws Exception {
        byte[] bytes = source.official(API + "/actions/runs/" + metadata.runId, 2 * 1024 * 1024);
        if (bytes.length > 2 * 1024 * 1024) throw new IOException("CI response exceeds limit");
        Run run = Updater.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Run.class);
        if (run == null || !Long.toString(run.id).equals(metadata.runId) || run.run_number != metadata.runNumber || run.run_attempt != metadata.runAttempt
            || !"completed".equals(run.status) || !"success".equals(run.conclusion) || !"push".equals(run.event)
            || !".github/workflows/ci.yml".equals(run.path) || !metadata.commit.equals(run.head_sha)
            || run.head_repository == null || !REPOSITORY.equals(run.head_repository.full_name) || run.repository == null || !REPOSITORY.equals(run.repository.full_name)
            || !Objects.equals(run.head_branch, metadata.channel.equals("nightly") ? "main" : release.tag_name)) throw new IOException("Updater CI provenance is not a successful trusted main/tag push");
        if (Math.addExact(Math.multiplyExact(run.run_number, 1000), metadata.runAttempt) != metadata.sequence) throw new IOException("Updater CI sequence mismatch");
    }
    private static Metadata metadata(byte[] bytes, Config config) throws IOException {
        try {
            Metadata metadata = Updater.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Metadata.class);
            if (metadata == null || metadata.schema != 1 || !REPOSITORY.equals(metadata.repository) || !"1.21.1".equals(metadata.minecraft)
                || !config.selfUpdateChannel.equals(metadata.channel) || metadata.version == null || !metadata.version.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]*")
                || metadata.commit == null || !metadata.commit.matches("[0-9a-f]{40}") || metadata.sequence <= 0 || metadata.runNumber <= 0 || metadata.runId == null || !metadata.runId.matches("[1-9][0-9]*") || metadata.runAttempt <= 0 || metadata.runAttempt >= 1000
                || metadata.artifacts == null || metadata.artifacts.size() != 2) throw new IOException("Invalid updater metadata");
            Set<String> seen = new HashSet<>();
            for (Artifact artifact : metadata.artifacts) {
                if (artifact == null || !Set.of("bootstrap", "companion").contains(artifact.component) || !seen.add(artifact.component)
                    || !Updater.hash(artifact.sha256) || artifact.size <= 0 || artifact.size > LIMIT) throw new IOException("Invalid updater artifact");
                String expected = artifact.component.equals("bootstrap") ? "folkways-bootstrap-" + metadata.version + "-standalone.jar" : "folkways-updater-neoforge-1.21.1-" + metadata.version + ".jar";
                if (!expected.equals(artifact.fileName)) throw new IOException("Unexpected updater artifact filename");
                URI url = Transport.safeUri(artifact.url);
                if (!"github.com".equals(url.getHost()) || !url.getPath().startsWith("/" + REPOSITORY + "/releases/download/")) throw new IOException("Updater download repository is fixed");
            }
            return metadata;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid updater metadata", e); }
    }
    static BuildInfo jar(byte[] bytes, String component, Metadata expected) throws Exception {
        BuildInfo info = null; String toml = null; Manifest manifest = null; boolean bootstrapClass = false; long expanded = 0; int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 50000) throw new IOException("Updater JAR has too many entries");
                byte[] data = zip.readNBytes(8 * 1024 * 1024 + 1); expanded += data.length;
                if (data.length > 8 * 1024 * 1024 || expanded > 256L * 1024 * 1024) throw new IOException("Updater JAR expansion exceeds limit");
                if (entry.getName().equals(BUILD_INFO)) { if (info != null) throw new IOException("Duplicate updater build info"); info = Updater.JSON.fromJson(new String(data, StandardCharsets.UTF_8), BuildInfo.class); }
                if (entry.getName().equals("META-INF/neoforge.mods.toml")) toml = new String(data, StandardCharsets.UTF_8);
                if (entry.getName().equals("META-INF/MANIFEST.MF")) manifest = new Manifest(new ByteArrayInputStream(data));
                if (entry.getName().equals("dev/davisye/folkways/update/core/Bootstrap.class")) bootstrapClass = true;
            }
        }
        if (info == null || info.schema != 1 || !REPOSITORY.equals(info.repository) || !component.equals(info.component) || info.version == null || !"1.21.1".equals(info.minecraft)) throw new IOException("Updater JAR build identity is missing");
        if (component.equals("bootstrap")) {
            if (!bootstrapClass || manifest == null || !"dev.davisye.folkways.update.core.Bootstrap".equals(manifest.getMainAttributes().getValue("Main-Class"))) throw new IOException("Unexpected bootstrap entrypoint");
        } else if (!ownUpdaterMod(toml, info.version)) throw new IOException("Unexpected companion mod ID or version");
        if (expected != null && (!publishedBuild(info) || !expected.version.equals(info.version) || !expected.commit.equals(info.commit) || !expected.runId.equals(info.runId)
            || expected.runNumber != info.runNumber || expected.runAttempt != info.runAttempt || expected.sequence != info.sequence || !expected.channel.equals(info.channel))) throw new IOException("Updater JAR provenance mismatch");
        return info;
    }
    private static boolean ownUpdaterMod(String toml, String version) {
        if (toml == null) return false;
        boolean inMods = false, id = false, correctVersion = false; int mods = 0;
        for (String original : toml.split("\\R")) {
            String line = original.strip();
            if (line.startsWith("[")) { inMods = line.matches("\\[\\[\\s*mods\\s*\\]\\]\\s*(?:#.*)?"); if (inMods) mods++; }
            if (inMods && line.matches("modId\\s*=\\s*[\"']folkways_updater[\"']\\s*(?:#.*)?")) id = true;
            if (inMods && Pattern.compile("version\\s*=\\s*[\"']" + Pattern.quote(version) + "[\"']\\s*(?:#.*)?").matcher(line).matches()) correctVersion = true;
        }
        return mods == 1 && id && correctVersion;
    }
    static boolean sameBuild(BuildInfo a, BuildInfo b) {
        return Objects.equals(a.version, b.version) && Objects.equals(a.commit, b.commit) && Objects.equals(a.runId, b.runId)
            && a.runNumber == b.runNumber && a.runAttempt == b.runAttempt && a.sequence == b.sequence && Objects.equals(a.channel, b.channel);
    }
    static boolean publishedBuild(BuildInfo info) {
        try {
            return info.sequence > 0 && info.runNumber > 0 && info.sequence == Math.addExact(Math.multiplyExact(info.runNumber, 1000), info.runAttempt)
                && info.runAttempt > 0 && info.runAttempt < 1000 && info.runId != null && info.runId.matches("[1-9][0-9]*")
                && info.commit != null && info.commit.matches("[0-9a-f]{40}") && ("nightly".equals(info.channel) || "release".equals(info.channel));
        } catch (ArithmeticException e) { return false; }
    }

    static final class Owned { String path, sha256; long size; Owned(String p, String hash, long n) { path = p; sha256 = hash; size = n; } }
    static final class State { Map<String, Owned> owned = new TreeMap<>(); String version, accepted; long highest; boolean localBuild; }
    static final class Journal { State before, after; }
    static class BuildInfo { int schema, runAttempt; String repository, component, version, commit, runId, channel, minecraft; long sequence, runNumber; }
    static final class Metadata { int schema, runAttempt; String repository, channel, version, commit, runId, minecraft; long sequence, runNumber; List<Artifact> artifacts; }
    static final class Artifact { String component, fileName, sha256, url; long size; }
    static final class ApiRelease { long id; String url, tag_name, target_commitish; boolean draft, prerelease; List<Asset> assets; }
    static final class Asset { long id, size; String name, digest, state, browser_download_url; }
    static final class Repo { String full_name; }
    static final class Run { long id, run_number; int run_attempt; String status, conclusion, event, path, head_sha, head_branch; Repo repository, head_repository; }
    static final class Trusted { ApiRelease release; Asset metadataAsset; Metadata metadata; byte[] bytes; }
}
