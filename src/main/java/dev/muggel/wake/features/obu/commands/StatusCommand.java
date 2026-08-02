package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ActiveContexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StatusCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-status")
                .withHelpKey("commands.obu.help.status")
                .withPreset(PermissionPreset.PLAYER)
                .executesPlayer((ctx, player) -> execute(ctx, player, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Player player, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        Boat boat = boatInFocus(player);
        Map<String, OBUSetting> boatOverrides = boat == null
                ? Map.of()
                : OBUCommandHelper.sync(plugin).getLocalOverrides(boat.getUniqueId());
        plugin.getMessageManager().send(sender, "commands.obu.status.player");
        printPlayerSection(plugin, sender, player, boatOverrides.keySet());
        if (boat != null) {
            plugin.getMessageManager().send(sender, "commands.obu.status.boat");
            printBoatSection(plugin, sender, boat, boatOverrides);
        }
        if (OBUCommandHelper.active(plugin).sandboxOf(player.getUniqueId()) == null) {
            CommandHelper.sendHint(plugin, sender, "commands.obu.status.hint");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static @Nullable Boat boatInFocus(@NonNull Player player) {
        if (player.getVehicle() instanceof Boat ridden) {
            return ridden;
        }
        Entity aimed = player.getTargetEntity(CommandNode.AIM_DISTANCE);
        return aimed instanceof Boat boat ? boat : null;
    }

    private static void printPlayerSection(Wake plugin, CommandSender sender, @NonNull Player player, @NonNull Set<String> boatOverriddenKeys) {
        ActiveContexts active = OBUCommandHelper.active(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String sandbox = active.sandboxOf(player.getUniqueId());
        String baseName = sandbox != null ? sandbox : active.contextOf(player.getUniqueId());
        OBUContext base = contextManager.getContext(baseName);
        Map<String, OBUSetting> overrides = OBUCommandHelper.sync(plugin).getLocalOverrides(player.getUniqueId());
        if (base == null && overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.empty");
            return;
        }
        if (base != null) {
            List<OBUSetting> inherited = sandbox != null ? List.of() : inheritedDefaults(contextManager, base);
            if (!base.settings().isEmpty() || inherited.isEmpty()) {
                plugin.getMessageManager().send(sender, "commands.obu.status.subtitle",
                        Placeholder.unparsed("context", OBUContextManager.displayName(baseName)));
                if (base.settings().isEmpty()) {
                    plugin.getMessageManager().send(sender, "commands.obu.status.empty");
                } else {
                    printSettings(plugin, sender, base.settings(), overrides, boatOverriddenKeys);
                }
            }
            if (!inherited.isEmpty()) {
                plugin.getMessageManager().send(sender, "commands.obu.status.subtitle",
                        Placeholder.parsed("context", OBUContextManager.DEFAULT_CONTEXT));
                printSettings(plugin, sender, inherited, overrides, boatOverriddenKeys);
            }
        }
        if (!overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.temp");
            printSettings(plugin, sender, List.copyOf(overrides.values()), null, boatOverriddenKeys);
        }
    }

    private static @NonNull List<OBUSetting> inheritedDefaults(@NonNull OBUContextManager contextManager, @NonNull OBUContext base) {
        OBUContext defaults = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
        if (defaults == null || !OBUContextManager.inheritsDefault(base)) {
            return List.of();
        }
        Set<String> own = new HashSet<>();
        for (OBUSetting setting : base.settings()) {
            own.add(setting.getUniqueKey());
        }
        List<OBUSetting> inherited = new ArrayList<>();
        for (OBUSetting setting : defaults.settings()) {
            if (!own.contains(setting.getUniqueKey())) {
                inherited.add(setting);
            }
        }
        return inherited;
    }

    private static void printBoatSection(Wake plugin, CommandSender sender, @NonNull Boat boat, @NonNull Map<String, OBUSetting> overrides) {
        String pinned = OBUCommandHelper.active(plugin).pinnedOn(boat);
        OBUContext base = pinned == null ? null : OBUCommandHelper.contexts(plugin).getContext(pinned);
        if (base == null && overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.empty");
            return;
        }
        if (base != null) {
            printSettings(plugin, sender, base.settings(), overrides, Set.of());
        }
        if (!overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.temp");
            printSettings(plugin, sender, List.copyOf(overrides.values()), null, Set.of());
        }
    }

    private static void printSettings(Wake plugin, CommandSender audience, @NonNull List<OBUSetting> settings, @Nullable Map<String, OBUSetting> overrides, @NonNull Set<String> boatOverriddenKeys) {
        for (OBUSetting setting : settings) {
            boolean shadowedByBoat = boatOverriddenKeys.contains(setting.getUniqueKey());
            boolean shadowed = shadowedByBoat || (overrides != null && overrides.containsKey(setting.getUniqueKey()));
            Component line = plugin.getMessageManager().getComponent(
                    shadowed ? "commands.obu.status.overridden" : "commands.obu.status.line",
                    Placeholder.parsed("name", setting.definition().name()),
                    Placeholder.unparsed("value", String.join(", ", OBUCommandHelper.displayArgs(setting))));
            if (shadowed) {
                line = line.append(plugin.getMessageManager().getComponent(
                        shadowedByBoat ? "commands.obu.status.boat_suffix" : "commands.obu.status.suffix"));
            }
            audience.sendMessage(line);
        }
    }
}