package dev.muggel.wake.core.module;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.WakeCommandManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Owns the module lifecycle. <br>
 * {@link #syncModules()} compares {@code config.yml} with what is running and enables, disables, or reloads each module to match (at boot and on {@code /wake reload}). <br>
 * {@link #getModule(Class)} returns an active module or {@code null}. Callers must tolerate {@code null}, because any module can be off.
 */
public final class ModuleManager {
    private final Wake plugin;
    private final List<WakeModule> registeredModules = new ArrayList<>();
    private final Map<String, WakeModule> activeModules = new ConcurrentHashMap<>();
    public ModuleManager(Wake plugin) {
        this.plugin = plugin;
    }

    public void registerModule(WakeModule module) {
        if (!registeredModules.contains(module)) {
            registeredModules.add(module);
        }
    }

    public void buildAllCommands() {
        for (WakeModule module : registeredModules) {
            CommandNode root = module.buildCommands(plugin);
            if (root != null) {
                WakeCommandManager.register(root);
            }
        }
    }

    public @NonNull List<Component> syncModules() {
        List<Component> feedback = new ArrayList<>();
        for (WakeModule module : registeredModules) {
            try {
                String id = module.getId();
                boolean configuredEnabled = isModuleEnabled(id);
                boolean compatible = module.isCompatible();
                boolean shouldBeEnabled = configuredEnabled && compatible;
                boolean isCurrentlyEnabled = activeModules.containsKey(id);

                if (shouldBeEnabled && !isCurrentlyEnabled) {
                    try {
                        module.onEnable(plugin);
                        activeModules.put(id, module);
                        plugin.getLogger().info("Module '" + id + "' has been enabled");
                        feedback.add(plugin.getMessageManager().getComponent("commands.reload.enabled", Placeholder.parsed("module", id)));
                    } catch (Exception e) {
                        try {
                            module.onDisable();
                        } catch (Exception disableEx) {
                            plugin.getLogger().log(Level.SEVERE, "Failed to cleanup module '" + id + "' after enable failure", disableEx);
                        }
                        throw e;
                    }
                } else if (!shouldBeEnabled && isCurrentlyEnabled) {
                    WakeModule activeInstance = activeModules.remove(id);
                    if (activeInstance != null) {
                        activeInstance.onDisable();
                    }
                    plugin.getLogger().info("Module '" + id + "' has been disabled");
                    feedback.add(plugin.getMessageManager().getComponent("commands.reload.disabled", Placeholder.parsed("module", id)));
                } else if (shouldBeEnabled) {
                    WakeModule activeInstance = activeModules.get(id);
                    if (activeInstance != null) {
                        activeInstance.reload();
                    }
                    feedback.add(plugin.getMessageManager().getComponent("commands.reload.reloaded", Placeholder.parsed("module", id)));
                } else if (configuredEnabled) {
                    plugin.getLogger().warning("Module '" + id + "' is enabled in config but incompatible with this environment");
                    feedback.add(plugin.getMessageManager().getComponent("commands.reload.incompatible", Placeholder.parsed("module", id)));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to sync module " + module.getId(), e);
                feedback.add(plugin.getMessageManager().getComponent("commands.reload.failed", Placeholder.parsed("module", module.getId())));
            }
        }
        return feedback;
    }

    public void disableAll() {
        List<WakeModule> ordered = new ArrayList<>(registeredModules);
        Collections.reverse(ordered);
        for (WakeModule module : ordered) {
            if (!activeModules.containsKey(module.getId())) continue;
            try {
                module.onDisable();
                plugin.getLogger().info("Module '" + module.getId() + "' has been disabled");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to disable module '" + module.getId() + "'", e);
            }
        }
        activeModules.clear();
    }

    @SuppressWarnings("unchecked")
    public <T extends WakeModule> @Nullable T getModule(Class<T> clazz) {
        for (WakeModule module : activeModules.values()) {
            if (clazz.isInstance(module)) {
                return (T) module;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T extends WakeModule> @Nullable T getRegisteredModule(Class<T> clazz) {
        for (WakeModule module : registeredModules) {
            if (clazz.isInstance(module)) {
                return (T) module;
            }
        }
        return null;
    }

    @Contract(" -> new")
    public @NonNull List<WakeModule> getActiveModules() {
        return new ArrayList<>(activeModules.values());
    }

    private boolean isModuleEnabled(String id) {
        return plugin.getConfig().getBoolean("modules." + id + ".enabled", true);
    }
}