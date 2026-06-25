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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.ArrayList;

public class BlockListArgumentType implements CustomArgumentType<String, String> {
    private static final DynamicCommandExceptionType INVALID_BLOCK = new DynamicCommandExceptionType(
            obj -> MessageComponentSerializer.message().serialize(Component.text("Invalid block: " + obj, TextColor.color(0xFF5555)))
    );

    public static BlockListArgumentType blockList() {
        return new BlockListArgumentType();
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

        String[] blocks = input.split("[\\s,]+");
        List<String> validBlocks = new ArrayList<>();
        int currentOffset = start;

        for (String b : blocks) {
            if (b.isEmpty()) continue;
            String trimmed = b.trim();
            NamespacedKey key = null;
            try {
                key = trimmed.contains(":") ? NamespacedKey.fromString(trimmed) : NamespacedKey.minecraft(trimmed.toLowerCase());
            } catch (IllegalArgumentException ignored) {}

            Material material = key != null ? Registry.MATERIAL.get(key) : null;
            if (material == null || !material.isBlock()) {
                reader.setCursor(currentOffset);
                throw INVALID_BLOCK.createWithContext(reader, trimmed);
            }
            validBlocks.add(key.toString());

            int indexInInput = input.indexOf(b, currentOffset - start);
            if (indexInInput != -1) {
                currentOffset = start + indexInInput + b.length();
            } else {
                currentOffset += b.length() + 1;
            }
        }
        return String.join(",", validBlocks);
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        String[] parts = input.split("[\\s,]+", -1);
        String currentSearch = parts[parts.length - 1].toLowerCase();

        String prefix = input.substring(0, input.length() - currentSearch.length());

        SuggestionsBuilder commaBuilder = builder.createOffset(builder.getStart() + prefix.length());

        Registry.MATERIAL.stream()
                .filter(Material::isBlock)
                .map(m -> m.getKey().getKey())
                .filter(k -> k.startsWith(currentSearch))
                .forEach(commaBuilder::suggest);

        return commaBuilder.buildFuture();
    }
}
