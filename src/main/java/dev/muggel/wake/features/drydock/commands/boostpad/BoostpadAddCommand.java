package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.BlockArgumentType;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;
import dev.muggel.wake.features.drydock.commands.DrydockCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jspecify.annotations.NonNull;

public class BoostpadAddCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("add")
                .arguments(
                        CommandNode.argument("block", BlockArgumentType.block()),
                        CommandNode.argument("x", DoubleArgumentType.doubleArg()),
                        CommandNode.argument("y", DoubleArgumentType.doubleArg()),
                        CommandNode.argument("z", DoubleArgumentType.doubleArg()),
                        CommandNode.argument("delay_ms", IntegerArgumentType.integer(0))
                                .executesSender((ctx, sender) -> execute(ctx, plugin, BoostpadConfig.DEFAULT_HITBOX_PERCENT)),
                        CommandNode.argument("hitbox_percent", IntegerArgumentType.integer(0, BoostpadConfig.MAX_HITBOX_PERCENT))
                                .executesSender((ctx, sender) -> execute(ctx, plugin, IntegerArgumentType.getInteger(ctx, "hitbox_percent"))));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin, int hitboxPercent) {
        String blockKey = ctx.getArgument("block", String.class);
        double forceX = DoubleArgumentType.getDouble(ctx, "x");
        double forceY = DoubleArgumentType.getDouble(ctx, "y");
        double forceZ = DoubleArgumentType.getDouble(ctx, "z");
        long delayMs = IntegerArgumentType.getInteger(ctx, "delay_ms");
        BoostpadConfig newConfig = new BoostpadConfig(blockKey, true, forceX, forceY, forceZ, delayMs, hitboxPercent);
        DrydockCommandHelper.boostpads(plugin).saveBoostpadConfig(newConfig);
        plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.drydock.boostpad.block_added");
        return Command.SINGLE_SUCCESS;
    }
}