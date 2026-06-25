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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class OBUSyncManager {
    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final OBUService obuService;
    private final Map<UUID, Map<String, OBUSetting>> localOverrides = new HashMap<>();

    public OBUSyncManager(Wake plugin, PacketSender packetSender, OBUContextManager contextManager, OBUService obuService) {
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.contextManager = contextManager;
        this.obuService = obuService;
    }

    public void cleanup(UUID uuid) {
        localOverrides.remove(uuid);
    }

    public void addLocalOverride(UUID uuid, OBUSetting setting) {
        localOverrides.computeIfAbsent(uuid, k -> new HashMap<>())
                .put(setting.getUniqueKey(), setting);
    }

    public void removeLocalOverride(UUID uuid, int definitionId) {
        Map<String, OBUSetting> map = localOverrides.get(uuid);
        if (map != null) {
            map.values().removeIf(s -> s.definition().id() == definitionId);
        }
    }

    public void clearLocalOverrides(UUID uuid) {
        localOverrides.remove(uuid);
    }
    
    public Map<String, OBUSetting> getLocalOverrides(UUID uuid) {
        Map<String, OBUSetting> overrides = localOverrides.get(uuid);
        if (overrides == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(overrides);
    }

    public List<OBUSetting> calculateAbsoluteTruth(UUID uuid) {
        String contextName = null;
        
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            contextName = obuService.getPlayerActiveSandbox(p);
            if (contextName == null) {
                contextName = obuService.getActiveContextName(p);
            }
        } else {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity instanceof Boat boat) {
                NamespacedKey key = new NamespacedKey(plugin, "obu_context");
                contextName = boat.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            }
        }

        Map<String, OBUSetting> absoluteTruth = new HashMap<>();

        // base context layer
        if (contextName != null) {
            OBUContext context = contextManager.getContext(contextName);
            if (context != null) {
                for (OBUSetting setting : context.getSettings()) {
                    absoluteTruth.put(setting.getUniqueKey(), setting);
                }
            }
        }

        // merge driver context if boat
        if (p == null) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity instanceof Boat boat && !boat.getPassengers().isEmpty() && boat.getPassengers().getFirst() instanceof Player driver) {
                List<OBUSetting> driverTruth = calculateAbsoluteTruth(driver.getUniqueId());
                for (OBUSetting setting : driverTruth) {
                    absoluteTruth.put(setting.getUniqueKey(), setting); // layers on top of base
                }
            }
        }

        // layer local overrides (shadow context)
        Map<String, OBUSetting> overrides = localOverrides.get(uuid);
        if (overrides != null) {
            absoluteTruth.putAll(overrides);
        }
        return new ArrayList<>(absoluteTruth.values());
    }

    // sync player context via personal context to avoid mutating base contexts
    public void syncPlayer(@NonNull Player player) {
        List<OBUSetting> truth = calculateAbsoluteTruth(player.getUniqueId());
        String personalContextName = "wake_personal";
        
        try {
            if (truth.isEmpty()) { // full reset when e.g. new sandbox context
                packetSender.sendResetSettings(player);
            } else {
                packetSender.sendStoreContext(player, personalContextName, truth);
                packetSender.sendSwitchContext(player, personalContextName);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to sync personal context to " + player.getName() + ": " + e.getMessage());
        }

        if (player.getVehicle() instanceof Boat boat) {
            broadcastSync(boat);
        }
    }

    // specific viewer
    public void syncToViewer(@NonNull Boat boat, Player viewer) {
        List<OBUSetting> settings = calculateAbsoluteTruth(boat.getUniqueId());
        
        try {
            var packet = packetSender.createEntityContextPacket(boat.getUniqueId(), settings);
            packetSender.sendPrecompiledPacket(viewer, packet);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to sync boat state to viewer: " + e.getMessage());
        }
    }

    // everyone with the boat rendered
    public void broadcastSync(@NonNull Boat boat) {
        List<OBUSetting> settings = calculateAbsoluteTruth(boat.getUniqueId());
        
        Set<Player> viewers = new HashSet<>(boat.getTrackedBy());
        if (!boat.getPassengers().isEmpty() && boat.getPassengers().getFirst() instanceof Player p) {
            viewers.add(p);
        }

        try {
            var packet = packetSender.createEntityContextPacket(boat.getUniqueId(), settings);
            for (Player viewer : viewers) {
                packetSender.sendPrecompiledPacket(viewer, packet);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to broadcast boat state: " + e.getMessage());
        }
    }
}
