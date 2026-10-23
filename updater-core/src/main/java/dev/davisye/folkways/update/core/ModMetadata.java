package dev.davisye.folkways.update.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Manifest;
import java.util.regex.*;
import java.util.zip.*;

/** Bounded reading of the simple Mod/required-dependency tables used by the audited release JARs. */
final class ModMetadata {
    final Map<String,String> mods = new TreeMap<>(), provided = new TreeMap<>();
    final List<Dependency> required = new ArrayList<>();
    String loader;
    static final class Dependency { String owner, id, range; }
    private static final Pattern ASSIGN = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([\"'])(.*?)\\2\\s*(?:#.*)?");

    static ModMetadata read(byte[] jar) throws Exception { return read(jar, 0, new long[2]); }
    static ModMetadata installed(java.nio.file.Path path) throws Exception {
        ModMetadata result = new ModMetadata();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml"); if (entry == null) entry = zip.getEntry("META-INF/mods.toml");
            if (entry == null) return result;
            byte[] toml; try (InputStream input = zip.getInputStream(entry)) { toml = input.readNBytes(1024 * 1024 + 1); }
            if (toml.length > 1024 * 1024) throw new IOException("Installed Mod metadata exceeds limit");
            String version = null; ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null) try (InputStream input = zip.getInputStream(manifest)) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1); if (bytes.length > 1024 * 1024) throw new IOException("Installed Mod manifest exceeds limit");
                version = new Manifest(new ByteArrayInputStream(bytes)).getMainAttributes().getValue("Implementation-Version");
            }
            result.parse(new String(toml, StandardCharsets.UTF_8), version, false);
        }
        return result;
    }
    private static ModMetadata read(byte[] jar, int depth, long[] budget) throws Exception {
        if (depth > 3) throw new IOException("Nested Mod metadata exceeds depth limit");
        Map<String,byte[]> relevant = new LinkedHashMap<>(); String version = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++budget[0] > 75000) throw new IOException("Mod archive has too many entries");
                byte[] bytes = zip.readNBytes(16 * 1024 * 1024 + 1); budget[1] += bytes.length;
                if (bytes.length > 16 * 1024 * 1024 || budget[1] > 384L * 1024 * 1024) throw new IOException("Mod archive expands beyond limits");
                String name = entry.getName();
                if (name.equals("META-INF/MANIFEST.MF")) version = new Manifest(new ByteArrayInputStream(bytes)).getMainAttributes().getValue("Implementation-Version");
                if (name.equals("META-INF/neoforge.mods.toml") || name.equals("META-INF/mods.toml") || name.equals("META-INF/jarjar/metadata.json")
                    || name.matches("META-INF/jarjar/[A-Za-z0-9._+\\-]+\\.jar")) {
                    if (relevant.putIfAbsent(name, bytes) != null) throw new IOException("Duplicate Mod metadata/archive entry");
                }
            }
        }
        ModMetadata result = new ModMetadata();
        byte[] toml = relevant.getOrDefault("META-INF/neoforge.mods.toml", relevant.get("META-INF/mods.toml"));
        if (toml != null) {
            if (toml.length > 1024 * 1024) throw new IOException("Mod TOML exceeds limit");
            result.parse(new String(toml, StandardCharsets.UTF_8), version);
        }
        byte[] jarjar = relevant.get("META-INF/jarjar/metadata.json");
        if (jarjar != null) {
            if (jarjar.length > 1024 * 1024) throw new IOException("JarJar metadata exceeds limit");
            var json = com.google.gson.JsonParser.parseString(new String(jarjar, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("jars") || json.getAsJsonArray("jars").size() > 64) throw new IOException("Invalid nested Mod catalogue");
            for (var item : json.getAsJsonArray("jars")) {
                String path = item.getAsJsonObject().get("path").getAsString();
                if (!path.matches("META-INF/jarjar/[A-Za-z0-9._+\\-]+\\.jar") || !relevant.containsKey(path)) throw new IOException("Invalid nested Mod path");
                ModMetadata nested = read(relevant.get(path), depth + 1, budget);
                for (var mod : nested.mods.entrySet()) result.provided.merge(mod.getKey(), mod.getValue(), ModMetadata::higher);
                for (var mod : nested.provided.entrySet()) result.provided.merge(mod.getKey(), mod.getValue(), ModMetadata::higher);
                result.required.addAll(nested.required);
            }
        }
        return result;
    }
    private void parse(String toml, String manifestVersion) throws IOException {
        parse(toml, manifestVersion, true);
    }
    private void parse(String toml, String manifestVersion, boolean dependencies) throws IOException {
        String table = "", multiline = null; Map<String,String> values = new HashMap<>();
        for (String raw : toml.split("\\R")) {
            String line = raw.strip();
            if (multiline != null) { if (line.contains(multiline)) multiline = null; continue; }
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (line.startsWith("[")) {
                finish(table, values, manifestVersion); values.clear(); table = line.replaceFirst("\\s*#.*$", "").replaceAll("\\s+", "");
                if (!dependencies && table.contains("dependencies")) { table = "ignored-installed-dependency"; continue; }
                table = table.replaceAll("^\\[\\[dependencies\\.([\"'])([a-z][a-z0-9_]*)\\1\\]\\]$", "[[dependencies.$2]]");
                if (table.contains("dependencies") && !table.matches("\\[\\[dependencies\\.[a-z][a-z0-9_]*\\]\\]")) throw new IOException("Unsupported dependency table syntax");
                continue;
            }
            for (String quote : List.of("\"\"\"", "'''")) {
                int first = line.indexOf(quote); if (first >= 0) { if (line.indexOf(quote, first + 3) < 0) multiline = quote; line = ""; break; }
            }
            Matcher match = ASSIGN.matcher(line);
            if (match.matches()) {
                if (values.putIfAbsent(match.group(1), match.group(3)) != null) throw new IOException("Duplicate Mod TOML key");
                if (table.isEmpty() && match.group(1).equals("modLoader")) loader = match.group(3);
            } else if (line.matches("mandatory\\s*=\\s*true\\s*(?:#.*)?")) values.put("type", "required");
            else if (line.matches("mandatory\\s*=\\s*false\\s*(?:#.*)?")) values.put("type", "optional");
        }
        finish(table, values, manifestVersion);
    }
    private void finish(String table, Map<String,String> values, String manifestVersion) throws IOException {
        if (table.startsWith("[[dependencies.") && !Set.of("required", "optional", "incompatible", "discouraged").contains(values.getOrDefault("type", ""))) throw new IOException("Unsupported dependency requirement type");
        if (table.matches("\\[\\[\\s*mods\\s*\\]\\]")) {
            String id = values.get("modId"), version = values.get("version");
            if ("${file.jarVersion}".equals(version)) version = manifestVersion;
            if (id == null || !id.matches("[a-z][a-z0-9_]{1,63}") || version == null || version.length() > 256 || mods.putIfAbsent(id, version) != null) throw new IOException("Invalid or duplicate Mod identity");
        }
        if (table.matches("\\[\\[dependencies\\.[a-z][a-z0-9_]*\\]\\]") && "required".equals(values.get("type"))) {
            Dependency value = new Dependency(); value.owner = table.substring(15, table.length() - 2); value.id = values.get("modId"); value.range = values.get("versionRange");
            if (value.id == null || !value.id.matches("[a-z][a-z0-9_]{1,63}") || value.range == null || value.range.length() > 256) throw new IOException("Invalid required dependency metadata"); required.add(value);
        }
    }
    static boolean matches(String version, String range) throws IOException {
        if (version == null || range == null || version.length() > 256 || range.length() > 256) return false;
        if (range.startsWith("[") && range.endsWith("]") && !range.contains(",")) return compare(version, range.substring(1, range.length() - 1)) == 0;
        if (!(range.startsWith("[") || range.startsWith("(")) || !(range.endsWith("]") || range.endsWith(")"))) throw new IOException("Unsupported dependency version range: " + range);
        String[] ends = range.substring(1, range.length() - 1).split(",", -1);
        if (ends.length != 2) throw new IOException("Unsupported dependency version range: " + range);
        int lower = ends[0].isBlank() ? 1 : compare(version, ends[0].strip());
        int upper = ends[1].isBlank() ? -1 : compare(version, ends[1].strip());
        return (lower > 0 || lower == 0 && range.startsWith("[")) && (upper < 0 || upper == 0 && range.endsWith("]"));
    }
    private static String higher(String a, String b) { try { return compare(a, b) >= 0 ? a : b; } catch (IOException e) { return a; } }
    static int compare(String a, String b) throws IOException {
        // Audit targets use numeric Maven versions with loader/build suffixes. Unknown syntax fails closed.
        Pattern version = Pattern.compile("([0-9]+(?:\\.[0-9]+)*)(?:[-+][A-Za-z0-9._+\\-]+)?");
        Matcher am = version.matcher(a), bm = version.matcher(b);
        if (!am.matches() || !bm.matches()) throw new IOException("Unsupported dependency version syntax");
        for (String suffix : List.of(a.substring(am.group(1).length()), b.substring(bm.group(1).length()))) {
            if (!suffix.isEmpty() && !suffix.matches("(?i)(?:-neoforge)?(?:\\+mc1\\.21\\.1)?") && !suffix.matches("(?i)-(?:alpha|beta|rc|snapshot)[0-9._-]*")) throw new IOException("Unsupported dependency version qualifier");
        }
        String[] av = am.group(1).split("\\."), bv = bm.group(1).split("\\.");
        for (int i = 0; i < Math.max(av.length, bv.length); i++) {
            var x = new java.math.BigInteger(i < av.length ? av[i] : "0"); var y = new java.math.BigInteger(i < bv.length ? bv[i] : "0");
            int comparison = x.compareTo(y); if (comparison != 0) return comparison;
        }
        boolean apre = a.toLowerCase(Locale.ROOT).matches(".*-(?:alpha|beta|rc|snapshot)[0-9._-]*.*");
        boolean bpre = b.toLowerCase(Locale.ROOT).matches(".*-(?:alpha|beta|rc|snapshot)[0-9._-]*.*");
        if (apre != bpre) return apre ? -1 : 1;
        if (apre && !a.equals(b)) throw new IOException("Unsupported prerelease dependency comparison");
        return 0;
    }
}
