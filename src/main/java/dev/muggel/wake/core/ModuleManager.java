package dev.muggel.wake.core;

import dev.muggel.wake.Wake;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ModuleManager {
    private final Wake plugin;
    private final Map<String, Module> registeredModules = new LinkedHashMap<>();
    private final Set<String> activeModules = new HashSet<>();

    public ModuleManager(Wake plugin) {
        this.plugin = plugin;
    }

    public void registerModule(Module module) {
        registeredModules.put(module.getId(), module);
    }

    public void syncModules(CommandSender sender) {
        for (Module module : registeredModules.values()) {
            try {
                String configPath = "wake.modules." + module.getId();
                boolean shouldBeEnabled = plugin.getConfig().getBoolean(configPath, true);
                boolean isCurrentlyEnabled = activeModules.contains(module.getId());

                if (shouldBeEnabled && !isCurrentlyEnabled) {
                    module.onEnable(plugin);
                    activeModules.add(module.getId());
                    reportStatus(sender, "Module '" + module.getId() + "' has been enabled.", "enabled");
                } else if (!shouldBeEnabled && isCurrentlyEnabled) {
                    module.onDisable(plugin);
                    activeModules.remove(module.getId());
                    reportStatus(sender, "Module '" + module.getId() + "' has been disabled.", "disabled");
                } else if (shouldBeEnabled && isCurrentlyEnabled) {
                    module.reload(plugin);
                    reportStatus(sender, "Module '" + module.getId() + "' has been reloaded.", "reloaded");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to sync module '" + module.getId() + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void reportStatus(CommandSender sender, String message, String type) {
        plugin.getLogger().info(message);
        if (sender != null && !(sender instanceof ConsoleCommandSender)) {
            TextColor color = switch (type) {
                case "enabled" -> NamedTextColor.GREEN;
                case "disabled" -> NamedTextColor.RED;
                case "reloaded" -> NamedTextColor.YELLOW;
                default -> WakeColors.NEUTRAL;
            };

            sender.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text(message, color)));
        }
    }

    public void disableAll() {
        for (String moduleId : activeModules) {
            registeredModules.get(moduleId).onDisable(plugin);
        }
        activeModules.clear();
    }

    public <T extends Module> T getModule(Class<T> clazz) {
        return registeredModules.values().stream()
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .findFirst()
                .orElse(null);
    }
}
