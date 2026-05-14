package dev.muggel.wake.core.commands;

import org.bukkit.command.CommandSender;
import java.util.Collections;
import java.util.List;

public interface SubCommand {
    String getName();
    String getPermission();
    void execute(CommandSender sender, String label, String[] args);
    default List<String> suggest(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
