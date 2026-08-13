package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.SettingMerge;
import dev.muggel.wake.features.obu.protocol.SettingMerge.Removal;
import dev.muggel.wake.features.obu.protocol.SettingSelector;
import dev.muggel.wake.features.obu.protocol.SettingType;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import dev.muggel.wake.features.obu.delivery.OBUSyncManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class SettingsCommand {
    public static @NonNull List<CommandNode> getNodes(Wake plugin) {
        List<CommandNode> nodes = new ArrayList<>();
        for (OBUDefinition def : OBUDefinition.values()) {
            nodes.add(createSettingNode(def, plugin));
        }
        return nodes;
    }

    private static @NonNull CommandNode createSettingNode(@NonNull OBUDefinition def, Wake plugin) {
        CommandNode settingNode = CommandNode.literal(def.commandName());
        if (def == OBUDefinition.reset) {
            settingNode.withPreset(PermissionPreset.PLAYER).withHelpKey("commands.obu.help.reset");
        }
        List<SettingType> types = def.types();
        String[] argNames = OBUCommandHelper.argNames(def);
        if (types.isEmpty()) {
            settingNode.executesEntityOrAimedBoat((ctx, target) -> executeSetting(ctx, def, types, argNames, target, OBUCommandHelper.delivery(plugin), plugin));
            return settingNode;
        }
        OBUDefinition edits = def.subtractsFrom();
        List<CommandNode> argNodes = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            SettingType type = types.get(i);
            CommandNode argNode = CommandNode.argument(argNames[i], type.argument());
            if (edits != null && type.isList()) {
                argNode.suggests((ctx, builder) -> OBUCommandHelper.suggestNarrowing(ctx, builder, plugin, edits, List.of()));
            }
            argNodes.add(argNode);
        }
        argNodes.getLast().executesEntityOrAimedBoat((ctx, target) -> executeSetting(ctx, def, types, argNames, target, OBUCommandHelper.delivery(plugin), plugin));
        settingNode.arguments(argNodes.toArray(new CommandNode[0]));
        return settingNode;
    }

    private static int executeSetting(CommandContext<CommandSourceStack> ctx, OBUDefinition def, @NonNull List<SettingType> types, String[] argNames, Entity target, ContextDelivery delivery, Wake plugin) {
        String[] args = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {
            Object argVal = ctx.getArgument(argNames[i], Object.class);
            args[i] = String.valueOf(argVal);
        }
        OBUSetting setting = new OBUSetting(def, Arrays.asList(args));
        CommandSender sender = ctx.getSource().getSender();
        if (def == OBUDefinition.reset) {
            return executeReset(plugin, sender, target, delivery);
        }
        OBUDefinition edits = def.subtractsFrom();
        if (edits != null) {
            return executeRemoval(plugin, sender, target, SettingSelector.of(setting), edits, delivery);
        }
        if (target instanceof Boat && def.isGlobalSetting()) {
            plugin.getMessageManager().send(sender, "commands.obu.settings.global_only", Placeholder.unparsed("setting", def.commandName()));
            return 0;
        }
        if (!delivery.applySetting(target, setting)) {
            plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
            return 0;
        }
        String sandbox = null;
        if (target instanceof Player p) {
            sandbox = OBUCommandHelper.active(plugin).sandboxOf(p.getUniqueId());
        }
        if (sandbox != null && !def.isActionSetting()) {
            plugin.getMessageManager().send(sender, "commands.obu.settings.sandbox",
                    Placeholder.unparsed("setting", def.commandName()),
                    Placeholder.component("value", OBUCommandHelper.displayValue(plugin, setting, false, Set.of())),
                    Placeholder.unparsed("sandbox", OBUContextManager.displayName(sandbox)));
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.settings.success",
                    Placeholder.unparsed("setting", def.commandName()),
                    Placeholder.component("value", OBUCommandHelper.displayValue(plugin, setting, false, Set.of())),
                    Placeholder.component("target", OBUCommandHelper.targetName(plugin, target, sender)));
        }
        OBUCommandHelper.warnIfPastClient(plugin, sender, target, setting);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeRemoval(Wake plugin, CommandSender sender, Entity target, @NonNull SettingSelector selector, @NonNull OBUDefinition edits, @NonNull ContextDelivery delivery) {
        Removal removal = delivery.removeSettings(target, selector);
        if (removal == null) {
            plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
            return 0;
        }
        String sandbox = target instanceof Player player ? OBUCommandHelper.active(plugin).sandboxOf(player.getUniqueId()) : null;
        if (removal.taken().isEmpty()) {
            reportNothingRemoved(plugin, sender, target, selector, edits, sandbox);
            return 0;
        }
        if (sandbox != null) {
            plugin.getMessageManager().send(sender, "commands.obu.settings.removed_sandbox",
                    Placeholder.unparsed("setting", edits.name()),
                    Placeholder.component("value", OBUCommandHelper.entryList(plugin, removal.removed())),
                    Placeholder.unparsed("sandbox", OBUContextManager.displayName(sandbox)));
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.settings.removed",
                    Placeholder.unparsed("setting", edits.name()),
                    Placeholder.component("value", OBUCommandHelper.entryList(plugin, removal.removed())),
                    Placeholder.component("target", OBUCommandHelper.targetName(plugin, target, sender)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void reportNothingRemoved(Wake plugin, CommandSender sender, Entity target, @NonNull SettingSelector selector, @NonNull OBUDefinition edits, @Nullable String sandbox) {
        Predicate<OBUSetting> taken = held -> SettingMerge.takesFrom(held, selector);
        if (sandbox == null && target instanceof Player player && OBUCommandHelper.inBaseContext(plugin, player, taken)) {
            plugin.getMessageManager().send(sender, "commands.obu.clear.base_blocked", Placeholder.unparsed("setting", edits.name()));
            return;
        }
        plugin.getMessageManager().send(sender, "commands.obu.settings.removed_none",
                Placeholder.unparsed("setting", edits.name()),
                Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, target, sender)));
    }

    private static int executeReset(Wake plugin, CommandSender sender, Entity target, @NonNull ContextDelivery delivery) {
        if (target instanceof Player player) {
            delivery.applyDefaultContext(player);
        } else if (target instanceof Boat boat) {
            OBUSyncManager sync = OBUCommandHelper.sync(plugin);
            sync.clearLocalOverrides(boat.getUniqueId());
            sync.broadcastSync(boat);
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.settings.reset",
                Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, target, sender)));
        return Command.SINGLE_SUCCESS;
    }
}