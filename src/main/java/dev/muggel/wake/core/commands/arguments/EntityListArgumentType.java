package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A greedy, space- or comma-separated list of entity types or UUIDs. <br>
 * Parses to a comma-joined string of namespaced keys or UUIDs. <br>
 * Retrieve with {@code ctx.getArgument(name, String.class)}.
 */
public class EntityListArgumentType extends GreedyListArgumentType {
    private static final DynamicCommandExceptionType INVALID_ENTITY = ArgumentHelper.localizedException("commands.invalid_entity");

    @Contract(value = " -> new", pure = true)
    public static @NonNull EntityListArgumentType entityList() {
        return new EntityListArgumentType();
    }

    @Override
    protected @Nullable String canonicalize(@NonNull String entry) {
        try {
            UUID.fromString(entry);
            return entry;
        } catch (IllegalArgumentException ignored) {}
        NamespacedKey key = ArgumentHelper.resolveKey(entry);
        if (key != null) {
            EntityType type = Registry.ENTITY_TYPE.get(key);
            if (type != null && type != EntityType.UNKNOWN) {
                return key.toString();
            }
        }
        return null;
    }

    @Override
    protected void suggestEntry(@NonNull String typed, @NonNull Consumer<String> out) {
        boolean namespaced = typed.indexOf(':') != -1;
        Registry.ENTITY_TYPE.stream()
                .filter(e -> e != EntityType.UNKNOWN)
                .map(e -> namespaced ? e.getKey().toString() : e.getKey().getKey())
                .filter(k -> k.startsWith(typed))
                .forEach(out);
    }

    @Override
    protected @NonNull DynamicCommandExceptionType invalidEntryException() {
        return INVALID_ENTITY;
    }
}