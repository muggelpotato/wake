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
 * handles permission checks
 */
public abstract class BaseCommand extends Command {
    private boolean playerOnly = false;
    private String parentPermission = null;
    private String specificPermission = null;

    protected BaseCommand(@NotNull String name) {
        super(name);
    }

    protected BaseCommand(@NotNull String name, @NotNull String description, @NotNull String usageMessage, @NotNull List<String> aliases) {
        super(name, description, usageMessage, aliases);
    }

    public void setPlayerOnly(boolean playerOnly) {
        this.playerOnly = playerOnly;
    }

    public void setParentPermission(String parentPermission) {
        this.parentPermission = parentPermission;
        super.setPermission(parentPermission);
    }

    @Override
    public void setPermission(String permission) {
        if (this.parentPermission == null) {
            super.setPermission(permission);
        }
        this.specificPermission = permission;
    }

    @Override
    public String getPermission() {
        return this.specificPermission;
    }

    @Override
    public boolean testPermission(@NotNull CommandSender target) {
        return hasBasePermission(target) && hasSpecificPermission(target);
    }

    private boolean hasBasePermission(@NotNull CommandSender target) {
        return parentPermission == null || target.hasPermission(parentPermission);
    }

    private boolean hasSpecificPermission(@NotNull CommandSender target) {
        return specificPermission == null || target.hasPermission(specificPermission);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        if (playerOnly && !(sender instanceof Player)) {
            sender.sendMessage(Component.text("This command can only be executed by players.", WakeColors.ERROR));
            return true;
        }

        if (!hasBasePermission(sender)) {
            sender.sendMessage(Component.text("No permission (Module access denied).", WakeColors.ERROR));
            return true;
        }

        if (!hasSpecificPermission(sender)) {
            sender.sendMessage(Component.text("No permission.", WakeColors.ERROR));
            return true;
        }

        return onExecute(sender, commandLabel, args);
    }

    public abstract boolean onExecute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args);

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) throws IllegalArgumentException {
        if (!hasBasePermission(sender) || !hasSpecificPermission(sender)) {
            return Collections.emptyList();
        }
        return onTabComplete(sender, alias, args);
    }

    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
