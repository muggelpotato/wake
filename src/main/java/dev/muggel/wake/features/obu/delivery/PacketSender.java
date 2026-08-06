package dev.muggel.wake.features.obu.delivery;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.PacketWriter;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public final class PacketSender {
    private final ClientRegistry clients;
    public PacketSender(@NonNull ClientRegistry clients) {
        this.clients = clients;
    }

    public void sendWipePlayer(@NonNull Player player) {
        sendContextPacket(player, PacketWriter.dropContext(OBUDefinition.CONTEXT_PERSONAL));
        sendContextPacket(player, PacketWriter.resetContext());
    }

    public void sendVersionRequest(@NonNull Player player) {
        sendUngated(player, OBUDefinition.CHANNEL_SETTINGS, PacketWriter.versionRequest());
    }

    public void sendRawSetting(@NonNull Player player, @NonNull OBUSetting setting) {
        sendPluginMessage(player, OBUDefinition.CHANNEL_SETTINGS, PacketWriter.rawSetting(setting));
    }

    public void sendSwitchContext(@NonNull Player player, @NonNull String contextId) {
        sendContextPacket(player, PacketWriter.switchContext(contextId));
    }

    public void sendStoreContext(@NonNull Player player, @NonNull String contextId, @NonNull List<OBUSetting> settings) {
        sendContextPacket(player, PacketWriter.storeContext(contextId, settings));
    }

    public @NonNull WrapperPlayServerPluginMessage createEntityContextPacket(@NonNull UUID entityUuid, @NonNull List<OBUSetting> settings) {
        return new WrapperPlayServerPluginMessage(OBUDefinition.CHANNEL_CONTEXT, PacketWriter.entityContext(entityUuid, settings));
    }

    public void sendPrecompiledPacket(@NonNull Player player, @NonNull WrapperPlayServerPluginMessage packet) {
        if (!clients.isDriven(player.getUniqueId())) return;
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private void sendPluginMessage(@NonNull Player player, @NonNull String channel, byte @NonNull [] data) {
        if (!clients.isDriven(player.getUniqueId())) return;
        sendUngated(player, channel, data);
    }

    private void sendUngated(@NonNull Player player, @NonNull String channel, byte @NonNull [] data) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) return;
        if (user.getConnectionState() == ConnectionState.CONFIGURATION) {
            user.sendPacket(new WrapperConfigServerPluginMessage(channel, data));
        } else {
            user.sendPacket(new WrapperPlayServerPluginMessage(channel, data));
        }
    }

    private void sendContextPacket(@NonNull Player player, byte @NonNull [] data) {
        sendPluginMessage(player, OBUDefinition.CHANNEL_CONTEXT, data);
    }
}