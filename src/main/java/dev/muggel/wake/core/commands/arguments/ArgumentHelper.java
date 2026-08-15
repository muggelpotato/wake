package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Consumer;

/** Shared helpers for custom Brigadier argument types */
public final class ArgumentHelper {
    private ArgumentHelper() {}

    /** Builds parse-failure exceptions whose message comes from the lang file */
    public static @NonNull DynamicCommandExceptionType localizedException(@NonNull String messageKey) {
        return new DynamicCommandExceptionType(obj -> {
            Component comp = Wake.getPlugin(Wake.class).getMessageManager().getComponent(messageKey,
                    Placeholder.unparsed("input", String.valueOf(obj)));
            return MessageComponentSerializer.message().serialize(comp);
        });
    }

    public static @NonNull String readToken(@NonNull StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    /** Converts raw user input to {@link NamespacedKey} (default: {@code minecraft:}), or null */
    public static @Nullable NamespacedKey resolveKey(@NonNull String input) {
        try {
            return NamespacedKey.fromString(input.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Bare names by default, namespaced when typing ":" */
    public static void suggestKeys(@NonNull String typed, @NonNull Collection<NamespacedKey> keys, @NonNull Consumer<String> out) {
        boolean namespaced = typed.indexOf(':') != -1;
        for (NamespacedKey key : keys) {
            String shown = namespaced ? key.toString() : key.getKey();
            if (CommandHelper.suggestionMatches(typed, shown)) {
                out.accept(shown);
            }
        }
    }
}