package dev.muggel.wake.features.obu.networking;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.api.OBUService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HandshakeListener extends PacketListenerAbstract implements Listener {

    private final Wake plugin;
    private final OBUService obuService;
    private final Set<UUID> initializedPlayers = ConcurrentHashMap.newKeySet();

    public HandshakeListener(Wake plugin, OBUService obuService) {
        this.plugin = plugin;
        this.obuService = obuService;
    }

    @Override
    public void onPacketReceive(@NonNull PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage msg = new WrapperConfigClientPluginMessage(event);
            if (msg.getChannelName().equals(OBUDefinition.CHANNEL_HANDSHAKE)) {
                handleHandshake(event.getPlayer(), msg.getData());
            }
        } else if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage msg = new WrapperPlayClientPluginMessage(event);
            if (msg.getChannelName().equals(OBUDefinition.CHANNEL_HANDSHAKE)) {
                handleHandshake(event.getPlayer(), msg.getData());
            }
        }
    }

    private void handleHandshake(Object playerObj, byte[] data) {
        if (!(playerObj instanceof Player player)) return;
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            int version = buf.readInt();
            boolean isUnstable = buf.readBoolean();
            handleOBUPlayer(player, version, isUnstable);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse OpenBoatUtils handshake: " + e.getMessage());
        } finally {
            buf.release();
        }
    }


    @EventHandler
    public void onPlayerQuit(@NonNull PlayerQuitEvent event) {
        initializedPlayers.remove(event.getPlayer().getUniqueId());
        obuService.cleanupPlayer(event.getPlayer());
    }

    @EventHandler
    public void onEntityTrack(@NonNull PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Boat boat) {
            // sync entity context to viewer entering tracking range
            obuService.sendBoatContext(boat, event.getPlayer());
        }
    }

    @EventHandler
    public void onVehicleEnter(@NonNull VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Boat boat && event.getEntered() instanceof Player) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                obuService.getSyncManager().broadcastSync(boat);
            });
        }
    }

    @EventHandler
    public void onVehicleExit(@NonNull VehicleExitEvent event) {
        if (event.getVehicle() instanceof Boat boat && event.getExited() instanceof Player) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                obuService.getSyncManager().broadcastSync(boat);
            });
        }
    }

    @EventHandler
    public void onVehicleDestroy(@NonNull VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Boat boat) {
            obuService.cleanupBoat(boat);
        }
    }

    private void handleOBUPlayer(@NonNull Player player, int versionId, boolean isUnstable) {
        if (!initializedPlayers.add(player.getUniqueId())) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            String unstableTag = isUnstable ? " [UNSTABLE BUILD]" : "";
            plugin.getLogger().info(player.getName() + " connected with OBU Version ID: " + versionId + unstableTag);

            List<Integer> rejectedVersions = plugin.getConfig().getIntegerList("obu.versions.rejected");
            int latestVersion = plugin.getConfig().getInt("obu.versions.latest", 19);

            if (rejectedVersions.contains(versionId)) {
                player.kick(plugin.getMessageManager().getComponent("networking.obu.rejected"));
                return;
            }

            if (versionId < latestVersion) {
                plugin.getMessageManager().send(player, "networking.obu.outdated");
            } else if (versionId > latestVersion) {
                plugin.getMessageManager().send(player, "networking.obu.ahead");
            }

            obuService.applyDefaultContext(player);
        });
    }
}
