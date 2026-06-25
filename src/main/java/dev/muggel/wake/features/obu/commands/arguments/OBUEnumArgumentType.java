package dev.muggel.wake.features.obu.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

public class OBUEnumArgumentType<T extends Enum<T>> implements CustomArgumentType<String, String> {
    private final List<String> validNames;

    private static final DynamicCommandExceptionType INVALID_ENUM = new DynamicCommandExceptionType(
            obj -> MessageComponentSerializer.message().serialize(Component.text("Invalid option: " + obj, TextColor.color(0xFF5555)))
    );

    private OBUEnumArgumentType(@NonNull Class<T> enumClass) {
        this.validNames = Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toList());
    }

    public static <T extends Enum<T>> OBUEnumArgumentType<T> obuEnum(Class<T> enumClass) {
        return new OBUEnumArgumentType<>(enumClass);
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = reader.readUnquotedString();

        if (validNames.stream().noneMatch(n -> n.equalsIgnoreCase(input))) {
            reader.setCursor(start);
            throw INVALID_ENUM.createWithContext(reader, input);
        }

        return input.toUpperCase();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        validNames.stream()
                .filter(s -> s.toLowerCase().startsWith(remaining))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
