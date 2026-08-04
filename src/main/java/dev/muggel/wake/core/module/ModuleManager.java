package dev.muggel.wake.core.module;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.sync.SyncService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Owns the module lifecycle. <br>
 * The set of modules is fixed at construction, so nothing can register one after the command tree is declared. <br>
 * {@link #syncModules()} compares {@code config.yml} with what is running and enables, disables, or reloads each module to match (at boot and on {@code /wake reload}). <br>
 * {@link #getModule(Class)} returns an active module or {@code null}. Callers must tolerate {@code null}, because any module can be off.
 */
public final class ModuleManager {
    private final Wake plugin;
    private final List<WakeModule> modules;
    private final Map<String, WakeModule> activeModules = new ConcurrentHashMap<>();
    public ModuleManager(@NonNull Wake plugin, @NonNull List<WakeModule> modules) {
        this.plugin = plugin;
        this.modules = List.copyOf(modules);
        Set<String> ids = new HashSet<>();
        for (WakeModule module : this.modules) {
            String id = module.getId();
            if (SyncService.SCOPE_STATE.equals(id) || SyncService.SCOPE_FULL.equals(id)) {
                throw new IllegalStateException("Module '" + id + "' takes a reserved sync scope");
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("Two modules are registered as '" + id + "': an id is what config.yml, the command tree and every state key address");
            }
        }
    }

    public void declareCommands() {
        Map<String, CommandNode> roots = new LinkedHashMap<>();
        for (WakeModule module : modules) {
            CommandNode root = module.buildCommands();
            if (root != null) {
                roots.put(module.getId(), root);
            }
        }
        WakeCommandManager.declare(roots);
    }

    public @NonNull List<Component> syncModules() {
        List<Component> feedback = new ArrayList<>();
        for (WakeModule module : modules) {
            String id = module.getId();
            boolean configured = plugin.getConfig().getBoolean("modules." + id + ".enabled", true);
            boolean shouldRun = configured && compatible(module);
            boolean running = activeModules.containsKey(id);
            String outcome;
            if (shouldRun != running) {
                outcome = shouldRun ? enable(module) : disable(module);
            } else if (running) {
                outcome = reload(module);
            } else if (configured) {
                plugin.getLogger().warning("Module '" + id + "' is enabled in config but incompatible with this environment");
                outcome = "incompatible";
            } else {
                continue;
            }
            feedback.add(plugin.getMessageManager().getComponent("commands.reload." + outcome, Placeholder.parsed("module", id)));
        }
        return feedback;
    }

    public void disableAll() {
        for (WakeModule module : modules.reversed()) {
            if (activeModules.containsKey(module.getId())) {
                disable(module);
            }
        }
    }

    public <T extends WakeModule> @Nullable T getModule(@NonNull Class<T> type) {
        for (WakeModule module : activeModules.values()) {
            if (type.isInstance(module)) {
                return type.cast(module);
            }
        }
        return null;
    }

    public boolean isActive(@NonNull String id) {
        return activeModules.containsKey(id);
    }

    public @NonNull List<WakeModule> getActiveModules() {
        return List.copyOf(activeModules.values());
    }

    private @NonNull String enable(@NonNull WakeModule module) {
        String id = module.getId();
        activeModules.put(id, module);
        if (!attempt(module, "enable", module::enable)) {
            activeModules.remove(id);
            return "failed";
        }
        plugin.getLogger().info("Module '" + id + "' has been enabled");
        return "enabled";
    }

    private @NonNull String disable(@NonNull WakeModule module) {
        activeModules.remove(module.getId());
        if (!attempt(module, "disable", module::disable)) {
            return "failed";
        }
        plugin.getLogger().info("Module '" + module.getId() + "' has been disabled");
        return "disabled";
    }

    private @NonNull String reload(@NonNull WakeModule module) {
        return attempt(module, "reload", module::reload) ? "reloaded" : "failed";
    }

    /** A module that cannot answer counts as one that cannot run */
    private boolean compatible(@NonNull WakeModule module) {
        try {
            return module.isCompatible();
        } catch (Exception | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to check whether module '" + module.getId() + "' can run here", e);
            return false;
        }
    }

    private boolean attempt(@NonNull WakeModule module, @NonNull String verb, @NonNull Runnable step) {
        try {
            step.run();
            return true;
        } catch (Exception | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to " + verb + " module '" + module.getId() + "'", e);
            return false;
        }
    }
}