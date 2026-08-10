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
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

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
        plugin.getMessageManager().send(sender, "commands.obu.status.player");
        Set<String> playerKeys = printPlayerSection(plugin, sender, player,
                ridden == null ? Set.of() : boatOverrides.keySet());
        if (boat != null) {
            plugin.getMessageManager().send(sender, "commands.obu.status.boat");
            printBoatSection(plugin, sender, boat, boatOverrides, ridden == null ? Set.of() : playerKeys);
        }
        if (OBUCommandHelper.active(plugin).sandboxOf(player.getUniqueId()) == null) {
            CommandHelper.sendHint(plugin, sender, "commands.obu.status.hint");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull Set<String> printPlayerSection(Wake plugin, CommandSender sender, @NonNull Player player, @NonNull Set<String> boatOverriddenKeys) {
        ActiveContexts active = OBUCommandHelper.active(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String sandbox = active.sandboxOf(player.getUniqueId());
        OBUContext base = contextManager.getContext(sandbox != null ? sandbox : active.contextOf(player.getUniqueId()));
        Map<String, OBUSetting> overrides = OBUCommandHelper.sync(plugin).getLocalOverrides(player.getUniqueId());
        if (base == null && overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.empty");
            return Set.of();
        }
        Set<String> held = new HashSet<>(overrides.keySet());
        if (base != null) {
            List<OBUSetting> inherited = sandbox != null ? List.of() : inheritedDefaults(contextManager, base);
            printLayers(plugin, sender, base, inherited, overrides.keySet(), boatOverriddenKeys);
            for (OBUSetting setting : base.settings()) held.add(setting.uniqueKey());
            for (OBUSetting setting : inherited) held.add(setting.uniqueKey());
        }
        if (!overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.temp");
            printSettings(plugin, sender, List.copyOf(overrides.values()), Set.of(), boatOverriddenKeys);
        }
        return held;
    }

    private static @NonNull List<OBUSetting> inheritedDefaults(@NonNull OBUContextManager contextManager, @NonNull OBUContext base) {
        OBUContext defaults = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
        if (defaults == null || !OBUContextManager.inheritsDefault(base)) {
            return List.of();
        }
        Set<String> own = new HashSet<>();
        for (OBUSetting setting : base.settings()) {
            own.add(setting.uniqueKey());
        }
        List<OBUSetting> inherited = new ArrayList<>();
        for (OBUSetting setting : defaults.settings()) {
            if (!own.contains(setting.uniqueKey())) {
                inherited.add(setting);
            }
        }
        return inherited;
    }

    private static void printBoatSection(Wake plugin, CommandSender sender, @NonNull Boat boat, @NonNull Map<String, OBUSetting> overrides, @NonNull Set<String> riderKeys) {
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String pinned = OBUCommandHelper.active(plugin).pinnedOn(boat);
        OBUContext base = pinned == null ? null : contextManager.getContext(pinned);
        if (base == null && overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.empty");
            return;
        }
        if (base != null) {
            Set<String> shadowed = new HashSet<>(riderKeys);
            shadowed.addAll(overrides.keySet());
            printLayers(plugin, sender, base, inheritedDefaults(contextManager, base), shadowed, Set.of());
        }
        if (!overrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.temp");
            printSettings(plugin, sender, List.copyOf(overrides.values()), Set.of(), Set.of());
        }
    }

    private static void printLayers(Wake plugin, CommandSender sender, @NonNull OBUContext base, @NonNull List<OBUSetting> inherited, @NonNull Set<String> shadowed, @NonNull Set<String> boatShadowed) {
        if (!base.settings().isEmpty() || inherited.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.subtitle", Placeholder.unparsed("context", OBUContextManager.displayName(base.name())));
            if (base.settings().isEmpty()) {
                plugin.getMessageManager().send(sender, "commands.obu.status.empty");
            } else {
                printLayer(plugin, sender, base.name(), base.settings(), shadowed, boatShadowed);
            }
        }
        if (!inherited.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.subtitle", Placeholder.parsed("context", OBUContextManager.DEFAULT_CONTEXT));
            printLayer(plugin, sender, OBUContextManager.DEFAULT_CONTEXT, inherited, shadowed, boatShadowed);
        }
    }

    private static void printLayer(Wake plugin, CommandSender audience, @NonNull String contextName, @NonNull List<OBUSetting> settings, @NonNull Set<String> overriddenKeys, @NonNull Set<String> boatOverriddenKeys) {
        if (!OBUContextManager.DEFAULT_CONTEXT.equals(contextName) || !plugin.getStateDao().get(STATE_KEY_COLLAPSE_DEFAULT_CONTEXT, DEFAULT_COLLAPSE_DEFAULT_CONTEXT)) {
            printSettings(plugin, audience, settings, overriddenKeys, boatOverriddenKeys);
            return;
        }
        Component hover = plugin.getMessageManager().getComponent("commands.obu.context.settings", Placeholder.unparsed("context", contextName));
        for (OBUSetting setting : settings) {
            hover = hover.append(Component.newline()).append(settingLine(plugin, setting, overriddenKeys, boatOverriddenKeys, true));
        }
        audience.sendMessage(plugin.getMessageManager().getComponent("commands.obu.status.collapsed_layer", Placeholder.unparsed("count", String.valueOf(settings.size()))).hoverEvent(HoverEvent.showText(hover)));
    }

    private static void printSettings(Wake plugin, CommandSender audience, @NonNull List<OBUSetting> settings, @NonNull Set<String> overriddenKeys, @NonNull Set<String> boatOverriddenKeys) {
        for (OBUSetting setting : settings) {
            audience.sendMessage(settingLine(plugin, setting, overriddenKeys, boatOverriddenKeys, false));
        }
    }

    private static @NonNull Component settingLine(Wake plugin, @NonNull OBUSetting setting, @NonNull Set<String> overriddenKeys, @NonNull Set<String> boatOverriddenKeys, boolean flat) {
        boolean shadowedByBoat = boatOverriddenKeys.contains(setting.uniqueKey());
        boolean shadowed = shadowedByBoat || overriddenKeys.contains(setting.uniqueKey());
        Component line = plugin.getMessageManager().getComponent(
                shadowed ? "commands.obu.status.overridden" : "commands.obu.status.line",
                Placeholder.parsed("name", setting.definition().name()),
                flat ? Placeholder.unparsed("value", String.join(", ", OBUCommandHelper.displayArgs(setting))) : Placeholder.component("value", OBUCommandHelper.displayValue(plugin, setting)));
        return shadowed ? line.append(plugin.getMessageManager().getComponent(shadowedByBoat ? "commands.obu.status.boat_suffix" : "commands.obu.status.suffix")) : line;
    }
}