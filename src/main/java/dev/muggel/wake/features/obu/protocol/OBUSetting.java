package dev.muggel.wake.features.obu.protocol;

import org.jspecify.annotations.NonNull;

import java.util.List;

public record OBUSetting(OBUDefinition definition, List<String> args) {
    public OBUSetting {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public @NonNull String getUniqueKey() {
        return definition.generateUniqueKey(args);
    }
}