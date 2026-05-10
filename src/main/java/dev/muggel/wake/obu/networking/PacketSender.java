package dev.muggel.wake.obu.networking;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import org.bukkit.entity.Player;

import java.util.List;

public class PacketSender {
    public void sendDynamicPacket(Player player, String channel, int packetId, List<String> semanticTypes, String[] rawArgs) throws Exception {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) packetId);

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
                case "string", "block_list", "entity_list", "context_id" -> buf.writeString(arg);
                case "setting_enum" -> buf.writeShort(parseSettingEnum(arg));
                case "collision_enum" -> buf.writeShort(parseCollisionEnum(arg));
                default -> throw new IllegalArgumentException("Unknown semantic type in config: " + type);
            }
        }

        WrapperPlayServerPluginMessage obuPacket = new WrapperPlayServerPluginMessage("openboatutils:" + channel, buf.toBytes());
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, obuPacket);
    }
    private short parseSettingEnum(String arg) {
        return switch (arg.toUpperCase()) {
            case "JUMP_FORCE" -> 0;
            case "FORWARDS_ACCEL" -> 1;
            case "BACKWARDS_ACCEL" -> 2;
            case "YAW_ACCEL" -> 3;
            case "TURN_FORWARDS_ACCEL" -> 4;
            case "WALLTAP_MULTIPLIER" -> 5;
            case "JUMPS" -> 6;
            case "COYOTE_TIME" -> 7;
            default -> throw new IllegalArgumentException("Invalid setting enum");
        };
    }
    private short parseCollisionEnum(String arg) {
        return switch (arg.toUpperCase()) {
            case "VANILLA" -> 0;
            case "NO_BOATS_OR_PLAYERS" -> 1;
            case "NO_ENTITIES" -> 2;
            case "ENTITYTYPE_FILTER" -> 3;
            case "NO_BOATS_OR_PLAYERS_PLUS_FILTER" -> 4;
            default -> throw new IllegalArgumentException("Invalid collision enum");
        };
    }
}