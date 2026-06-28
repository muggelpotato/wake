package dev.muggel.wake.core.module;

import dev.muggel.wake.Wake;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class ModuleManager {
    private final Wake plugin;
    private final List<WakeModule> registeredModules = new ArrayList<>();
    private final Map<String, WakeModule> activeModules = new LinkedHashMap<>();

    public ModuleManager(Wake plugin) {
        this.plugin = plugin;
    }

    public void registerModule(WakeModule module) {
        if (!registeredModules.contains(module)) {
            registeredModules.add(module);
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
                    plugin.getLogger().info("Module '" + id + "' has been reloaded");
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
        List<WakeModule> modulesToDisable = new ArrayList<>(activeModules.values());
        Collections.reverse(modulesToDisable);
        for (WakeModule module : modulesToDisable) {
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

    private boolean isModuleEnabled(String id) {
        return plugin.getConfig().getBoolean("modules." + id + ".enabled", true);
    }
}
