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
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * One key out of a known set. <br>
 * Bare or namespaced, case-insensitive. <br>
 * Parses to the namespaced key string. <br>
 * Retrieve with {@code ctx.getArgument(name, String.class)}.
 */
public final class KeyArgumentType implements CustomArgumentType<String, NamespacedKey> {
    private final Supplier<Set<NamespacedKey>> keys;
    private final DynamicCommandExceptionType invalid;
    private KeyArgumentType(@NonNull Supplier<Set<NamespacedKey>> keys, @NonNull String messageKey) {
        this.keys = keys;
        this.invalid = ArgumentHelper.localizedException(messageKey);
    }

    @Contract(value = " -> new", pure = true)
    public static @NonNull KeyArgumentType block() {
        return new KeyArgumentType(() -> RegistryKeys.BLOCKS, "commands.invalid_block");
    }

    @Contract(value = " -> new", pure = true)
    public static @NonNull KeyArgumentType boatType() {
        return new KeyArgumentType(() -> RegistryKeys.BOATS, "commands.invalid_boat");
    }

    @Contract(value = "_, _ -> new", pure = true)
    public static @NonNull KeyArgumentType of(@NonNull Supplier<Set<NamespacedKey>> keys, @NonNull String messageKey) {
        return new KeyArgumentType(keys, messageKey);
    }

    @Override
    public @NonNull ArgumentType<NamespacedKey> getNativeType() {
        return ArgumentTypes.namespacedKey();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = readToken(reader);
        NamespacedKey key = ArgumentHelper.resolveKey(input);
        if (key == null || !keys.get().contains(key)) {
            reader.setCursor(start);
            throw invalid.createWithContext(reader, input);
        }
        return key.toString();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        ArgumentHelper.suggestKeys(builder.getRemaining().toLowerCase(Locale.ROOT), keys.get(), builder::suggest);
        return builder.buildFuture();
    }

    private static @NonNull String readToken(@NonNull StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }
}