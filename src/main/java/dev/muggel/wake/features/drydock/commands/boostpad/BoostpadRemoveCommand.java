package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import dev.muggel.wake.features.drydock.commands.DrydockCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class BoostpadRemoveCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("remove")
                .arguments(CommandNode.argument("block", DrydockCommandHelper.boostpadKey(plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        BoostpadRegistry boostpads = DrydockCommandHelper.boostpads(plugin);
        BoostpadConfig pad = DrydockCommandHelper.storedPad(boostpads, ctx.getArgument("block", String.class));
        if (pad == null) {
            plugin.getMessageManager().send(sender, "commands.drydock.boostpad.block_not_found");
            return 0;
        }
        boostpads.deleteBoostpadConfig(pad.blockKey());
        plugin.getMessageManager().send(sender, "commands.drydock.boostpad.block_removed");
        return Command.SINGLE_SUCCESS;
    }
}