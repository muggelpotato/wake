package dev.muggel.wake.core.commands.arguments;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** The registry key sets Wake's arguments accept, built once */
final class RegistryKeys {
    static final Set<NamespacedKey> BLOCKS = materials(Material::isBlock);
    static final Set<NamespacedKey> BOATS = materials(RegistryKeys::isBoat);
    static final Set<NamespacedKey> ENTITIES = Registry.ENTITY_TYPE.keyStream().collect(Collectors.toUnmodifiableSet()); // keyStream never asks UNKNOWN for its key
    private RegistryKeys() {}

    private static @NonNull Set<NamespacedKey> materials(@NonNull Predicate<Material> filter) {
        return Registry.MATERIAL.stream().filter(filter).map(Material::getKey).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isBoat(@NonNull Material material) {
        String key = material.getKey().getKey();
        return material.isItem() && (key.endsWith("_boat") || key.endsWith("_raft"));
    }
}