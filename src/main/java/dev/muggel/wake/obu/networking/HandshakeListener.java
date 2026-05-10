package dev.muggel.wake.obu.networking;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.config.OBUConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class HandshakeListener extends PacketListenerAbstract implements Listener {

    private final Wake plugin;
    private final PacketSender packetSender;
    private final OBUConfigManager configManager;
    private final Set<UUID> pendingHandshakes = new HashSet<>();

    public HandshakeListener(Wake plugin, PacketSender packetSender, OBUConfigManager configManager) {
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.configManager = configManager;
        PacketEvents.getAPI().getEventManager().registerListener(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Player player = (Player) event.getPlayer();
        if (player == null) return;

        if (event.getPacketType() == PacketType.Play.Client.CLIENT_SETTINGS) {
            UUID uuid = player.getUniqueId();
            if (!pendingHandshakes.contains(uuid)) {
                pendingHandshakes.add(uuid);
                try {
                    // Send Packet 15 (Handshake Request)
                    packetSender.sendDynamicPacket(player, "settings", 15, java.util.List.of(), new String[0]);
                } catch (Exception ignored) {}
            }
        }

        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);

            if (wrapper.getChannelName().equals("openboatutils:settings")) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(wrapper.getData()))) {
                    short packetId = in.readShort();
                    if (packetId == 0) {
                        int versionId = in.readInt();
                        boolean isUnstable = versionId >= 19 && in.readBoolean();
                        handleOBUPlayer(player, versionId, isUnstable);
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to read OBU packet from " + player.getName());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingHandshakes.remove(event.getPlayer().getUniqueId());
    }

    private void handleOBUPlayer(Player player, int versionId, boolean isUnstable) {
        String unstableTag = isUnstable ? " [UNSTABLE BUILD]" : "";
        plugin.getLogger().info(player.getName() + " connected with OBU Version ID: " + versionId + unstableTag);
        Bukkit.getScheduler().runTask(plugin, () -> configManager.applyProfile(player, "default"));
    }
}