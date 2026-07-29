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
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.logging.Level;
import dev.muggel.wake.features.obu.OBUDao;

public class OBUServiceImpl implements OBUService {
    public static final String STATE_KEY_PERSISTENT_STATES = "obu.persistent_player_states";
    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final PlayerSelections selections = new PlayerSelections();
    private final VehicleScaleCache scales = new VehicleScaleCache();
    private final OBUSyncManager syncManager;
    private final OBUDao dao;
    private final ClientRegistry clients;
    private final NamespacedKey boatContextKey;
    public OBUServiceImpl(Wake plugin, PacketSender packetSender, OBUContextManager contextManager, OBUDao dao, ClientRegistry clients) {
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.contextManager = contextManager;
        this.dao = dao;
        this.clients = clients;
        this.boatContextKey = new NamespacedKey(plugin, "obu_context");
        this.syncManager = new OBUSyncManager(plugin, packetSender, contextManager, this);
    }

    public void cleanupPlayer(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        selections.forget(uuid);
        scales.forget(uuid);
        clients.forget(uuid);
        syncManager.cleanup(uuid);
    }

    public @NonNull ClientRegistry clients() {
        return clients;
    }

    public @NonNull PacketSender packetSender() {
        return packetSender;
    }

    public void requestClientVersion(@NonNull Player player) {
        clients.reopen(player.getUniqueId());
        try {
            packetSender.sendVersionRequest(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to ask " + player.getName() + " to resend its OBU version", e);
        }
    }

    public void cleanupVehicle(@NonNull UUID uuid) {
        scales.forget(uuid);
        syncManager.cleanup(uuid);
    }

    public void applyDefaultContext(@NonNull Player player) {
        setPlayerActiveSandbox(player, null);
        syncManager.clearLocalOverrides(player.getUniqueId());
        OBUContext defaultContext = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
        if (defaultContext != null) {
            applyContext(player, defaultContext);
        }
        syncManager.syncPlayer(player);
    }

    public void applyContext(@NonNull Player player, @NonNull OBUContext context) {
        applyContext(player.getUniqueId(), context.name());
    }

    public boolean isAffectedBy(@NonNull Set<String> changedContexts, @NonNull Player player) {
        if (changedContexts.contains(OBUContextManager.DEFAULT_CONTEXT)) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        String sandbox = selections.sandbox(uuid);
        if (sandbox != null && changedContexts.contains(sandbox.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (changedContexts.contains(selections.context(uuid).toLowerCase(Locale.ROOT))) {
            return true;
        }
        String pinned = player.getVehicle() instanceof Boat boat ? getBoatContextName(boat) : null;
        return pinned != null && changedContexts.contains(pinned.toLowerCase(Locale.ROOT));
    }

    public void resyncActiveSelection(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        String sandbox = selections.sandbox(uuid);
        if (sandbox != null) {
            OBUContext ctx = contextManager.getContext(sandbox);
            if (ctx == null || !ctx.isSandbox() || !uuid.equals(ctx.ownerUuid())) {
                setPlayerActiveSandbox(player, null);
                sandbox = null;
            }
        }
        if (sandbox == null && contextManager.getContext(selections.context(uuid)) == null) {
            OBUContext def = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
            if (def != null) {
                applyContext(player, def);
            }
        }
        syncManager.syncPlayer(player);
    }

    private void applyContext(@NonNull UUID uuid, @NonNull String contextName) {
        selections.setContext(uuid, contextName);
        syncManager.clearLocalOverrides(uuid);
        refreshIfSandbox(contextName);
    }

    public boolean applySetting(@NonNull Entity target, @NonNull OBUSetting setting) {
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
            String sandboxName = selections.sandbox(player.getUniqueId());
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

    public @Nullable String getBoatContextName(@NonNull Boat boat) {
        return boat.getPersistentDataContainer().get(boatContextKey, PersistentDataType.STRING);
    }

    public void applyEntityContext(@NonNull Boat boat, String contextName) {
        syncManager.clearLocalOverrides(boat.getUniqueId());
        if (contextName == null || contextName.equalsIgnoreCase(OBUContextManager.DEFAULT_CONTEXT)) {
            boat.getPersistentDataContainer().remove(boatContextKey);
            syncManager.broadcastSync(boat);
            return;
        }
        OBUContext context = contextManager.getContext(contextName);
        if (context != null) {
            boat.getPersistentDataContainer().set(boatContextKey, PersistentDataType.STRING, contextName);
            refreshIfSandbox(context);
            syncManager.broadcastSync(boat);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean createSandbox(@NonNull String name, @Nullable UUID ownerUuid) {
        return contextManager.createSandbox(name, ownerUuid);
    }

    public @NonNull List<Player> deleteContextAndEvict(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        contextManager.deleteContext(lower);
        List<Player> evicted = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            if (lower.equals(selections.sandbox(uuid)) || lower.equalsIgnoreCase(selections.context(uuid))) {
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
        for (UUID uuid : selections.clearSandbox(lower)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                syncManager.syncPlayer(player);
            }
        }
        return true;
    }

    public void setPlayerActiveSandbox(@NonNull Player player, @Nullable String sandboxName) {
        selections.setSandbox(player.getUniqueId(), sandboxName);
        refreshIfSandbox(sandboxName);
    }

    private void refreshIfSandbox(@Nullable String contextName) {
        if (contextName != null) {
            refreshIfSandbox(contextManager.getContext(contextName));
        }
    }

    private void refreshIfSandbox(@Nullable OBUContext context) {
        if (context != null && context.isSandbox()) {
            dao.updateSandboxAccessTime(context.name());
        }
    }

    public @Nullable String getPlayerActiveSandbox(@NonNull Player player) {
        return selections.sandbox(player.getUniqueId());
    }

    public @Nullable String getPlayerActiveSandbox(@NonNull UUID uuid) {
        return selections.sandbox(uuid);
    }

    public @NonNull String getActiveContextName(@NonNull Player player) {
        return selections.context(player.getUniqueId());
    }

    public @NonNull String getActiveContextName(@NonNull UUID uuid) {
        return selections.context(uuid);
    }

    public OBUSyncManager getSyncManager() {
        return syncManager;
    }

    public OBUContextManager getContextManager() {
        return contextManager;
    }

    @Override
    public double getVehicleScale(@NonNull UUID uuid) {
        return scales.scaleOf(uuid);
    }

    public void updateVehicleScaleCache(@NonNull UUID uuid, @NonNull List<OBUSetting> truth) {
        scales.update(uuid, truth);
    }

    @Override
    public void applyRelativeImpulse(@NonNull Player player, double x, double y, double z) {
        OBUSetting setting = new OBUSetting(OBUDefinition.applyimpulserelative, Arrays.asList(
                String.valueOf(x), String.valueOf(y), String.valueOf(z)
        ));
        applySetting(player, setting);
    }

    public void loadPlayerState(@NonNull UUID uuid, @NonNull Consumer<@Nullable OBUPlayerState> applyOnMain) {
        plugin.getDatabaseManager().readAsync(() -> dao.getPlayerState(uuid), applyOnMain);
    }

    public void saveSelection(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        if (!clients.isDriven(uuid) || !selections.hasSelection(uuid)) {
            return;
        }
        String sandbox = selections.sandbox(uuid);
        String context = selections.context(uuid);
        boolean keep = plugin.getStateDao().get(STATE_KEY_PERSISTENT_STATES, true)
                && (sandbox != null || !OBUContextManager.DEFAULT_CONTEXT.equals(context));
        dao.savePlayerState(uuid, keep ? sandbox : null, keep ? context : null);
    }
}