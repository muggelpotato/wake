package dev.muggel.wake.features.obu;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.muggel.wake.core.commands.arguments.BlockListArgumentType;
import dev.muggel.wake.core.commands.arguments.EntityListArgumentType;
import dev.muggel.wake.core.commands.arguments.WakeEnumArgumentType;
import dev.muggel.wake.features.obu.OBUDefinition.CollisionMode;
import dev.muggel.wake.features.obu.OBUDefinition.PerBlockSetting;
import dev.muggel.wake.features.obu.networking.PacketByteBuf;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
    BYTE(() -> IntegerArgumentType.integer(0, 255), (buf, arg) -> {
        int value = Integer.parseInt(arg);
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Byte argument out of range: " + arg);
        }
        buf.writeByte((byte) value);
    }),
    BLOCK_LIST(BlockListArgumentType::blockList, (buf, arg) -> buf.writeString(namespacedBlocks(arg))),
    ENTITY_LIST(EntityListArgumentType::entityList, (buf, arg) -> buf.writeString(namespacedEntities(arg))),
    SETTING_ENUM(() -> WakeEnumArgumentType.wakeEnum(PerBlockSetting.class), (buf, arg) -> buf.writeShort(resolve(PerBlockSetting.parse(arg), "per-block setting", arg))),
    COLLISION_ENUM(() -> WakeEnumArgumentType.wakeEnum(CollisionMode.class), (buf, arg) -> buf.writeShort(resolve(CollisionMode.parse(arg), "collision mode", arg)));

    @FunctionalInterface
    private interface Encoder {
        void write(@NonNull PacketByteBuf buf, @NonNull String arg) throws IOException;
    }

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

    private static @NonNull String namespacedBlocks(@NonNull String raw) {
        List<String> blocks = new ArrayList<>();
        for (String entry : raw.split("[\\s,]+")) {
            if (!entry.isEmpty()) {
                blocks.add(namespaced(entry.trim()));
            }
        }
        return String.join(",", blocks);
    }

    private static @NonNull String namespacedEntities(@NonNull String raw) {
        List<String> entities = new ArrayList<>();
        for (String entry : raw.split("[\\s,]+")) {
            if (entry.isEmpty()) continue;
            String trimmed = entry.trim();
            entities.add(isUuid(trimmed) ? trimmed : namespaced(trimmed));
        }
        return String.join(",", entities);
    }

    private static @NonNull String namespaced(@NonNull String entry) {
        return entry.contains(":") ? entry : "minecraft:" + entry.toLowerCase(Locale.ROOT);
    }

    private static boolean isUuid(@NonNull String entry) {
        try {
            UUID.fromString(entry);
            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}