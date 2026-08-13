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
import dev.muggel.wake.features.obu.protocol.SettingMerge;
import dev.muggel.wake.features.obu.delivery.ActiveContexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StatusCommand {
    public static final String STATE_KEY_COLLAPSE_DEFAULT_CONTEXT = "obu.collapse_default_context";
    public static final boolean DEFAULT_COLLAPSE_DEFAULT_CONTEXT = true;

    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-status")
                .withHelpKey("commands.obu.help.status")
                .withPreset(PermissionPreset.PLAYER)
                .executesPlayer((ctx, player) -> execute(ctx, player, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Player player, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        Boat ridden = player.getVehicle() instanceof Boat vehicle ? vehicle : null;
        Boat boat = ridden != null ? ridden
                : player.getTargetEntity(CommandNode.TargetType.AIM_DISTANCE) instanceof Boat aimed ? aimed : null;
        Map<String, OBUSetting> boatOverrides = boat == null
                ? Map.of()
                : OBUCommandHelper.sync(plugin).getLocalOverrides(boat.getUniqueId());
        String sandbox = OBUCommandHelper.active(plugin).sandboxOf(player.getUniqueId());
        plugin.getMessageManager().send(sender, "commands.obu.status.player", CommandHelper.hint(plugin, sandbox == null ? "commands.obu.status.player_hint" : null));
        List<OBUSetting> playerHeld = printPlayerSection(plugin, sender, player, sandbox, ridden == null ? List.of() : List.copyOf(boatOverrides.values()));
        if (boat != null) {
            plugin.getMessageManager().send(sender, "commands.obu.status.boat", CommandHelper.hint(plugin, "commands.obu.status.boat_hint"));
            printBoatSection(plugin, sender, player, boat, boatOverrides, ridden == null ? List.of() : playerHeld);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull List<OBUSetting> printPlayerSection(Wake plugin, CommandSender sender, @NonNull Player player, @Nullable String sandbox, @NonNull List<OBUSetting> boatAbove) {
        ActiveContexts active = OBUCommandHelper.active(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        OBUContext base = contextManager.getContext(sandbox != null ? sandbox : active.contextOf(player.getUniqueId()));
        Map<String, OBUSetting> overrides = OBUCommandHelper.sync(plugin).getLocalOverrides(player.getUniqueId());
        if (base == null && overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.no_settings");
            return List.of();
        }
        List<OBUSetting> held = new ArrayList<>(overrides.values());
        if (base != null) {
            List<OBUSetting> inherited = sandbox != null ? List.of() : inheritedDefaults(contextManager, base);
            printLayers(plugin, sender, player, base, inherited, List.copyOf(overrides.values()), boatAbove);
            held.addAll(base.settings());
            held.addAll(inherited);
        }
        if (!overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.temp", CommandHelper.hint(plugin, "commands.obu.status.temp_hint"));
            printSettings(plugin, sender, player, List.copyOf(overrides.values()), List.of(), boatAbove);
        }
        return held;
    }

    private static @NonNull List<OBUSetting> inheritedDefaults(@NonNull OBUContextManager contextManager, @NonNull OBUContext base) {
        OBUContext defaults = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
        return defaults == null || !OBUContextManager.inheritsDefault(base) ? List.of() : defaults.settings();
    }

    private static void printBoatSection(Wake plugin, CommandSender sender, @NonNull Player subject, @NonNull Boat boat, @NonNull Map<String, OBUSetting> overrides, @NonNull List<OBUSetting> riderHeld) {
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String pinned = OBUCommandHelper.active(plugin).pinnedOn(boat);
        OBUContext base = pinned == null ? null : contextManager.getContext(pinned);
        if (base == null && overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.no_settings");
            return;
        }
        if (base != null) {
            List<OBUSetting> above = new ArrayList<>(riderHeld);
            above.addAll(overrides.values());
            printLayers(plugin, sender, subject, base, inheritedDefaults(contextManager, base), above, List.of());
        }
        if (!overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.temp", CommandHelper.hint(plugin, "commands.obu.status.temp_hint"));
            printSettings(plugin, sender, subject, List.copyOf(overrides.values()), List.of(), List.of());
        }
    }

    private static void printLayers(Wake plugin, CommandSender sender, @NonNull Player subject, @NonNull OBUContext base, @NonNull List<OBUSetting> inherited, @NonNull List<OBUSetting> above, @NonNull List<OBUSetting> boatAbove) {
        plugin.getMessageManager().send(sender, "commands.obu.status.subtitle", Placeholder.unparsed("context", OBUContextManager.displayName(base.name())));
        if (base.settings().isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.no_settings");
        } else {
            printLayer(plugin, sender, subject, base.name(), base.settings(), above, boatAbove);
        }
        if (!inherited.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.subtitle", Placeholder.unparsed("context", OBUContextManager.DEFAULT_CONTEXT));
            List<OBUSetting> overDefaults = new ArrayList<>(above);
            overDefaults.addAll(base.settings());
            printLayer(plugin, sender, subject, OBUContextManager.DEFAULT_CONTEXT, inherited, overDefaults, boatAbove);
        }
    }

    private static void printLayer(Wake plugin, CommandSender audience, @NonNull Player subject, @NonNull String contextName, @NonNull List<OBUSetting> settings, @NonNull List<OBUSetting> above, @NonNull List<OBUSetting> boatAbove) {
        if (!OBUContextManager.DEFAULT_CONTEXT.equals(contextName) || !plugin.getStateDao().get(STATE_KEY_COLLAPSE_DEFAULT_CONTEXT, DEFAULT_COLLAPSE_DEFAULT_CONTEXT)) {
            printSettings(plugin, audience, subject, settings, above, boatAbove);
            return;
        }
        Component hover = plugin.getMessageManager().getComponent("commands.obu.context.settings", Placeholder.unparsed("context", contextName));
        boolean allShadowed = true;
        for (OBUSetting setting : settings) {
            hover = hover.append(Component.newline()).append(settingLine(plugin, subject, setting, above, boatAbove, true));
            allShadowed &= shadowOf(setting, above, boatAbove).suffix() != null;
        }
        Component chip = OBUCommandHelper.countChip(plugin, settings.size(), allShadowed).hoverEvent(HoverEvent.showText(hover));
        Component line = plugin.getMessageManager().getComponent(
                allShadowed ? "commands.obu.status.collapsed_layer_overridden" : "commands.obu.status.collapsed_layer",
                Placeholder.component("count", chip));
        audience.sendMessage(allShadowed ? line.append(plugin.getMessageManager().getComponent("commands.obu.status.suffix")) : line);
    }

    private static void printSettings(Wake plugin, CommandSender audience, @NonNull Player subject, @NonNull List<OBUSetting> settings, @NonNull List<OBUSetting> above, @NonNull List<OBUSetting> boatAbove) {
        for (OBUSetting setting : settings) {
            audience.sendMessage(settingLine(plugin, subject, setting, above, boatAbove, false));
        }
    }

    private static @NonNull Component settingLine(Wake plugin, @NonNull Player subject, @NonNull OBUSetting setting, @NonNull List<OBUSetting> above, @NonNull List<OBUSetting> boatAbove, boolean inHover) {
        Shadow shadow = shadowOf(setting, above, boatAbove);
        Component line = OBUCommandHelper.settingLine(plugin, subject, setting, inHover, shadow.suffix() != null, shadow.struck());
        return shadow.suffix() == null ? line : line.append(plugin.getMessageManager().getComponent(shadow.suffix()));
    }

    private record Shadow(@NonNull Set<String> struck, @Nullable String suffix) {}

    private static @NonNull Shadow shadowOf(@NonNull OBUSetting setting, @NonNull List<OBUSetting> above, @NonNull List<OBUSetting> boatAbove) {
        Set<String> byBoat = SettingMerge.shadowedEntries(setting, boatAbove);
        Set<String> struck = new HashSet<>(SettingMerge.shadowedEntries(setting, above));
        struck.addAll(byBoat);
        String key = setting.uniqueKey();
        boolean boatHolds = holds(boatAbove, key);
        if (!boatHolds && !holds(above, key) && !SettingMerge.coversEntries(setting, struck)) {
            return new Shadow(struck, null);
        }
        return new Shadow(struck, boatHolds || !byBoat.isEmpty() ? "commands.obu.status.boat_suffix" : "commands.obu.status.suffix");
    }

    private static boolean holds(@NonNull List<OBUSetting> layer, @NonNull String uniqueKey) {
        for (OBUSetting setting : layer) {
            if (setting.uniqueKey().equals(uniqueKey)) return true;
        }
        return false;
    }
}