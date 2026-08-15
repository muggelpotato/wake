package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.core.commands.CommandHelper;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A greedy, space- or comma-separated list of keys. <br>
 * Parses to a comma-joined string of keys. <br>
 * Retrieve with {@code ctx.getArgument(name, String.class)}.
 */
public final class KeyListArgumentType implements CustomArgumentType<String, String> {
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,]+");
    private static final KeyListArgumentType BLOCK_LIST = new KeyListArgumentType(() -> RegistryKeys.BLOCKS, false, "commands.invalid_block");
    private static final KeyListArgumentType ENTITY_LIST = new KeyListArgumentType(() -> RegistryKeys.ENTITIES, true, "commands.invalid_entity");
    private final Supplier<Set<NamespacedKey>> keys;
    private final boolean uuids;
    private final DynamicCommandExceptionType invalid;
    private KeyListArgumentType(@NonNull Supplier<Set<NamespacedKey>> keys, boolean uuids, @NonNull String messageKey) {
        this.keys = keys;
        this.uuids = uuids;
        this.invalid = ArgumentHelper.localizedException(messageKey);
    }

    @Contract(pure = true)
    public static @NonNull KeyListArgumentType blockList() {
        return BLOCK_LIST;
    }

    @Contract(pure = true)
    public static @NonNull KeyListArgumentType entityList() {
        return ENTITY_LIST;
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = reader.getRemaining();
        reader.setCursor(reader.getTotalLength());
        Set<String> valid = new LinkedHashSet<>();
        int searchFrom = 0;
        for (String entry : SEPARATORS.split(input)) {
            if (entry.isEmpty()) continue;
            int at = input.indexOf(entry, searchFrom);
            searchFrom = at + entry.length();
            String canonical = canonical(entry);
            if (canonical == null) {
                reader.setCursor(start + at);
                throw invalid.createWithContext(reader, entry);
            }
            valid.add(canonical);
        }
        if (valid.isEmpty()) {
            reader.setCursor(start);
            throw invalid.createWithContext(reader, input);
        }
        return String.join(",", valid);
    }

    public @NonNull String normalize(@NonNull String raw) {
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : SEPARATORS.split(raw)) {
            if (entry.isEmpty()) continue;
            String canonical = canonical(entry);
            entries.add(canonical != null ? canonical : CommandHelper.stripNamespace(entry));
        }
        return String.join(",", entries);
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        SuggestionsBuilder word = atCurrentWord(builder);
        String before = entriesBefore(word);
        ArgumentHelper.suggestKeys(word.getRemaining().substring(before.length()).toLowerCase(Locale.ROOT), keys.get(), key -> word.suggest(before + key));
        return word.buildFuture();
    }

    public static @NonNull SuggestionsBuilder atCurrentWord(@NonNull SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        return builder.createOffset(builder.getStart() + input.lastIndexOf(' ') + 1);
    }

    public static @NonNull String entriesBefore(@NonNull SuggestionsBuilder word) {
        String input = word.getRemaining();
        return input.substring(0, input.lastIndexOf(',') + 1);
    }

    public boolean accepts(@NonNull String entry) {
        return canonical(entry) != null;
    }

    private @Nullable String canonical(@NonNull String entry) {
        if (uuids) {
            try {
                return UUID.fromString(entry).toString();
            } catch (IllegalArgumentException notAUuid) {
                //  fall through to the key lookup
            }
        }
        NamespacedKey key = ArgumentHelper.resolveKey(entry);
        return key != null && keys.get().contains(key) ? CommandHelper.stripNamespace(key.toString()) : null;
    }
}