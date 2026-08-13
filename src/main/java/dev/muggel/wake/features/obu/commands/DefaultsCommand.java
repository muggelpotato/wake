package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ActiveContexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class DefaultsCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-defaults")
                .withHelpKey("commands.obu.help.defaults")
                .arguments(CommandNode.argument("setting", NameArgumentType.greedy())
                        .suggests(DefaultsCommand::suggestSetting)
                        .executesSender((ctx, subject) -> execute(ctx, subject, plugin)));
    }

    private static @NonNull CompletableFuture<Suggestions> suggestSetting(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return CommandHelper.suggestMatching(builder, Arrays.stream(OBUDefinition.values())
                .filter(def -> def.defaultValue() != null)
                .map(OBUDefinition::commandName).toList());
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String settingName = StringArgumentType.getString(ctx, "setting");
        OBUDefinition def = OBUDefinition.byName(settingName);
        String defValueStr = def == null ? null : def.defaultValue();
        if (defValueStr == null) {
            plugin.getMessageManager().send(sender, "commands.obu.defaults.missing", Placeholder.unparsed("setting", settingName));
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.defaults.vanilla",
                Placeholder.unparsed("setting", def.commandName()),
                Placeholder.unparsed("value", defValueStr));
        if (!(subject instanceof Player player)) {
            return Command.SINGLE_SUCCESS;
        }
        ActiveContexts active = OBUCommandHelper.active(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String sandboxName = active.sandboxOf(player.getUniqueId());
        int id = def.id();
        OBUSetting effectiveSetting = OBUCommandHelper.sync(plugin).getLocalOverrides(player.getUniqueId()).values().stream().filter(s -> s.definition().id() == id).findFirst().orElse(null);
        boolean isServerDefault = false;
        if (effectiveSetting == null && sandboxName != null) {
            effectiveSetting = settingOf(contextManager.getContext(sandboxName), id);
        }
        if (effectiveSetting == null && sandboxName == null) {
            String baseName = active.contextOf(player.getUniqueId());
            effectiveSetting = settingOf(contextManager.getContext(baseName), id);
            if (effectiveSetting == null && OBUContextManager.inheritsDefault(baseName)) {
                effectiveSetting = settingOf(contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT), id);
            }
            isServerDefault = effectiveSetting != null;
        }
        if (effectiveSetting == null) {
            plugin.getMessageManager().send(sender, "commands.obu.defaults.active", Placeholder.unparsed("value", defValueStr));
            return Command.SINGLE_SUCCESS;
        }
        Component button = isServerDefault
                ? plugin.getMessageManager().getComponent("commands.obu.defaults.blocked_btn")
                : plugin.getMessageManager().getComponent("commands.obu.defaults.clear_btn", Placeholder.parsed("setting", def.commandName()));
        plugin.getMessageManager().send(sender, "commands.obu.defaults.custom",
                Placeholder.component("value", OBUCommandHelper.displayValue(plugin, effectiveSetting, false, Set.of())),
                Placeholder.component("badge", OBUCommandHelper.outdatedBadge(plugin, player, effectiveSetting)),
                Placeholder.component("button", button));
        return Command.SINGLE_SUCCESS;
    }

    private static @Nullable OBUSetting settingOf(@Nullable OBUContext context, int id) {
        if (context == null) {
            return null;
        }
        for (OBUSetting setting : context.settings()) {
            if (setting.definition().id() == id) {
                return setting;
            }
        }
        return null;
    }
}