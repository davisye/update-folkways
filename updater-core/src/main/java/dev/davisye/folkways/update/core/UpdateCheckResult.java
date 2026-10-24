package dev.davisye.folkways.update.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** A check's existing human-readable status and the stable identity of a verified pending update. */
public record UpdateCheckResult(String message, String reminderKey) {
    public UpdateCheckResult { Objects.requireNonNull(message); }
    static UpdateCheckResult status(String message) { return new UpdateCheckResult(message, null); }
    static UpdateCheckResult pending(String message, String namespace, Object... identity) {
        return new UpdateCheckResult(message, namespace + ":" + hash(Updater.JSON.toJson(identity)));
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required by Java", impossible); }
    }
}
