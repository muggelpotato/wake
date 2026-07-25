package dev.muggel.wake.features.obu.networking;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.commands.ConfigCommand;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUPlayerState;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HandshakeListener extends PacketListenerAbstract implements Listener {
    private final Wake plugin;
    private final OBUServiceImpl obuService;
    private final Set<UUID> initializedPlayers = ConcurrentHashMap.newKeySet();
    public HandshakeListener(Wake plugin, OBUServiceImpl obuService) {
        this.plugin = plugin;
        this.obuService = obuService;
    }

    @Override
    @SuppressWarnings("ConstantValue")
    public void onUserDisconnect(@NonNull UserDisconnectEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid != null && event.getUser().getConnectionState() != ConnectionState.PLAY) {
            pendingHandshakes.remove(uuid);
            initializedPlayers.remove(uuid);
        }
    }

    private record HandshakeData(int versionId, boolean isUnstable) {}
    private final Map<UUID, HandshakeData> pendingHandshakes = new ConcurrentHashMap<>();

    @Override
    public void onPacketReceive(@NonNull PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage msg = new WrapperConfigClientPluginMessage(event);
            String channel = msg.getChannelName();
            if (OBUDefinition.CHANNEL_HANDSHAKE.equals(channel) || 
                OBUDefinition.CHANNEL_CONFIGURATION.equals(channel) ||
                OBUDefinition.CHANNEL_SETTINGS.equals(channel)) {
                HandshakeData data = parseHandshakeData(msg.getData());
                if (data != null) {
                    pendingHandshakes.put(event.getUser().getUUID(), data);
                }
            }
        } else if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage msg = new WrapperPlayClientPluginMessage(event);
            String channel = msg.getChannelName();
            if (OBUDefinition.CHANNEL_HANDSHAKE.equals(channel) ||
                OBUDefinition.CHANNEL_CONFIGURATION.equals(channel) ||
                OBUDefinition.CHANNEL_SETTINGS.equals(channel)) {
                HandshakeData data = parseHandshakeData(msg.getData());
                if (data != null && event.getPlayer() instanceof Player player) {
                    handleOBUPlayer(player, data.versionId(), data.isUnstable());
                }
            }
        }
    }

    private HandshakeData parseHandshakeData(byte[] data) {
        if (data == null || data.length < 5) return null;
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            int version;
            boolean isUnstable;
            if (buf.readableBytes() >= 7) {
                short packetId = buf.readShort();
                if (packetId == 0) {
                    version = buf.readInt();
                    isUnstable = buf.readBoolean();
                } else {
                    return null;
                }
            } else if (buf.readableBytes() == 5) {
                version = buf.readInt();
                isUnstable = buf.readBoolean();
            } else {
                return null;
            }
            return new HandshakeData(version, isUnstable);
        } catch (IndexOutOfBoundsException e) {
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse OpenBoatUtils handshake: " + e.getMessage());
            return null;
        } finally {
            buf.release();
        }
    }

    @EventHandler
    public void onPlayerJoin(@NonNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        initializedPlayers.remove(player.getUniqueId());
        HandshakeData data = pendingHandshakes.remove(player.getUniqueId());
        if (data != null) {
            handleOBUPlayer(player, data.versionId(), data.isUnstable());
        }
    }

    @EventHandler
    public void onPlayerQuit(@NonNull PlayerQuitEvent event) {
        boolean wasObuPlayer = initializedPlayers.remove(event.getPlayer().getUniqueId());
        pendingHandshakes.remove(event.getPlayer().getUniqueId());
        if (wasObuPlayer) {
            boolean persist = plugin.getStateDao().get(ConfigCommand.STATE_KEY_PERSISTENT_STATES, true);
            String activeSandbox = obuService.getPlayerActiveSandbox(event.getPlayer());
            String activeContext = obuService.getActiveContextName(event.getPlayer());
            if (persist && (activeSandbox != null || !"default".equals(activeContext))) {
                obuService.savePlayerState(event.getPlayer().getUniqueId(), activeSandbox, activeContext);
            } else {
                obuService.savePlayerState(event.getPlayer().getUniqueId(), null, null);
            }
        }
        obuService.cleanupPlayer(event.getPlayer());
    }

    @EventHandler
    public void onEntityTrack(@NonNull PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Boat boat) {
            obuService.sendBoatContext(boat, event.getPlayer());
        }
    }

    @EventHandler
    public void onVehicleEnter(@NonNull VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Boat boat && event.getEntered() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                obuService.getSyncManager().syncPlayer(player);
                if (!(player.getVehicle() instanceof Boat)) {
                    obuService.getSyncManager().broadcastSync(boat);
                }
            });
        }
    }

    @EventHandler
    public void onVehicleExit(@NonNull VehicleExitEvent event) {
        if (event.getVehicle() instanceof Boat boat && event.getExited() instanceof Player) {
            Bukkit.getScheduler().runTask(plugin, () -> obuService.getSyncManager().broadcastSync(boat));
        }
    }

    private void handleOBUPlayer(@NonNull Player player, int versionId, boolean isUnstable) {
        if (!initializedPlayers.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OBUPlayerState state = obuService.getPlayerState(player.getUniqueId());
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (Wake.getServiceRegistry().get(OBUService.class) != obuService) {
                    return;
                }
                plugin.getDatabaseManager().notifyIfDegraded(player.getUniqueId());
                String unstableTag = isUnstable ? " [UNSTABLE BUILD]" : "";
                plugin.getLogger().info(player.getName() + " connected with OBU Version ID: " + versionId + unstableTag);
                List<Integer> rejectedVersions = OBUDefinition.REJECTED_VERSIONS;
                int latestVersion = OBUDefinition.LATEST_SUPPORTED_VERSION;
                if (rejectedVersions.contains(versionId)) {
                    player.kick(plugin.getMessageManager().getComponent("networking.obu.rejected"));
                    return;
                }
                if (versionId < latestVersion) {
                    plugin.getMessageManager().send(player, "networking.obu.outdated");
                } else if (versionId > latestVersion) {
                    plugin.getMessageManager().send(player, "networking.obu.ahead");
                }
                OBUContextManager contextManager = obuService.getContextManager();
                if (state != null && (state.activeSandbox() != null || state.activeContext() != null)) {
                    String sandboxName = state.activeSandbox();
                    if (sandboxName != null) {
                        OBUContext sandbox = contextManager.getContext(sandboxName);
                        if (sandbox == null || !sandbox.isSandbox() || !player.getUniqueId().equals(sandbox.ownerUuid())) {
                            sandboxName = null;
                        }
                    }
                    obuService.setPlayerActiveSandbox(player, sandboxName);
                    obuService.getSyncManager().clearLocalOverrides(player.getUniqueId());
                    String ctxName = state.activeContext() != null ? state.activeContext() : "default";
                    OBUContext ctx = contextManager.getContext(ctxName);
                    if (ctx == null) {
                        ctx = contextManager.getContext("default");
                    }
                    if (ctx != null) {
                        obuService.applyContext(player, ctx);
                    }
                    obuService.getSyncManager().syncPlayer(player);
                } else {
                    obuService.applyDefaultContext(player);
                }
                });
            } catch (IllegalPluginAccessException ignored) {
            }
        });
    }
}