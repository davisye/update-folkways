package dev.davisye.folkways.update.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipException;

/** Official-platform default channel, isolated from the user-configured Ed25519 manifest channel. */
final class GitHubFolkways {
    static final String JOURNAL = "github-folkways-journal.json";
    private static final String STATE = "github-folkways-state.json", PENDING = "github-folkways-pending";
    private static final long LIMIT = 64L * 1024 * 1024;
    interface Source {
        byte[] official(String url, long maximum) throws Exception;
        byte[] asset(String url, long size, String algorithm, String hash) throws Exception;
    }
    private final Updater u;
    private final ApprovedDependencies.Catalog catalog;
    private final java.util.function.BooleanSupplier stillCurrent;
    private final Updater.CommitAuthorization authorization;
    GitHubFolkways(Updater updater) { this(updater, () -> true, Updater.PendingCommit::run); }
    GitHubFolkways(Updater updater, java.util.function.BooleanSupplier stillCurrent, Updater.CommitAuthorization authorization) {
        u = updater; catalog = updater.approvedDependencies == null ? ApprovedDependencies.PRODUCTION : updater.approvedDependencies;
        this.stillCurrent = stillCurrent; this.authorization = authorization;
    }
    private Source source(Config config) {
        if (u.githubSource != null) return u.githubSource;
        Transport transport = new Transport(config);
        return new Source() {
            public byte[] official(String url, long maximum) throws Exception {
                try { return transport.fetchFolkwaysApi(url, maximum); }
                catch (IOException e) { throw new IOException("Official verification is temporarily unavailable; current files and complete downloads were retained", e); }
            }
            public byte[] asset(String url, long size, String algorithm, String hash) throws Exception {
                return transport.fetchValidated(url, size, bytes -> verifyHash(bytes, algorithm, hash, size));
            }
        };
    }
    String checkAndStage(Config config) throws Exception { return checkAndStage(config, false); }
    String checkAndStage(Config config, boolean takeOverExisting) throws Exception {
        if (u.loaderVersion == null) return "Official Folkways checks require the current NeoForge version; use the in-game updater";
        return u.locked(() -> {
            managed(config); rejectOtherJournal();
            if (Files.exists(u.home.resolve(JOURNAL))) throw new IOException("An interrupted Folkways installation must recover after the game exits");
            State recorded = state(); Map<String,Present> installed = scan();
            if (recorded.owned.isEmpty()) {
                if (!takeOverExisting && installed.keySet().stream().anyMatch(GitHubFolkways::folkways)) return "Existing Folkways JARs are protected; automatic installation only starts from an empty Folkways set";
                if (legacyOwned()) return "Existing signed-channel ownership is protected; restore its manifest or migrate explicitly";
            }
            if (takeOverExisting && legacyOwned()) return "Existing signed-channel ownership is protected; restore its manifest or migrate explicitly";
            State before = takeOverExisting ? takeoverBaseline(recorded, installed, config) : recorded;
            Set<String> takenOver = new TreeSet<>(before.owned.keySet()); takenOver.removeAll(recorded.owned.keySet());
            validateOwned(before, installed); restorePending(before);
            Source source = source(config); Release release = release(source, config);
            if (release == null) return "No official " + config.channel + " Folkways release is available in " + config.repository;
            Map<String,Asset> all = releaseAssets(release, config);
            Commit commit = decode(source.official(base(config) + "/commits/" + release.target_commitish, 2 * 1024 * 1024), Commit.class);
            if (commit == null || !release.target_commitish.equals(commit.sha)) throw new IOException("Official Folkways release commit could not be verified");
            Runs runs = decode(source.official(base(config) + "/actions/workflows/build.yml/runs?head_sha=" + commit.sha + "&event=push&status=success&per_page=20", 4 * 1024 * 1024), Runs.class);
            SelfUpdater.Run run = successfulRun(runs, release, config.repository);
            long sequence = sequence(run); String fingerprint = fingerprint(release);
            long accepted = before.highWater.getOrDefault(config.scope(), 0L);
            if (sequence < accepted || sequence == accepted && !fingerprint.equals(before.accepted.get(config.scope()))) throw new IOException("Official release is older or changed within the same CI build");
            if (takenOver.isEmpty() && sequence == accepted && config.scope().equals(before.scope) && currentSelection(before, config)
                && existingClosureComplete(before, installed, config)) return "Already installed official Folkways " + commit.sha;
            if (before.pending != null) {
                try {
                    Plan old = pending(before, false);
                    if (sameScope(old, config) && old.sequence > sequence) throw new IOException("A newer Folkways build is already queued");
                    if (takenOver.isEmpty() && sameScope(old, config) && old.sequence == sequence && old.fingerprint.equals(fingerprint)) return "Official Folkways is already downloaded; installs after this game JVM exits; commit " + old.commit;
                } catch (DifferentSelection ignored) { }
            }
            Plan plan = new Plan(); plan.schema = 1; plan.repository = config.repository; plan.minecraft = config.minecraft; plan.channel = config.channel;
            plan.modules = new ArrayList<>(config.modules); plan.loaderVersion = u.loaderVersion; plan.release = release; plan.run = run;
            plan.sequence = sequence; plan.commit = commit.sha; plan.fingerprint = fingerprint;
            Map<String,byte[]> bytes = new TreeMap<>(); Resolver resolver = new Resolver(plan, installed);
            for (String module : config.modules) {
                managed(config); Asset asset = all.get(module);
                byte[] data = source.asset(asset.browser_download_url, asset.size, "SHA-256", digest(asset)); verifyHash(data, "SHA-256", digest(asset), asset.size); managed(config);
                Updater.validateJar(data, module); ModMetadata info = ModMetadata.read(data);
                String id = "folkways_" + module;
                if (!"javafml".equals(info.loader) || !info.mods.keySet().equals(Set.of(id))) throw new IOException("Unexpected Folkways Mod loader/identity");
                String version = info.mods.get(id);
                if (!asset.name.equals("folkways-" + module + "-neoforge-1.21.1-" + version + ".jar")) throw new IOException("Folkways asset filename/version mismatch");
                File item = file(id, asset.name, asset.browser_download_url, data, before); item.version = version;
                plan.files.add(item); bytes.put(id, data); resolver.add(info, null);
            }
            resolver.resolve(source, config, before, bytes);
            Release finalRelease = release(source, config); releaseAssets(finalRelease, config);
            if (!fingerprint.equals(fingerprint(finalRelease))) throw new IOException("Official release changed while downloading; previous files were retained");
            validatePlan(plan, config); managed(config); validateBaseline(recorded, before);
            Path candidate = u.home.resolve("github-folkways-candidate"); u.clear(candidate); u.directory(candidate);
            try {
                for (File item : plan.files) Updater.atomic(candidate.resolve(item.id + ".jar"), bytes.get(item.id));
                byte[] planBytes = json(plan); Updater.atomic(candidate.resolve("plan.json"), planBytes);
                managed(config); validateBaseline(recorded, before);
                Path queued = u.home.resolve(PENDING), previous = u.home.resolve(PENDING + ".previous");
                if (Files.exists(previous)) throw new IOException("Unresolved previous Folkways cache");
                if (Files.exists(queued)) Files.move(queued, previous, StandardCopyOption.ATOMIC_MOVE);
                boolean moved = false; boolean[] committed = {false};
                try {
                    managed(config); validateBaseline(recorded, before); Files.move(candidate, queued, StandardCopyOption.ATOMIC_MOVE); moved = true;
                    managed(config); validateBaseline(recorded, before);
                    String pendingHash = Updater.sha(planBytes);
                    authorization.commit(() -> {
                        // The caller serializes this short consent-consumption point with request cancellation.
                        // Downloads and Mod archive scans have already completed outside that caller lock.
                        managed(config); validateRecordedState(recorded);
                        State acceptedState = decode(json(before), State.class); acceptedState.pending = pendingHash; save(acceptedState); committed[0] = true;
                    });
                    if (!committed[0]) throw new IOException("Updater commit was not authorized; ownership was retained");
                } catch (Exception e) {
                    if (!committed[0]) {
                        if (moved) u.clear(queued);
                        if (Files.exists(previous)) Files.move(previous, queued, StandardCopyOption.ATOMIC_MOVE);
                    }
                    throw e;
                }
                u.clear(previous);
            } finally { u.clear(candidate); }
            return "Downloaded official Folkways " + config.modules + (plan.libraries.isEmpty() ? "" : " + required dependencies " + plan.libraries.keySet())
                + (takenOver.isEmpty() ? "" : "; one-time takeover staged for " + takenOver) + "; installs after the game JVM exits; commit " + plan.commit;
        });
    }

