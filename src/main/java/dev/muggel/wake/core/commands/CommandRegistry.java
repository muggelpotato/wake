package dev.muggel.wake.core.commands;

import dev.muggel.wake.Wake;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CommandRegistry {
    private final Wake plugin;
    private final List<Command> registeredCommands = new ArrayList<>();

    public CommandRegistry(Wake plugin) {
        this.plugin = plugin;
    }

    public void register(String prefix, BaseCommand cmd) {
        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        commandMap.register(prefix, cmd);
        registeredCommands.add(cmd);
    }

    public void unregisterAll() {
        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        if (!(commandMap instanceof SimpleCommandMap simpleCommandMap)) return;

        try {
            Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(simpleCommandMap);

            List<String> keysToRemove = new java.util.ArrayList<>();
            for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
                if (registeredCommands.contains(entry.getValue())) {
                    keysToRemove.add(entry.getKey());
                }
            }

            for (String key : keysToRemove) {
                knownCommands.remove(key);
            }

            for (Command cmd : registeredCommands) {
                cmd.unregister(commandMap);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to unregister commands: " + e.getMessage());
            e.printStackTrace();
        } finally {
            registeredCommands.clear();
        }
    }
}
