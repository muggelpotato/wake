package dev.muggel.wake.features.obu.networking;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.OBUDefinition;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public class PacketSender {
    public void writeSetting(@NonNull PacketByteBuf buf, @NonNull OBUSetting setting) throws IOException {
        buf.writeShort((short) setting.definition().id());
        writeSettingArgs(buf, setting.definition().types(), setting.args());
    }

    public boolean isEncodable(@NonNull OBUSetting setting) {
        try {
            writeSetting(new PacketByteBuf(), setting);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeSettingArgs(PacketByteBuf buf, @NonNull List<String> types, @NonNull List<String> args) throws IOException {
        if (args.size() < types.size()) {
            throw new IllegalArgumentException("Not enough arguments for setting. Expected: " + types.size() + ", Got: " + args.size());
        }
        for (int i = 0; i < types.size(); i++) {
            String arg = args.get(i);
            String type = types.get(i).toLowerCase(Locale.ROOT);
            switch (type) {
                case "float" -> buf.writeFloat(Float.parseFloat(arg));
                case "boolean"-> {
                    if (!arg.equalsIgnoreCase("true") && !arg.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("Invalid input for boolean");
                    }
                    buf.writeBoolean(Boolean.parseBoolean(arg));
                }
                case "double" -> buf.writeDouble(Double.parseDouble(arg));
                case "int" -> buf.writeInt(Integer.parseInt(arg));
                case "byte" -> {
                    int value = Integer.parseInt(arg);
                    if (value < 0 || value > 255) {
                        throw new IllegalArgumentException("Byte argument out of range: " + arg);
                    }
                    buf.writeByte((byte) value);
                }
                case "string", "context_id" -> buf.writeString(arg);
                case "block_list" -> buf.writeString(formatBlockList(arg));
                case "entity_list" -> buf.writeString(formatEntityList(arg));
                case "setting_enum" -> {
                    short id = OBUDefinition.PerBlockSetting.parse(arg);
                    if (id < 0) throw new IllegalArgumentException("Unknown per-block setting: " + arg);
                    buf.writeShort(id);
                }
                case "collision_enum" -> {
                    short id = OBUDefinition.CollisionMode.parse(arg);
                    if (id < 0) throw new IllegalArgumentException("Unknown collision mode: " + arg);
                    buf.writeShort(id);
                }
                default -> throw new IllegalArgumentException("Unknown semantic type: " + type);
            }
        }
    }

    private @NonNull String formatBlockList(@NonNull String raw) {
        String[] blocks = raw.split("[\\s,]+");
        List<String> validBlocks = new ArrayList<>();
        for (String b : blocks) {
            if (b.isEmpty()) continue;
            String trimmed = b.trim();
            if (!trimmed.contains(":")) {
                trimmed = "minecraft:" + trimmed.toLowerCase(Locale.ROOT);
            }
            validBlocks.add(trimmed);
        }
        return String.join(",", validBlocks);
    }

    private @NonNull String formatEntityList(@NonNull String raw) {
        String[] entities = raw.split("[\\s,]+");
        List<String> validEntities = new ArrayList<>();
        for (String e : entities) {
            if (e.isEmpty()) continue;
            String trimmed = e.trim();
            
            boolean isUuid = false;
            try {
                UUID.fromString(trimmed);
                isUuid = true;
            } catch (IllegalArgumentException ignored) {}

            if (!isUuid && !trimmed.contains(":")) {
                trimmed = "minecraft:" + trimmed.toLowerCase(Locale.ROOT);
            }
            validEntities.add(trimmed);
        }
        return String.join(",", validEntities);
    }

    public void sendWipePlayer(Player player, @NonNull String contextId) throws IOException {
        sendDropContext(player, contextId);
        sendResetContext(player);
    }

    public void sendResetContext(Player player) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) OBUDefinition.ContextPacket.RESET_CONTEXT.getId());
        sendContextPacket(player, buf);
    }

    public void sendDropContext(Player player, @NonNull String contextId) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) OBUDefinition.ContextPacket.DROP_CONTEXT.getId());
        buf.writeString(getNamespacedContextId(contextId));
        sendContextPacket(player, buf);
    }

    public void sendRawSetting(Player player, OBUSetting setting) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        writeSetting(buf, setting);
        sendPluginMessage(player, OBUDefinition.CHANNEL_SETTINGS, buf.toBytes());
    }

    public void sendSwitchContext(Player player, @NonNull String contextId) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) OBUDefinition.ContextPacket.SWITCH_CONTEXT.getId());
        buf.writeString(getNamespacedContextId(contextId));
        sendContextPacket(player, buf);
    }

    public void sendStoreContext(Player player, @NonNull String contextId, @NonNull List<OBUSetting> settings) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) OBUDefinition.ContextPacket.STORE_CONTEXT.getId());
        buf.writeString(getNamespacedContextId(contextId));
        buf.writeInt(settings.size());
        for (OBUSetting setting : settings) {
            writeSetting(buf, setting);
        }
        sendContextPacket(player, buf);
    }

    private @NonNull String getNamespacedContextId(@NonNull String contextId) {
        return contextId.contains(":") ? contextId : "wake:" + contextId;
    }

    public WrapperPlayServerPluginMessage createEntityContextPacket(@NonNull UUID entityUuid, @NonNull List<OBUSetting> settings) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) OBUDefinition.ContextPacket.ENTITY_CONTEXT.getId());
        buf.writeString(entityUuid.toString());
        buf.writeInt(settings.size());
        for (OBUSetting setting : settings) {
            writeSetting(buf, setting);
        }
        return new WrapperPlayServerPluginMessage(OBUDefinition.CHANNEL_CONTEXT, buf.toBytes());
    }

    public void sendPrecompiledPacket(Player player, WrapperPlayServerPluginMessage packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    public void sendPluginMessage(Player player, String channel, byte[] data) {
        var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) return;
        sendPluginMessage(user, channel, data);
    }

    public void sendPluginMessage(@NonNull User user, String channel, byte[] data) {
        if (user.getConnectionState() == ConnectionState.CONFIGURATION) {
            user.sendPacket(new WrapperConfigServerPluginMessage(channel, data));
        } else {
            user.sendPacket(new WrapperPlayServerPluginMessage(channel, data));
        }
    }

    private void sendContextPacket(Player player, @NonNull PacketByteBuf buf) {
        sendPluginMessage(player, OBUDefinition.CHANNEL_CONTEXT, buf.toBytes());
    }
}