package dev.muggel.wake.core.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.text.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Generic command-executor helpers. <br>
 * Independent of any feature module.
 */
public final class CommandHelper {
    public static final String STATE_KEY_SHOW_HINTS = "base.show_hints"; // Gates hint messages plugin-wide
    private CommandHelper() {}
    /**
     * The standard boolean feature flag: {@code <literal> <true|false>} <br>
     * Writes {@code stateKey} and confirms with localized {@code featureKey} <br>
     * Declare every on/off flag through this factory instead of writing a command class for it
     */
    public static @NonNull CommandNode toggleCommand(@NonNull Wake plugin, @NonNull String literal, @NonNull String stateKey, @NonNull String featureKey) {
        return CommandNode.literal(literal)
                .arguments(CommandNode.argument("state", BoolArgumentType.bool())
                        .executesSender((ctx, subject) -> {
                            boolean enabled = BoolArgumentType.getBool(ctx, "state");
                            plugin.getStateDao().set(stateKey, enabled);
                            toggle(plugin, ctx.getSource().getSender(), featureKey, enabled);
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** Who the command acts on: the {@code /execute as} subject when there is one, else the sender. Feedback still goes to {@code source.getSender()} */
    public static @NonNull CommandSender actingSender(@NonNull CommandSourceStack source) {
        Entity executor = source.getExecutor();
        return executor != null ? executor : source.getSender();
    }

    public static void sendHint(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String messageKey) {
        if (plugin.getStateDao().get(STATE_KEY_SHOW_HINTS, true)) {
            plugin.getMessageManager().send(sender, messageKey);
        }
    }

    /**
     * Resolves a module's published service from the registry <br>
     * Returns {@code null} or notifies the sender if it's not loaded <br>
     * Callers should {@code return 0} when this returns null <br>
     * Pass the module id as {@code moduleLabel} for canonical module names in messages
     */
    public static <T> @Nullable T requireService(@NonNull Class<T> type, @NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String moduleLabel) {
        T service = Wake.getServiceRegistry().get(type);
        if (service == null) {
            plugin.getMessageManager().send(sender, "commands.base.module_not_loaded", Placeholder.unparsed("module", moduleLabel));
        }
        return service;
    }

    public static @NonNull CompletableFuture<Suggestions> suggestMatching(@NonNull SuggestionsBuilder builder, @NonNull Iterable<String> options) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }

    public static void toggle(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String featureKey, boolean enabled) {
        plugin.getMessageManager().send(sender, enabled ? "commands.base.toggle_enabled" : "commands.base.toggle_disabled",
                Placeholder.component("name", plugin.getMessageManager().getComponent(featureKey)));
    }

    public static @NonNull Component moduleDescription(@NonNull Wake plugin, @NonNull String moduleId, @NonNull CommandNode root) {
        String key = "commands.help.module." + moduleId;
        MessageManager mm = plugin.getMessageManager();
        return mm.hasKey(key) ? mm.getComponent(key) : Component.text(root.getDescription());
    }
}