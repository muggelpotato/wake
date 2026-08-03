package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.drydock.commands.DrydockCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jspecify.annotations.NonNull;

public class BoostpadRemoveCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("remove")
                .arguments(CommandNode.argument("block", DrydockCommandHelper.boostpadKey(plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        String blockKey = ctx.getArgument("block", String.class);
        DrydockCommandHelper.boostpads(plugin).deleteBoostpadConfig(blockKey);
        plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.drydock.boostpad.block_removed");
        return Command.SINGLE_SUCCESS;
    }
}