    String apply(Config config) throws Exception {
        if (config.isLocalDevelopment(u.game)) {
            if (Files.exists(u.home.resolve(JOURNAL))) throw new IOException("Local development blocks interrupted Folkways recovery");
            return "Local development: Folkways and dependencies retained";
        }
        return u.locked(() -> {
            rejectOtherJournal(); gate(); recover(config); State before = state(); restorePending(before);
            if (before.pending == null) return "No pending official Folkways installation";
            Plan plan;
            try { plan = pending(before, true); }
            catch (DifferentSelection e) { return "Queued official Folkways belongs to a different source/channel/module selection; retained without installation"; }
            validateOwned(before, scan());
            long accepted = before.highWater.getOrDefault(config.scope(), 0L);
            if (plan.sequence < accepted || plan.sequence == accepted && !plan.fingerprint.equals(before.accepted.get(config.scope()))) throw new IOException("Queued official Folkways would downgrade or reuse a changed CI build");
            State after = decode(json(before), State.class); after.scope = config.scope(); after.pending = null;
            after.highWater.put(config.scope(), plan.sequence); after.accepted.put(config.scope(), plan.fingerprint);
            after.owned.keySet().removeIf(id -> folkways(id) && !config.modules.contains(id.substring(9)));
            for (File item : plan.files) after.owned.put(item.id, new Owned(item.target, item.sha256, item.size, item.version));
            Path backup = u.home.resolve("github-folkways-backup"); u.clear(backup); u.directory(backup);
            for (var entry : before.owned.entrySet()) Updater.atomic(backup.resolve(entry.getKey() + ".jar"), u.readBounded(u.mods.resolve(entry.getValue().file), LIMIT));
            validateTargets(before, after); gate(); managed(config);
            Journal journal = new Journal(); journal.before = before; journal.after = after;
            Updater.atomic(u.home.resolve(JOURNAL), json(journal));
            try {
                for (var old : before.owned.entrySet()) if (!after.owned.containsKey(old.getKey())) {
                    gate(); managed(config); Updater.verify(u.readBounded(u.mods.resolve(old.getValue().file), LIMIT), old.getValue().sha256, old.getValue().size); Files.delete(u.mods.resolve(old.getValue().file));
                }
                for (File item : plan.files) {
                    gate(); managed(config);
                    Path target = u.mods.resolve(item.target); u.safe(target);
                    Owned old = before.owned.get(item.id);
                    if (old == null && Files.exists(target)) throw new IOException("An unowned file appeared at the installation target");
                    if (old != null) Updater.verify(u.readBounded(target, LIMIT), old.sha256, old.size);
                    for (External external : plan.external) Updater.verify(u.readBounded(u.mods.resolve(external.file), LIMIT), external.sha256, external.size);
                    Updater.atomic(target, u.readBounded(u.home.resolve(PENDING).resolve(item.id + ".jar"), LIMIT));
                }
                gate(); managed(config); validateOwned(after, scan()); save(after); committed(); Files.delete(u.home.resolve(JOURNAL)); u.clear(u.home.resolve(PENDING));
                return "Installed official Folkways and required dependencies";
            } catch (Exception e) {
                if (!Config.load(u.game).isLocalDevelopment(u.game)) recover(config);
                throw e;
            }
        });
    }
    private void recover(Config config) throws Exception {
        Path path = u.home.resolve(JOURNAL); if (!Files.exists(path)) return;
        gate(); if (Config.load(u.game).isLocalDevelopment(u.game)) throw new IOException("Local development blocks recovery writes");
        Journal journal = read(path, Journal.class); validateState(journal == null ? null : journal.before); validateState(journal.after);
        Set<String> keys = new TreeSet<>(journal.before.owned.keySet()); keys.addAll(journal.after.owned.keySet());
        if (keys.isEmpty()) throw new IOException("Invalid empty Folkways recovery journal");
        for (String id : keys) {
            Owned old = journal.before.owned.get(id), next = journal.after.owned.get(id);
            if (old != null && next != null && !old.file.equals(next.file)) throw new IOException("Recovery ownership path changed");
            Owned location = old == null ? next : old; Path target = u.mods.resolve(location.file); u.safe(target);
            if (old != null) Updater.verify(u.readBounded(u.home.resolve("github-folkways-backup/" + id + ".jar"), LIMIT), old.sha256, old.size);
            if (Files.exists(target)) {
                String hash = Updater.sha(u.readBounded(target, LIMIT));
                if ((old == null || !old.sha256.equals(hash)) && (next == null || !next.sha256.equals(hash))) throw new IOException("A local file change blocks recovery: " + location.file);
            } else if (old != null && next != null) throw new IOException("Owned file disappeared during replacement");
        }
        for (String id : keys) {
            gate(); if (Config.load(u.game).isLocalDevelopment(u.game)) throw new IOException("Local protection enabled during recovery");
            Owned old = journal.before.owned.get(id), next = journal.after.owned.get(id);
            Path target = u.mods.resolve(old == null ? next.file : old.file); u.safe(target);
            if (Files.exists(target)) {
                String current = Updater.sha(u.readBounded(target, LIMIT));
                if ((old == null || !current.equals(old.sha256)) && (next == null || !current.equals(next.sha256))) throw new IOException("A local edit appeared during recovery; file retained");
            } else if (old != null && next != null) throw new IOException("Owned file disappeared during recovery");
            if (old == null) Files.deleteIfExists(target);
            else {
                byte[] backup = u.readBounded(u.home.resolve("github-folkways-backup/" + id + ".jar"), LIMIT); Updater.verify(backup, old.sha256, old.size); Updater.atomic(target, backup);
            }
        }
        gate(); save(journal.before); committed(); Files.delete(path);
    }

