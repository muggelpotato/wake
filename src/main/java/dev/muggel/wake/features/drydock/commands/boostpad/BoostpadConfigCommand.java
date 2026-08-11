package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.drydock.boostpads.BoostpadDetectorListener;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class BoostpadConfigCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("config")
                .addSubcommand(CommandHelper.toggleCommand(plugin, "early-out-x", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_X, "words.feature.boostpad_early_out_x"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "early-out-y", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_Y, "words.feature.boostpad_early_out_y"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "early-out-z", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_Z, "words.feature.boostpad_early_out_z"))
                .addSubcommand(CommandNode.literal("global-cooldown")
                        .arguments(CommandNode.argument("ms", IntegerArgumentType.integer(0))
                                .executesSender((ctx, sender) -> executeGlobalCooldown(ctx, plugin))));
    }

    private static int executeGlobalCooldown(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        int cooldownMs = IntegerArgumentType.getInteger(ctx, "ms");
        plugin.getStateDao().set(BoostpadDetectorListener.STATE_KEY_GLOBAL_COOLDOWN_MS, cooldownMs);
        if (cooldownMs == 0) {
            plugin.getMessageManager().send(sender, "commands.drydock.boostpad.global_cooldown_disabled");
        } else {
            plugin.getMessageManager().send(sender, "commands.drydock.boostpad.global_cooldown_set", Placeholder.unparsed("delay", String.valueOf(cooldownMs)));
        }
        return Command.SINGLE_SUCCESS;
    }
}