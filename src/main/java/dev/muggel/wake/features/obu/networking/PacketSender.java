package dev.muggel.wake.features.obu.networking;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.ClientRegistry;
import dev.muggel.wake.features.obu.OBUDefinition;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class PacketSender {
    private final ClientRegistry clients;
    public PacketSender(@NonNull ClientRegistry clients) {
        this.clients = clients;
    }

    public void sendWipePlayer(Player player, @NonNull String contextId) throws IOException {
        sendContextPacket(player, PacketWriter.dropContext(contextId));
        sendContextPacket(player, PacketWriter.resetContext());
    }

    public void sendVersionRequest(Player player) throws IOException {
        sendUngated(player, OBUDefinition.CHANNEL_SETTINGS, PacketWriter.versionRequest());
    }

    public void sendRawSetting(Player player, OBUSetting setting) throws IOException {
        sendPluginMessage(player, OBUDefinition.CHANNEL_SETTINGS, PacketWriter.rawSetting(setting));
    }

    public void sendSwitchContext(Player player, @NonNull String contextId) throws IOException {
        sendContextPacket(player, PacketWriter.switchContext(contextId));
    }

    public void sendStoreContext(Player player, @NonNull String contextId, @NonNull List<OBUSetting> settings) throws IOException {
        sendContextPacket(player, PacketWriter.storeContext(contextId, settings));
    }

    public WrapperPlayServerPluginMessage createEntityContextPacket(@NonNull UUID entityUuid, @NonNull List<OBUSetting> settings) throws IOException {
        return new WrapperPlayServerPluginMessage(OBUDefinition.CHANNEL_CONTEXT, PacketWriter.entityContext(entityUuid, settings));
    }

    public void sendPrecompiledPacket(Player player, WrapperPlayServerPluginMessage packet) {
        if (!clients.isDriven(player.getUniqueId())) return;
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    public void sendPluginMessage(Player player, String channel, byte[] data) {
        if (!clients.isDriven(player.getUniqueId())) return;
        sendUngated(player, channel, data);
    }

    private void sendUngated(Player player, String channel, byte[] data) {
        var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) return;
        sendPluginMessage(user, channel, data);
    }

    private void sendPluginMessage(@NonNull User user, String channel, byte[] data) {
        if (user.getConnectionState() == ConnectionState.CONFIGURATION) {
            user.sendPacket(new WrapperConfigServerPluginMessage(channel, data));
        } else {
            user.sendPacket(new WrapperPlayServerPluginMessage(channel, data));
        }
    }

    private void sendContextPacket(Player player, byte[] data) {
        sendPluginMessage(player, OBUDefinition.CHANNEL_CONTEXT, data);
    }
}