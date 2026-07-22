package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A greedy, space- or comma-separated list of blocks. <br>
 * Each validated like {@link BlockArgumentType}. <br>
 * Parses to a comma-joined string of namespaced keys. <br>
 * Retrieve with {@code ctx.getArgument(name, String.class)}.
 */
public class BlockListArgumentType extends GreedyListArgumentType {
    private static final DynamicCommandExceptionType INVALID_BLOCK = ArgumentHelper.localizedException("commands.invalid_block");

    @Contract(value = " -> new", pure = true)
    public static @NonNull BlockListArgumentType blockList() {
        return new BlockListArgumentType();
    }

    @Override
    protected @Nullable String canonicalize(@NonNull String entry) {
        NamespacedKey key = ArgumentHelper.resolveKey(entry);
        Material material = key != null ? Registry.MATERIAL.get(key) : null;
        return material != null && material.isBlock() ? key.toString() : null;
    }

    @Override
    protected void suggestEntry(@NonNull String typed, @NonNull Consumer<String> out) {
        BlockArgumentType.suggestBlocks(typed, out);
    }

    @Override
    protected @NonNull DynamicCommandExceptionType invalidEntryException() {
        return INVALID_BLOCK;
    }
}