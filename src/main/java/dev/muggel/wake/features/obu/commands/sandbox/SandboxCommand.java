package dev.muggel.wake.features.obu.commands.sandbox;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

public class SandboxCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-sandbox")
                .withHelpKey("commands.obu.help.sandbox")
                .addSubcommand(SandboxCreateCommand.getNode(plugin))
                .addSubcommand(SandboxForkCommand.getNode(plugin))
                .addSubcommand(SandboxImportCommand.getNode(plugin))
                .addSubcommand(SandboxPublishCommand.getNode(plugin))
                .addSubcommand(SandboxDeleteCommand.getNode(plugin))
                .addSubcommand(SandboxSwitchCommand.getNode(plugin))
                .addSubcommand(SandboxExitCommand.getNode(plugin))
                .addSubcommand(SandboxViewCommand.getNode(plugin))
                .addSubcommand(SandboxExportCommand.getNode(plugin));
    }
}