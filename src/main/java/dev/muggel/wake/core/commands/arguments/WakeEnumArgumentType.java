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
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Any enum as an argument (case-insensitive input). <br>
 * Parses to the constant's own name, so a call site can hand it straight to {@code valueOf}. <br>
 * Use for closed sets of choices instead of validating a raw word in the executor.
 */
public final class WakeEnumArgumentType implements CustomArgumentType<String, String> {
    private static final DynamicCommandExceptionType INVALID_ENUM = ArgumentHelper.localizedException("commands.invalid_option");
    private final List<String> validNames;

    private WakeEnumArgumentType(@NonNull Class<? extends Enum<?>> enumClass) {
        this.validNames = Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .toList();
    }

    @Contract(value = "_ -> new", pure = true)
    public static @NonNull WakeEnumArgumentType wakeEnum(@NonNull Class<? extends Enum<?>> enumClass) {
        return new WakeEnumArgumentType(enumClass);
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = reader.readUnquotedString();
        for (String name : validNames) {
            if (name.equalsIgnoreCase(input)) {
                return name;
            }
        }
        reader.setCursor(start);
        throw INVALID_ENUM.createWithContext(reader, input);
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        return CommandHelper.suggestMatching(builder, validNames);
    }
}