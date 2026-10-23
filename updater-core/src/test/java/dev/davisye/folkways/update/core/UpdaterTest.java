package dev.davisye.folkways.update.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdaterTest {
    @TempDir Path game;
    Config config;
    KeyPair keys;
    byte[] envelope, bundle;
    Map<String, byte[]> fresh;
    Updater updater;

    void fixture() throws Exception {
        fixture(List.of("core", "vanilla"));
    }
    void fixture(List<String> selected) throws Exception {
        Updater.initialize(game); keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        config = new Config(); config.localDevelopment = false;
        config.modules = new ArrayList<>(selected);
        config.manifestUrl = "https://pan.davisye.cn/d/folkways/manifest.json";
        config.publicKeys.put("test", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())); saveConfig();
        Files.createDirectories(game.resolve("mods"));
        for (String module : config.modules) Files.write(game.resolve("mods/folkways-" + module + "-initial.jar"), jar(module, "old"));
        updater = new Updater(game, (url, max) -> url.equals(config.manifestUrl) ? envelope : bundle);
        updater.adopt(); release(2);
    }

    void saveConfig() throws Exception { Files.writeString(game.resolve(".folkways-updater/config.json"), Updater.JSON.toJson(config)); }
    static byte[] jar(String module, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            ZipEntry metadataEntry = new ZipEntry("META-INF/neoforge.mods.toml"); metadataEntry.setTime(0); out.putNextEntry(metadataEntry);
            String metadata = "modLoader=\"javafml\"\n[[mods]]\nmodId = \"folkways_" + module + "\"\nversion=\"0.1.0\"\n";
            if (!module.equals("core")) metadata += "[[dependencies.folkways_" + module + "]]\nmodId=\"folkways_core\"\n";
            out.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            ZipEntry payloadEntry = new ZipEntry("payload.txt"); payloadEntry.setTime(0); out.putNextEntry(payloadEntry); out.write(content.getBytes()); out.closeEntry();
        }
        return bytes.toByteArray();
    }
    void release(long sequence) throws Exception {
        fresh = new TreeMap<>();
        Updater.Manifest manifest = new Updater.Manifest(); manifest.schema = 1;
        manifest.repository = config.repository; manifest.minecraft = config.minecraft; manifest.channel = config.channel;
        manifest.loader = "neoforge"; manifest.buildId = "a".repeat(39) + sequence; manifest.sequence = sequence;
        manifest.components = new ArrayList<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String module : List.of("core", "vanilla", "create", "modulargolems", "cookery")) {
                byte[] data = jar(module, "new-" + sequence); fresh.put(module, data);
                Updater.Artifact a = new Updater.Artifact(); a.module = module; a.file = "folkways-" + module + "-new.jar";
                a.sha256 = Updater.sha(data); a.size = data.length; manifest.components.add(a);
                zip.putNextEntry(new ZipEntry(a.file)); zip.write(data); zip.closeEntry();
            }
        }
        bundle = bytes.toByteArray(); manifest.bundle = new Updater.Bundle(); manifest.bundle.url = "https://pan.davisye.cn/d/bundle.zip";
        manifest.bundle.size = bundle.length; manifest.bundle.sha256 = Updater.sha(bundle);
        envelope = sign(manifest);
    }
    byte[] sign(Updater.Manifest m) throws Exception {
        byte[] payload = Updater.JSON.toJson(m).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(keys.getPrivate()); signer.update(payload);
        Updater.Envelope e = new Updater.Envelope(); e.keyId = "test"; e.payload = Base64.getEncoder().encodeToString(payload);
        e.signature = Base64.getEncoder().encodeToString(signer.sign());
        return Updater.JSON.toJson(e).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    byte[] installed(String module) throws Exception { return Files.readAllBytes(game.resolve("mods/folkways-" + module + "-initial.jar")); }
    void assertOld() throws Exception { for (String module : config.modules) assertArrayEquals(jar(module, "old"), installed(module)); }
    Path pending() { return game.resolve(".folkways-updater/pending"); }

    Updater.Manifest directComponents() throws Exception {
        Updater.Manifest manifest = updater.manifest(envelope, config); manifest.bundle = null;
        for (var a : manifest.components) a.url = "https://github.com/" + manifest.repository + "/releases/download/nightly/" + a.file;
        envelope = sign(manifest); return manifest;
    }

    byte[] component(String url) {
        for (String module : Updater.MODULES) if (url.endsWith("folkways-" + module + "-new.jar")) return fresh.get(module);
        throw new IllegalArgumentException("Unexpected download URL: " + url);
    }

    @Test void stagesWithoutLiveReplacementThenInstallsOneCompleteSet() throws Exception {
        fixture(); updater.checkAndStage(); assertOld();
        updater.applyPending();
        for (String module : config.modules) assertArrayEquals(fresh.get(module), installed(module));
        assertFalse(Files.exists(pending()));
        assertEquals(2, Files.list(game.resolve(".folkways-updater/backup")).count());
        assertTrue(updater.checkAndStage().startsWith("Already installed"));
    }

    @Test void noZipManifestDownloadsOnlySelectedJarsThenAppliesOneBuild() throws Exception {
        fixture(List.of("core", "vanilla", "cookery"));
        Updater.Manifest manifest = directComponents(); assertEquals(5, manifest.components.size());
        List<String> downloaded = new ArrayList<>();
        updater = new Updater(game, (url, max) -> {
            if (url.equals(config.manifestUrl)) return envelope;
            downloaded.add(url); return component(url);
        });
        updater.checkAndStage(); assertOld();
        assertEquals(3, downloaded.size());
        assertFalse(downloaded.stream().anyMatch(url -> url.contains("-create-") || url.contains("-modulargolems-")));
        try (var paths = Files.list(pending())) { assertEquals(4, paths.count()); }
        assertFalse(Files.exists(pending().resolve("bundle.zip")));
        updater.applyPending();
        for (String module : config.modules) assertArrayEquals(fresh.get(module), installed(module));
    }

    @Test void failedComponentKeepsPreviousCompletePendingAndLiveJars() throws Exception {
        fixture(); directComponents();
        updater = new Updater(game, (url, max) -> url.equals(config.manifestUrl) ? envelope : component(url));
        updater.checkAndStage();
        byte[] priorEnvelope = Files.readAllBytes(pending().resolve("manifest.json"));
        byte[] priorCore = Files.readAllBytes(pending().resolve("folkways-core-new.jar"));
        byte[] priorVanilla = Files.readAllBytes(pending().resolve("folkways-vanilla-new.jar"));
        release(3); directComponents();
        updater = new Updater(game, (url, max) -> {
            if (url.equals(config.manifestUrl)) return envelope;
            byte[] bytes = component(url).clone(); if (url.contains("-vanilla-")) bytes[0] ^= 1; return bytes;
        });
        assertThrows(IOException.class, () -> updater.checkAndStage());
        assertArrayEquals(priorEnvelope, Files.readAllBytes(pending().resolve("manifest.json")));
        assertArrayEquals(priorCore, Files.readAllBytes(pending().resolve("folkways-core-new.jar")));
        assertArrayEquals(priorVanilla, Files.readAllBytes(pending().resolve("folkways-vanilla-new.jar")));
        assertFalse(Files.exists(game.resolve(".folkways-updater/candidate"))); assertOld();
        updater.applyPending();
        assertArrayEquals(priorCore, installed("core")); assertArrayEquals(priorVanilla, installed("vanilla"));
    }

    @Test void localModeEnabledMidComponentDownloadPreservesPreviousPending() throws Exception {
        fixture(); directComponents();
        updater = new Updater(game, (url, max) -> url.equals(config.manifestUrl) ? envelope : component(url));
        updater.checkAndStage(); byte[] prior = Files.readAllBytes(pending().resolve("manifest.json"));
        release(3); directComponents();
        updater = new Updater(game, (url, max) -> {
            if (url.equals(config.manifestUrl)) return envelope;
            byte[] bytes = component(url);
            if (url.contains("-vanilla-")) { config.localDevelopment = true; saveConfig(); }
            return bytes;
        });
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertOld();
        assertArrayEquals(prior, Files.readAllBytes(pending().resolve("manifest.json")));
        assertFalse(Files.exists(game.resolve(".folkways-updater/candidate")));
        assertTrue(updater.applyPending().startsWith("Local development")); assertOld();
    }

    @Test void directManifestRequiresAllFiveModulesAndUrlsEvenForUnselectedOnes() throws Exception {
        fixture(); Updater.Manifest manifest = directComponents();
        manifest.components.removeIf(a -> a.module.equals("create")); envelope = sign(manifest);
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertFalse(Files.exists(pending()));
        release(2); manifest = directComponents();
        manifest.components.stream().filter(a -> a.module.equals("create")).findFirst().orElseThrow().url = null;
        envelope = sign(manifest);
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertOld();
    }

    @Test void componentMirrorCanRecoverAfterCorruptPrimary() throws Exception {
        fixture(); config.networkPolicy = "direct-first"; saveConfig(); Updater.Manifest manifest = directComponents();
        for (var a : manifest.components) a.mirrors = List.of("https://pan.davisye.cn/d/fallback/" + a.file);
        envelope = sign(manifest); List<String> downloads = new ArrayList<>();
        updater = new Updater(game, (url, max) -> {
            if (url.equals(config.manifestUrl)) return envelope;
            downloads.add(url); byte[] bytes = component(url).clone();
            if (url.startsWith("https://github.com/")) bytes[0] ^= 1;
            return bytes;
        });
        updater.checkAndStage(); assertEquals(4, downloads.size());
        updater.applyPending();
        for (String module : config.modules) assertArrayEquals(fresh.get(module), installed(module));
    }

    @Test void domesticPolicyTriesExplicitSignedMirrorBeforeOfficialComponentUrl() throws Exception {
        fixture(); Updater.Manifest manifest = directComponents();
        for (var a : manifest.components) a.mirrors = List.of("https://pan.davisye.cn/d/mirror/" + a.file);
        envelope = sign(manifest); List<String> downloads = new ArrayList<>();
        updater = new Updater(game, (url, max) -> {
            if (url.equals(config.manifestUrl)) return envelope;
            downloads.add(url); assertTrue(url.startsWith("https://pan.davisye.cn/")); return component(url);
        });
        updater.checkAndStage(); assertEquals(2, downloads.size()); assertOld();
    }

    @Test void pendingCacheCommitInterruptedBetweenRenamesRestoresPreviousSet() throws Exception {
        fixture(); directComponents();
        updater = new Updater(game, (url, max) -> url.equals(config.manifestUrl) ? envelope : component(url));
        updater.checkAndStage();
        Files.move(pending(), game.resolve(".folkways-updater/pending.previous"), StandardCopyOption.ATOMIC_MOVE);
        updater.applyPending();
        for (String module : config.modules) assertArrayEquals(fresh.get(module), installed(module));
        assertFalse(Files.exists(game.resolve(".folkways-updater/pending.previous")));
    }

    @Test void damagedNewPendingNeverDeletesPreviousCompleteCache() throws Exception {
        fixture(); directComponents();
        updater = new Updater(game, (url, max) -> url.equals(config.manifestUrl) ? envelope : component(url));
        updater.checkAndStage(); byte[] prior = Files.readAllBytes(pending().resolve("manifest.json"));
        Path previous = game.resolve(".folkways-updater/pending.previous");
        Files.move(pending(), previous, StandardCopyOption.ATOMIC_MOVE);
        release(3); directComponents(); Files.createDirectories(pending());
        Files.write(pending().resolve("manifest.json"), envelope);
        Files.write(pending().resolve("folkways-core-new.jar"), fresh.get("core"));
        byte[] bad = fresh.get("vanilla").clone(); bad[0] ^= 1; Files.write(pending().resolve("folkways-vanilla-new.jar"), bad);
        assertThrows(IOException.class, () -> updater.applyPending());
        assertArrayEquals(prior, Files.readAllBytes(previous.resolve("manifest.json")));
        for (String module : config.modules) assertArrayEquals(jar(module, "new-2"), Files.readAllBytes(previous.resolve("folkways-" + module + "-new.jar")));
        assertTrue(Files.exists(pending())); assertOld();
    }

    @Test void newConfigPrefersDomesticMirrorAndExplicitLegacySettingIsPreserved() throws Exception {
        Updater.initialize(game); assertEquals("mirror-first", Config.load(game).networkPolicy);
        Files.writeString(game.resolve(".folkways-updater/config.json"), "{\"networkPolicy\":\"direct-first\"}");
        assertEquals("direct-first", Config.load(game).networkPolicy);
    }

    @Test void mirrorRouteFailureFallsBackToDirectAndValidationFailureAlsoFallsBack() throws Exception {
        Config c = new Config(); List<String> calls = new ArrayList<>();
        String direct = "https://github.com/IzakyL/folkways/releases/download/nightly/test.jar";
        String mirror = "https://github.davisye.cn/_github/github.com/IzakyL/folkways/releases/download/nightly/test.jar";
        Transport transport = new Transport(c, (uri, maximum) -> {
            calls.add(uri.toString());
            if (uri.getHost().equals("github.davisye.cn")) throw new IOException("Redirect without location");
            return new byte[]{1, 2};
        });
        assertArrayEquals(new byte[]{1, 2}, transport.fetch(direct, 2)); assertEquals(List.of(mirror, direct), calls);
        calls.clear();
        transport = new Transport(c, (uri, maximum) -> { calls.add(uri.toString()); return new byte[]{(byte)(uri.getHost().equals("github.davisye.cn") ? 0 : 1)}; });
        assertArrayEquals(new byte[]{1}, transport.fetchValidated(direct, 1, bytes -> { if (bytes[0] != 1) throw new IOException("bad digest"); }));
        assertEquals(List.of(mirror, direct), calls);
    }

    @Test void defaultLocalDevelopmentNeverTouchesNetwork() throws Exception {
        Updater.initialize(game);
        AtomicInteger calls = new AtomicInteger();
        Updater u = new Updater(game, (url, max) -> { calls.incrementAndGet(); throw new IOException("network must not run"); });
        assertTrue(u.checkAndStage().startsWith("Local development")); assertEquals(0, calls.get());
        assertTrue(u.applyPending().startsWith("Local development"));
        assertFalse(Files.exists(game.resolve("mods")));
    }

    @Test void markerAfterPredownloadBlocksApply() throws Exception {
        fixture(); updater.checkAndStage(); Files.writeString(game.resolve(".folkways-updater/LOCAL_DEVELOPMENT"), "");
        assertTrue(updater.applyPending().startsWith("Local development")); assertOld(); assertTrue(Files.exists(pending()));
    }

    @Test void switchingToLocalModeDuringDownloadCannotCommitPending() throws Exception {
        fixture();
        updater = new Updater(game, (url, max) -> {
            if (url.equals(config.manifestUrl)) return envelope;
            config.localDevelopment = true; saveConfig(); return bundle;
        });
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertOld(); assertFalse(Files.exists(pending()));
    }

    @Test void localCompilationAfterStageIsNeverOverwritten() throws Exception {
        fixture(); updater.checkAndStage(); byte[] local = jar("core", "local compile");
        Files.write(game.resolve("mods/folkways-core-initial.jar"), local);
        assertThrows(IOException.class, () -> updater.applyPending());
        assertArrayEquals(local, installed("core"));
        assertThrows(IOException.class, () -> updater.checkAndStage());
    }

    @Test void rejectsBadSignatureAndCorruptedBundle() throws Exception {
        fixture(); byte[] valid = envelope.clone();
        Updater.Envelope e = Updater.JSON.fromJson(new String(envelope), Updater.Envelope.class);
        byte[] signature = Base64.getDecoder().decode(e.signature); signature[0] ^= 1;
        e.signature = Base64.getEncoder().encodeToString(signature); envelope = Updater.JSON.toJson(e).getBytes();
        assertThrows(IOException.class, () -> updater.checkAndStage());
        envelope = valid; bundle[bundle.length / 2] ^= 1;
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertOld(); assertFalse(Files.exists(pending()));
    }

    @Test void rejectsTraversalAndIncompleteBundleEvenWhenSigned() throws Exception {
        fixture();
        Updater.Manifest m = updater.manifest(envelope, config);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("../outside.jar")); zip.write(new byte[]{1}); zip.closeEntry(); }
        bundle = bytes.toByteArray(); m.bundle.size = bundle.length; m.bundle.sha256 = Updater.sha(bundle); envelope = sign(m);
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertOld();
        bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(m.components.getFirst().file)); zip.write(fresh.get("core")); zip.closeEntry(); }
        bundle = bytes.toByteArray(); m.bundle.size = bundle.length; m.bundle.sha256 = Updater.sha(bundle); envelope = sign(m);
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertFalse(Files.exists(pending()));
    }

    @Test void renamedDuplicateJarIsDetectedFromItsOwnModsTable() throws Exception {
        fixture(); Files.write(game.resolve("mods/local-build.jar"), jar("core", "renamed local"));
        assertThrows(IOException.class, () -> updater.checkAndStage()); assertOld();
    }

    @Test void dependencyModIdsAreNotMistakenForJarIdentity() throws Exception {
        assertEquals(Set.of("vanilla"), Updater.ownModules("[[mods]]\nmodId='folkways_vanilla'\n[[dependencies.folkways_vanilla]]\nmodId=\"folkways_core\"\n"));
        assertThrows(IOException.class, () -> Updater.validateJar(jar("vanilla", "test"), "core"));
        fixture(); // vanilla fixture includes a core dependency, but explicit adoption succeeds.
        assertTrue(updater.status().contains("managed"));
    }

    @Test void rejectsSequenceRollbackAfterSuccessfulInstall() throws Exception {
        fixture(); updater.checkAndStage(); updater.applyPending(); release(1);
        assertThrows(IOException.class, () -> updater.checkAndStage());
    }

    @Test void switchingFromSourceAToBThenBackActuallyReinstallsA() throws Exception {
        fixture(); updater.checkAndStage(); updater.applyPending();
        byte[] sourceAEnvelope = envelope.clone(), sourceABundle = bundle.clone();
        Map<String, byte[]> sourceAJars = new HashMap<>(fresh);
        config.repository = "davisye/folkways"; saveConfig(); release(3);
        updater.checkAndStage(); updater.applyPending();
        config.repository = "IzakyL/folkways"; saveConfig(); envelope = sourceAEnvelope; bundle = sourceABundle;
        assertTrue(updater.checkAndStage().startsWith("Downloaded")); updater.applyPending();
        for (String module : config.modules) assertArrayEquals(sourceAJars.get(module), installed(module));
        assertTrue(updater.checkAndStage().startsWith("Already installed"));
    }

    @Test void committedButUnclearedPendingCacheDoesNotBlockNextLaunch() throws Exception {
        fixture(); updater.checkAndStage();
        byte[] savedEnvelope = Files.readAllBytes(pending().resolve("manifest.json"));
        Map<String, byte[]> staged = new HashMap<>();
        for (var a : updater.manifest(envelope, config).components) staged.put(a.file, Files.readAllBytes(pending().resolve(a.file)));
        updater.applyPending(); Files.createDirectories(pending()); Files.write(pending().resolve("manifest.json"), savedEnvelope);
        for (var e : staged.entrySet()) Files.write(pending().resolve(e.getKey()), e.getValue());
        assertTrue(updater.applyPending().startsWith("Already installed")); assertFalse(Files.exists(pending()));
    }

    @Test void interruptedMultiJarTransactionRollsBackBeforeLaunch() throws Exception {
        fixture(); updater.checkAndStage();
        Updater.State before = Updater.JSON.fromJson(Files.readString(game.resolve(".folkways-updater/state.json")), Updater.State.class);
        Files.createDirectories(game.resolve(".folkways-updater/backup"));
        Updater.Journal journal = new Updater.Journal(); journal.before = before;
        for (String module : config.modules) {
            Files.write(game.resolve(".folkways-updater/backup/" + module + ".jar"), installed(module));
            journal.after.put(module, new Updater.Owned(before.installed.get(module).file, Updater.sha(fresh.get(module)), fresh.get(module).length));
        }
        Files.writeString(game.resolve(".folkways-updater/journal.json"), Updater.JSON.toJson(journal));
        Files.write(game.resolve("mods/folkways-core-initial.jar"), fresh.get("core"));
        assertThrows(IOException.class, () -> updater.checkAndStage()); updater.recover(); assertOld();
        assertFalse(Files.exists(game.resolve(".folkways-updater/journal.json")));
    }

    @Test void restartRequestRequiresBothTokenAndAction() throws Exception {
        Path session = game.resolve("session"); Files.createDirectories(session);
        Files.writeString(session.resolve("restart-request.json"), "{\"token\":\"valid\",\"action\":\"restart\"}");
        assertTrue(Bootstrap.validRequest(session, "valid")); assertFalse(Bootstrap.validRequest(session, "wrong"));
        Files.writeString(session.resolve("restart-request.json"), "{\"token\":\"valid\",\"action\":\"shutdown\"}");
        assertFalse(Bootstrap.validRequest(session, "valid"));
    }

    @Test void unknownOrStillRunningChildBarrierBlocksReplacement() throws Exception {
        fixture();
        Path barrier = game.resolve(".folkways-updater/child.json"); Files.writeString(barrier, "{\"phase\":\"launching\"}");
        assertThrows(IOException.class, () -> Bootstrap.guardPreviousChild(updater));
        var current = ProcessHandle.current();
        Bootstrap.Child child = new Bootstrap.Child(); child.phase = "running"; child.pid = current.pid(); child.start = current.info().startInstant().orElseThrow().toString();
        Files.writeString(barrier, Updater.JSON.toJson(child));
        assertThrows(IOException.class, () -> Bootstrap.guardPreviousChild(updater)); assertTrue(Files.exists(barrier));
    }

    @Test void properProxyRouteAndUnapprovedHosts() throws Exception {
        assertEquals("https://github.davisye.cn/_github/api.github.com/repos/IzakyL/folkways?x=y", Transport.proxy("https://api.github.com/repos/IzakyL/folkways?x=y"));
        assertThrows(IOException.class, () -> Transport.safeUri("http://github.com/test"));
        assertThrows(IOException.class, () -> Transport.safeUri("https://github.com.evil.test/test"));
        assertThrows(IOException.class, () -> Transport.safeUri("https://token@github.com/test"));
    }

    @Test void windowsDirectoryJunctionIsRefusedBeforeCleanup() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        fixture(); Path target = game.resolve("junction-target"); Files.createDirectories(target);
        Path sentinel = target.resolve("keep.txt"); Files.writeString(sentinel, "must survive");
        Path junction = game.resolve(".folkways-updater/junction");
        Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()); assertEquals(0, p.waitFor(), output);
        try {
            assertTrue(Updater.unsafeLink(junction));
            assertThrows(IOException.class, () -> updater.clear(junction));
            assertEquals("must survive", Files.readString(sentinel));
        } finally { Files.delete(junction); }
    }
}
