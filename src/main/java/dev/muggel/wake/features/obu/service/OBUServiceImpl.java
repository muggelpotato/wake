package dev.muggel.wake.features.obu.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.context.OBUPlayerState;
import dev.muggel.wake.features.obu.networking.PacketSender;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import dev.muggel.wake.features.obu.OBUDao;

public class OBUServiceImpl implements OBUService {
    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final Map<UUID, String> activeSandboxContexts = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeContexts = new ConcurrentHashMap<>();
    private final Map<UUID, Double> vehicleScaleCache = new ConcurrentHashMap<>();
    private final OBUSyncManager syncManager;
    private final OBUDao dao;

    public OBUServiceImpl(Wake plugin, PacketSender packetSender, OBUContextManager contextManager, OBUDao dao) {
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.contextManager = contextManager;
        this.dao = dao;
        this.syncManager = new OBUSyncManager(plugin, packetSender, contextManager, this);
    }

    public void resetPlayer(@NonNull Player player) {
        syncManager.clearLocalOverrides(player.getUniqueId());
    }

    public boolean isEncodable(@NonNull OBUSetting setting) {
        return packetSender.isEncodable(setting);
    }

    public void cleanupPlayer(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        activeSandboxContexts.remove(uuid);
        activeContexts.remove(uuid);
        vehicleScaleCache.remove(uuid);
        syncManager.cleanup(uuid);
    }

    public void cleanupVehicle(@NonNull UUID uuid) {
        vehicleScaleCache.remove(uuid);
        syncManager.cleanup(uuid);
    }

    public void applyDefaultContext(Player player) {
        setPlayerActiveSandbox(player, null);
        syncManager.clearLocalOverrides(player.getUniqueId());
        OBUContext defaultContext = contextManager.getContext("default");
        if (defaultContext != null) {
            applyContext(player, defaultContext);
        }
        syncManager.syncPlayer(player);
    }

    public void applyContext(@NonNull Player player, @NonNull OBUContext context) {
        applyContext(player.getUniqueId(), context.name());
    }

    public void resyncActiveSelection(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        String sandbox = getPlayerActiveSandbox(uuid);
        if (sandbox != null) {
            OBUContext ctx = contextManager.getContext(sandbox);
            if (ctx == null || !ctx.isSandbox() || !uuid.equals(ctx.ownerUuid())) {
                setPlayerActiveSandbox(player, null);
                sandbox = null;
            }
        }
        if (sandbox == null && contextManager.getContext(getActiveContextName(uuid)) == null) {
            OBUContext def = contextManager.getContext("default");
            if (def != null) {
                applyContext(player, def);
            }
        }
        syncManager.syncPlayer(player);
    }

    public void applyContext(UUID uuid, String contextName) {
        activeContexts.put(uuid, contextName);
        syncManager.clearLocalOverrides(uuid);
        OBUContext context = contextManager.getContext(contextName);
        if (context != null && context.isSandbox()) {
            dao.updateSandboxAccessTime(contextName);
        }
    }

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
                plugin.getLogger().log(Level.WARNING, "Failed to send raw action setting", e);
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

    public @NonNull NamespacedKey boatContextKey() {
        return new NamespacedKey(plugin, "obu_context");
    }

    public @Nullable String getBoatContextName(@NonNull Boat boat) {
        return boat.getPersistentDataContainer().get(boatContextKey(), PersistentDataType.STRING);
    }

