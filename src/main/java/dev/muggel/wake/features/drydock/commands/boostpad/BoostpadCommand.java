package dev.muggel.wake.features.drydock.commands.boostpad;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

public class BoostpadCommand {
    public static final String STATE_KEY_ENABLED = "drydock.boostpads_enabled";
    public static final boolean DEFAULT_ENABLED = false;

    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("boostpad")
                .addSubcommand(BoostpadAddCommand.getNode(plugin))
                .addSubcommand(BoostpadRemoveCommand.getNode(plugin))
                .addSubcommand(BoostpadListCommand.getNode(plugin))
                .addSubcommand(BoostpadToggleCommand.getNode(plugin));
    }
}