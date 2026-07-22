package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class ContextCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-context")
                .executesSender((ctx, sender) -> executeList(ctx, plugin))
                .addSubcommand(CommandNode.literal("delete")
                        .arguments(CommandNode.argument("name", StringArgumentType.string())
                                .suggests((c, b) -> suggestDeletable(c, b, plugin))
                                .executesSender((ctx, sender) -> executeDelete(ctx, plugin))))
                .arguments(CommandNode.argument("name", StringArgumentType.string())
                        .suggests((c, b) -> suggestContexts(c, b, plugin))
                        .executesEntity((ctx, target) -> executeApply(ctx, target, plugin)));
    }

    private static int executeList(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        if (contextManager.getContextNames().isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.context.empty");
            return 0;
        }
        List<OBUContext> serverContexts = new ArrayList<>();
        List<OBUContext> mySandboxes = new ArrayList<>();
        for (String contextName : contextManager.getContextNames()) {
            if (contextName.equals(OBUDefinition.CONTEXT_EMPTY) || contextName.equals(OBUDefinition.CONTEXT_PERSONAL)) continue;
            OBUContext context = contextManager.getContext(contextName);
            if (context == null) continue;
            if (context.isSandbox()) {
                if (sender instanceof Player p && p.getUniqueId().equals(context.ownerUuid())) {
                    mySandboxes.add(context);
                } else if (!(sender instanceof Player)) {
                    mySandboxes.add(context);
                }
            } else {
                serverContexts.add(context);
            }
        }
        if (!serverContexts.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.context.header_server");
            for (OBUContext context : serverContexts) {
                sendContextItem(sender, plugin, context);
            }
        }
        if (!mySandboxes.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.context.header_sandbox");
            for (OBUContext context : mySandboxes) {
                sendContextItem(sender, plugin, context);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeApply(@NonNull CommandContext<CommandSourceStack> ctx, Entity target, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String contextName = StringArgumentType.getString(ctx, "name");
        OBUContext context = contextManager.getContext(contextName);
        if (context != null && context.isSandbox() && sender instanceof Player) {
            context = null;
        }
        if (context == null && sender instanceof Player requester) {
            context = contextManager.getContext(OBUContextManager.sandboxKey(contextName, requester.getUniqueId()));
        }
        if (context == null) {
            plugin.getMessageManager().send(sender, "commands.obu.context.missing", Placeholder.unparsed("context", contextName));
            return 0;
        }
        String shownName = OBUContextManager.displayName(context.name());
        if (target instanceof Player p) {
            String previousSandbox = service.getPlayerActiveSandbox(p);
            service.setPlayerActiveSandbox(p, null);
            service.applyContext(p, context);
            service.getSyncManager().syncPlayer(p);
            if (previousSandbox != null && !previousSandbox.equals(context.name())) {
                plugin.getMessageManager().send(p, "commands.obu.context.applied_from_sandbox",
                        Placeholder.unparsed("sandbox", OBUContextManager.displayName(previousSandbox)),
                        Placeholder.unparsed("context", shownName));
                if (!p.equals(sender)) {
                    plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.unparsed("context", shownName), Placeholder.component("target", OBUCommandHelper.targetName(plugin, p, sender)));
                }
            } else {
                plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.unparsed("context", shownName), Placeholder.component("target", OBUCommandHelper.targetName(plugin, p, sender)));
            }
        } else if (target instanceof Boat boat) {
            service.applyEntityContext(boat, context.name());
            plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.unparsed("context", shownName), Placeholder.component("target", OBUCommandHelper.targetName(plugin, boat, sender)));
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDelete(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contextManager.getContext(lower);
        if (context == null || context.isSandbox()) {
            plugin.getMessageManager().send(sender, "commands.obu.context.missing", Placeholder.unparsed("context", name));
            return 0;
        }
        if (OBUContextManager.isReserved(lower)) {
            plugin.getMessageManager().send(sender, "commands.obu.context.cannot_delete", Placeholder.unparsed("context", name));
            return 0;
        }
        service.deleteContextAndEvict(lower);
        plugin.getMessageManager().send(sender, "commands.obu.context.deleted", Placeholder.unparsed("context", name));
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull CompletableFuture<Suggestions> suggestContexts(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, Wake plugin) {
        return OBUCommandHelper.suggestContexts(ctx, builder, plugin,
                c -> !c.name().equals(OBUDefinition.CONTEXT_EMPTY) && !c.name().equals(OBUDefinition.CONTEXT_PERSONAL));
    }

    private static @NonNull CompletableFuture<Suggestions> suggestDeletable(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, Wake plugin) {
        return OBUCommandHelper.suggestContexts(ctx, builder, plugin,
                c -> !c.isSandbox()
                        && !c.name().equals(OBUDefinition.CONTEXT_EMPTY)
                        && !c.name().equals(OBUDefinition.CONTEXT_PERSONAL)
                        && !c.name().equals("default"));
    }

    private static void sendContextItem(@NonNull CommandSender sender, Wake plugin, OBUContext context) {
        String shownName = sender instanceof Player ? OBUContextManager.displayName(context.name()) : context.name();
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
        for (OBUSetting setting : context.settings()) {
            hoverText = hoverText.append(Component.newline())
                    .append(plugin.getMessageManager().getComponent("commands.obu.status.line",
                            Placeholder.parsed("name", setting.definition().name()),
                            Placeholder.unparsed("value", String.join(", ", setting.args()))));
        }
        return hoverText;
    }
}