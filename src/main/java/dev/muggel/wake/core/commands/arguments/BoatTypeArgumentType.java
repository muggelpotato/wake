package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Boat/Raft type by key, bare or namespaced ({@code oak_boat}, {@code minecraft:bamboo_raft}). <br>
 * Parses to the namespaced key string. <br>
 * Retrieve with {@code ctx.getArgument(name, String.class)}.
 */
public class BoatTypeArgumentType implements CustomArgumentType<String, NamespacedKey> {
    private static final DynamicCommandExceptionType INVALID_BOAT = ArgumentHelper.localizedException("commands.invalid_boat");
    private static final List<String> BOAT_KEYS = Registry.MATERIAL.stream()
            .filter(BoatTypeArgumentType::isBoatType)
            .map(m -> m.getKey().getKey())
            .toList();

    @Contract(value = " -> new", pure = true)
    public static @NonNull BoatTypeArgumentType boatType() {
        return new BoatTypeArgumentType();
    }

    private static boolean isBoatType(@NonNull Material material) {
        if (!material.isItem()) return false;
        String key = material.getKey().getKey();
        return key.endsWith("_boat") || key.endsWith("_raft");
    }

    @Override
    public @NonNull ArgumentType<NamespacedKey> getNativeType() {
        return ArgumentTypes.namespacedKey();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = BlockArgumentType.readKey(reader);
        NamespacedKey key = ArgumentHelper.resolveKey(input);
        Material material = key != null ? Registry.MATERIAL.get(key) : null;
        if (material == null || !isBoatType(material)) {
            reader.setCursor(start);
            throw INVALID_BOAT.createWithContext(reader, input);
        }
        return key.toString();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        boolean namespaced = typed.indexOf(':') != -1;
        for (String key : BOAT_KEYS) {
            String shown = namespaced ? "minecraft:" + key : key;
            if (shown.contains(typed)) {
                builder.suggest(shown);
            }
        }
        return builder.buildFuture();
    }
}