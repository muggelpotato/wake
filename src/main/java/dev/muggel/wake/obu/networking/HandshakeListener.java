package dev.muggel.wake.obu.networking;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.config.OBUProfileManager;
import dev.muggel.wake.obu.service.OBUService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HandshakeListener extends PacketListenerAbstract implements Listener {

    private final Wake plugin;
    private final OBUProfileManager profileManager;
    private final OBUService obuService;
    private final Set<UUID> obuPlayers = ConcurrentHashMap.newKeySet();

    public HandshakeListener(Wake plugin, OBUProfileManager profileManager, OBUService obuService) {
        this.plugin = plugin;
        this.profileManager = profileManager;
        this.obuService = obuService;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // OBU 0.5.0+
        if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage wrapper = new WrapperConfigClientPluginMessage(event);
            if (wrapper.getChannelName().equals("openboatutils:configuration")) {
                ByteBuf buffer = Unpooled.wrappedBuffer(wrapper.getData());
                try {
                    short packetId = buffer.readShort();
                    if (packetId == 0) {
                        int versionId = buffer.readInt();
                        boolean isUnstable = versionId >= 19 && buffer.readBoolean();

                        obuPlayers.add(event.getUser().getUUID());

                        String unstableTag = isUnstable ? " [UNSTABLE]" : "";
                        plugin.getLogger().info("OBU Config Phase Detected: " + event.getUser().getName() + " (v" + versionId + ")" + unstableTag);
                    }
                } finally {
                    buffer.release();
                }
            }
        }
        // OBU < 0.5.0
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
            if (wrapper.getChannelName().equals("openboatutils:settings")) {
                ByteBuf buffer = Unpooled.wrappedBuffer(wrapper.getData());
                try {
                    short packetId = buffer.readShort();
                    if (packetId == 0) {
                        int versionId = buffer.readInt();
                        boolean isUnstable = versionId >= 19 && buffer.readBoolean();
                        UUID playerId = event.getUser().getUUID();
                        if (obuPlayers.add(playerId)) {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Player player = Bukkit.getPlayer(playerId);
                                if (player != null && player.isOnline()) {
                                    handleOBUPlayer(player, versionId, isUnstable);
                                }
                            });
                        }
                    }
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (obuPlayers.contains(player.getUniqueId())) {
            obuService.applyDefaultProfile(player, profileManager);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        obuPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void handleOBUPlayer(Player player, int versionId, boolean isUnstable) {
        String unstableTag = isUnstable ? " [UNSTABLE BUILD]" : "";
        plugin.getLogger().info(player.getName() + " connected with OBU Version ID: " + versionId + unstableTag);
        obuService.applyDefaultProfile(player, profileManager);
    }
}
