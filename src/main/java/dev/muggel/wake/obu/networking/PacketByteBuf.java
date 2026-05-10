package dev.muggel.wake.obu.networking;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class PacketByteBuf {
    private final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(byteArrayOutputStream);

    public PacketByteBuf writeFloat(float v) throws IOException { out.writeFloat(v); return this; }
    public PacketByteBuf writeBoolean(boolean v) throws IOException { out.writeBoolean(v); return this; }
    public PacketByteBuf writeDouble(double v) throws IOException { out.writeDouble(v); return this; }
    public PacketByteBuf writeShort(short v) throws IOException { out.writeShort(v); return this; }
    public PacketByteBuf writeInt(int v) throws IOException { out.writeInt(v); return this; }
    public PacketByteBuf writeByte(byte v) throws IOException { out.writeByte(v); return this; }

    public PacketByteBuf writeString(String s) throws IOException {
        int len = s.length();
        while (true) {
            if ((len & ~0x7F) == 0) {
                out.writeByte(len);
                break;
            }
            out.writeByte((len & 0x7F) | 0x80);
            len >>>= 7;
        }
        out.writeBytes(s);
        return this;
    }

    public PacketByteBuf writeShortArray(short[] array) throws IOException {
        for (short v : array) out.writeShort(v);
        return this;
    }

    public byte[] toBytes() { return byteArrayOutputStream.toByteArray(); }
}