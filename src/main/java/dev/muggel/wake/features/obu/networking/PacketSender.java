package dev.muggel.wake.features.obu.networking;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.OBUDefinition;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public class PacketSender {
    public void writeSetting(@NonNull PacketByteBuf buf, @NonNull OBUSetting setting) throws IOException {
        buf.writeShort((short) setting.definition().id());
        writeSettingArgs(buf, setting.definition().types(), setting.args());
    }

    private void writeSettingArgs(PacketByteBuf buf, @NonNull List<String> semanticTypes, String @NonNull [] rawArgs) throws IOException {
        if (rawArgs.length < semanticTypes.size()) {
            throw new IllegalArgumentException("Not enough arguments for setting. Expected: " + semanticTypes.size() + ", Got: " + rawArgs.length);
        }
        for (int i = 0; i < semanticTypes.size(); i++) {
            String arg = rawArgs[i];
            String type = semanticTypes.get(i).toLowerCase();

            switch (type) {
                case "float" -> buf.writeFloat(Float.parseFloat(arg));
                case "boolean"-> {
                    if (!arg.equalsIgnoreCase("true") && !arg.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("Invalid input for boolean");
                    }
                    buf.writeBoolean(Boolean.parseBoolean(arg));
                }
                case "double" -> buf.writeDouble(Double.parseDouble(arg));
                case "short" -> buf.writeShort(Short.parseShort(arg));
                case "int" -> buf.writeInt(Integer.parseInt(arg));
                case "byte" -> buf.writeByte(Byte.parseByte(arg));
                case "string", "context_id" -> buf.writeString(arg);
                case "block_list" -> buf.writeString(formatBlockList(arg));
                case "entity_list" -> buf.writeString(formatEntityList(arg));
                case "setting_enum" -> buf.writeShort(OBUDefinition.PerBlockSetting.parse(arg));
                case "collision_enum" -> buf.writeShort(OBUDefinition.CollisionMode.parse(arg));
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
                trimmed = "minecraft:" + trimmed.toLowerCase();
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
                trimmed = "minecraft:" + trimmed.toLowerCase();
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
        String namespaced = contextId.contains(":") ? contextId : "wake:" + contextId;
        buf.writeString(namespaced);
        sendContextPacket(player, buf);
    }

    public void sendRawSetting(Player player, OBUSetting setting) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        writeSetting(buf, setting);
        sendPrecompiledPacket(player, new WrapperPlayServerPluginMessage(OBUDefinition.CHANNEL_SETTINGS, buf.toBytes()));
    }

    public void sendSwitchContext(Player player, @NonNull String contextId) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) OBUDefinition.ContextPacket.SWITCH_CONTEXT.getId());
        String namespaced = contextId.contains(":") ? contextId : "wake:" + contextId;
        buf.writeString(namespaced);
        sendContextPacket(player, buf);
    }

    public void sendStoreContext(Player player, @NonNull String contextId, @NonNull List<OBUSetting> settings) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) OBUDefinition.ContextPacket.STORE_CONTEXT.getId());
        String namespaced = contextId.contains(":") ? contextId : "wake:" + contextId;
        buf.writeString(namespaced);
        buf.writeInt(settings.size());
        for (OBUSetting setting : settings) {
            writeSetting(buf, setting);
        }
        sendContextPacket(player, buf);
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

    private void sendContextPacket(Player player, @NonNull PacketByteBuf buf) {
        sendPrecompiledPacket(player, new WrapperPlayServerPluginMessage(OBUDefinition.CHANNEL_CONTEXT, buf.toBytes()));
    }
}
