package dev.muggel.wake.core.commands;

import dev.muggel.wake.Wake;

import java.util.List;

public class WakeCommand extends ParentCommand {

    public WakeCommand(Wake plugin) {
        super("wake");
        this.setAliases(List.of("wa"));
        this.setDescription("Main command for the Wake plugin.");
        this.setUsage("/wake <subcommand>");
        this.setPermission("wake.commands.use");

        registerSubCommand(new WakeReloadSubCommand(plugin));
        registerSubCommand(new WakeKillBoatSubCommand(plugin));
    }
}
