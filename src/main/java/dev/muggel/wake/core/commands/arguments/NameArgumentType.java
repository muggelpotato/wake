package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A name a player types: sandbox, context, setting etc. <br>
 * Parses to the lowercase, trimmed form. <br>
 * Suggestions stay with the node: attach them through {@code .suggests(...)} as usual. Never override {@code listSuggestions} here.
 */
public final class NameArgumentType implements CustomArgumentType<String, String> {
    private record Rule(Pattern pattern, DynamicCommandExceptionType invalid) {}
    private final boolean greedy;
    private final @Nullable Rule rule;
    private NameArgumentType(boolean greedy, @Nullable Rule rule) {
        this.greedy = greedy;
        this.rule = rule;
    }

    /** Everything that is left, whatever it contains: the executor decides what it can resolve */
    @Contract(value = " -> new", pure = true)
    public static @NonNull NameArgumentType greedy() {
        return new NameArgumentType(true, null);
    }

    /** Everything that is left, rejected with {@code messageKey} unless it matches {@code pattern} */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NonNull NameArgumentType greedy(@NonNull Pattern pattern, @NonNull String messageKey) {
        return new NameArgumentType(true, new Rule(pattern, ArgumentHelper.localizedException(messageKey)));
    }

    /** Up to the next space, for a name that is not the last argument */
    @Contract(value = " -> new", pure = true)
    public static @NonNull NameArgumentType word() {
        return new NameArgumentType(false, null);
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return greedy ? StringArgumentType.greedyString() : StringArgumentType.word();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input;
        if (greedy) {
            input = reader.getRemaining();
            reader.setCursor(reader.getTotalLength());
        } else {
            input = reader.readUnquotedString();
        }
        String name = input.trim().toLowerCase(Locale.ROOT);
        if (rule != null && !rule.pattern().matcher(name).matches()) {
            reader.setCursor(start);
            throw rule.invalid().createWithContext(reader, input);
        }
        return name;
    }
}