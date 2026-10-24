package dev.davisye.folkways.update.core;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Display-time deduplication; independent of the updater's download/install lock. */
public final class UpdateReminderHistory {
    private static final int MAX_ENTRIES = 256, MAX_BYTES = 32768;
    private final Path gameDir;
    private final Consumer<IOException> onError;
    private final Set<String> seen = new HashSet<>();
    private final LinkedHashSet<String> recent = new LinkedHashSet<>();
    private boolean loaded, reported, disabled;
    private Updater paths;
    private Path file;

    public UpdateReminderHistory(Path gameDir, Consumer<IOException> onError) {
        this.gameDir = Objects.requireNonNull(gameDir); this.onError = Objects.requireNonNull(onError);
    }

    /** Call only when a current result is actually about to be displayed. Null represents no update. */
    public synchronized boolean first(String reminderKey) {
        if (reminderKey == null) return false;
        if (!loaded) {
            loaded = true;
            try { load(); }
            catch (IOException | RuntimeException e) { unavailable(e); }
        }
        String hash = UpdateCheckResult.hash(reminderKey);
        if (!seen.add(hash)) return false;
        recent.remove(hash); recent.add(hash);
        while (recent.size() > MAX_ENTRIES) recent.remove(recent.iterator().next());
        if (!disabled) try { persist(); } catch (IOException | RuntimeException e) { unavailable(e); }
        return true;
    }

    private void load() throws IOException {
        paths = new Updater(gameDir); file = paths.home.resolve("notified-updates.json"); paths.safe(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) throw new IOException("Unsafe update reminder history");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Update reminder history exceeds limit");
        LinkedHashSet<String> validated = new LinkedHashSet<>(); Set<String> fields = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setLenient(false); reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw new IOException("Duplicate update reminder history field");
                if (field.equals("schema")) {
                    if (reader.peek() != JsonToken.NUMBER || !reader.nextString().equals("1")) throw new IOException("Invalid update reminder history schema");
                } else if (field.equals("hashes")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.STRING) throw new IOException("Invalid update reminder hash");
                        String hash = reader.nextString();
                        if (!hash.matches("[0-9a-f]{64}") || !validated.add(hash) || validated.size() > MAX_ENTRIES) throw new IOException("Invalid or excessive update reminder hashes");
                    }
                    reader.endArray();
                } else throw new IOException("Unknown update reminder history field");
            }
            reader.endObject();
            if (!fields.equals(Set.of("schema", "hashes")) || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid update reminder history");
        }
        recent.addAll(validated); seen.addAll(validated);
    }

    private void persist() throws IOException {
        paths.directory(paths.home); paths.safe(file);
        byte[] bytes = Updater.JSON.toJson(Map.of("schema", 1, "hashes", new ArrayList<>(recent))).getBytes(StandardCharsets.UTF_8);
        Path temporary = file.resolveSibling("notified-updates-" + UUID.randomUUID() + ".tmp"); paths.safe(temporary);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            paths.safe(file); paths.safe(temporary);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { paths.safe(temporary); Files.deleteIfExists(temporary); }
    }

    private void unavailable(Exception cause) {
        disabled = true;
        if (reported) return;
        reported = true;
        IOException failure = cause instanceof IOException io ? io : new IOException("Invalid update reminder history", cause);
        try { onError.accept(failure); } catch (RuntimeException ignored) { /* Reminder persistence must not interrupt update checks or the game. */ }
    }
}
