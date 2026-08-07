package dev.muggel.wake.features.obu.contexts;

import dev.muggel.wake.features.obu.protocol.OBUSetting;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record OBUContext(@NonNull String name, @NonNull ContextType type, @Nullable UUID ownerUuid, @NonNull @Unmodifiable List<OBUSetting> settings) {
    public enum ContextType { SERVER, SANDBOX }
    public OBUContext {
        settings = List.copyOf(settings);
    }

    public boolean isSandbox() {
        return type == ContextType.SANDBOX;
    }
}