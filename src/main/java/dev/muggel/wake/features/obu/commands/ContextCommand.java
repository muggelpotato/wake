package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ContextCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-context")
                .withHelpKey("commands.obu.help.context")
                .withPreset(PermissionPreset.PLAYER)
                .withGate(CommandNode.Gate.OPEN)
                .executesSender((ctx, subject) -> executeList(ctx, subject, plugin))
                .addSubcommand(CommandNode.literal("-delete")
                        .withoutPresets()
                        .arguments(CommandNode.argument("name", NameArgumentType.greedy())
                                .suggests((c, b) -> suggestDeletable(c, b, plugin))
                                .executesSender((ctx, subject) -> executeDelete(ctx, plugin))))
                .arguments(CommandNode.argument("name", NameArgumentType.greedy())
                        .withGate((source, target) -> OBUCommandHelper.requireClient(plugin, source, target))
                        .suggests((c, b) -> OBUCommandHelper.suggestContexts(c, b, plugin, context -> true))
                        .executesEntity((ctx, target) -> executeApply(ctx, target, plugin)));
    }

    private static int executeList(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        List<OBUContext> serverContexts = new ArrayList<>();
        List<OBUContext> mySandboxes = new ArrayList<>();
        for (String contextName : contextManager.getContextNames()) {
            if (OBUContextManager.isInternal(contextName)) continue;
            OBUContext context = contextManager.getContext(contextName);
            if (context == null) continue;
            if (context.isSandbox()) {
                if (!(subject instanceof Player p) || p.getUniqueId().equals(context.ownerUuid())) {
                    mySandboxes.add(context);
                }
            } else {
                serverContexts.add(context);
            }
        }
        if (!serverContexts.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.context.header_server");
            for (OBUContext context : serverContexts) {
                sendContextItem(sender, subject, plugin, context);
            }
        }
        if (!mySandboxes.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.context.header_sandbox");
            for (OBUContext context : mySandboxes) {
                sendContextItem(sender, subject, plugin, context);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeApply(@NonNull CommandContext<CommandSourceStack> ctx, Entity target, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        ContextDelivery service = OBUCommandHelper.delivery(plugin);
        String contextName = StringArgumentType.getString(ctx, "name");
        OBUContext context = OBUCommandHelper.resolveForSubject(plugin, target, contextName);
        if (context == null) {
            plugin.getMessageManager().send(sender, "commands.obu.context.missing", Placeholder.unparsed("context", contextName));
            return 0;
        }
        String shownName = OBUContextManager.displayName(context.name());
        if (target instanceof Player p) {
            String previousSandbox = OBUCommandHelper.active(plugin).sandboxOf(p.getUniqueId());
            service.setPlayerActiveSandbox(p, null);
            service.applyContext(p, context);
            OBUCommandHelper.sync(plugin).syncPlayer(p);
            String leftSandbox = previousSandbox != null && !previousSandbox.equals(context.name()) ? previousSandbox : null;
            if (leftSandbox != null) {
                plugin.getMessageManager().send(p, "commands.obu.context.applied_from_sandbox",
                        Placeholder.unparsed("sandbox", OBUContextManager.displayName(leftSandbox)),
                        Placeholder.unparsed("context", shownName));
            }
            if (leftSandbox == null || !p.equals(sender)) {
                plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.unparsed("context", shownName), Placeholder.component("target", OBUCommandHelper.targetName(plugin, p, sender)));
            }
        } else if (target instanceof Boat boat) {
            service.applyEntityContext(boat, context.name());
            plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.unparsed("context", shownName), Placeholder.component("target", OBUCommandHelper.targetName(plugin, boat, sender)));
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDelete(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        ContextDelivery service = OBUCommandHelper.delivery(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        OBUContext context = contextManager.getContext(name);
        if (context == null || context.isSandbox()) {
            plugin.getMessageManager().send(sender, "commands.obu.context.missing", Placeholder.unparsed("context", name));
            return 0;
        }
        if (OBUContextManager.isReserved(name)) {
            plugin.getMessageManager().send(sender, "commands.obu.context.cannot_delete", Placeholder.unparsed("context", name));
            return 0;
        }
        for (Player evicted : service.deleteContextAndEvict(name)) {
            if (!evicted.equals(sender)) {
                plugin.getMessageManager().send(evicted, "commands.obu.context.kicked", Placeholder.unparsed("context", name));
            }
        }
        plugin.getMessageManager().send(sender, "commands.obu.context.deleted", Placeholder.unparsed("context", name));
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull CompletableFuture<Suggestions> suggestDeletable(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, Wake plugin) {
        return OBUCommandHelper.suggestContexts(ctx, builder, plugin,
                context -> !context.isSandbox() && !OBUContextManager.isReserved(context.name()));
    }

    private static void sendContextItem(@NonNull CommandSender sender, CommandSender subject, Wake plugin, OBUContext context) {
        String shownName = subject instanceof Player ? OBUContextManager.displayName(context.name()) : context.name();
        Component hoverText = getContextHoverComponent(context, plugin, shownName)
                .append(Component.newline()).append(Component.newline())
                .append(plugin.getMessageManager().getComponent("commands.obu.context.hover"));
        Component contextComp = plugin.getMessageManager().getComponent("commands.obu.context.item", Placeholder.unparsed("context", shownName))
                .clickEvent(ClickEvent.runCommand("/wobu -context " + shownName))
                .hoverEvent(HoverEvent.showText(hoverText));
        sender.sendMessage(contextComp);
    }

    private static Component getContextHoverComponent(@NonNull OBUContext context, @NonNull Wake plugin, @NonNull String shownName) {
        Component hoverText = plugin.getMessageManager().getComponent("commands.obu.context.settings", Placeholder.unparsed("context", shownName));
        if (context.settings().isEmpty()) {
            return hoverText.append(Component.newline()).append(plugin.getMessageManager().getComponent("commands.obu.no_settings"));
        }
        for (OBUSetting setting : context.settings()) {
            hoverText = hoverText.append(Component.newline())
                    .append(plugin.getMessageManager().getComponent("commands.obu.status.line",
                            Placeholder.parsed("name", setting.definition().name()),
                            Placeholder.unparsed("value", String.join(", ", OBUCommandHelper.displayArgs(setting)))));
        }
        return hoverText;
    }
}