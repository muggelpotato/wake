package dev.muggel.wake.core.commands.arguments;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The registry key sets Wake's arguments accept, built once */
final class RegistryKeys {
    static final Set<NamespacedKey> BLOCKS = keys(Registry.MATERIAL.stream().filter(Material::isBlock));
    static final Set<NamespacedKey> BOATS = keys(Registry.MATERIAL.stream().filter(RegistryKeys::isBoat));
    static final Set<NamespacedKey> ENTITIES = keys(Registry.ENTITY_TYPE.stream());
    private RegistryKeys() {}

    private static <T extends Keyed> @NonNull Set<NamespacedKey> keys(@NonNull Stream<T> values) {
        return values.map(Keyed::getKey).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isBoat(@NonNull Material material) {
        String key = material.getKey().getKey();
        return material.isItem() && (key.endsWith("_boat") || key.endsWith("_raft"));
    }
}