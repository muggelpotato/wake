package dev.muggel.wake.features.drydock.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.arguments.ArgumentHelper;
import dev.muggel.wake.core.commands.arguments.BlockArgumentType;
import dev.muggel.wake.features.drydock.DrydockModule;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class BoostpadKeyArgumentType implements CustomArgumentType<String, NamespacedKey> {
    private static final DynamicCommandExceptionType NOT_A_BOOSTPAD = ArgumentHelper.localizedException("commands.drydock.boostpad.block_not_found");

    @Contract(value = " -> new", pure = true)
    public static @NonNull BoostpadKeyArgumentType boostpadKey() {
        return new BoostpadKeyArgumentType();
    }

    private static @Nullable BoostpadRegistry boostpads() {
        DrydockModule module = Wake.getPlugin(Wake.class).getModule(DrydockModule.class);
        return module != null ? module.getBoostpads() : null;
    }

    @Override
    public @NonNull ArgumentType<NamespacedKey> getNativeType() {
        return ArgumentTypes.namespacedKey();
    }

    @Override
    public @NonNull String parse(@NonNull StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        String input = BlockArgumentType.readKey(reader);
        String key = input.contains(":") ? input.toLowerCase(Locale.ROOT) : "minecraft:" + input.toLowerCase(Locale.ROOT);
        BoostpadRegistry registry = boostpads();
        if (registry == null || !registry.cachedBoostpads().containsKey(key)) {
            reader.setCursor(start);
            throw NOT_A_BOOSTPAD.createWithContext(reader, input);
        }
        return key;
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        boolean namespaced = builder.getRemaining().indexOf(':') != -1;
        BoostpadRegistry registry = boostpads();
        if (registry == null) {
            return builder.buildFuture();
        }
        List<String> shown = new ArrayList<>();
        for (String key : registry.cachedBoostpads().keySet()) {
            shown.add(namespaced || !key.contains(":") ? key : key.substring(key.indexOf(':') + 1));
        }
        return CommandHelper.suggestMatching(builder, shown);
    }
}