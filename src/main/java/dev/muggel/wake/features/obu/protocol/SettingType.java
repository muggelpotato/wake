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
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public enum SettingType {
    BOOLEAN(BoolArgumentType::bool, SettingType::asBoolean, (buf, arg) -> buf.writeBoolean(Boolean.parseBoolean(arg))),
    FLOAT(FloatArgumentType::floatArg, SettingType::asFloat, (buf, arg) -> buf.writeFloat(Float.parseFloat(arg))),
    DOUBLE(DoubleArgumentType::doubleArg, SettingType::asDouble, (buf, arg) -> buf.writeDouble(Double.parseDouble(arg))),
    INT(IntegerArgumentType::integer, SettingType::asInt, (buf, arg) -> buf.writeInt(Integer.parseInt(arg))),
    BYTE(() -> IntegerArgumentType.integer(SettingType.MIN_RESOLUTION, SettingType.MAX_RESOLUTION), SettingType::asResolution, (buf, arg) -> buf.writeByte((byte) Integer.parseInt(arg))),
    BLOCK_LIST(KeyListArgumentType::blockList, arg -> asKeyList(KeyListArgumentType.blockList(), arg), PacketByteBuf::writeString),
    ENTITY_LIST(KeyListArgumentType::entityList, arg -> asKeyList(KeyListArgumentType.entityList(), arg), PacketByteBuf::writeString),
    SETTING_ENUM(() -> WakeEnumArgumentType.wakeEnum(PerBlockSetting.class), arg -> PerBlockSetting.valueOf(upper(arg)).name(), (buf, arg) -> buf.writeShort(PerBlockSetting.valueOf(arg).id())),
    COLLISION_ENUM(() -> WakeEnumArgumentType.wakeEnum(CollisionMode.class), arg -> CollisionMode.valueOf(upper(arg)).name(), (buf, arg) -> buf.writeShort(CollisionMode.valueOf(arg).id()));

    @FunctionalInterface
    private interface Writer {
        void write(@NonNull PacketByteBuf buf, @NonNull String canonical);
    }

    private static final int MIN_RESOLUTION = 1;
    private static final int MAX_RESOLUTION = 50;
    private static final Pattern NAMESPACED_KEY = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9/._-]+");
    private final Supplier<ArgumentType<?>> argument;
    private final UnaryOperator<String> canonical;
    private final Writer writer;
    SettingType(Supplier<ArgumentType<?>> argument, UnaryOperator<String> canonical, Writer writer) {
        this.argument = argument;
        this.canonical = canonical;
        this.writer = writer;
    }

    public @NonNull ArgumentType<?> argument() {
        return argument.get();
    }

    public @NonNull String canonical(@NonNull String arg) {
        return canonical.apply(arg);
    }

    public void encode(@NonNull PacketByteBuf buf, @NonNull String arg) {
        writer.write(buf, canonical(arg));
    }

    public boolean isList() {
        return this == BLOCK_LIST || this == ENTITY_LIST;
    }

    public boolean accepts(@NonNull String entry) {
        return argument() instanceof KeyListArgumentType list && list.accepts(entry);
    }

    public boolean isIdentity() {
        return isList() || this == SETTING_ENUM;
    }

    private static @NonNull String asKeyList(@NonNull KeyListArgumentType type, @NonNull String arg) {
        String list = type.normalize(arg);
        for (String entry : list.split(",", -1)) {
            if (!NAMESPACED_KEY.matcher(entry).matches()) {
                throw new IllegalArgumentException("Not a namespaced key: " + entry);
            }
        }
        return list;
    }

    private static @NonNull String asBoolean(@NonNull String arg) {
        if (!arg.equalsIgnoreCase("true") && !arg.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Not a boolean: " + arg);
        }
        return arg.toLowerCase(Locale.ROOT);
    }

    private static @NonNull String asFloat(@NonNull String arg) {
        float value = Float.parseFloat(arg);
        requireFinite(value);
        return Float.toString(value);
    }

    private static @NonNull String asDouble(@NonNull String arg) {
        double value = Double.parseDouble(arg);
        requireFinite(value);
        return Double.toString(value);
    }

    private static @NonNull String asInt(@NonNull String arg) {
        return Integer.toString(Integer.parseInt(arg));
    }

    private static @NonNull String asResolution(@NonNull String arg) {
        int value = Integer.parseInt(arg);
        if (value < MIN_RESOLUTION || value > MAX_RESOLUTION) {
            throw new IllegalArgumentException("Collision resolution out of range: " + arg);
        }
        return Integer.toString(value);
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Not a finite number: " + value);
        }
    }

    private static @NonNull String upper(@NonNull String arg) {
        return arg.toUpperCase(Locale.ROOT);
    }
}