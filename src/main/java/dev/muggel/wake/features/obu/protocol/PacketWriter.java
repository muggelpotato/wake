package dev.muggel.wake.features.obu.protocol;

import dev.muggel.wake.features.obu.protocol.OBUDefinition.ContextPacket;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PacketWriter {
    private PacketWriter() {}

    public static byte @NonNull [] resetContext() {
        return frame(ContextPacket.RESET_CONTEXT).toBytes();
    }

    public static byte @NonNull [] dropContext(@NonNull String contextId) {
        PacketByteBuf buf = frame(ContextPacket.DROP_CONTEXT);
        buf.writeString(contextId);
        return buf.toBytes();
    }

    public static byte @NonNull [] switchContext(@NonNull String contextId) {
        PacketByteBuf buf = frame(ContextPacket.SWITCH_CONTEXT);
        buf.writeString(contextId);
        return buf.toBytes();
    }

    public static byte @NonNull [] storeContext(@NonNull String contextId, @NonNull List<OBUSetting> settings, int clientVersion) {
        PacketByteBuf buf = frame(ContextPacket.STORE_CONTEXT);
        buf.writeString(contextId);
        writeSettings(buf, settings, clientVersion);
        return buf.toBytes();
    }

    public static byte @NonNull [] entityContext(@NonNull UUID entityUuid, @NonNull List<OBUSetting> settings, int clientVersion) {
        PacketByteBuf buf = frame(ContextPacket.ENTITY_CONTEXT);
        buf.writeString(entityUuid.toString());
        writeSettings(buf, settings, clientVersion);
        return buf.toBytes();
    }

    public static byte @NonNull [] versionRequest() {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort(OBUDefinition.PACKET_RESEND_VERSION);
        return buf.toBytes();
    }

    public static byte @NonNull [] rawSetting(@NonNull OBUSetting setting) {
        PacketByteBuf buf = new PacketByteBuf();
        writeSetting(buf, setting);
        return buf.toBytes();
    }

    private static @NonNull PacketByteBuf frame(@NonNull ContextPacket packet) {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort(packet.id());
        return buf;
    }

    private static void writeSettings(@NonNull PacketByteBuf buf, @NonNull List<OBUSetting> settings, int clientVersion) {
        List<byte[]> written = new ArrayList<>(settings.size());
        for (OBUSetting setting : settings) {
            if (OBUVersions.isPastCeiling(setting, clientVersion)) {
                continue;
            }
            PacketByteBuf one = new PacketByteBuf();
            try {
                writeSetting(one, setting);
            } catch (IllegalArgumentException notWritable) {
                continue;
            }
            written.add(one.toBytes());
        }
        buf.writeInt(written.size());
        for (byte[] bytes : written) {
            buf.writeBytes(bytes);
        }
    }

    private static void writeSetting(@NonNull PacketByteBuf buf, @NonNull OBUSetting setting) {
        List<SettingType> types = setting.definition().types();
        List<String> args = setting.args();
        if (args.size() < types.size()) {
            throw new IllegalArgumentException("Not enough arguments for setting. Expected: " + types.size() + ", Got: " + args.size());
        }
        buf.writeShort((short) setting.definition().id());
        for (int i = 0; i < types.size(); i++) {
            types.get(i).encode(buf, args.get(i));
        }
    }
}