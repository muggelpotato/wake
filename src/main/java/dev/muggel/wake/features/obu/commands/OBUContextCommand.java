package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class OBUContextCommand {

    public static @NonNull CommandNode getNode(Wake plugin) {
        CommandNode contextNode = CommandNode.literal("-context")
                .withModule(OBUModule.class)
                .executesSender((ctx, sender) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUContextManager contextManager = obuModule.getContextManager();

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
                });

        CommandNode nameArg = CommandNode.argument("name", StringArgumentType.string())
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
                .executesEntityNoSmart((ctx, target) -> {
                    CommandSender sender = ctx.getSource().getSender();
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

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
                });

        contextNode.addSubcommand(nameArg);
        return contextNode;
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