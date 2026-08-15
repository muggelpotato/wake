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

    private record Client(@NonNull ClientState state, int version) {}

    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();

    public boolean claim(@NonNull UUID uuid, @NonNull ClientState verdict, int version) {
        Client claimed = new Client(verdict, version);
        Client held = clients.putIfAbsent(uuid, claimed);
        return held == null || (held.state() == ClientState.UNKNOWN && clients.replace(uuid, held, claimed));
    }

    public void reopen(@NonNull UUID uuid) {
        clients.put(uuid, new Client(ClientState.UNKNOWN, 0));
    }

    public boolean isDriven(@NonNull UUID uuid) {
        return state(uuid) == ClientState.DRIVEN;
    }

    public int versionOf(@NonNull UUID uuid) {
        Client held = clients.get(uuid);
        return held == null ? 0 : held.version();
    }

    public @Nullable ClientState state(@NonNull UUID uuid) {
        Client held = clients.get(uuid);
        return held == null ? null : held.state();
    }

    public void forget(@NonNull UUID uuid) {
        clients.remove(uuid);
    }
}