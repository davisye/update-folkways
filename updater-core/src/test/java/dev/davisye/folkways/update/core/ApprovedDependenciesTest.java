package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovedDependenciesTest {
    @TempDir Path root;
    static final List<String> ALL = List.of("core", "vanilla", "create", "modulargolems", "cookery");
    static final Set<String> DEPENDENCIES = Set.of("ldlib2", "create", "modulargolems", "l2library", "kaleidoscope_cookery");
    static byte[] nested(byte[] outer, String id, String version, byte[] child) throws Exception {
        Map<String,byte[]> entries = DropInUpdaterTest.entries(outer);
        String path = "META-INF/jarjar/" + id + "-" + version + ".jar";
        var declaration = Map.of("identifier", Map.of("group", "fixture", "artifact", id), "version", Map.of("artifactVersion", version, "range", "[" + version + ",)"), "path", path);
        List<Object> jars = new ArrayList<>();
        if (entries.containsKey("META-INF/jarjar/metadata.json")) for (var entry : com.google.gson.JsonParser.parseString(new String(entries.get("META-INF/jarjar/metadata.json"), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("jars")) jars.add(entry);
        jars.add(declaration); entries.put("META-INF/jarjar/metadata.json", GitHubFolkwaysTest.json(Map.of("jars", jars))); entries.put(path, child);
        return DropInUpdaterTest.zip(entries);
    }
    static String requires(String owner, String id, String range) { return GitHubFolkwaysTest.dependency(owner, id, range); }
    static byte[] jar(String id, String version, String extra) throws Exception { return GitHubFolkwaysTest.jar(id, version, extra); }
    static long fileCount(Path directory) throws Exception { try (var files = Files.list(directory)) { return files.count(); } }
    final class Fixture {
        final GitHubFolkwaysTest base = new GitHubFolkwaysTest();
        final Map<String,ApprovedDependencies.Pin> pins = new TreeMap<>();
        final Map<String,byte[]> data = new TreeMap<>(); final Map<String,GitHubFolkways.Modrinth> proofs = new TreeMap<>();
        final Map<String,Integer> requests = new TreeMap<>(); String corrupt;
        Fixture(String name, List<String> modules) throws Exception {
            base.game = Files.createDirectory(root.resolve(name)); base.fixture(false); base.config.modules = new ArrayList<>(modules); base.save();
            pin("ldlib2", "2.2.37", jar("ldlib2", "2.2.37", ""));
            byte[] create = nested(jar("create", "6.0.10", requires("create", "ponder", "[1.0.82,)") + requires("create", "flywheel", "[1.0,2.0)")), "ponder", "1.0.82+mc1.21.1", jar("ponder", "1.0.82+mc1.21.1", ""));
            create = nested(create, "flywheel", "1.0.6", jar("flywheel", "1.0.6", "")); pin("create", "6.0.10", create);
            byte[] cataclysm = nested(jar("cataclysm_mux", "1.0.4", ""), "cataclysm_mux_0316", "1.0.4", jar("cataclysm_mux_0316", "1.0.4", ""));
            byte[] golems = nested(jar("modulargolems", "3.1.44", requires("modulargolems", "l2library", "[3.0.0,)")), "cataclysm_mux", "1.0.4", cataclysm);
            golems = nested(golems, "l2itemselector", "3.1.6", jar("l2itemselector", "3.1.6", requires("l2itemselector", "l2library", "[3.0.0,)")));
            golems = nested(golems, "l2tabs", "3.0.6+7", jar("l2tabs", "3.0.6+7", "")); pin("modulargolems", "3.1.44", golems);
            byte[] l2 = nested(jar("l2library", "3.0.9", ""), "l2itemselector", "3.0.9", jar("l2itemselector", "3.0.9", ""));
            l2 = nested(l2, "l2tabs", "3.0.5+7", jar("l2tabs", "3.0.5+7", "")); pin("l2library", "3.0.9", l2);
            pin("kaleidoscope_cookery", "1.6.0-neoforge+mc1.21.1", jar("kaleidoscope_cookery", "1.6.0-neoforge+mc1.21.1", ""));
            refreshCatalog(); base.updater.githubSource = source(); publish(2);
        }
        void pin(String id, String version, byte[] bytes) throws Exception {
            String filename = "fixture-" + id + ".jar", project = "P" + id.replace("_", ""), versionId = "V" + id.replace("_", "");
            String url = "https://cdn.modrinth.com/data/" + project + "/versions/" + versionId + "/" + filename;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
            var pin = new ApprovedDependencies.Pin(id, project, versionId, filename, url, bytes.length, hash, Map.of(id, version)); pins.put(id, pin); data.put(url, bytes);
            var proof = new GitHubFolkways.Modrinth(); proof.id = versionId; proof.project_id = project; proof.game_versions = List.of("1.21.1"); proof.loaders = List.of("neoforge");
            var file = new GitHubFolkways.ModrinthFile(); file.filename = filename; file.url = url; file.size = bytes.length; file.primary = true; file.hashes = Map.of("sha512", hash); proof.files = List.of(file); proofs.put(pin.api(), proof);
        }
        void refreshCatalog() { base.updater.approvedDependencies = new ApprovedDependencies.Catalog(pins.values().toArray(ApprovedDependencies.Pin[]::new)); }
        void publish(long sequence) throws Exception {
            base.publish(sequence);
            for (var asset : base.release.assets) {
                String module = ALL.stream().filter(m -> asset.name.startsWith("folkways-" + m + "-neoforge")).findFirst().orElseThrow();
                String id = "folkways_" + module, extra = module.equals("core") ? requires(id, "ldlib2", "[2.2.37,)") : requires(id, "folkways_core", "[0.1.0]");
                if (module.equals("create")) extra += requires(id, "create", "[6.0.0,)");
                if (module.equals("modulargolems")) extra += requires(id, "modulargolems", "[3.1.0,)");
                if (module.equals("cookery")) extra += requires(id, "kaleidoscope_cookery", "[1.6.0-neoforge+mc1.21.1,1.7)");
                byte[] bytes = jar(id, "0.1.0", extra + "# build " + sequence); asset.size = bytes.length; asset.digest = "sha256:" + Updater.sha(bytes); base.downloads.put(asset.browser_download_url, bytes);
            }
        }
        GitHubFolkways.Source source() {
            GitHubFolkways.Source original = base.source();
            return new GitHubFolkways.Source() {
                public byte[] official(String url, long maximum) throws Exception {
                    if (!url.contains("api.modrinth.com")) return original.official(url, maximum);
                    var proof = proofs.get(url); if (proof == null) throw new IOException("Unapproved fixture endpoint"); requests.merge(proof.id, 1, Integer::sum); return GitHubFolkwaysTest.json(proof);
                }
                public byte[] asset(String url, long size, String algorithm, String hash) throws Exception {
                    if (!url.contains("cdn.modrinth.com")) return original.asset(url, size, algorithm, hash);
                    base.duringDownload.accept(url); byte[] bytes = data.get(url).clone();
                    if (corrupt != null && pins.get(corrupt).url().equals(url)) bytes[bytes.length - 1] ^= 1; return bytes;
                }
            };
        }
        void install() throws Exception { assertTrue(base.updater.checkAndStage().contains("Downloaded official")); assertTrue(base.updater.applyPending().contains("Installed official")); }
        GitHubFolkways.Plan plan() throws Exception { return Updater.JSON.fromJson(Files.readString(base.pending().resolve("plan.json")), GitHubFolkways.Plan.class); }
        Path mod(String id) { return base.game.resolve("mods/" + pins.get(id).name()); }
    }
    @Test void emptyModsEachExtensionAndAllThreeInstallOnlyTheirRecursiveClosure() throws Exception {
        for (String extension : List.of("create", "modulargolems", "cookery", "all")) {
            List<String> modules = extension.equals("all") ? ALL : List.of("core", "vanilla", extension); Fixture fixture = new Fixture(extension, modules); fixture.install();
            Set<String> expected = new TreeSet<>(); modules.forEach(m -> expected.add("folkways_" + m)); expected.add("ldlib2");
            if (modules.contains("create")) expected.add("create"); if (modules.contains("modulargolems")) expected.addAll(List.of("modulargolems", "l2library")); if (modules.contains("cookery")) expected.add("kaleidoscope_cookery");
            assertEquals(expected, fixture.base.state().owned.keySet()); assertEquals(expected.size(), fileCount(fixture.base.game.resolve("mods")));
            assertTrue(fixture.requests.values().stream().allMatch(count -> count == 1)); assertFalse(Files.exists(fixture.base.journal()));
        }
    }
    @Test void embeddedCandidatesUseContainmentIntersectionAndMultiLevelClosureWithoutExtraDownloads() throws Exception {
        Fixture fixture = new Fixture("nested", ALL); fixture.install(); List<ModMetadata> metadata = new ArrayList<>();
        for (byte[] bytes : fixture.data.values()) metadata.add(ModMetadata.read(bytes));
        var provided = ModMetadata.validateBundles(metadata); assertEquals(Set.of("3.1.6"), provided.get("l2itemselector")); assertEquals(Set.of("3.0.6+7"), provided.get("l2tabs"));
        assertEquals(Set.of("1.0.4"), provided.get("cataclysm_mux_0316")); assertEquals(5, fixture.requests.size());
        assertFalse(ModMetadata.matches("3.0.6+6", "[3.0.6+7,)"));
    }
    @Test void compatibleUserDependenciesIncludingNestedModsRemainUnownedAndUnchanged() throws Exception {
        Fixture fixture = new Fixture("existing", ALL); Map<Path,byte[]> original = new TreeMap<>();
        for (var pin : fixture.pins.values()) { Path file = fixture.base.game.resolve("mods/user-" + pin.name()); byte[] bytes = fixture.data.get(pin.url()); Files.write(file, bytes); original.put(file, bytes); }
        fixture.install(); assertEquals(5, fixture.base.state().owned.size()); assertTrue(fixture.requests.isEmpty());
        for (var entry : original.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }
    @Test void incompatibleTopLevelModCannotBeHiddenByNewerEmbeddedCopyAndDuplicateModsAreRejected() throws Exception {
        Fixture fixture = new Fixture("conflict", List.of("core", "vanilla", "create")); byte[] old = jar("create", "6.0.0-beta.1", "");
        Path target = fixture.base.game.resolve("mods/user-create.jar"); Files.write(target, old);
        DependencyConflictException conflict = assertThrows(DependencyConflictException.class, fixture.base.updater::checkAndStage);
        assertEquals("create", conflict.id); assertEquals("6.0.0-beta.1", conflict.version); assertArrayEquals(old, Files.readAllBytes(target));
        Files.write(target, fixture.data.get(fixture.pins.get("create").url())); Files.write(fixture.base.game.resolve("mods/duplicate-create.jar"), Files.readAllBytes(target));
        assertThrows(IOException.class, fixture.base.updater::checkAndStage); assertFalse(Files.exists(fixture.base.pending()));
        Fixture nested = new Fixture("top-overrides-nested", List.of("core", "vanilla", "create"));
        Files.write(nested.base.game.resolve("mods/user-flywheel.jar"), jar("flywheel", "0.9.0", ""));
        assertEquals("flywheel", assertThrows(DependencyConflictException.class, nested.base.updater::checkAndStage).id);
    }
    @Test void corruptedDependencyOrWrongOfficialIdentityPreservesThePreviousCompleteQueue() throws Exception {
        Fixture fixture = new Fixture("bad", List.of("core", "vanilla")); fixture.base.updater.checkAndStage(); byte[] previous = Files.readAllBytes(fixture.base.pending().resolve("plan.json"));
        fixture.base.config.modules = new ArrayList<>(ALL); fixture.base.save(); fixture.publish(3); fixture.corrupt = "create";
        assertThrows(IOException.class, fixture.base.updater::checkAndStage); assertArrayEquals(previous, Files.readAllBytes(fixture.base.pending().resolve("plan.json")));
        fixture.corrupt = null; fixture.proofs.get(fixture.pins.get("create").api()).project_id = "wrong-project";
        assertThrows(IOException.class, fixture.base.updater::checkAndStage); assertArrayEquals(previous, Files.readAllBytes(fixture.base.pending().resolve("plan.json"))); assertEquals(0, fileCount(fixture.base.game.resolve("mods")));
    }
    @Test void localButtonToggledDuringDependencyDownloadPreventsAllInstallationAndRetainsQueue() throws Exception {
        Fixture fixture = new Fixture("local", List.of("core", "vanilla")); fixture.base.updater.checkAndStage(); byte[] previous = Files.readAllBytes(fixture.base.pending().resolve("plan.json"));
        fixture.base.config.modules = new ArrayList<>(ALL); fixture.base.save(); fixture.publish(3);
        fixture.base.duringDownload = url -> { if (url.equals(fixture.pins.get("create").url())) try { fixture.base.config.localDevelopment = true; fixture.base.save(); } catch (Exception e) { throw new RuntimeException(e); } };
        assertThrows(IOException.class, fixture.base.updater::checkAndStage); assertArrayEquals(previous, Files.readAllBytes(fixture.base.pending().resolve("plan.json")));
        assertTrue(fixture.base.updater.applyPending().contains("Local development")); assertEquals(0, fileCount(fixture.base.game.resolve("mods")));
    }
    @Test void legacyLdOnlyPlanStillAppliesButConflictingDuplicateLibraryProofDoesNot() throws Exception {
        Fixture legacy = new Fixture("legacy", List.of("core", "vanilla")); legacy.base.updater.checkAndStage(); var plan = legacy.plan(); plan.libraries.clear();
        var legacyJson = Updater.JSON.toJsonTree(plan).getAsJsonObject(); legacyJson.remove("libraries");
        byte[] bytes = GitHubFolkwaysTest.json(legacyJson); Files.write(legacy.base.pending().resolve("plan.json"), bytes); var state = legacy.base.state(); state.pending = Updater.sha(bytes);
        Files.writeString(legacy.base.game.resolve(".folkways-updater/github-folkways-state.json"), Updater.JSON.toJson(state));
        assertTrue(legacy.base.updater.applyPending().contains("Installed official")); assertTrue(legacy.base.state().owned.containsKey("ldlib2"));
        Fixture conflicting = new Fixture("conflicting", List.of("core", "vanilla")); conflicting.base.updater.checkAndStage(); plan = conflicting.plan();
        plan.library.project_id = "wrong"; bytes = GitHubFolkwaysTest.json(plan); Files.write(conflicting.base.pending().resolve("plan.json"), bytes); state = conflicting.base.state(); state.pending = Updater.sha(bytes);
        Files.writeString(conflicting.base.game.resolve(".folkways-updater/github-folkways-state.json"), Updater.JSON.toJson(state));
        assertThrows(IOException.class, conflicting.base.updater::applyPending); assertEquals(0, fileCount(conflicting.base.game.resolve("mods")));
    }
    @Test void partialFirstInstallRollsBackKnownNewDependencyHashesAlongWithFolkways() throws Exception {
        Fixture fixture = new Fixture("rollback", ALL); fixture.base.updater.checkAndStage(); var plan = fixture.plan(); var transaction = new GitHubFolkways.Journal();
        transaction.before = fixture.base.state(); transaction.after = new GitHubFolkways.State();
        for (var file : plan.files) transaction.after.owned.put(file.id, new GitHubFolkways.Owned(file.target, file.sha256, file.size, file.version));
        Files.createDirectory(fixture.base.game.resolve(".folkways-updater/github-folkways-backup")); Files.writeString(fixture.base.journal(), Updater.JSON.toJson(transaction));
        for (var file : plan.files) if (file.id.equals("folkways_core") || file.id.equals("create")) Files.write(fixture.base.game.resolve("mods/" + file.target), Files.readAllBytes(fixture.base.pending().resolve(file.id + ".jar")));
        fixture.base.config.repository = "davisye/folkways"; fixture.base.save(); assertTrue(fixture.base.updater.applyPending().contains("different source"));
        assertEquals(0, fileCount(fixture.base.game.resolve("mods"))); assertFalse(Files.exists(fixture.base.journal()));
    }
    @Test void cancelingExtensionsKeepsOwnedDependenciesAndDoesNotRedownloadCompatibleLibraries() throws Exception {
        Fixture fixture = new Fixture("retained", ALL); fixture.install(); Map<String,String> hashes = new TreeMap<>();
        for (String id : DEPENDENCIES) hashes.put(id, fixture.base.state().owned.get(id).sha256);
        fixture.requests.clear(); fixture.base.config.modules = new ArrayList<>(List.of("core", "vanilla")); fixture.base.save(); fixture.publish(3); fixture.install();
        assertEquals(7, fixture.base.state().owned.size()); assertTrue(fixture.requests.isEmpty());
        for (String id : DEPENDENCIES) { assertEquals(hashes.get(id), fixture.base.state().owned.get(id).sha256); assertEquals(hashes.get(id), Updater.sha(Files.readAllBytes(fixture.mod(id)))); }
    }
    @Test void unknownRequiredModHasNoFallbackSearchOrUnapprovedDownload() throws Exception {
        Fixture fixture = new Fixture("unknown", List.of("core", "vanilla", "cookery"));
        fixture.pin("kaleidoscope_cookery", "1.6.0-neoforge+mc1.21.1", jar("kaleidoscope_cookery", "1.6.0-neoforge+mc1.21.1", requires("kaleidoscope_cookery", "unknown_library", "[1,)"))); fixture.refreshCatalog();
        assertTrue(assertThrows(IOException.class, fixture.base.updater::checkAndStage).getMessage().contains("unknown_library"));
        assertFalse(Files.exists(fixture.base.pending())); assertEquals(0, fileCount(fixture.base.game.resolve("mods")));
    }
    @Test void offlinePendingRechecksPinnedSha512EvenWhenItsRecordedSha256WasChanged() throws Exception {
        Fixture fixture = new Fixture("offline-tamper", ALL); fixture.base.updater.checkAndStage(); var plan = fixture.plan();
        var create = plan.files.stream().filter(file -> file.id.equals("create")).findFirst().orElseThrow();
        byte[] replacement = Files.readAllBytes(fixture.base.pending().resolve("create.jar"));
        for (int i = 0; i + 46 < replacement.length; i++) if (replacement[i] == 'P' && replacement[i + 1] == 'K' && replacement[i + 2] == 1 && replacement[i + 3] == 2) { replacement[i + 38] ^= 1; break; }
        Files.write(fixture.base.pending().resolve("create.jar"), replacement); create.sha256 = Updater.sha(replacement);
        byte[] bytes = GitHubFolkwaysTest.json(plan); Files.write(fixture.base.pending().resolve("plan.json"), bytes); var state = fixture.base.state(); state.pending = Updater.sha(bytes);
        Files.writeString(fixture.base.game.resolve(".folkways-updater/github-folkways-state.json"), Updater.JSON.toJson(state));
        assertThrows(IOException.class, fixture.base.updater::applyPending); assertEquals(0, fileCount(fixture.base.game.resolve("mods")));
    }
    @Test void unmentionedTopLevelOverrideMustAlsoMeetBundledArtifactRangesAndIsReadOnlyWhenCompatible() throws Exception {
        Fixture fixture = new Fixture("shadow", List.of("core", "vanilla", "modulargolems"));
        Path user = fixture.base.game.resolve("mods/user-itemselector.jar"); Files.write(user, jar("l2itemselector", "3.0.9", ""));
        DependencyConflictException conflict = assertThrows(DependencyConflictException.class, fixture.base.updater::checkAndStage);
        assertEquals("l2itemselector", conflict.id); assertEquals("[3.1.6,)", conflict.requiredRange); assertFalse(Files.exists(fixture.base.pending()));
        byte[] compatible = jar("l2itemselector", "3.1.6", ""); Files.write(user, compatible); fixture.base.updater.checkAndStage();
        String expectedHash = Updater.sha(compatible);
        assertTrue(fixture.plan().external.stream().anyMatch(proof -> proof.file.equals("user-itemselector.jar") && proof.sha256.equals(expectedHash)));
        assertTrue(fixture.base.updater.applyPending().contains("Installed official")); assertArrayEquals(compatible, Files.readAllBytes(user));
        assertFalse(fixture.base.state().owned.containsKey("l2itemselector"));
    }
}