    private void validateTargets(State before, State after) throws Exception {
        Set<String> names = new HashSet<>();
        for (var entry : after.owned.entrySet()) {
            Owned file = entry.getValue(); Updater.fileName(file.file); u.safe(u.mods.resolve(file.file));
            if (!names.add(file.file.toLowerCase(Locale.ROOT))) throw new IOException("Conflicting installation filenames");
            if (!before.owned.containsKey(entry.getKey()) && Files.exists(u.mods.resolve(file.file))) throw new IOException("Refusing to overwrite unowned file " + file.file);
        }
    }
    private void gate() throws Exception { if (u.installGate != null) u.installGate.check(); }
    private void committed() throws Exception { if (u.installGate != null) u.installGate.committed(); }
    private void rejectOtherJournal() throws IOException {
        for (String file : List.of("journal.json", "self-journal.json", "dropin-journal.json")) { u.safe(u.home.resolve(file)); if (Files.exists(u.home.resolve(file))) throw new IOException("Another updater transaction requires recovery before Folkways installation"); }
    }
    private void managed(Config expected) throws IOException {
        if (!stillCurrent.getAsBoolean()) throw new IOException("Updater check request was superseded; current files and ownership were retained");
        Config now = Config.load(u.game);
        if (now.isLocalDevelopment(u.game) || !now.manifestUrl.isBlank() || !now.scope().equals(expected.scope()) || !now.modules.equals(expected.modules)
            || !now.networkPolicy.equals(expected.networkPolicy) || !Objects.equals(now.regionHint, expected.regionHint)) throw new IOException("Local mode or updater configuration changed; current files were retained");
    }
    private State state() throws Exception {
        Path path = u.home.resolve(STATE); if (!Files.exists(path)) return new State();
        State state = read(path, State.class); validateState(state); return state;
    }
    private void validateState(State state) throws IOException {
        if (state == null || state.owned == null || state.highWater == null || state.accepted == null || state.owned.size() > catalog.size() + Updater.MODULES.size()) throw new IOException("Invalid official Folkways ownership state");
        Set<String> names = new HashSet<>();
        for (var entry : state.owned.entrySet()) {
            Owned item = entry.getValue();
            if (!folkways(entry.getKey()) && catalog.get(entry.getKey()) == null || item == null || !Updater.hash(item.sha256) || item.size <= 0 || item.size > LIMIT) throw new IOException("Invalid official Folkways owned artifact");
            Updater.fileName(item.file); if (!names.add(item.file.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate owned filename");
        }
    }
    private void save(State state) throws IOException { Updater.atomic(u.home.resolve(STATE), json(state)); }
    private boolean legacyOwned() throws Exception {
        Path path = u.home.resolve("state.json"); if (!Files.exists(path)) return false;
        Updater.State state = read(path, Updater.State.class); return state == null || state.installed == null || !state.installed.isEmpty();
    }
    private Map<String,Present> scan() throws Exception {
        u.directory(u.mods); Map<String,Present> result = new TreeMap<>();
        try (var paths = Files.list(u.mods)) {
            for (Path path : paths.toList()) {
                if (!path.getFileName().toString().endsWith(".jar")) continue; u.safe(path);
                ModMetadata info;
                try { info = ModMetadata.installed(path); }
                catch (ZipException e) { if (path.getFileName().toString().startsWith("folkways-")) throw e; else continue; }
                if (path.getFileName().toString().startsWith("folkways-") && !path.getFileName().toString().startsWith("folkways-updater-") && info.mods.keySet().stream().noneMatch(GitHubFolkways::folkways)) throw new IOException("Unrecognized Folkways-named JAR is protected: " + path.getFileName());
                boolean relevant = info.mods.keySet().stream().anyMatch(id -> folkways(id) || catalog.get(id) != null);
                if (relevant) info = ModMetadata.installedWithDependencies(path);
                boolean fromFolkways = info.mods.keySet().stream().anyMatch(GitHubFolkways::folkways);
                for (var mod : info.mods.entrySet()) {
                    Present value = present(path, mod.getValue(), info, false, fromFolkways);
                    Present previous = result.get(mod.getKey());
                    if (previous != null && previous.nested && folkways(mod.getKey())) throw new TakeoverRejectedException(mod.getKey(), TakeoverRejectedException.Reason.COMPOSITE_OR_NESTED);
                    if (previous != null && !previous.nested) throw new IOException("Duplicate installed Mod: " + mod.getKey());
                    result.put(mod.getKey(), value);
                }
                if (!fromFolkways) for (var mod : info.provided.entrySet()) {
                    Present value = present(path, mod.getValue(), info, true, false), previous = result.get(mod.getKey());
                    if (previous != null && !previous.nested && folkways(mod.getKey())) throw new TakeoverRejectedException(mod.getKey(), TakeoverRejectedException.Reason.COMPOSITE_OR_NESTED);
                    if (previous == null || previous.nested && ModMetadata.compare(value.version, previous.version) > 0) result.put(mod.getKey(), value);
                }
            }
        }
        return result;
    }
    private Present present(Path path, String version, ModMetadata metadata, boolean nested, boolean fromFolkways) throws Exception {
        Present value = new Present(); value.file = path.getFileName().toString(); value.version = version; value.size = Files.size(path);
        value.metadata = metadata; value.nested = nested; value.fromFolkways = fromFolkways;
        // Unrelated large Mods are metadata-only until they are actually required.
        if (fromFolkways || metadata.mods.keySet().stream().anyMatch(id -> catalog.get(id) != null)) value.sha256 = Updater.sha(u.readBounded(path, LIMIT));
        return value;
    }
    private void bind(Present value) throws Exception {
        if (value.sha256 != null) return;
        Path path = u.mods.resolve(value.file); u.safe(path); byte[] bytes = u.readBounded(path, LIMIT);
        value.sha256 = Updater.sha(bytes); value.size = bytes.length; value.metadata = ModMetadata.read(bytes);
    }
    private boolean existingClosureComplete(State state, Map<String,Present> installed, Config config) throws Exception {
        Plan current = new Plan(); current.loaderVersion = u.loaderVersion;
        Resolver resolver = new Resolver(current, installed);
        for (String module : config.modules) {
            Present present = installed.get("folkways_" + module); if (present == null) return false;
            resolver.add(present.metadata, null);
        }
        return resolver.resolve(null, config, state, null);
    }
    private final class Resolver {
        final Plan plan; final Map<String,Present> installed;
        final Map<String,String> provided = new TreeMap<>(); final Set<String> top = new HashSet<>(), usedExisting = new HashSet<>();
        final List<ModMetadata> metadata = new ArrayList<>(); final List<ModMetadata.Dependency> requirements = new ArrayList<>();
        Resolver(Plan plan, Map<String,Present> installed) { this.plan = plan; this.installed = installed; }
        void add(ModMetadata info, Present existing) throws Exception {
            metadata.add(info); requirements.addAll(info.required);
            if (metadata.size() > 128 || requirements.size() > 2048) throw new IOException("Dependency closure exceeds limits");
            for (var mod : info.mods.entrySet()) {
                if (top.contains(mod.getKey()) && !Objects.equals(provided.get(mod.getKey()), mod.getValue())) throw new IOException("Conflicting top-level dependency: " + mod.getKey());
                top.add(mod.getKey()); provided.put(mod.getKey(), mod.getValue());
            }
            for (var mod : info.provided.entrySet()) if (!top.contains(mod.getKey())) {
                String previous = provided.get(mod.getKey());
                if (previous == null || ModMetadata.compare(mod.getValue(), previous) > 0) provided.put(mod.getKey(), mod.getValue());
            }
        }
        boolean resolve(Source source, Config config, State before, Map<String,byte[]> bytes) throws Exception {
            int cursor = 0;
            while (true) {
              for (; cursor < requirements.size(); cursor++) {
                var dependency = requirements.get(cursor);
                if (platform(dependency)) continue;
                Present external = installed.get(dependency.id);
                if (external != null && !external.fromFolkways) {
                    if (!external.nested && !ModMetadata.matches(external.version, dependency.range)) throw new DependencyConflictException(dependency.id, external.version, dependency.range);
                    useExisting(dependency.id, external, before);
                }
                if (provided.containsKey(dependency.id)) continue;
                ApprovedDependencies.Pin pin = catalog.get(dependency.id);
                if (pin == null || !ModMetadata.matches(pin.mods().get(dependency.id), dependency.range)) throw new IOException("Required dependency " + dependency.id + " " + dependency.range + " is missing and has no compatible audited download");
                if (source == null) return false;
                managed(config); Modrinth proof = decode(source.official(pin.api(), 1024 * 1024), Modrinth.class); pin.validate(proof);
                managed(config); byte[] data = source.asset(pin.url(), pin.size(), "SHA-512", pin.sha512()); managed(config);
                ModMetadata info = ModMetadata.read(data); pin.validateJar(data, info);
                File item = file(pin.id(), pin.name(), pin.url(), data, before); item.version = info.mods.get(pin.id());
                plan.files.add(item); plan.libraries.put(pin.id(), proof); if (pin.id().equals("ldlib2")) plan.library = proof;
                bytes.put(pin.id(), data); add(info, null);
              }
              boolean added = false;
              // FML gives a top-level Mod precedence over a JarJar Mod even without a TOML dependency on it.
              for (String id : new ArrayList<>(provided.keySet())) {
                  Present external = installed.get(id);
                  if (external != null && !external.nested && !external.fromFolkways && !usedExisting.contains(external.file)) {
                      useExisting(id, external, before); added = true;
                  }
              }
              if (!added) break;
            }
            Map<String,Set<String>> candidates = ModMetadata.validateBundles(metadata);
            for (ModMetadata info : metadata) for (var bundle : info.bundles) for (var mod : bundle.mods.entrySet()) {
                Present actual = installed.get(mod.getKey());
                if (actual != null && !actual.nested && !actual.fromFolkways) {
                    String artifactVersion = actual.version;
                    if (!bundle.version.equals(mod.getValue())) {
                        artifactVersion = provenArtifactVersion(actual, mod.getKey(), bundle.id);
                        if (artifactVersion == null) throw new UnverifiedBundleException(mod.getKey(), actual.version);
                    }
                    if (!ModMetadata.matches(artifactVersion, bundle.range)) throw new DependencyConflictException(mod.getKey(), actual.version, bundle.range);
                }
            }
            for (var dependency : requirements) if (!platform(dependency)) {
                Set<String> versions = top.contains(dependency.id) ? Set.of(provided.get(dependency.id)) : candidates.getOrDefault(dependency.id, Set.of());
                if (versions.isEmpty()) throw new IOException("Bundled dependency is unavailable: " + dependency.id);
                for (String version : versions) if (!ModMetadata.matches(version, dependency.range)) throw new DependencyConflictException(dependency.id, version, dependency.range);
            }
            return true;
        }
        String provenArtifactVersion(Present actual, String id, String identifier) throws IOException {
            String proven = null;
            for (ModMetadata info : metadata) for (var candidate : info.bundles) {
                if (identifier.equals(candidate.id) && actual.version.equals(candidate.mods.get(id)) && actual.size == candidate.size
                    && actual.sha256 != null && actual.sha256.equals(candidate.sha256) && actual.metadata.mods.equals(candidate.mods)) {
                    if (proven != null && !proven.equals(candidate.version)) throw new IOException("Ambiguous artifact identity for an installed bundled library");
                    proven = candidate.version;
                }
            }
            return proven;
        }
        void useExisting(String id, Present external, State before) throws Exception {
            if (!usedExisting.add(external.file)) return;
            bind(external); add(external.metadata, external);
            boolean owned = before.owned.values().stream().anyMatch(o -> o.file.equals(external.file));
            if (!owned) plan.external.add(external(id, external));
        }
        boolean platform(ModMetadata.Dependency dependency) throws Exception {
            if (dependency.id.equals("minecraft")) {
                if (!ModMetadata.matches("1.21.1", dependency.range)) throw new IOException("This Folkways build does not support Minecraft 1.21.1"); return true;
            }
            if (dependency.id.equals("neoforge")) {
                if (!ModMetadata.matches(plan.loaderVersion, dependency.range)) throw new IOException("Folkways requires NeoForge " + dependency.range + "; current version is " + plan.loaderVersion); return true;
            }
            return false;
        }
    }
    private void validateOwned(State state, Map<String,Present> installed) throws Exception {
        for (String id : installed.keySet()) if (folkways(id) && !state.owned.containsKey(id)) throw new IOException("Existing Folkways JAR is not owned by this updater; local/manual files will not be replaced");
        validateOwnedHashes(state, installed);
    }
    private void validateOwnedHashes(State state, Map<String,Present> installed) throws Exception {
        for (var item : state.owned.entrySet()) {
            Present actual = installed.get(item.getKey()); Owned expected = item.getValue();
            if (actual == null || !expected.file.equals(actual.file) || !expected.sha256.equals(actual.sha256) || expected.size != actual.size) throw new IOException("Local or manual changes protect " + item.getKey() + "; no files were replaced");
        }
    }
    private State takeoverBaseline(State recorded, Map<String,Present> installed, Config config) throws Exception {
        // Explicit consent can add previously unowned files; it never resets an existing ownership hash.
        validateOwnedHashes(recorded, installed); State baseline = decode(json(recorded), State.class);
        for (var entry : installed.entrySet()) {
            String id = entry.getKey(); if (!folkways(id) || recorded.owned.containsKey(id)) continue;
            if (!config.modules.contains(id.substring(9))) throw new TakeoverRejectedException(id, TakeoverRejectedException.Reason.UNSELECTED_MODULE);
            Present present = entry.getValue(); Path path = u.mods.resolve(present.file); u.safe(path); Updater.fileName(present.file);
            if (present.nested || !present.fromFolkways) throw new TakeoverRejectedException(id, TakeoverRejectedException.Reason.COMPOSITE_OR_NESTED);
            byte[] bytes = u.readBounded(path, LIMIT); Updater.verify(bytes, present.sha256, present.size); ModMetadata metadata = ModMetadata.read(bytes);
            if (present.nested || !present.fromFolkways || !"javafml".equals(metadata.loader) || !metadata.mods.keySet().equals(Set.of(id)) || metadata.provided.keySet().stream().anyMatch(GitHubFolkways::folkways))
                throw new TakeoverRejectedException(id, TakeoverRejectedException.Reason.COMPOSITE_OR_NESTED);
            Updater.validateJar(bytes, id.substring(9));
            baseline.owned.put(id, new Owned(present.file, present.sha256, present.size, metadata.mods.get(id)));
        }
        validateState(baseline); return baseline;
    }
    private void validateBaseline(State recorded, State baseline) throws Exception {
        validateRecordedState(recorded);
        validateOwned(baseline, scan());
    }
    private void validateRecordedState(State recorded) throws Exception {
        if (!Updater.JSON.toJson(state()).equals(Updater.JSON.toJson(recorded))) throw new IOException("Folkways ownership changed while verifying the update");
    }
    private static boolean currentSelection(State state, Config config) {
        Set<String> modules = new TreeSet<>(); for (String id : state.owned.keySet()) if (folkways(id)) modules.add(id.substring(9));
        return modules.equals(new TreeSet<>(config.modules));
    }
    private static File file(String id, String filename, String url, byte[] bytes, State state) throws Exception {
        File item = new File(); item.id = id; item.name = filename; item.target = state.owned.containsKey(id) ? state.owned.get(id).file : filename;
        Updater.fileName(item.target); item.url = url; item.sha256 = Updater.sha(bytes); item.size = bytes.length; return item;
    }
    private static External external(String id, Present present) { External item = new External(); item.id = id; item.file = present.file; item.version = present.version; item.sha256 = present.sha256; item.size = present.size; return item; }
    private Plan pending(State state, boolean requireCurrent) throws Exception { return pendingAt(u.home.resolve(PENDING), state, requireCurrent); }
    private Plan pendingAt(Path directory, State state, boolean requireCurrent) throws Exception {
        byte[] bytes = u.readBounded(directory.resolve("plan.json"), 8 * 1024 * 1024);
        if (state.pending == null || !state.pending.equals(Updater.sha(bytes))) throw new IOException("Uncommitted official Folkways cache");
        Plan plan = decode(bytes, Plan.class); Config config = Config.load(u.game);
        if (requireCurrent && !sameScope(plan, config)) throw new DifferentSelection();
        validatePlan(plan, null);
        Plan closure = new Plan(); closure.loaderVersion = plan.loaderVersion; Resolver resolver = new Resolver(closure, scan());
        for (File file : plan.files) {
            byte[] jar = u.readBounded(directory.resolve(file.id + ".jar"), LIMIT); Updater.verify(jar, file.sha256, file.size);
            ModMetadata info = ModMetadata.read(jar);
            if (!file.version.equals(info.mods.get(file.id))) throw new IOException("Cached Mod identity/version mismatch");
            ApprovedDependencies.Pin pin = catalog.get(file.id);
            if (pin != null) pin.validateJar(jar, info);
            resolver.add(info, null);
        }
        for (External external : plan.external) {
            Updater.fileName(external.file); Updater.verify(u.readBounded(u.mods.resolve(external.file), LIMIT), external.sha256, external.size);
        }
        if (!resolver.resolve(null, configFor(plan), state, null)) throw new IOException("Cached dependency closure is incomplete");
        for (External actual : closure.external) if (plan.external.stream().noneMatch(e -> e.file.equals(actual.file) && e.sha256.equals(actual.sha256) && e.size == actual.size))
            throw new IOException("Cached plan depends on an unrecorded external Mod");
        return plan;
    }
    private void restorePending(State state) throws Exception {
        Path previous = u.home.resolve(PENDING + ".previous"), queued = u.home.resolve(PENDING); u.safe(previous); u.safe(queued);
        if (!Files.exists(previous)) return;
        try { pendingAt(queued, state, false); u.clear(previous); }
        catch (Exception e) { pendingAt(previous, state, false); u.clear(queued); Files.move(previous, queued, StandardCopyOption.ATOMIC_MOVE); }
    }
    private void validatePlan(Plan plan, Config current) throws Exception {
        if (plan == null || plan.schema != 1 || !Set.of("IzakyL/folkways", "davisye/folkways").contains(plan.repository) || !"1.21.1".equals(plan.minecraft)
            || plan.modules == null || !plan.modules.containsAll(List.of("core", "vanilla")) || new HashSet<>(plan.modules).size() != plan.modules.size() || !Updater.MODULES.containsAll(plan.modules)
            || plan.files == null || plan.files.size() < 2 || plan.files.size() > Updater.MODULES.size() + catalog.size() || plan.external == null || plan.external.size() > 64 || plan.loaderVersion == null
            || plan.release == null || plan.run == null || !Objects.equals(plan.commit, plan.release.target_commitish)) throw new IOException("Invalid official Folkways install plan");
        Config config = configFor(plan); config.validate();
        if (current != null && !sameScope(plan, current)) throw new DifferentSelection();
        Map<String,Asset> assets = releaseAssets(plan.release, config);
        verifyRun(plan.run, plan.release, plan.repository);
        if (sequence(plan.run) != plan.sequence || !fingerprint(plan.release).equals(plan.fingerprint)) throw new IOException("Cached official CI/release proof changed");
        Set<String> ids = new HashSet<>(), names = new HashSet<>(), libraryIds = new HashSet<>();
        if (plan.libraries == null || plan.libraries.size() > catalog.size()) throw new IOException("Invalid dependency proof catalogue");
        for (File file : plan.files) {
            if (file == null || !ids.add(file.id) || !Updater.hash(file.sha256) || file.size <= 0 || file.size > LIMIT || !names.add(file.target.toLowerCase(Locale.ROOT))) throw new IOException("Invalid or duplicate official artifact");
            Updater.fileName(file.target);
            if (folkways(file.id)) {
                if (!plan.modules.contains(file.id.substring(9))) throw new IOException("Unexpected selected module"); Asset asset = assets.get(file.id.substring(9));
                if (!asset.name.equals(file.name) || !asset.name.equals("folkways-" + file.id.substring(9) + "-neoforge-1.21.1-" + file.version + ".jar") || !asset.browser_download_url.equals(file.url) || asset.size != file.size || !digest(asset).equals(file.sha256)) throw new IOException("Cached artifact is not the official release asset");
            } else {
                ApprovedDependencies.Pin pin = catalog.get(file.id); if (pin == null) throw new IOException("Unknown automatic dependency");
                pin.validate(libraryProof(plan, file.id)); libraryIds.add(file.id);
                if (!pin.url().equals(file.url) || !pin.name().equals(file.name) || file.size != pin.size() || !pin.mods().get(file.id).equals(file.version)) throw new IOException("Unapproved dependency artifact");
            }
        }
        if (!libraryIds.containsAll(plan.libraries.keySet()) || plan.library != null && !libraryIds.contains("ldlib2")) throw new IOException("Unused or conflicting dependency proof");
        for (String module : plan.modules) if (!ids.contains("folkways_" + module)) throw new IOException("Incomplete module set");
    }
    private static Config configFor(Plan plan) { Config config = new Config(); config.repository = plan.repository; config.channel = plan.channel; config.modules = plan.modules; return config; }
    private static Modrinth libraryProof(Plan plan, String id) throws IOException {
        Modrinth proof = plan.libraries.get(id);
        if (id.equals("ldlib2") && plan.library != null) {
            if (proof != null && !Updater.JSON.toJson(proof).equals(Updater.JSON.toJson(plan.library))) throw new IOException("Conflicting legacy and current LDLib2 proofs");
            if (proof == null) proof = plan.library;
        }
        return proof;
    }
    private static boolean sameScope(Plan plan, Config config) { return plan != null && config.manifestUrl.isBlank() && plan.repository.equals(config.repository) && plan.minecraft.equals(config.minecraft) && plan.channel.equals(config.channel) && plan.modules.equals(config.modules); }
    private static String base(Config config) { return "https://api.github.com/repos/" + config.repository; }
    private static Release release(Source source, Config config) throws Exception {
        String endpoint = config.channel.equals("nightly") ? "/releases/tags/nightly" : "/releases?per_page=50";
        byte[] bytes = source.official(base(config) + endpoint, 4 * 1024 * 1024);
        if (config.channel.equals("nightly")) return decode(bytes, Release.class);
        Release[] releases = decode(bytes, Release[].class);
        if (releases == null) throw new IOException("Invalid official release list");
        return Arrays.stream(releases).filter(r -> r != null && !r.draft && r.tag_name != null && !r.tag_name.equals("nightly"))
            .filter(r -> config.channel.equals("release") ? !r.prerelease : r.prerelease && r.tag_name.toLowerCase(Locale.ROOT).contains(config.channel))
            .max(Comparator.comparingLong(r -> r.id)).orElse(null);
    }
    private static Map<String,Asset> releaseAssets(Release release, Config config) throws Exception {
        if (release == null || release.id <= 0 || release.draft || release.assets == null || release.assets.size() > 100 || release.tag_name == null
            || !release.tag_name.matches("[A-Za-z0-9._+\\-]+") || release.target_commitish == null || !release.target_commitish.matches("[0-9a-f]{40}")
            || !Objects.equals(release.url, base(config) + "/releases/" + release.id)
            || (config.channel.equals("nightly") ? !release.tag_name.equals("nightly") : config.channel.equals("release") ? release.prerelease || release.tag_name.equals("nightly") : !release.prerelease || !release.tag_name.toLowerCase(Locale.ROOT).contains(config.channel))) throw new IOException("Official Folkways release identity is invalid or not pinned to a commit");
        Map<String,Asset> result = new TreeMap<>(); String version = null;
        for (Asset asset : release.assets) {
            if (asset == null || asset.name == null) throw new IOException("Invalid official release asset");
            for (String module : Updater.MODULES) {
                String prefix = "folkways-" + module + "-neoforge-1.21.1-";
                if (!asset.name.startsWith(prefix) || !asset.name.endsWith(".jar")) continue;
                Updater.fileName(asset.name);
                if (asset.id <= 0 || asset.size <= 0 || asset.size > LIMIT || !"uploaded".equals(asset.state)
                    || !Objects.equals(asset.browser_download_url, "https://github.com/" + config.repository + "/releases/download/" + release.tag_name + "/" + asset.name)
                    || result.putIfAbsent(module, asset) != null) throw new IOException("Missing or duplicate official Folkways component");
                digest(asset); String current = asset.name.substring(prefix.length(), asset.name.length() - 4);
                if (version != null && !version.equals(current)) throw new IOException("Official release mixes module versions"); version = current;
            }
        }
        if (!result.keySet().equals(Updater.MODULES)) throw new IOException("Official release does not contain all five Folkways module assets"); return result;
    }
    private static SelfUpdater.Run successfulRun(Runs runs, Release release, String repository) throws Exception {
        if (runs == null || runs.workflow_runs == null || runs.workflow_runs.size() > 20) throw new IOException("Official CI response is invalid");
        List<SelfUpdater.Run> valid = new ArrayList<>();
        for (SelfUpdater.Run run : runs.workflow_runs) { try { verifyRun(run, release, repository); valid.add(run); } catch (IOException ignored) { } }
        return valid.stream().max(Comparator.comparingLong(r -> r.run_number)).orElseThrow(() -> new IOException("No successful official Folkways push CI exists for this release commit"));
    }
    private static void verifyRun(SelfUpdater.Run run, Release release, String repository) throws Exception {
        if (run == null || run.id <= 0 || run.run_number <= 0 || run.run_attempt <= 0 || run.run_attempt >= 1000 || !"completed".equals(run.status) || !"success".equals(run.conclusion)
            || !"push".equals(run.event) || !".github/workflows/build.yml".equals(run.path) || !release.target_commitish.equals(run.head_sha)
            || run.repository == null || !repository.equals(run.repository.full_name) || run.head_repository == null || !repository.equals(run.head_repository.full_name)
            || !List.of("master", "main", release.tag_name).contains(run.head_branch)) throw new IOException("Folkways release CI must be a successful same-repository push on its pinned commit");
        sequence(run);
    }
    private static long sequence(SelfUpdater.Run run) throws IOException { try { return Math.addExact(Math.multiplyExact(run.run_number, 1000), run.run_attempt); } catch (ArithmeticException e) { throw new IOException("Invalid CI sequence", e); } }
    private static String fingerprint(Release release) throws Exception {
        if (release == null || release.assets == null) throw new IOException("Release disappeared during download");
        return Updater.sha(json(List.of(release.id, release.tag_name, release.target_commitish, release.draft, release.prerelease, release.assets.stream().filter(a -> a.name != null && a.name.startsWith("folkways-") && a.name.endsWith(".jar"))
            .sorted(Comparator.comparing(a -> a.name)).map(a -> a.id + ":" + a.name + ":" + a.size + ":" + a.digest).toList())));
    }
    private static String digest(Asset asset) throws IOException { if (asset.digest == null || !asset.digest.matches("sha256:[0-9a-f]{64}")) throw new IOException("Official GitHub asset SHA-256 is missing"); return asset.digest.substring(7); }
    static void verifyHash(byte[] bytes, String algorithm, String hash, long size) throws Exception {
        if (bytes.length != size || !HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)).equals(hash)) throw new IOException("Official download hash/size mismatch; current files were retained");
    }
    private static boolean folkways(String id) { return id != null && id.startsWith("folkways_") && Updater.MODULES.contains(id.substring(9)); }
    private static byte[] json(Object value) { return Updater.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    private <T> T read(Path path, Class<T> type) throws Exception { return decode(u.readBounded(path, 8 * 1024 * 1024), type); }
    private static <T> T decode(byte[] bytes, Class<T> type) throws IOException {
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("Official metadata exceeds limit");
        try { return Updater.JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type); } catch (RuntimeException e) { throw new IOException("Invalid official metadata JSON", e); }
    }
    static final class Owned { String file, sha256, version; long size; Owned(String f, String h, long n, String v) { file = f; sha256 = h; size = n; version = v; } }
    static final class State { Map<String,Owned> owned = new TreeMap<>(); Map<String,Long> highWater = new TreeMap<>(); Map<String,String> accepted = new TreeMap<>(); String scope, pending; }
    static final class File { String id, name, target, url, sha256, version; long size; }
    static final class External { String id, file, version, sha256; long size; }
    static final class Plan { int schema; String repository, minecraft, channel, commit, loaderVersion, fingerprint; long sequence; List<String> modules; Release release; SelfUpdater.Run run; Modrinth library; Map<String,Modrinth> libraries = new TreeMap<>(); List<File> files = new ArrayList<>(); List<External> external = new ArrayList<>(); }
    static final class Journal { State before, after; }
    static final class Present { String file, version, sha256; long size; boolean nested, fromFolkways; ModMetadata metadata; }
    static final class Release { long id; String url, tag_name, target_commitish; boolean draft, prerelease; List<Asset> assets; }
    static final class Asset { long id, size; String name, browser_download_url, digest, state; }
    static final class Commit { String sha; }
    static final class Runs { List<SelfUpdater.Run> workflow_runs; }
    static final class Modrinth { String id, project_id; List<String> game_versions, loaders; List<ModrinthFile> files; }
    static final class ModrinthFile { String url, filename; long size; boolean primary; Map<String,String> hashes; }
    static final class DifferentSelection extends IOException { DifferentSelection() { super("Official plan belongs to another source/channel/selection"); } }
}
