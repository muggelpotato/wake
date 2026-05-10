package dev.muggel.wake.obu.networking;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.config.OBUConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HandshakeListener extends PacketListenerAbstract implements Listener {

    private final Wake plugin;
    private final OBUConfigManager configManager;
    private final Set<UUID> obuPlayers = ConcurrentHashMap.newKeySet();

    public HandshakeListener(Wake plugin, OBUConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        PacketEvents.getAPI().getEventManager().registerListener(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // OBU 0.5.0+
        if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage wrapper = new WrapperConfigClientPluginMessage(event);
            if (wrapper.getChannelName().equals("openboatutils:configuration")) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wrapper.getData()))) {
                    short packetId = in.readShort();
                    if (packetId == 0) {
                        int versionId = in.readInt();
                        boolean isUnstable = versionId >= 19 && in.readBoolean();

                        obuPlayers.add(event.getUser().getUUID());

                        String unstableTag = isUnstable ? " [UNSTABLE]" : "";
                        plugin.getLogger().info("OBU Config Phase Detected: " + event.getUser().getName() + " (v" + versionId + ")" + unstableTag);
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to parse openboatutils:configuration packet from " + event.getUser().getName() + ": " + e.getMessage());
                }
            }
        }
        // OBU < 0.5.0
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
            if (wrapper.getChannelName().equals("openboatutils:settings")) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wrapper.getData()))) {
                    short packetId = in.readShort();
                    if (packetId == 0) {
                        int versionId = in.readInt();
                        boolean isUnstable = versionId >= 19 && in.readBoolean();
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
                } catch (IOException e) {
                    String identity = event.getPlayer() != null ? event.getPlayer().toString() : "Unknown";
                    plugin.getLogger().warning("Failed to parse openboatutils:settings packet from " + identity + ": " + e.getMessage());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (obuPlayers.contains(player.getUniqueId())) {
            configManager.resetAndApplyProfile(player, "default");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        obuPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void handleOBUPlayer(Player player, int versionId, boolean isUnstable) {
        String unstableTag = isUnstable ? " [UNSTABLE BUILD]" : "";
        plugin.getLogger().info(player.getName() + " connected with OBU Version ID: " + versionId + unstableTag);
        configManager.resetAndApplyProfile(player, "default");
    }
}