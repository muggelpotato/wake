package dev.muggel.wake.features.obu.contexts;

import dev.muggel.wake.features.obu.protocol.OBUSetting;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record OBUContext(String name, ContextType type, @Nullable UUID ownerUuid, List<OBUSetting> settings) {
    public enum ContextType { SERVER, SANDBOX }
    public OBUContext {
        settings = List.copyOf(settings);
    }

    public boolean isSandbox() {
        return type == ContextType.SANDBOX;
    }
}