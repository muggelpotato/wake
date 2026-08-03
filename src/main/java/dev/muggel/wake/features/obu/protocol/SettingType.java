package dev.muggel.wake.features.obu.protocol;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.muggel.wake.core.commands.arguments.KeyListArgumentType;
import dev.muggel.wake.core.commands.arguments.WakeEnumArgumentType;
import dev.muggel.wake.features.obu.protocol.OBUDefinition.CollisionMode;
import dev.muggel.wake.features.obu.protocol.OBUDefinition.PerBlockSetting;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.function.Supplier;

public enum SettingType {
    BOOLEAN(BoolArgumentType::bool, (buf, arg) -> {
        if (!arg.equalsIgnoreCase("true") && !arg.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Invalid input for boolean");
        }
        buf.writeBoolean(Boolean.parseBoolean(arg));
    }),
    FLOAT(FloatArgumentType::floatArg, (buf, arg) -> buf.writeFloat(Float.parseFloat(arg))),
    DOUBLE(DoubleArgumentType::doubleArg, (buf, arg) -> buf.writeDouble(Double.parseDouble(arg))),
    INT(IntegerArgumentType::integer, (buf, arg) -> buf.writeInt(Integer.parseInt(arg))),
    BYTE(() -> IntegerArgumentType.integer(0, SettingType.MAX_BYTE), (buf, arg) -> {
        int value = Integer.parseInt(arg);
        if (value < 0 || value > SettingType.MAX_BYTE) {
            throw new IllegalArgumentException("Byte argument out of range: " + arg);
        }
        buf.writeByte((byte) value);
    }),
    BLOCK_LIST(KeyListArgumentType::blockList, (buf, arg) -> buf.writeString(KeyListArgumentType.blockList().normalize(arg))),
    ENTITY_LIST(KeyListArgumentType::entityList, (buf, arg) -> buf.writeString(KeyListArgumentType.entityList().normalize(arg))),
    SETTING_ENUM(() -> WakeEnumArgumentType.wakeEnum(PerBlockSetting.class), (buf, arg) -> buf.writeShort(resolve(PerBlockSetting.parse(arg), "per-block setting", arg))),
    COLLISION_ENUM(() -> WakeEnumArgumentType.wakeEnum(CollisionMode.class), (buf, arg) -> buf.writeShort(resolve(CollisionMode.parse(arg), "collision mode", arg)));

    @FunctionalInterface
    private interface Encoder {
        void write(@NonNull PacketByteBuf buf, @NonNull String arg) throws IOException;
    }

    private static final int MAX_BYTE = 255;
    private final Supplier<ArgumentType<?>> argument;
    private final Encoder encoder;
    SettingType(Supplier<ArgumentType<?>> argument, Encoder encoder) {
        this.argument = argument;
        this.encoder = encoder;
    }

    public @NonNull ArgumentType<?> argument() {
        return argument.get();
    }

    public void encode(@NonNull PacketByteBuf buf, @NonNull String arg) throws IOException {
        encoder.write(buf, arg);
    }

    @Contract(pure = true)
    public boolean isList() {
        return this == BLOCK_LIST || this == ENTITY_LIST;
    }

    private static short resolve(short id, @NonNull String what, @NonNull String arg) {
        if (id < 0) {
            throw new IllegalArgumentException("Unknown " + what + ": " + arg);
        }
        return id;
    }
}