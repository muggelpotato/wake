package dev.muggel.wake.features.obu.delivery;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.SettingMerge.Removal;
import dev.muggel.wake.features.obu.protocol.SettingSelector;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import dev.muggel.wake.features.obu.OBUDao;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.OBUPlayerState;

public final class ContextDelivery implements OBUService {
    public static final String STATE_KEY_PERSISTENT_STATES = "obu.persistent_player_states";
    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUContextManager contextManager;
    private final ActiveContexts active;
    private final OBUSyncManager syncManager;
    private final OBUDao dao;
    private final ClientRegistry clients;
    public ContextDelivery(@NonNull Wake plugin, @NonNull PacketSender packetSender, @NonNull OBUContextManager contextManager, @NonNull OBUDao dao, @NonNull ClientRegistry clients, @NonNull ActiveContexts active, @NonNull OBUSyncManager syncManager) {
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

    public void applyDefaultContext(@NonNull Player player) {
        setPlayerActiveSandbox(player, null);
        applyContext(player.getUniqueId(), OBUContextManager.DEFAULT_CONTEXT);
        syncManager.syncPlayer(player);
    }

    public void applyContext(@NonNull Player player, @NonNull OBUContext context) {
        applyContext(player.getUniqueId(), context.name());
    }

    public void resyncAffected(@NonNull Set<String> changedContexts) {
        if (changedContexts.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isAffectedBy(changedContexts, player)) {
                resyncActiveSelection(player);
            }
        }
        syncManager.resyncPinnedBoats();
    }

    private boolean isAffectedBy(@NonNull Set<String> changedContexts, @NonNull Player player) {
        UUID uuid = player.getUniqueId();
        String sandbox = active.sandboxOf(uuid);
        if (sandbox != null) {
            return changedContexts.contains(sandbox);
        }
        String context = active.contextOf(uuid);
        return changedContexts.contains(context)
                || (OBUContextManager.inheritsDefault(context) && changedContexts.contains(OBUContextManager.DEFAULT_CONTEXT));
    }

    private void resyncActiveSelection(@NonNull Player player) {
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
                contextManager.addSettings(sandboxName, List.of(setting));
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

    public @Nullable Removal removeSettings(@NonNull Entity target, @NonNull SettingSelector selector) {
        if (target instanceof Player player) {
            UUID uuid = player.getUniqueId();
            String sandboxName = active.sandboxOf(uuid);
            Removal temporary = syncManager.removeLocalOverrides(uuid, selector);
            Removal fromSandbox = sandboxName == null
                    ? Removal.NOTHING
                    : contextManager.removeSettings(sandboxName, selector);
            Removal removal = temporary.taken().isEmpty() ? fromSandbox : temporary;
            if (!removal.taken().isEmpty()) {
                syncManager.syncPlayer(player);
            }
            return removal;
        }
        if (target instanceof Boat boat) {
            Removal removal = syncManager.removeLocalOverrides(boat.getUniqueId(), selector);
            if (!removal.taken().isEmpty()) {
                syncManager.broadcastSync(boat);
            }
            return removal;
        }
        return null;
    }

    private void sendActionSetting(@NonNull Entity target, @NonNull OBUSetting setting) {
        Player driver = OBUSyncManager.driverOf(target);
        if (driver != null) {
            packetSender.sendRawSetting(driver, setting);
        }
    }

    public void applyEntityContext(@NonNull Boat boat, @NonNull OBUContext context) {
        syncManager.clearLocalOverrides(boat.getUniqueId());
        active.pin(boat, OBUContextManager.DEFAULT_CONTEXT.equals(context.name()) ? null : context.name());
        refreshIfSandbox(context);
        syncManager.broadcastSync(boat);
    }

    public @NonNull Map<Player, String> deleteContextsAndEvict(@NonNull Collection<String> names) {
        Set<String> gone = new HashSet<>();
        for (String name : names) {
            String lower = OBUContextManager.canonical(name);
            if (contextManager.deleteContext(lower)) {
                gone.add(lower);
            }
        }
        Map<Player, String> evicted = new LinkedHashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            String selected = active.sandboxOf(uuid);
            if (selected == null) {
                selected = active.contextOf(uuid);
            }
            if (gone.contains(selected)) {
                applyDefaultContext(online);
                evicted.put(online, selected);
            }
        }
        syncManager.resyncPinnedBoats();
        return evicted;
    }

    public @Nullable List<Player> publishSandbox(@NonNull String name) {
        if (!contextManager.publishSandbox(name)) {
            return null;
        }
        List<Player> evicted = new ArrayList<>();
        for (UUID uuid : active.clearSandbox(name)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                syncManager.syncPlayer(player);
                evicted.add(player);
            }
        }
        syncManager.resyncPinnedBoats();
        return evicted;
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
    public double getVehicleScale(@NonNull Boat boat) {
        double scale = syncManager.scaleOf(boat.getUniqueId());
        return scale > 0 ? scale : 1.0;
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