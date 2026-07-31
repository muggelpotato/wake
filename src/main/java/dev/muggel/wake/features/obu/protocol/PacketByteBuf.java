package dev.muggel.wake.features.obu.protocol;

import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PacketByteBuf {
    private final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(byteArrayOutputStream);

    public void writeFloat(float v) throws IOException { out.writeFloat(v);
    }
    public void writeBoolean(boolean v) throws IOException { out.writeBoolean(v);
    }
    public void writeDouble(double v) throws IOException { out.writeDouble(v);
    }
    public void writeShort(short v) throws IOException { out.writeShort(v);
    }
    public void writeInt(int v) throws IOException { out.writeInt(v);
    }
    public void writeByte(byte v) throws IOException { out.writeByte(v);
    }

    public void writeString(@NonNull String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int len = bytes.length;
        while (true) {
            if ((len & ~0x7F) == 0) {
                out.writeByte(len);
                break;
            }
            out.writeByte((len & 0x7F) | 0x80);
            len >>>= 7;
        }
        out.write(bytes);
    }

    public byte[] toBytes() { return byteArrayOutputStream.toByteArray(); }
}