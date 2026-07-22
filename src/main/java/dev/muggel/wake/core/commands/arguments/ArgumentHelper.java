package dev.muggel.wake.core.commands.arguments;

import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.muggel.wake.Wake;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Shared helpers for custom Brigadier argument types <br>
 */
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

    /** Converts raw user input to {@link NamespacedKey} (default: {@code minecraft:}) */
    public static @Nullable NamespacedKey resolveKey(@NonNull String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        try {
            return lower.contains(":") ? NamespacedKey.fromString(lower) : NamespacedKey.minecraft(lower);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}