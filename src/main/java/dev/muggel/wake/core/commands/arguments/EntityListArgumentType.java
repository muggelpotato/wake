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
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EntityListArgumentType implements CustomArgumentType<String, String> {
    private static final DynamicCommandExceptionType INVALID_ENTITY = new DynamicCommandExceptionType(
            obj -> {
                Component comp = Wake.getPlugin(Wake.class).getMessageManager().getComponent("commands.invalid_entity",
                        Placeholder.unparsed("input", String.valueOf(obj)));
                return MessageComponentSerializer.message().serialize(comp);
            }
    );

    public static EntityListArgumentType entityList() {
        return new EntityListArgumentType();
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

        String[] entities = input.split("[\\s,]+");
        List<String> validEntities = new ArrayList<>();
        int currentOffset = start;

        for (String b : entities) {
            if (b.isEmpty()) continue;
            String trimmed = b.trim();

            boolean isValid = false;
            try {
                UUID.fromString(trimmed);
                isValid = true;
            } catch (IllegalArgumentException e) {
                try {
                    NamespacedKey key = trimmed.contains(":") ? NamespacedKey.fromString(trimmed) : NamespacedKey.minecraft(trimmed.toLowerCase());
                    if (key != null) {
                        EntityType type = Registry.ENTITY_TYPE.get(key);
                        if (type != null && type != EntityType.UNKNOWN) {
                            isValid = true;
                            trimmed = key.toString();
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }

            if (!isValid) {
                reader.setCursor(currentOffset);
                throw INVALID_ENTITY.createWithContext(reader, b.trim());
            }

            validEntities.add(trimmed);

            int indexInInput = input.indexOf(b, currentOffset - start);
            if (indexInInput != -1) {
                currentOffset = start + indexInInput + b.length();
            } else {
                currentOffset += b.length() + 1;
            }
        }
        return String.join(",", validEntities);
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, @NonNull SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        String[] parts = input.split("[\\s,]+", -1);
        String currentSearch = parts[parts.length - 1].toLowerCase();

        String prefix = input.substring(0, input.length() - currentSearch.length());

        SuggestionsBuilder commaBuilder = builder.createOffset(builder.getStart() + prefix.length());

        Registry.ENTITY_TYPE.stream()
                .map(e -> e.getKey().getKey())
                .filter(k -> k.startsWith(currentSearch))
                .forEach(commaBuilder::suggest);

        return commaBuilder.buildFuture();
    }
}
