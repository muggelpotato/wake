package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;
import dev.muggel.wake.features.drydock.boostpads.BoostpadDetectorListener;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import dev.muggel.wake.features.drydock.commands.DrydockCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.NonNull;

public class BoostpadToggleCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("toggle")
                .executesSender((ctx, sender) -> executeGlobal(ctx, plugin))
                .arguments(CommandNode.argument("block", DrydockCommandHelper.boostpadKey(plugin))
                        .executesSender((ctx, sender) -> executeBlock(ctx, plugin)));
    }

    private static int executeGlobal(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        boolean newState = plugin.getStateDao().toggle(BoostpadDetectorListener.STATE_KEY_ENABLED, BoostpadDetectorListener.DEFAULT_ENABLED);
        DrydockCommandHelper.boostpads(plugin).refreshRegistration();
        String stateKey = newState ? "commands.drydock.boostpad.enabled" : "commands.drydock.boostpad.disabled";
        plugin.getMessageManager().send(ctx.getSource().getSender(), stateKey);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeBlock(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        BoostpadRegistry boostpads = DrydockCommandHelper.boostpads(plugin);
        String blockKey = ctx.getArgument("block", String.class);
        BoostpadConfig existing = boostpads.cachedBoostpads().get(blockKey);
        if (existing == null) {
            plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.drydock.boostpad.block_not_found");
            return 0;
        }
        boolean newState = !existing.enabled();
        BoostpadConfig newConfig = new BoostpadConfig(existing.blockKey(), newState, existing.forceX(), existing.forceY(), existing.forceZ(), existing.delayMs(), existing.padding());
        boostpads.saveBoostpadConfig(newConfig);
        String stateKey = newState ? "commands.drydock.boostpad.block_enabled" : "commands.drydock.boostpad.block_disabled";
        plugin.getMessageManager().send(ctx.getSource().getSender(), stateKey, Placeholder.unparsed("block", MessageManager.stripNamespace(blockKey)));
        return Command.SINGLE_SUCCESS;
    }
}