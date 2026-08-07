package dev.muggel.wake.features.obu.clients;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientRegistry {
    public enum ClientState {
        DRIVEN,
        UNSUPPORTED,
        UNKNOWN
    }

    private final Map<UUID, ClientState> clients = new ConcurrentHashMap<>();

    public boolean claim(@NonNull UUID uuid, @NonNull ClientState verdict) {
        ClientState held = clients.putIfAbsent(uuid, verdict);
        return held == null || (held == ClientState.UNKNOWN && clients.replace(uuid, ClientState.UNKNOWN, verdict));
    }

    public void reopen(@NonNull UUID uuid) {
        clients.put(uuid, ClientState.UNKNOWN);
    }

    public boolean isDriven(@NonNull UUID uuid) {
        return clients.get(uuid) == ClientState.DRIVEN;
    }

    public @Nullable ClientState state(@NonNull UUID uuid) {
        return clients.get(uuid);
    }

    public void forget(@NonNull UUID uuid) {
        clients.remove(uuid);
    }
}