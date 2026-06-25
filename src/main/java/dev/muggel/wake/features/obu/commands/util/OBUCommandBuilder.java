package dev.muggel.wake.features.obu.commands.util;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class OBUCommandBuilder {

    @FunctionalInterface
    public interface OBUExecution {
        int run(CommandContext<CommandSourceStack> ctx, OBUService service, OBUContextManager contextManager);
    }

    @FunctionalInterface
    public interface OBUPlayerExecution {
        int run(CommandContext<CommandSourceStack> ctx, Player player, OBUService service, OBUContextManager contextManager);
    }

    @FunctionalInterface
    public interface OBUEntityExecution {
        int run(CommandContext<CommandSourceStack> ctx, Entity entity, OBUService service, OBUContextManager contextManager);
    }

    public static Command<CommandSourceStack> executes(Wake plugin, OBUExecution execution) {
        return ctx -> {
            OBUModule obuModule = plugin.getModule(OBUModule.class);
            if (obuModule == null) {
                plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.obu.not_loaded");
                return 0;
            }
            return execution.run(ctx, obuModule.getObuService(), obuModule.getContextManager());
        };
    }

    public static Command<CommandSourceStack> executesPlayer(Wake plugin, OBUPlayerExecution execution) {
        return executes(plugin, (ctx, service, contextManager) -> {
            CommandSender sender = ctx.getSource().getSender();
            if (!(sender instanceof Player player)) {
                plugin.getMessageManager().send(sender, "commands.only_players");
                return 0;
            }
            return execution.run(ctx, player, service, contextManager);
        });
    }

    public static Command<CommandSourceStack> executesEntity(Wake plugin, OBUEntityExecution execution) {
        return executes(plugin, (ctx, service, contextManager) -> {
            CommandSender sender = ctx.getSource().getSender();
            Entity target = ctx.getSource().getExecutor();
            if (target == null) {
                if (sender instanceof Entity e) {
                    target = e;
                } else {
                    plugin.getMessageManager().send(sender, "commands.only_entities");
                    return 0;
                }
            }
            if (target instanceof Player p) {
                Entity rayTraceTarget = p.getTargetEntity(16);
                if (rayTraceTarget instanceof Boat boat) {
                    target = boat;
                }
            }
            return execution.run(ctx, target, service, contextManager);
        });
    }

    public static Command<CommandSourceStack> executesEntityNoSmart(Wake plugin, OBUEntityExecution execution) {
        return executes(plugin, (ctx, service, contextManager) -> {
            CommandSender sender = ctx.getSource().getSender();
            Entity target = ctx.getSource().getExecutor();
            if (target == null) {
                if (sender instanceof Entity e) {
                    target = e;
                } else {
                    plugin.getMessageManager().send(sender, "commands.only_entities");
                    return 0;
                }
            }
            return execution.run(ctx, target, service, contextManager);
        });
    }
}
