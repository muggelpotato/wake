package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUPlayerState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import dev.muggel.wake.features.obu.OBUDao;
import dev.muggel.wake.features.obu.OBUModule;

public class ContextDelivery implements OBUService {
    public static final String STATE_KEY_PERSISTENT_STATES = "obu.persistent_player_states";
    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final ActiveContexts active;
    private final OBUSyncManager syncManager;
    private final OBUDao dao;
    private final ClientRegistry clients;
    public ContextDelivery(Wake plugin, PacketSender packetSender, OBUContextManager contextManager, OBUDao dao, ClientRegistry clients, ActiveContexts active, OBUSyncManager syncManager) {
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.contextManager = contextManager;
        this.dao = dao;
        this.clients = clients;
        this.active = active;
        this.syncManager = syncManager;
    }

    public void cleanupPlayer(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        active.forgetPlayer(uuid);
        clients.forget(uuid);
        syncManager.cleanup(uuid);
    }

    public boolean isStale() {
        OBUModule module = plugin.getModule(OBUModule.class);
        return module == null || module.getDelivery() != this;
    }

    public void requestClientVersion(@NonNull Player player) {
        clients.reopen(player.getUniqueId());
        packetSender.sendVersionRequest(player);
    }

    public void cleanupVehicle(@NonNull UUID uuid) {
        active.forgetVehicle(uuid);
        syncManager.cleanup(uuid);
    }

    public void applyDefaultContext(@NonNull Player player) {
        setPlayerActiveSandbox(player, null);
        applyContext(player.getUniqueId(), OBUContextManager.DEFAULT_CONTEXT);
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
        String sandbox = active.sandboxOf(uuid);
        if (sandbox != null && changedContexts.contains(sandbox.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (changedContexts.contains(active.contextOf(uuid).toLowerCase(Locale.ROOT))) {
            return true;
        }
        String pinned = player.getVehicle() instanceof Boat boat ? active.pinnedOn(boat) : null;
        return pinned != null && changedContexts.contains(pinned.toLowerCase(Locale.ROOT));
    }

    public void resyncActiveSelection(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        String sandbox = active.sandboxOf(uuid);
        if (sandbox != null) {
            OBUContext ctx = contextManager.getContext(sandbox);
            if (ctx == null || !ctx.isSandbox() || !uuid.equals(ctx.ownerUuid())) {
                setPlayerActiveSandbox(player, null);
                sandbox = null;
            }
        }
        if (sandbox == null && contextManager.getContext(active.contextOf(uuid)) == null) {
            applyContext(uuid, OBUContextManager.DEFAULT_CONTEXT);
        }
        syncManager.syncPlayer(player);
    }

    private void applyContext(@NonNull UUID uuid, @NonNull String contextName) {
        active.selectContext(uuid, contextName);
        syncManager.clearLocalOverrides(uuid);
        refreshIfSandbox(contextName);
    }

    public boolean applySetting(@NonNull Entity target, @NonNull OBUSetting setting) {
        if (setting.definition().isActionSetting()) {
            sendActionSetting(target, setting);
            return target instanceof Player || target instanceof Boat;
        }
        if (target instanceof Player player) {
            String sandboxName = active.sandboxOf(player.getUniqueId());
            if (sandboxName != null) {
                contextManager.updateSandboxSetting(sandboxName, setting);
            } else {
                syncManager.addLocalOverride(player.getUniqueId(), setting);
            }
            syncManager.syncPlayer(player);
            return true;
        }
        if (target instanceof Boat boat) {
            syncManager.addLocalOverride(boat.getUniqueId(), setting);
            syncManager.broadcastSync(boat);
            return true;
        }
        return false;
    }

    private void sendActionSetting(@NonNull Entity target, @NonNull OBUSetting setting) {
        Player driver = OBUSyncManager.driverOf(target);
        if (driver != null) {
            packetSender.sendRawSetting(driver, setting);
        }
    }

    public void applyEntityContext(@NonNull Boat boat, @NonNull String contextName) {
        syncManager.clearLocalOverrides(boat.getUniqueId());
        if (contextName.equalsIgnoreCase(OBUContextManager.DEFAULT_CONTEXT)) {
            active.pin(boat, null);
            syncManager.broadcastSync(boat);
            return;
        }
        OBUContext context = contextManager.getContext(contextName);
        if (context != null) {
            active.pin(boat, contextName);
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
            if (lower.equals(active.sandboxOf(uuid)) || lower.equalsIgnoreCase(active.contextOf(uuid))) {
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
        for (UUID uuid : active.clearSandbox(lower)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                syncManager.syncPlayer(player);
            }
        }
        return true;
    }

    public void setPlayerActiveSandbox(@NonNull Player player, @Nullable String sandboxName) {
        active.selectSandbox(player.getUniqueId(), sandboxName);
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

    @Override
    public double getVehicleScale(@NonNull UUID uuid) {
        return active.scaleOf(uuid);
    }

    @Override
    public void applyRelativeImpulse(@NonNull Player player, double x, double y, double z) {
        OBUSetting setting = OBUSetting.of(OBUDefinition.applyimpulserelative, List.of(
                String.valueOf(x), String.valueOf(y), String.valueOf(z)
        ));
        if (setting != null) {
            applySetting(player, setting);
        }
    }

    public void loadPlayerState(@NonNull UUID uuid, @NonNull Consumer<@Nullable OBUPlayerState> applyOnMain) {
        plugin.getDatabaseManager().readAsync(() -> dao.getPlayerState(uuid), applyOnMain);
    }

    public void saveSelection(@NonNull Player player) {
        UUID uuid = player.getUniqueId();
        if (!clients.isDriven(uuid) || !active.hasSelection(uuid)) {
            return;
        }
        String sandbox = active.sandboxOf(uuid);
        String context = active.contextOf(uuid);
        boolean keep = plugin.getStateDao().get(STATE_KEY_PERSISTENT_STATES, true)
                && (sandbox != null || !OBUContextManager.DEFAULT_CONTEXT.equals(context));
        dao.savePlayerState(uuid, keep ? sandbox : null, keep ? context : null);
    }
}