package dev.muggel.wake.core.sync;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tells the other servers what moved. <br>
 * Scope + exact rows in a mirrored table that changed (if possible). <br>
 * The receiver reads back only those and nothing else.
 */
record SyncMessage(@NonNull String scope, @Nullable String table, @Nullable List<String> keys) {
    private static final String FIELD = "|";
    private static final String KEY_SEPARATOR = "\037";
    private static final Pattern FIELDS = Pattern.compile(Pattern.quote(FIELD));
    private static final Pattern KEYS = Pattern.compile(Pattern.quote(KEY_SEPARATOR));
    private static final int MAX_KEYS_PER_MESSAGE = 500;
    SyncMessage {
        keys = keys == null ? null : List.copyOf(keys);
    }

    /** The payload naming {@code keys} in {@code table}, or {@code scope} alone when it cannot carry them */
    static @NonNull String encode(@NonNull String scope, @NonNull String table, @NonNull Set<String> keys) {
        if (keys.isEmpty() || keys.size() > MAX_KEYS_PER_MESSAGE || keys.stream().anyMatch(key -> key.contains(KEY_SEPARATOR))) {
            return scope;
        }
        return scope + FIELD + table + FIELD + String.join(KEY_SEPARATOR, keys);
    }

    /** A payload without the table and keys is not malformed */
    static @NonNull SyncMessage parse(@NonNull String payload) {
        String[] parts = FIELDS.split(payload, 3);
        if (parts.length < 3) {
            return new SyncMessage(parts[0], null, null);
        }
        return new SyncMessage(parts[0], parts[1], List.of(KEYS.split(parts[2], -1)));
    }

    /** Tags a payload with its sender to ignore own announcements */
    static @NonNull String addressed(@NonNull String senderId, @NonNull String payload) {
        return senderId + FIELD + payload;
    }

    /** The payload or {@code null} when it is malformed or this server sent it */
    static @Nullable String payloadFor(@NonNull String wire, @NonNull String selfId) {
        int separator = wire.indexOf(FIELD);
        if (separator < 0 || wire.substring(0, separator).equals(selfId)) {
            return null;
        }
        return wire.substring(separator + 1);
    }
}