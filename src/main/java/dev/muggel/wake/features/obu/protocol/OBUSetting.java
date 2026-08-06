package dev.muggel.wake.features.obu.protocol;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record OBUSetting(@NonNull OBUDefinition definition, @NonNull @Unmodifiable List<String> args) {
    public OBUSetting {
        args = List.copyOf(args);
    }

    public static @Nullable OBUSetting of(@NonNull OBUDefinition definition, @NonNull List<String> args) {
        List<SettingType> types = definition.types();
        if (args.size() < types.size()) {
            return null;
        }
        List<String> canonical = new ArrayList<>(types.size());
        try {
            for (int i = 0; i < types.size(); i++) {
                canonical.add(types.get(i).canonical(args.get(i)));
            }
        } catch (IllegalArgumentException notWritable) {
            return null;
        }
        return new OBUSetting(definition, canonical);
    }

    public @NonNull String uniqueKey() {
        return definition.uniqueKey(args);
    }
}