    public void applyEntityContext(@NonNull Boat boat, String contextName) {
        NamespacedKey key = boatContextKey();
        syncManager.clearLocalOverrides(boat.getUniqueId());
        if (contextName == null || contextName.equalsIgnoreCase("default")) {
            boat.getPersistentDataContainer().remove(key);
            syncManager.broadcastSync(boat);
            return;
        }
        OBUContext context = contextManager.getContext(contextName);
        if (context != null) {
            boat.getPersistentDataContainer().set(key, PersistentDataType.STRING, contextName);
            if (context.isSandbox()) {
                dao.updateSandboxAccessTime(contextName);
            }
            syncManager.broadcastSync(boat);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean createSandbox(String name, UUID ownerUuid) {
        return contextManager.createSandbox(name, ownerUuid);
    }

    public @NonNull List<Player> deleteContextAndEvict(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        contextManager.deleteContext(lower);
        List<Player> evicted = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (lower.equals(getPlayerActiveSandbox(online)) || lower.equalsIgnoreCase(getActiveContextName(online))) {
                applyDefaultContext(online);
                evicted.add(online);
            }
        }
        return evicted;
    }

    public boolean publishSandbox(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!contextManager.publishSandbox(lower)) {
            return false;
        }
        for (Map.Entry<UUID, String> entry : Map.copyOf(activeSandboxContexts).entrySet()) {
            if (entry.getValue().equals(lower)) {
                activeSandboxContexts.remove(entry.getKey());
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    syncManager.syncPlayer(player);
                }
            }
        }
        return true;
    }

    public void setPlayerActiveSandbox(@NonNull Player player, String sandboxName) {
        setPlayerActiveSandbox(player.getUniqueId(), sandboxName);
    }

    public void setPlayerActiveSandbox(UUID uuid, String sandboxName) {
        if (sandboxName == null) {
            activeSandboxContexts.remove(uuid);
        } else {
            String lower = sandboxName.toLowerCase(Locale.ROOT);
            activeSandboxContexts.put(uuid, lower);
            dao.updateSandboxAccessTime(lower);
        }
    }

    public @Nullable String getPlayerActiveSandbox(@NonNull Player player) {
        return getPlayerActiveSandbox(player.getUniqueId());
    }

    public @Nullable String getPlayerActiveSandbox(UUID uuid) {
        return activeSandboxContexts.get(uuid);
    }

    public String getActiveContextName(@NonNull Player player) {
        return getActiveContextName(player.getUniqueId());
    }

    public String getActiveContextName(UUID uuid) {
        return activeContexts.getOrDefault(uuid, "default");
    }

    public void broadcastBoatContext(Boat boat) {
        syncManager.broadcastSync(boat);
    }

    public void sendBoatContext(Boat boat, Player viewer) {
        syncManager.syncToViewer(boat, viewer);
    }

    public OBUSyncManager getSyncManager() {
        return syncManager;
    }

    public OBUContextManager getContextManager() {
        return contextManager;
    }

    @Override
    public double getVehicleScale(UUID uuid) {
        if (uuid == null) return 1.0;
        Double cached = vehicleScaleCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        List<OBUSetting> truth = syncManager.calculateAbsoluteTruth(uuid);
        double scale = parseScaleFromTruth(truth);
        if (scale != 1.0 && Bukkit.getEntity(uuid) != null) {
            vehicleScaleCache.put(uuid, scale);
        }
        return scale;
    }

    public void updateVehicleScaleCache(UUID uuid, List<OBUSetting> truth) {
        if (uuid == null) return;
        double scale = parseScaleFromTruth(truth);
        if (scale == 1.0) {
            vehicleScaleCache.remove(uuid);
        } else {
            vehicleScaleCache.put(uuid, scale);
        }
    }

    private double parseScaleFromTruth(List<OBUSetting> absoluteTruth) {
        if (absoluteTruth != null) {
            for (OBUSetting setting : absoluteTruth) {
                if (setting.definition() == OBUDefinition.setscale && !setting.args().isEmpty()) {
                    try {
                        return Double.parseDouble(setting.args().getFirst());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return 1.0;
    }

    @Override
    public void applyRelativeImpulse(Player player, double x, double y, double z) {
        if (player == null) return;
        OBUSetting setting = new OBUSetting(OBUDefinition.applyimpulserelative, Arrays.asList(
                String.valueOf(x), String.valueOf(y), String.valueOf(z)
        ));
        applySetting(player, setting);
    }

    public @Nullable OBUPlayerState getPlayerState(UUID uuid) {
        return dao.getPlayerState(uuid);
    }

    public void savePlayerState(UUID uuid, String activeSandbox, String activeContext) {
        dao.savePlayerState(uuid, activeSandbox, activeContext);
    }
}