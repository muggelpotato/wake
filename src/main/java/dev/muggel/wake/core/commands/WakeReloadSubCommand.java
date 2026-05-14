package dev.muggel.wake.core.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.WakeColors;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

public class WakeReloadSubCommand implements SubCommand {
    private final Wake plugin;

    public WakeReloadSubCommand(Wake plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() { return "reload"; }

    @Override
    public String getPermission() { return "wake.commands.reload"; }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        plugin.reloadSettings(sender);

        sender.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                .append(Component.text("Configuration reloaded", WakeColors.PRIMARY)));
    }
}
