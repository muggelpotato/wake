package dev.muggel.wake.features.obu.protocol;

import dev.muggel.wake.features.obu.protocol.OBUDefinition.ContextPacket;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class PacketWriter {
    private PacketWriter() {}

    public static byte @NonNull [] resetContext() throws IOException {
        return frame(ContextPacket.RESET_CONTEXT).toBytes();
    }

    public static byte @NonNull [] dropContext(@NonNull String contextId) throws IOException {
        PacketByteBuf buf = frame(ContextPacket.DROP_CONTEXT);
        buf.writeString(namespacedContextId(contextId));
        return buf.toBytes();
    }

    public static byte @NonNull [] switchContext(@NonNull String contextId) throws IOException {
        PacketByteBuf buf = frame(ContextPacket.SWITCH_CONTEXT);
        buf.writeString(namespacedContextId(contextId));
        return buf.toBytes();
    }

    public static byte @NonNull [] storeContext(@NonNull String contextId, @NonNull List<OBUSetting> settings) throws IOException {
        PacketByteBuf buf = frame(ContextPacket.STORE_CONTEXT);
        buf.writeString(namespacedContextId(contextId));
        writeSettings(buf, settings);
        return buf.toBytes();
    }

    public static byte @NonNull [] entityContext(@NonNull UUID entityUuid, @NonNull List<OBUSetting> settings) throws IOException {
        PacketByteBuf buf = frame(ContextPacket.ENTITY_CONTEXT);
        buf.writeString(entityUuid.toString());
        writeSettings(buf, settings);
        return buf.toBytes();
    }

    public static byte @NonNull [] versionRequest() throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort(OBUDefinition.PACKET_RESEND_VERSION);
        return buf.toBytes();
    }

    public static byte @NonNull [] rawSetting(@NonNull OBUSetting setting) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        writeSetting(buf, setting);
        return buf.toBytes();
    }

    public static boolean isEncodable(@NonNull OBUSetting setting) {
        try {
            writeSetting(new PacketByteBuf(), setting);
            return true;
        } catch (Exception notEncodable) {
            return false;
        }
    }

    private static @NonNull PacketByteBuf frame(@NonNull ContextPacket packet) throws IOException {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeShort((short) packet.getId());
        return buf;
    }

    private static void writeSettings(@NonNull PacketByteBuf buf, @NonNull List<OBUSetting> settings) throws IOException {
        buf.writeInt(settings.size());
        for (OBUSetting setting : settings) {
            writeSetting(buf, setting);
        }
    }

    private static void writeSetting(@NonNull PacketByteBuf buf, @NonNull OBUSetting setting) throws IOException {
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

    private static @NonNull String namespacedContextId(@NonNull String contextId) {
        return contextId.contains(":") ? contextId : "wake:" + contextId;
    }
}