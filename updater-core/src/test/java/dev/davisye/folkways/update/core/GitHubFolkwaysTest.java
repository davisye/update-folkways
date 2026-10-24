package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubFolkwaysTest {
    @TempDir Path game;
    Config config; Updater updater;
    GitHubFolkways.Release release; SelfUpdater.Run run;
    Map<String,byte[]> downloads = new HashMap<>();
    int calls, libraryRequests; boolean offline, badLibraryMetadata;
    java.util.function.Consumer<String> duringDownload = ignored -> {};
    Path pending() { return game.resolve(".folkways-updater/github-folkways-pending"); }
    Path journal() { return game.resolve(".folkways-updater/" + GitHubFolkways.JOURNAL); }
    void fixture(boolean library) throws Exception {
        Updater.initialize(game); config = Config.load(game); config.localDevelopment = false; save(); Files.createDirectories(game.resolve("mods"));
        if (library) Files.write(game.resolve("mods/user-ldlib.jar"), jar("ldlib2", "2.2.37", ""));
        updater = new Updater(game, "21.1.233"); updater.githubSource = source(); publish(2);
    }
    void save() throws Exception { Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(config)); }
    void publish(long number) throws Exception {
        release = new GitHubFolkways.Release(); release.id = number; release.url = "https://api.github.com/repos/" + config.repository + "/releases/" + number;
        release.tag_name = "nightly"; release.prerelease = true; release.target_commitish = String.format("%040x", number); release.assets = new ArrayList<>();
        for (String module : List.of("core", "vanilla", "create", "modulargolems", "cookery")) {
            String dependencies = module.equals("core") ? dependency("folkways_core", "ldlib2", "[2.2.37,)") : dependency("folkways_" + module, "folkways_core", "[0.1.0]");
            if (module.equals("create")) dependencies += dependency("folkways_create", "create", "[6.0.0,)");
            byte[] bytes = jar("folkways_" + module, "0.1.0", dependencies + "#build " + number);
            GitHubFolkways.Asset asset = new GitHubFolkways.Asset(); asset.id = number * 100 + release.assets.size(); asset.name = "folkways-" + module + "-neoforge-1.21.1-0.1.0.jar";
            asset.browser_download_url = "https://github.com/" + config.repository + "/releases/download/nightly/" + asset.name;
            asset.size = bytes.length; asset.digest = "sha256:" + Updater.sha(bytes); asset.state = "uploaded"; release.assets.add(asset); downloads.put(asset.browser_download_url, bytes);
        }
        run = new SelfUpdater.Run(); run.id = 100 + number; run.run_number = number; run.run_attempt = 1; run.head_sha = release.target_commitish;
        run.path = ".github/workflows/build.yml"; run.head_branch = "master"; run.event = "push"; run.status = "completed"; run.conclusion = "success";
        run.repository = new SelfUpdater.Repo(); run.repository.full_name = config.repository; run.head_repository = run.repository;
    }
    GitHubFolkways.Source source() {
        return new GitHubFolkways.Source() {
            public byte[] official(String url, long maximum) throws Exception {
                calls++; if (offline) throw new IOException("Official GitHub API unavailable");
                if (url.contains("api.modrinth.com")) {
                    libraryRequests++; GitHubFolkways.Modrinth library = new GitHubFolkways.Modrinth(); library.id = "bpcbGDy4"; library.project_id = badLibraryMetadata ? "attacker" : "B1CBVXHX";
                    library.game_versions = List.of("1.21.1"); library.loaders = List.of("neoforge"); GitHubFolkways.ModrinthFile file = new GitHubFolkways.ModrinthFile();
                    file.url = "https://cdn.modrinth.com/data/B1CBVXHX/versions/bpcbGDy4/ldlib2-neoforge-1.21.1-2.2.37-all.jar"; file.primary = true; file.size = 7215985;
                    file.hashes = Map.of("sha512", "46114d746ed045bf9bfdab87877436bc24f3120362b89256aa4749ff794691988f58b694208aca4297f5042fd24cb3387590b9bc20b28d708c6e246aaa669c7b");
                    library.files = List.of(file); return json(library);
                }
                if (url.contains("/commits/")) { GitHubFolkways.Commit commit = new GitHubFolkways.Commit(); commit.sha = release.target_commitish; return json(commit); }
                if (url.contains("/actions/")) { GitHubFolkways.Runs runs = new GitHubFolkways.Runs(); runs.workflow_runs = List.of(run); return json(runs); }
                if (url.endsWith("/releases?per_page=50")) return json(new GitHubFolkways.Release[]{release});
                assertTrue(url.equals("https://api.github.com/repos/" + config.repository + "/releases/tags/nightly")); return json(release);
            }
            public byte[] asset(String url, long size, String algorithm, String hash) throws Exception {
                duringDownload.accept(url);
                if (url.contains("cdn.modrinth")) return new byte[(int)size]; // No fake binary can satisfy the pinned production SHA-512.
                return downloads.get(url).clone();
            }
        };
    }
    static byte[] json(Object value) { return Updater.JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    static String dependency(String owner, String id, String range) { return "\n[[ dependencies." + owner + " ]]\nmodId=\"" + id + "\"\ntype=\"required\"\nversionRange=\"" + range + "\"\n"; }
    static byte[] jar(String id, String version, String extra) throws Exception {
        String text = "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n"
            + dependency(id, "minecraft", "[1.21.1,1.21.2)") + dependency(id, "neoforge", "[21.1.233,)") + extra;
        ByteArrayOutputStream out = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(out)) { ZipEntry entry = new ZipEntry("META-INF/neoforge.mods.toml"); entry.setTime(0); zip.putNextEntry(entry); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        return out.toByteArray();
    }
    GitHubFolkways.State state() throws Exception { return Updater.JSON.fromJson(Files.readString(game.resolve(".folkways-updater/github-folkways-state.json")), GitHubFolkways.State.class); }
    @Test void emptyFolkwaysSetStagesAndInstallsWithExistingCompatibleLibraryWithoutTakingItOver() throws Exception {
        fixture(true); byte[] library = Files.readAllBytes(game.resolve("mods/user-ldlib.jar"));
        assertTrue(updater.checkAndStage().contains("Downloaded official")); assertEquals(1, Files.list(game.resolve("mods")).count());
        assertTrue(updater.applyPending().contains("Installed official"));
        assertEquals(Set.of("folkways_core", "folkways_vanilla"), state().owned.keySet()); assertArrayEquals(library, Files.readAllBytes(game.resolve("mods/user-ldlib.jar")));
        assertTrue(updater.checkAndStage().contains("Already installed")); assertEquals(0, libraryRequests);
    }
    @Test void noConfiguredManifestMeansOfficialModeAndUnavailableChannelsDoNotFallBackToNightly() throws Exception {
        fixture(true); config.channel = "release"; save(); assertTrue(updater.checkAndStage().contains("No official release")); assertFalse(Files.exists(pending()));
    }
    @Test void wrongCiAndMissingDigestDoNotCreateOwnershipOrPartialPending() throws Exception {
        fixture(true); run.event = "pull_request"; assertThrows(IOException.class, updater::checkAndStage);
        publish(2); release.assets.getFirst().digest = null; assertThrows(IOException.class, updater::checkAndStage);
        assertFalse(Files.exists(pending())); assertFalse(Files.exists(game.resolve(".folkways-updater/github-folkways-state.json")));
    }
    @Test void existingFolkwaysIsProtectedEvenWhenRenamedOrCompiledLocally() throws Exception {
        fixture(true); Files.write(game.resolve("mods/local.jar"), jar("folkways_core", "0.1.0-local", ""));
        assertTrue(updater.checkAndStage().contains("Existing Folkways JARs are protected")); assertEquals(0, calls);
    }
    @Test void missingLibraryUsesAuditedOfficialMetadataAndRejectsCorruptFile() throws Exception {
        fixture(false); assertThrows(IOException.class, updater::checkAndStage); assertEquals(1, libraryRequests);
        assertFalse(Files.exists(pending())); assertEquals(0, Files.list(game.resolve("mods")).count());
        badLibraryMetadata = true; assertThrows(IOException.class, updater::checkAndStage); assertFalse(Files.exists(pending()));
    }
    @Test void incompatibleExistingLibraryOrLoaderIsNeverOverwritten() throws Exception {
        fixture(true); updater = new Updater(game, "21.1.205"); updater.githubSource = source();
        assertTrue(assertThrows(IOException.class, updater::checkAndStage).getMessage().contains("requires NeoForge"));
        updater = new Updater(game, "21.1.233"); updater.githubSource = source(); Files.write(game.resolve("mods/user-ldlib.jar"), jar("ldlib2", "2.2.37-rc.1", ""));
        assertTrue(assertThrows(IOException.class, updater::checkAndStage).getMessage().contains("does not satisfy")); assertEquals(0, libraryRequests);
    }
    @Test void localModeDuringDownloadRetainsOldCompletePending() throws Exception {
        fixture(true); updater.checkAndStage(); byte[] old = Files.readAllBytes(pending().resolve("plan.json")); publish(3);
        duringDownload = ignored -> { config.localDevelopment = true; try { save(); } catch (Exception e) { throw new RuntimeException(e); } };
        assertThrows(IOException.class, updater::checkAndStage); assertArrayEquals(old, Files.readAllBytes(pending().resolve("plan.json")));
        assertTrue(updater.applyPending().contains("Local development"));
    }
    @Test void sourceOrSignedChannelSwitchNeverAppliesQueuedOfficialFiles() throws Exception {
        fixture(true); updater.checkAndStage(); config.repository = "davisye/folkways"; save(); assertTrue(updater.applyPending().contains("different source"));
        assertEquals(1, Files.list(game.resolve("mods")).count()); config.manifestUrl = "https://pan.davisye.cn/manifest.json"; save();
        assertEquals("No pending update", updater.applyPending()); assertEquals(1, Files.list(game.resolve("mods")).count());
    }
    @Test void unmanagedTargetOrDuplicateDependencyCannotBeOverwritten() throws Exception {
        fixture(true); updater.checkAndStage(); Files.write(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar"), new byte[]{1});
        assertThrows(IOException.class, updater::applyPending); assertEquals(1, Files.size(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar")));
        Files.delete(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar")); Files.write(game.resolve("mods/duplicate.jar"), jar("ldlib2", "2.2.37", ""));
        assertThrows(IOException.class, updater::checkAndStage);
    }
    @Test void freshInstallJournalCanRollBackNewFilesOnlyWhenTheirHashIsKnown() throws Exception {
        fixture(true); updater.checkAndStage(); GitHubFolkways.State before = state(); GitHubFolkways.Plan plan = Updater.JSON.fromJson(Files.readString(pending().resolve("plan.json")), GitHubFolkways.Plan.class);
        GitHubFolkways.Journal transaction = new GitHubFolkways.Journal(); transaction.before = before; transaction.after = new GitHubFolkways.State();
        for (var file : plan.files) transaction.after.owned.put(file.id, new GitHubFolkways.Owned(file.target, file.sha256, file.size, file.version));
        Files.createDirectories(game.resolve(".folkways-updater/github-folkways-backup")); Files.writeString(journal(), Updater.JSON.toJson(transaction));
        var first = plan.files.getFirst(); Files.write(game.resolve("mods/" + first.target), Files.readAllBytes(pending().resolve(first.id + ".jar")));
        config.repository = "davisye/folkways"; save(); // Recover old atomic set, but do not install previous source.
        assertTrue(updater.applyPending().contains("different source")); assertFalse(Files.exists(game.resolve("mods/" + first.target))); assertFalse(Files.exists(journal()));
    }
    @Test void activeGameDuringFirstInstallLeavesJournalAndNoFurtherWrites() throws Exception {
        fixture(true); updater.checkAndStage(); boolean[] seen = {false};
        updater.installGate = new Updater.InstallGate() {
            public void check() throws Exception {
                if (Files.exists(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar"))) { seen[0] = true; RuntimeLease.recordGame(updater); }
                RuntimeLease.ensureNoGame(updater);
            }
            public void committed() { }
        };
        assertThrows(RuntimeLease.ActiveGameException.class, updater::applyPending); assertTrue(seen[0]); assertTrue(Files.exists(journal()));
        assertFalse(Files.exists(game.resolve("mods/folkways-vanilla-neoforge-1.21.1-0.1.0.jar")));
    }
    @Test void localProtectionEnabledInsideTheLastGateStillPreventsTheFirstJarWrite() throws Exception {
        fixture(true); updater.checkAndStage();
        updater.installGate = new Updater.InstallGate() {
            public void check() throws Exception { if (Files.exists(journal())) { config.localDevelopment = true; save(); } }
            public void committed() { }
        };
        assertThrows(IOException.class, updater::applyPending); assertTrue(Files.exists(journal()));
        assertFalse(Files.exists(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar")));
        assertFalse(Files.exists(game.resolve("mods/folkways-vanilla-neoforge-1.21.1-0.1.0.jar")));
    }
    @Test void ownedLocalEditAndOlderOrRepublishedCiAreProtected() throws Exception {
        fixture(true); updater.checkAndStage(); updater.applyPending(); publish(1); assertThrows(IOException.class, updater::checkAndStage);
        publish(2); release.assets.getFirst().id++; assertThrows(IOException.class, updater::checkAndStage);
        publish(3); Files.write(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar"), jar("folkways_core", "0.1.0-local", ""));
        assertThrows(IOException.class, updater::checkAndStage);
    }
    @Test void dependencyWhitespaceAndReleaseQualifiersAreNotSilentlyIgnored() throws Exception {
        ModMetadata parsed = ModMetadata.read(jar("folkways_core", "0.1.0", dependency("folkways_core", "ldlib2", "[2.2.37,)")));
        assertTrue(parsed.required.stream().anyMatch(d -> d.id.equals("ldlib2")));
        assertFalse(ModMetadata.matches("6.0.0-beta.1", "[6.0.0,)")); assertFalse(ModMetadata.matches("2.2.37-rc.1", "[2.2.37,)"));
        assertTrue(ModMetadata.matches("1.6.0-neoforge+mc1.21.1", "[1.6.0-neoforge+mc1.21.1,1.7)"));
        assertThrows(IOException.class, () -> ModMetadata.matches("2.2.37-unknown", "[2.2.37,)"));
    }
    @Test void sourceSwitchUsesIndependentHighWaterAndCanReturnToAnAlreadyAcceptedBuild() throws Exception {
        fixture(true); publish(114); updater.checkAndStage(); updater.applyPending();
        String upstreamScope = config.scope(); byte[] upstreamCore = Files.readAllBytes(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar"));
        config.repository = "davisye/folkways"; save(); publish(14); String forkScope = config.scope();
        assertTrue(updater.checkAndStage().contains(release.target_commitish)); updater.applyPending(); assertEquals(forkScope, state().scope);
        assertEquals(114001, state().highWater.get(upstreamScope)); assertEquals(14001, state().highWater.get(forkScope));
        config.repository = "IzakyL/folkways"; save(); publish(114); assertTrue(updater.checkAndStage().contains(release.target_commitish)); updater.applyPending();
        assertEquals(upstreamScope, state().scope); assertArrayEquals(upstreamCore, Files.readAllBytes(game.resolve("mods/folkways-core-neoforge-1.21.1-0.1.0.jar")));
        assertEquals(114001, state().highWater.get(upstreamScope)); assertEquals(14001, state().highWater.get(forkScope)); assertFalse(Files.exists(pending()));
    }
    @Test void selectingInstalledSourceDoesNotApplyAnotherSourceQueueAndSwitchingBackReusesThatQueue() throws Exception {
        fixture(true); publish(114); updater.checkAndStage(); updater.applyPending();
        config.repository = "davisye/folkways"; save(); publish(14); updater.checkAndStage(); byte[] queued = Files.readAllBytes(pending().resolve("plan.json"));
        config.repository = "IzakyL/folkways"; save(); publish(114); assertTrue(updater.checkAndStage().contains("Already installed"));
        assertTrue(updater.applyPending().contains("different source")); assertArrayEquals(queued, Files.readAllBytes(pending().resolve("plan.json")));
        config.repository = "davisye/folkways"; save(); publish(14); String ready = updater.checkAndStage();
        assertTrue(ready.contains("already downloaded")); assertTrue(ready.contains(release.target_commitish)); updater.applyPending(); assertEquals(config.scope(), state().scope);
    }
}
