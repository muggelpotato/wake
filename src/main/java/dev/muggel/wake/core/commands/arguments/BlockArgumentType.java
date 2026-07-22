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

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A block by key, bare or namespaced ({@code stone}, {@code minecraft:stone}). <br>
 * Parses to the namespaced key string. <br>
 * Retrieve with {@code ctx.getArgument(name, String.class)}.
 */
public class BlockArgumentType implements CustomArgumentType<String, NamespacedKey> {
    private static final DynamicCommandExceptionType INVALID_BLOCK = ArgumentHelper.localizedException("commands.invalid_block");

    @Contract(value = " -> new", pure = true)
    public static @NonNull BlockArgumentType block() {
        return new BlockArgumentType();
    }

    @Override
    public @NonNull ArgumentType<NamespacedKey> getNativeType() {
        return ArgumentTypes.namespacedKey();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = readKey(reader);
        NamespacedKey key = ArgumentHelper.resolveKey(input);
        Material material = key != null ? Registry.MATERIAL.get(key) : null;
        if (material == null || !material.isBlock()) {
            reader.setCursor(start);
            throw INVALID_BLOCK.createWithContext(reader, input);
        }
        return key.toString();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        suggestBlocks(builder.getRemaining().toLowerCase(Locale.ROOT), builder::suggest);
        return builder.buildFuture();
    }

    // suggests bare names by default, namespaced when ":"
    public static void suggestBlocks(@NonNull String typed, @NonNull Consumer<String> out) {
        boolean namespaced = typed.indexOf(':') != -1;
        Registry.MATERIAL.stream()
                .filter(Material::isBlock)
                .map(m -> namespaced ? m.getKey().toString() : m.getKey().getKey())
                .filter(k -> k.startsWith(typed))
                .forEach(out);
    }

    public static @NonNull String readKey(@NonNull StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && isKeyChar(reader.peek())) {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    private static boolean isKeyChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || c == '_' || c == '.' || c == '-' || c == ':' || c == '/';
    }
}