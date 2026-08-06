package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.KeyArgumentType;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import dev.muggel.wake.features.drydock.commands.DrydockCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class BoostpadAddCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("add")
                .arguments(
                        CommandNode.argument("block", KeyArgumentType.block()),
                        CommandNode.argument("x", DoubleArgumentType.doubleArg()),
                        CommandNode.argument("y", DoubleArgumentType.doubleArg()),
                        CommandNode.argument("z", DoubleArgumentType.doubleArg()),
                        CommandNode.argument("delay_ms", IntegerArgumentType.integer(0))
                                .executesSender((ctx, sender) -> execute(ctx, plugin, BoostpadConfig.DEFAULT_PADDING)),
                        CommandNode.argument("padding", DoubleArgumentType.doubleArg(0.0, BoostpadConfig.MAX_PADDING))
                                .executesSender((ctx, sender) -> execute(ctx, plugin, DoubleArgumentType.getDouble(ctx, "padding"))));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin, double padding) {
        CommandSender sender = ctx.getSource().getSender();
        BoostpadRegistry boostpads = DrydockCommandHelper.boostpads(plugin);
        if (!boostpads.isLoaded()) {
            plugin.getMessageManager().send(sender, "commands.drydock.boostpad.table_unavailable");
            return 0;
        }
        String blockKey = ctx.getArgument("block", String.class);
        double forceX = DoubleArgumentType.getDouble(ctx, "x");
        double forceY = DoubleArgumentType.getDouble(ctx, "y");
        double forceZ = DoubleArgumentType.getDouble(ctx, "z");
        long delayMs = IntegerArgumentType.getInteger(ctx, "delay_ms");
        BoostpadConfig existing = DrydockCommandHelper.storedPad(boostpads, blockKey);
        boolean enabled = existing == null || existing.enabled();
        boostpads.saveBoostpadConfig(new BoostpadConfig(existing == null ? blockKey : existing.blockKey(), enabled, forceX, forceY, forceZ, delayMs, padding));
        plugin.getMessageManager().send(sender, existing == null ? "commands.drydock.boostpad.block_added" : "commands.drydock.boostpad.block_updated");
        return Command.SINGLE_SUCCESS;
    }
}