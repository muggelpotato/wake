package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One of Wake's running modules, by id (case-insensitive). <br>
 * Retrieve with {@code ctx.getArgument(name, String.class)}
 */
public final class ModuleArgumentType implements CustomArgumentType<String, String> {
    private static final DynamicCommandExceptionType NOT_LOADED = ArgumentHelper.localizedException("commands.invalid_module");
    private final Wake plugin;
    private ModuleArgumentType(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    @Contract(value = "_ -> new", pure = true)
    public static @NonNull ModuleArgumentType of(@NonNull Wake plugin) {
        return new ModuleArgumentType(plugin);
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = ArgumentHelper.readToken(reader);
        for (String id : activeIds()) {
            if (id.equalsIgnoreCase(input)) {
                return id;
            }
        }
        reader.setCursor(start);
        throw NOT_LOADED.createWithContext(reader, input);
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        return CommandHelper.suggestMatching(builder, activeIds());
    }

    private @NonNull List<String> activeIds() {
        return plugin.getActiveModules().stream().map(WakeModule::getId).toList();
    }
}