package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SettingsCommand {
    public static @NonNull List<CommandNode> getNodes(Wake plugin) {
        List<CommandNode> nodes = new ArrayList<>();
        for (String settingName : OBUDefinition.commandNames()) {
            OBUDefinition def = OBUDefinition.byName(settingName);
            if (def != null) {
                nodes.add(createSettingNode(def, plugin));
            }
        }
        return nodes;
    }

    private static @NonNull CommandNode createSettingNode(@NonNull OBUDefinition def, Wake plugin) {
        CommandNode settingNode = CommandNode.literal(def.commandName());
        if (def == OBUDefinition.reset) {
            settingNode.withPreset(PermissionPreset.PLAYER).withHelpKey("commands.obu.help.reset");
        }
        List<SettingType> types = def.types();
        String[] argNames = argNames(def);
        if (types.isEmpty()) {
            settingNode.executesEntityOrAimedBoat((ctx, target) -> executeSetting(ctx, def, types, argNames, target, OBUCommandHelper.delivery(plugin), plugin));
            return settingNode;
        }
        List<CommandNode> argNodes = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            argNodes.add(CommandNode.argument(argNames[i], types.get(i).argument()));
        }
        argNodes.getLast().executesEntityOrAimedBoat((ctx, target) -> executeSetting(ctx, def, types, argNames, target, OBUCommandHelper.delivery(plugin), plugin));
        settingNode.arguments(argNodes.toArray(new CommandNode[0]));
        return settingNode;
    }

    private static String @NonNull [] argNames(@NonNull OBUDefinition def) {
        List<SettingType> types = def.types();
        List<String> custom = def.argNames();
        String[] names = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {
            if (!custom.isEmpty()) {
                names[i] = custom.get(i);
                continue;
            }
            String type = types.get(i).name().toLowerCase(Locale.ROOT);
            int count = 1;
            for (int j = 0; j < i; j++) if (types.get(j) == types.get(i)) count++;
            names[i] = count > 1 ? type + count : type;
        }
        return names;
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
                    Placeholder.component("value", OBUCommandHelper.displayValue(plugin, setting, false)),
                    Placeholder.unparsed("sandbox", OBUContextManager.displayName(sandbox)));
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.settings.success",
                    Placeholder.unparsed("setting", def.commandName()),
                    Placeholder.component("value", OBUCommandHelper.displayValue(plugin, setting, false)),
                    Placeholder.component("target", OBUCommandHelper.targetName(plugin, target, sender)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeReset(Wake plugin, CommandSender sender, Entity target, @NonNull ContextDelivery delivery) {
        if (target instanceof Player player) {
            delivery.applyDefaultContext(player);
            plugin.getMessageManager().send(sender, "commands.obu.settings.reset");
            return Command.SINGLE_SUCCESS;
        }
        if (target instanceof Boat boat) {
            OBUSyncManager sync = OBUCommandHelper.sync(plugin);
            sync.clearLocalOverrides(boat.getUniqueId());
            sync.broadcastSync(boat);
            plugin.getMessageManager().send(sender, "commands.obu.settings.success",
                    Placeholder.unparsed("setting", OBUDefinition.reset.commandName()),
                    Placeholder.unparsed("value", ""),
                    Placeholder.component("target", plugin.getMessageManager().getComponent("words.target.boat")));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
        return 0;
    }
}