package dev.muggel.wake.features.obu.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.networking.PacketSender;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class OBUServiceImpl implements OBUService {
    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final Map<UUID, String> activeSandboxContexts = new HashMap<>();
    private final Map<UUID, String> activeContexts = new HashMap<>();
    private final OBUSyncManager syncManager;

    public OBUServiceImpl(Wake plugin, PacketSender packetSender, OBUContextManager contextManager) {
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.contextManager = contextManager;
        this.syncManager = new OBUSyncManager(plugin, packetSender, contextManager, this);
    }

    @Override
    public void resetPlayer(@NonNull Player player) {
        syncManager.clearLocalOverrides(player.getUniqueId());
    }

    @Override
    public void cleanupPlayer(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        activeSandboxContexts.remove(uuid);
        activeContexts.remove(uuid);
        syncManager.cleanup(uuid);
    }

    @Override
    public void applyDefaultContext(Player player) {
        setPlayerActiveSandbox(player, null);
        syncManager.clearLocalOverrides(player.getUniqueId());

        OBUContext defaultContext = contextManager.getContext("default");
        if (defaultContext != null) {
            applyContext(player, defaultContext);
        }
        syncManager.syncPlayer(player);
    }

    @Override
    public void applyContext(@NonNull Player player, @NonNull OBUContext context) {
        activeContexts.put(player.getUniqueId(), context.name());
        syncManager.clearLocalOverrides(player.getUniqueId());
    }

    @Override
    public boolean applySetting(Entity target, OBUSetting setting) {
        if (!(target instanceof Player) && !(target instanceof Boat)) {
            return false;
        }

        if (setting.definition().isActionSetting()) {
            try {
                if (target instanceof Player player) {
                    packetSender.sendRawSetting(player, setting);
                } else {
                    Boat boat = (Boat) target;
                    if (!boat.getPassengers().isEmpty() && boat.getPassengers().getFirst() instanceof Player driver) {
                        packetSender.sendRawSetting(driver, setting);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send raw action setting: " + e.getMessage());
            }
            return true;
        }

        if (target instanceof Player player) {
            String sandboxName = getPlayerActiveSandbox(player);
            if (sandboxName != null) {
                contextManager.updateSandboxSetting(sandboxName, setting);
            } else {
                syncManager.addLocalOverride(player.getUniqueId(), setting);
            }
            syncManager.syncPlayer(player);
        } else {
            Boat boat = (Boat) target;
            if (setting.definition().isGlobalSetting()) {
                return false;
            }
            syncManager.addLocalOverride(boat.getUniqueId(), setting);
            syncManager.broadcastSync(boat);
        }
        return true;
    }

    @Override
    public void applyEntityContext(@NonNull Boat boat, String contextName) {
        NamespacedKey key = new NamespacedKey(plugin, "obu_context");
        syncManager.clearLocalOverrides(boat.getUniqueId());
        
        if (contextName == null) {
            boat.getPersistentDataContainer().remove(key);
            syncManager.broadcastSync(boat);
            return;
        }

        boat.getPersistentDataContainer().set(key, PersistentDataType.STRING, contextName);
        OBUContext context = contextManager.getContext(contextName);
        if (context != null) {
            syncManager.broadcastSync(boat);
        }
    }

    @Override
    public void createSandbox(String name) {
        contextManager.createSandbox(name);
    }

    @Override
    public void setPlayerActiveSandbox(Player player, String sandboxName) {
        if (sandboxName == null) {
            activeSandboxContexts.remove(player.getUniqueId());
        } else {
            activeSandboxContexts.put(player.getUniqueId(), sandboxName.toLowerCase());
        }
    }

    @Override
    public String getPlayerActiveSandbox(@NonNull Player player) {
        return activeSandboxContexts.get(player.getUniqueId());
    }

    @Override
    public String getActiveContextName(@NonNull Player player) {
        return activeContexts.getOrDefault(player.getUniqueId(), "default");
    }

    @Override
    public void broadcastBoatContext(Boat boat) {
        syncManager.broadcastSync(boat);
    }

    @Override
    public void sendBoatContext(Boat boat, Player viewer) {
        syncManager.syncToViewer(boat, viewer);
    }

    @Override
    public Set<String> getSandboxNames() {
        return contextManager.getSandboxNames();
    }

    @Override
    public OBUSyncManager getSyncManager() {
        return syncManager;
    }
}
