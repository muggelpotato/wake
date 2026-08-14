package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.features.obu.clients.BoatLagInterceptor;
import dev.muggel.wake.features.obu.clients.HandshakeListener;
import dev.muggel.wake.features.obu.contexts.OBUContextManager.ContextCounts;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import dev.muggel.wake.features.obu.contexts.SandboxPurger;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;

public class ConfigCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-settings")
                .withHelpKey("commands.obu.help.config")
                .withoutPresets()
                .withGate(CommandNode.Gate.OPEN)
                .executesSender((ctx, sender) -> executeOverview(ctx, plugin))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "persistent-player-states", ContextDelivery.STATE_KEY_PERSISTENT_STATES, "words.feature.persistent_states"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "boat-lag-fix", BoatLagInterceptor.STATE_KEY_BOAT_LAG_FIX, "words.feature.boat_lag_fix"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "collapse-default-context", StatusCommand.STATE_KEY_COLLAPSE_DEFAULT_CONTEXT, "words.feature.collapse_default_context"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "update-nag", HandshakeListener.STATE_KEY_UPDATE_NAG, "words.feature.update_nag"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, OBUDefinition.setinterpolationten.commandName(), ContextDelivery.STATE_KEY_INTERPOLATION_TEN, "words.feature.setinterpolationten", () -> OBUCommandHelper.delivery(plugin).pushGlobals()))
                .addSubcommand(CommandNode.literal("keep-unused-sandboxes")
                        .arguments(CommandNode.argument("duration", StringArgumentType.string())
                                .executesSender((ctx, sender) -> executeKeepUnused(ctx, plugin))))
                .addSubcommand(CommandNode.literal("query-context-count")
                        .executesSender((ctx, sender) -> executeContextCount(ctx, plugin)));
    }

    private static int executeOverview(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        List<Component> rows = List.of(
                row(plugin, ContextDelivery.STATE_KEY_PERSISTENT_STATES, ContextDelivery.DEFAULT_PERSISTENT_STATES, "words.feature.persistent_states"),
                row(plugin, BoatLagInterceptor.STATE_KEY_BOAT_LAG_FIX, BoatLagInterceptor.DEFAULT_BOAT_LAG_FIX, "words.feature.boat_lag_fix"),
                row(plugin, StatusCommand.STATE_KEY_COLLAPSE_DEFAULT_CONTEXT, StatusCommand.DEFAULT_COLLAPSE_DEFAULT_CONTEXT, "words.feature.collapse_default_context"),
                row(plugin, HandshakeListener.STATE_KEY_UPDATE_NAG, HandshakeListener.DEFAULT_UPDATE_NAG, "words.feature.update_nag"),
                row(plugin, ContextDelivery.STATE_KEY_INTERPOLATION_TEN, ContextDelivery.DEFAULT_INTERPOLATION_TEN, "words.feature.setinterpolationten"),
                purgeRow(plugin));
        plugin.getMessageManager().send(sender, "commands.obu.config.overview.layout",
                Placeholder.component("rows", Component.join(JoinConfiguration.newlines(), rows)),
                CommandHelper.hint(plugin, "commands.obu.config.overview.hint"));
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull Component row(@NonNull Wake plugin, @NonNull String stateKey, boolean fallback, @NonNull String featureKey) {
        MessageManager messages = plugin.getMessageManager();
        String key = plugin.getStateDao().get(stateKey, fallback)
                ? "commands.obu.config.overview.rows.switch_enabled"
                : "commands.obu.config.overview.rows.switch_disabled";
        return messages.getComponent(key, Placeholder.component("name", messages.getComponent(featureKey)));
    }

    private static @NonNull Component countRow(@NonNull Wake plugin, @NonNull String key, int count) {
        return plugin.getMessageManager().getComponent(key, Placeholder.unparsed("count", String.valueOf(count)));
    }

    private static @NonNull Component purgeRow(@NonNull Wake plugin) {
        String keep = SandboxPurger.configuredKeep(plugin);
        return SandboxPurger.parseKeepMillis(keep) > 0
                ? plugin.getMessageManager().getComponent("commands.obu.config.overview.rows.purge", Placeholder.unparsed("duration", keep))
                : plugin.getMessageManager().getComponent("commands.obu.config.overview.rows.purge_unset");
    }

    private static int executeContextCount(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        ContextCounts counts = OBUCommandHelper.contexts(plugin).countContexts();
        if (counts == null) {
            plugin.getMessageManager().send(sender, "commands.obu.config.context_unavailable");
            return 0;
        }
        List<Component> rows = List.of(
                countRow(plugin, "commands.obu.config.contexts.server", counts.serverContexts()),
                countRow(plugin, "commands.obu.config.contexts.sandboxes", counts.sandboxes()),
                countRow(plugin, "commands.obu.config.contexts.total", counts.total()));
        plugin.getMessageManager().send(sender, "commands.obu.config.contexts.layout",
                Placeholder.component("rows", Component.join(JoinConfiguration.newlines(), rows)));
        return Command.SINGLE_SUCCESS;
    }

    private static int executeKeepUnused(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String raw = StringArgumentType.getString(ctx, "duration");
        long millis = SandboxPurger.parseKeepMillis(raw);
        if (millis < 0) {
            plugin.getMessageManager().send(sender, "commands.obu.config.invalid_duration", Placeholder.unparsed("input", raw));
            return 0;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        plugin.getStateDao().set(SandboxPurger.STATE_KEY_KEEP_UNUSED, normalized);
        OBUCommandHelper.module(plugin).schedulePurgerSweep();
        if (millis == 0) {
            plugin.getMessageManager().send(sender, "commands.obu.config.purge_disabled");
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.config.purge_set", Placeholder.unparsed("duration", normalized));
        }
        return Command.SINGLE_SUCCESS;
    }
}