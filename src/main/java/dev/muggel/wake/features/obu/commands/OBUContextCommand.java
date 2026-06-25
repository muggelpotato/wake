package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.obu.commands.util.OBUCommandBuilder;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class OBUContextCommand {

    public static void register(@NonNull LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(WakeCommandBuilder.literal("-context", "wake.obu.commands.context")
                .executes(OBUCommandBuilder.executes(plugin, (ctx, service, contextManager) -> {
                    CommandSender sender = ctx.getSource().getSender();
                    if (contextManager.getContextNames().isEmpty()) {
                        plugin.getMessageManager().send(sender, "commands.obu.context.empty");
                        return 0;
                    }

                    plugin.getMessageManager().send(sender, "commands.obu.context.header");
                    for (String contextName : contextManager.getContextNames()) {
                        if (contextName.equals("empty")) continue;
                        OBUContext context = contextManager.getContext(contextName);
                        Component hoverText = getContextHoverComponent(context, plugin)
                                .append(Component.newline()).append(Component.newline())
                                .append(plugin.getMessageManager().getComponent("commands.obu.context.hover"));

                        Component contextComp = plugin.getMessageManager().getComponent("commands.obu.context.item", Placeholder.parsed("context", contextName))
                                .clickEvent(ClickEvent.runCommand("/wobu -context " + contextName))
                                .hoverEvent(HoverEvent.showText(hoverText));

                        sender.sendMessage(contextComp);
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("-remove")
                        .executes(OBUCommandBuilder.executesEntityNoSmart(plugin, (ctx, target, service, contextManager) -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (target instanceof Player p) {
                                service.applyDefaultContext(p);
                                service.getSyncManager().syncPlayer(p);
                                String targetStr = p.equals(sender) ? "you" : p.getName();
                                plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.parsed("context", "vanilla defaults (removed)"), Placeholder.parsed("target", targetStr));
                            } else if (target instanceof Boat boat) {
                                service.applyEntityContext(boat, null);
                                plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.parsed("context", "vanilla defaults (removed)"), Placeholder.parsed("target", "the boat"));
                            } else {
                                plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
                            }
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemaining().toLowerCase();
                            OBUModule module = plugin.getModule(OBUModule.class);
                            if (module != null) {
                                module.getContextManager().getContextNames().stream()
                                        .filter(name -> !name.equals("empty"))
                                        .filter(name -> name.toLowerCase().startsWith(remaining))
                                        .forEach(builder::suggest);
                            }
                            return builder.buildFuture();
                        })
                        .executes(OBUCommandBuilder.executesEntityNoSmart(plugin, (ctx, target, service, contextManager) -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String contextName = StringArgumentType.getString(ctx, "name");
                            OBUContext context = contextManager.getContext(contextName);

                            if (context == null) {
                                plugin.getMessageManager().send(sender, "commands.obu.context.missing", Placeholder.parsed("context", contextName));
                                return 0;
                            }

                            if (target instanceof Player p) {
                                service.setPlayerActiveSandbox(p, null);
                                service.applyContext(p, context);
                                service.getSyncManager().syncPlayer(p);
                                String targetStr = p.equals(sender) ? "you" : p.getName();
                                plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.parsed("context", contextName), Placeholder.parsed("target", targetStr));
                            } else if (target instanceof Boat boat) {
                                service.applyEntityContext(boat, context.name());
                                plugin.getMessageManager().send(sender, "commands.obu.context.applied", Placeholder.parsed("context", contextName), Placeholder.parsed("target", "the boat"));
                            } else {
                                plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    private static Component getContextHoverComponent(@NonNull OBUContext context, @NonNull Wake plugin) {
        Component hoverText = plugin.getMessageManager().getComponent("commands.obu.context.settings", Placeholder.parsed("context", context.name()));
        for (OBUSetting setting : context.getSettings()) {
            hoverText = hoverText.append(Component.newline())
                    .append(plugin.getMessageManager().getComponent("commands.obu.status.line", 
                            Placeholder.parsed("name", setting.definition().name()), 
                            Placeholder.parsed("value", String.join(", ", setting.args()))));
        }
        return hoverText;
    }
}

