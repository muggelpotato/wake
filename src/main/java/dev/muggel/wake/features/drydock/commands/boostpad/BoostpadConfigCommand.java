package dev.muggel.wake.features.drydock.commands.boostpad;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.drydock.boostpads.BoostpadDetectorListener;
import org.jspecify.annotations.NonNull;

public class BoostpadConfigCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("config")
                .addSubcommand(CommandHelper.toggleCommand(plugin, "early-out-x", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_X, "words.feature.boostpad_early_out_x"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "early-out-y", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_Y, "words.feature.boostpad_early_out_y"))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "early-out-z", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_Z, "words.feature.boostpad_early_out_z"));
    }
}