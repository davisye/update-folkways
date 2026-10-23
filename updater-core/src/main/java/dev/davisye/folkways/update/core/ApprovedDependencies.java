package dev.davisye.folkways.update.core;

import java.io.IOException;
import java.util.*;

/** Audited immutable Modrinth versions only. No project search or URLs supplied by a manifest. */
final class ApprovedDependencies {
    record Pin(String id, String project, String versionId, String name, String url, long size, String sha512, Map<String,String> mods) {
        Pin { mods = Map.copyOf(mods); }
        String api() { return "https://api.modrinth.com/v2/version/" + versionId; }
        void validate(GitHubFolkways.Modrinth proof) throws IOException {
            if (proof == null || !project.equals(proof.project_id) || !versionId.equals(proof.id)
                || proof.game_versions == null || !proof.game_versions.contains("1.21.1") || proof.loaders == null || !proof.loaders.contains("neoforge")
                || proof.files == null || proof.files.size() > 64) throw new IOException("Official dependency version metadata mismatch: " + id);
            var primary = proof.files.stream().filter(f -> f != null && f.primary).toList();
            if (primary.size() != 1) throw new IOException("Official dependency must have one primary file: " + id);
            var file = primary.getFirst();
            // Earlier schema-1 LDLib2 plans did not record filename. Its fixed URL already binds the filename.
            if (!url.equals(file.url) || size != file.size || file.hashes == null || !sha512.equals(file.hashes.get("sha512"))
                || file.filename != null && !name.equals(file.filename)) throw new IOException("Official dependency differs from its audited pin: " + id);
        }
        void validateJar(byte[] bytes, ModMetadata metadata) throws Exception {
            GitHubFolkways.verifyHash(bytes, "SHA-512", sha512, size);
            if (!"javafml".equals(metadata.loader) || !mods.equals(metadata.mods)) throw new IOException("Audited dependency has unexpected Mod identity/version: " + id);
        }
    }
    static final class Catalog {
        private final Map<String,Pin> pins = new TreeMap<>();
        Catalog(Pin... entries) {
            for (Pin pin : entries) {
                if (pins.putIfAbsent(pin.id, pin) != null || !pin.mods.containsKey(pin.id)) throw new IllegalArgumentException("Duplicate or invalid approved dependency");
            }
        }
        Pin get(String id) { return pins.get(id); }
        Set<String> ids() { return Collections.unmodifiableSet(pins.keySet()); }
        int size() { return pins.size(); }
        boolean allowsApi(String url) { return pins.values().stream().anyMatch(p -> p.api().equals(url)); }
    }
    static final Catalog PRODUCTION = new Catalog(
        new Pin("ldlib2", "B1CBVXHX", "bpcbGDy4", "ldlib2-neoforge-1.21.1-2.2.37-all.jar",
            "https://cdn.modrinth.com/data/B1CBVXHX/versions/bpcbGDy4/ldlib2-neoforge-1.21.1-2.2.37-all.jar", 7215985,
            "46114d746ed045bf9bfdab87877436bc24f3120362b89256aa4749ff794691988f58b694208aca4297f5042fd24cb3387590b9bc20b28d708c6e246aaa669c7b", Map.of("ldlib2", "2.2.37")),
        new Pin("create", "LNytGWDc", "UjX6dr61", "create-1.21.1-6.0.10.jar",
            "https://cdn.modrinth.com/data/LNytGWDc/versions/UjX6dr61/create-1.21.1-6.0.10.jar", 19123767,
            "11cc8fc049d2f67f6548c7abfada6b82a3adb5c7ca410a742de04bbca76e03862c518721b88d806f6e6d768a4d68531fdb903a85859b25d1484d550cc7bafd4b", Map.of("create", "6.0.10")),
        new Pin("modulargolems", "gtGDlsAD", "lcKGfUcI", "modulargolems-3.1.44.jar",
            "https://cdn.modrinth.com/data/gtGDlsAD/versions/lcKGfUcI/modulargolems-3.1.44.jar", 3685087,
            "f479bbd5690ec8cf71fb5247889e399a0bd40a11bc1bf1d0c3c8411efdfca65e5b8ed5693ec9bcc80bb7a17016dab3fe254c16b8a909058b085055902749fa2e", Map.of("modulargolems", "3.1.44")),
        new Pin("l2library", "4Vh3BQ3F", "7GS7JBkX", "l2library-3.0.9.jar",
            "https://cdn.modrinth.com/data/4Vh3BQ3F/versions/7GS7JBkX/l2library-3.0.9.jar", 982934,
            "e13aaa2203b4b927ea946ef3d0f5d3fdb006179fd41e8cb6c1bd1e06421588aab8af6f6096150aa3f01d32e30a3dc3c546ceb71d97fdd6ffc7b476b54bbb6e74", Map.of("l2library", "3.0.9")),
        new Pin("kaleidoscope_cookery", "v17FatAc", "g8w0b2ly", "kaleidoscopecookery-1.6.0-neoforge+mc1.21.1.jar",
            "https://cdn.modrinth.com/data/v17FatAc/versions/g8w0b2ly/kaleidoscopecookery-1.6.0-neoforge%2Bmc1.21.1.jar", 3496661,
            "b562f73395f70d39fb26d89ca62a5b5cebe7d66b11e8930217f0daf03fdcea617629728f55e4c8ced690406204c44462a8a568e5c15f05f5940274904ce0b77f", Map.of("kaleidoscope_cookery", "1.6.0-neoforge+mc1.21.1"))
    );
}
