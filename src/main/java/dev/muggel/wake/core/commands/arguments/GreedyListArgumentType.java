package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Base for greedy list of {@code n} space- or comma-separated arguments. <br>
 * Parses to a comma-joined string of namespaced keys. <br>
 * A subclass only supplies per-entry validation, per-entry suggestions, and its localized error. <br>
 * The split, error-cursor, and suggestion-offset live here.
 */
public abstract class GreedyListArgumentType implements CustomArgumentType<String, String> {
    /** Canonical form of one entry, or null when invalid (raises {@link #invalidEntryException()}) */
    protected abstract @Nullable String canonicalize(@NonNull String entry);

    /** Suggests completions for the entry currently being typed */
    protected abstract void suggestEntry(@NonNull String typed, @NonNull Consumer<String> out);

    protected abstract @NonNull DynamicCommandExceptionType invalidEntryException();

    @Override
    public final @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }

    @Override
    public final @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = reader.getRemaining();
        reader.setCursor(reader.getTotalLength());
        String[] entries = input.split("[\\s,]+");
        List<String> valid = new ArrayList<>();
        int currentOffset = start;
        for (String entry : entries) {
            if (entry.isEmpty()) continue;
            String trimmed = entry.trim();
            String canonical = canonicalize(trimmed);
            if (canonical == null) {
                reader.setCursor(currentOffset);
                throw invalidEntryException().createWithContext(reader, trimmed);
            }
            valid.add(canonical);

            int indexInInput = input.indexOf(entry, currentOffset - start);
            if (indexInInput != -1) {
                currentOffset = start + indexInInput + entry.length();
            } else {
                currentOffset += entry.length() + 1;
            }
        }
        return String.join(",", valid);
    }

    @Override
    public final <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        String[] parts = input.split("[\\s,]+", -1);
        String currentSearch = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        String prefix = input.substring(0, input.length() - currentSearch.length());
        SuggestionsBuilder entryBuilder = builder.createOffset(builder.getStart() + prefix.length());
        suggestEntry(currentSearch, entryBuilder::suggest);
        return entryBuilder.buildFuture();
    }
}