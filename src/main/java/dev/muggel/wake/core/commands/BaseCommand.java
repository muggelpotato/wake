package dev.muggel.wake.core.commands;

import dev.muggel.wake.core.WakeColors;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * base class for all wake commands
 * handles permission checks and player-only restriction
 */
public abstract class BaseCommand extends Command {
    private boolean playerOnly = false;

    protected BaseCommand(@NotNull String name) {
        super(name);
    }

    protected BaseCommand(@NotNull String name, @NotNull String description, @NotNull String usageMessage, @NotNull List<String> aliases) {
        super(name, description, usageMessage, aliases);
    }

    public void setPlayerOnly(boolean playerOnly) {
        this.playerOnly = playerOnly;
    }

    @Override
    public boolean testPermission(@NotNull CommandSender target) {
        String perm = getPermission();
        if (perm == null || perm.isEmpty()) {
            return true;
        }
        return target.hasPermission(perm);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if (playerOnly && !(sender instanceof Player)) {
            sender.sendMessage(Component.text("This command can only be executed by players.", WakeColors.ERROR));
            return true;
        }

        if (!testPermission(sender)) {
            sender.sendMessage(Component.text("No permission.", WakeColors.ERROR));
            return true;
        }

        return onExecute(sender, commandLabel, args);
    }

    public abstract boolean onExecute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args);

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if (playerOnly && !(sender instanceof Player)) {
            return Collections.emptyList();
        }
        if (!testPermission(sender)) {
            return Collections.emptyList();
        }
        return onTabComplete(sender, alias, args);
    }

    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
