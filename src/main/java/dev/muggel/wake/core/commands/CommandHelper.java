package dev.muggel.wake.core.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Generic command-executor helpers. <br>
 * Independent of any feature module.
 */
public final class CommandHelper {
    public static final String STATE_KEY_SHOW_HINTS = "core.show_hints"; // Gates hint messages plugin-wide
    private CommandHelper() {}
    /**
     * The standard boolean feature flag: {@code <literal> <true|false>} <br>
     * Writes {@code stateKey} and confirms with localized {@code featureKey} <br>
     * Declare every on/off flag through this factory instead of writing a command class for it
     */
    public static @NonNull CommandNode toggleCommand(@NonNull Wake plugin, @NonNull String literal, @NonNull String stateKey, @NonNull String featureKey) {
        return toggleCommand(plugin, literal, stateKey, featureKey, () -> {});
    }

    public static @NonNull CommandNode toggleCommand(@NonNull Wake plugin, @NonNull String literal, @NonNull String stateKey, @NonNull String featureKey, @NonNull Runnable onChange) {
        return CommandNode.literal(literal)
                .arguments(CommandNode.argument("state", BoolArgumentType.bool())
                        .executesSender((ctx, subject) -> {
                            boolean enabled = BoolArgumentType.getBool(ctx, "state");
                            plugin.getStateDao().set(stateKey, enabled);
                            onChange.run();
                            toggle(plugin, ctx.getSource().getSender(), featureKey, enabled);
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** Who the command acts on: the {@code /execute as} subject when there is one, else the sender. Feedback still goes to {@code source.getSender()} */
    public static @NonNull CommandSender actingSender(@NonNull CommandSourceStack source) {
        Entity executor = source.getExecutor();
        return executor != null ? executor : source.getSender();
    }

    public static @NonNull TagResolver hint(@NonNull Wake plugin, @Nullable String hintKey) {
        return Placeholder.component("hint", hintKey == null || !plugin.getStateDao().get(STATE_KEY_SHOW_HINTS, true)
                ? Component.empty() : plugin.getMessageManager().getComponent(hintKey));
    }

    public static @NonNull CompletableFuture<Suggestions> suggestMatching(@NonNull SuggestionsBuilder builder, @NonNull Iterable<String> options) {
        return suggestMatching(builder, "", options);
    }

    public static @NonNull CompletableFuture<Suggestions> suggestMatching(@NonNull SuggestionsBuilder builder, @NonNull String committed, @NonNull Iterable<String> options) {
        String remaining = builder.getRemaining().substring(committed.length()).toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (suggestionMatches(remaining, option.toLowerCase(Locale.ROOT))) {
                builder.suggest(committed + option);
            }
        }
        return builder.buildFuture();
    }

    @Contract(pure = true)
    public static boolean suggestionMatches(@NonNull String typed, @NonNull String candidate) {
        for (int at = 0; !candidate.startsWith(typed, at); at++) {
            at = candidate.indexOf('_', at);
            if (at < 0) {
                return false;
            }
        }
        return true;
    }

    public static void toggle(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String featureKey, boolean enabled) {
        plugin.getMessageManager().send(sender, enabled ? "commands.core.toggle_enabled" : "commands.core.toggle_disabled",
                Placeholder.component("name", plugin.getMessageManager().getComponent(featureKey)));
    }

    public static @NonNull Component moduleDescription(@NonNull Wake plugin, @NonNull CommandNode root) {
        return plugin.getMessageManager().getComponent("commands.help.module." + root.getModuleId());
    }

    public static @NonNull String stripNamespace(@NonNull String key) {
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }
}