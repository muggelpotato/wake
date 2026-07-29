package dev.muggel.wake.features.obu.networking;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUPlayerState;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import dev.muggel.wake.features.obu.service.ClientRegistry.ClientState;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HandshakeListener extends PacketListenerAbstract implements Listener {
    private record HandshakeData(int versionId, boolean isUnstable) {}
    private final Map<User, HandshakeData> pendingHandshakes = new ConcurrentHashMap<>();
    private final Wake plugin;
    private final OBUServiceImpl obuService;
    public HandshakeListener(Wake plugin, OBUServiceImpl obuService) {
        this.plugin = plugin;
        this.obuService = obuService;
    }

    @Override
    public void onUserDisconnect(@NonNull UserDisconnectEvent event) {
        pendingHandshakes.remove(event.getUser());
    }

    @Override
    public void onPacketReceive(@NonNull PacketReceiveEvent event) {
        String channel;
        byte[] data;
        if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage msg = new WrapperConfigClientPluginMessage(event);
            channel = msg.getChannelName();
            data = msg.getData();
        } else if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage msg = new WrapperPlayClientPluginMessage(event);
            channel = msg.getChannelName();
            data = msg.getData();
        } else {
            return;
        }
        if (!isHandshakeChannel(channel)) {
            return;
        }
        HandshakeData handshake = parseHandshakeData(data);
        if (handshake == null) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            handleOBUPlayer(player, event.getUser(), handshake);
        } else {
            pendingHandshakes.put(event.getUser(), handshake);
        }
    }

    private static boolean isHandshakeChannel(String channel) {
        return OBUDefinition.CHANNEL_HANDSHAKE.equals(channel)
                || OBUDefinition.CHANNEL_CONFIGURATION.equals(channel)
                || OBUDefinition.CHANNEL_SETTINGS.equals(channel);
    }

    private static @Nullable HandshakeData parseHandshakeData(byte @Nullable [] data) {
        if (data == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        if (data.length >= 7) {
            if (buf.getShort() != 0) return null;
        } else if (data.length != 5) {
            return null;
        }
        return new HandshakeData(buf.getInt(), buf.get() != 0);
    }

    @EventHandler
    public void onPlayerJoin(@NonNull PlayerJoinEvent event) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(event.getPlayer());
        HandshakeData handshake = user != null ? pendingHandshakes.remove(user) : null;
        if (handshake != null) {
            handleOBUPlayer(event.getPlayer(), user, handshake);
        }
    }

    @EventHandler
    public void onPlayerQuit(@NonNull PlayerQuitEvent event) {
        obuService.saveSelection(event.getPlayer());
        obuService.cleanupPlayer(event.getPlayer());
    }

    @EventHandler
    public void onEntityTrack(@NonNull PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Boat boat) {
            obuService.getSyncManager().syncToViewer(boat, event.getPlayer());
        }
    }

    @EventHandler
    public void onVehicleEnter(@NonNull VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Boat boat && event.getEntered() instanceof Player player) {
            Scheduling.onMain(plugin, () -> {
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
            Scheduling.onMain(plugin, () -> obuService.getSyncManager().broadcastSync(boat));
        }
    }

    private void logVersion(@NonNull User user, @NonNull HandshakeData handshake) {
        plugin.getLogger().info(user.getName() + " is running OpenBoatUtils version " + handshake.versionId() + (handshake.isUnstable() ? " [UNSTABLE BUILD]" : ""));
    }

    private void warnUnsupported(@NonNull Player player) {
        Scheduling.onMain(plugin, () -> {
            if (player.isOnline()) {
                plugin.getMessageManager().send(player, "networking.obu.unsupported");
            }
        });
    }

    private void handleOBUPlayer(@NonNull Player player, @NonNull User user, @NonNull HandshakeData handshake) {
        boolean rejected = OBUDefinition.REJECTED_VERSIONS.contains(handshake.versionId());
        ClientState verdict = rejected ? ClientState.UNSUPPORTED : ClientState.DRIVEN;
        if (!obuService.clients().claim(player.getUniqueId(), verdict)) {
            return;
        }
        logVersion(user, handshake);
        if (rejected) {
            warnUnsupported(player);
            return;
        }
        obuService.loadPlayerState(player.getUniqueId(), state -> driveClient(player, handshake.versionId(), state));
    }

    private void driveClient(@NonNull Player player, int versionId, @Nullable OBUPlayerState state) {
        if (!player.isOnline() || Wake.getServiceRegistry().get(OBUService.class) != obuService) {
            return;
        }
        if (versionId < OBUDefinition.LATEST_SUPPORTED_VERSION) {
            plugin.getMessageManager().send(player, "networking.obu.outdated");
        } else if (versionId > OBUDefinition.LATEST_SUPPORTED_VERSION) {
            plugin.getMessageManager().send(player, "networking.obu.ahead");
        }
        if (state == null) {
            obuService.getSyncManager().syncPlayer(player);
        } else if (state.activeSandbox() == null && state.activeContext() == null) {
            obuService.applyDefaultContext(player);
        } else {
            restoreSelection(player, state);
        }
        obuService.getSyncManager().syncTrackedBoats(player);
    }

    private void restoreSelection(@NonNull Player player, @NonNull OBUPlayerState state) {
        OBUContextManager contextManager = obuService.getContextManager();
        String sandboxName = state.activeSandbox();
        if (sandboxName != null) {
            OBUContext sandbox = contextManager.getContext(sandboxName);
            if (sandbox == null || !sandbox.isSandbox() || !player.getUniqueId().equals(sandbox.ownerUuid())) {
                sandboxName = null;
            }
        }
        obuService.setPlayerActiveSandbox(player, sandboxName);
        obuService.getSyncManager().clearLocalOverrides(player.getUniqueId());
        String contextName = state.activeContext() != null ? state.activeContext() : OBUContextManager.DEFAULT_CONTEXT;
        OBUContext context = contextManager.getContext(contextName);
        if (context == null) {
            context = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
        }
        if (context != null) {
            obuService.applyContext(player, context);
        }
        obuService.getSyncManager().syncPlayer(player);
    }
}