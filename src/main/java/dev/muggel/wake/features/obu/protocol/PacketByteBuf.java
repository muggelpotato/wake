package dev.muggel.wake.features.obu.protocol;

import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class PacketByteBuf {
    private static final int MAX_STRING_LENGTH = 32767;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public void writeBoolean(boolean v) {
        out.write(v ? 1 : 0);
    }

    public void writeByte(byte v) {
        out.write(v);
    }

    public void writeShort(short v) {
        out.write(v >>> 8);
        out.write(v);
    }

    public void writeInt(int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    public void writeFloat(float v) {
        writeInt(Float.floatToIntBits(v));
    }

    public void writeDouble(double v) {
        long bits = Double.doubleToLongBits(v);
        writeInt((int) (bits >>> 32));
        writeInt((int) bits);
    }

    public void writeString(@NonNull String s) {
        if (s.length() > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("String too long for the client to read: " + s.length());
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int len = bytes.length;
        while ((len & ~0x7F) != 0) {
            out.write((len & 0x7F) | 0x80);
            len >>>= 7;
        }
        out.write(len);
        out.writeBytes(bytes);
    }

    public void writeBytes(byte @NonNull [] bytes) {
        out.writeBytes(bytes);
    }

    public byte @NonNull [] toBytes() {
        return out.toByteArray();
    }
}