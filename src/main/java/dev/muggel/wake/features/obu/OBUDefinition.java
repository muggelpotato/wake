package dev.muggel.wake.features.obu;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import static dev.muggel.wake.features.obu.SettingType.BLOCK_LIST;
import static dev.muggel.wake.features.obu.SettingType.BOOLEAN;
import static dev.muggel.wake.features.obu.SettingType.BYTE;
import static dev.muggel.wake.features.obu.SettingType.COLLISION_ENUM;
import static dev.muggel.wake.features.obu.SettingType.DOUBLE;
import static dev.muggel.wake.features.obu.SettingType.ENTITY_LIST;
import static dev.muggel.wake.features.obu.SettingType.FLOAT;
import static dev.muggel.wake.features.obu.SettingType.INT;
import static dev.muggel.wake.features.obu.SettingType.SETTING_ENUM;

public enum OBUDefinition {
    reset(0, List.of(), (String[]) null),
    stepsize(1, List.of(FLOAT), "0.0"),
    defaultslipperiness(2, List.of(FLOAT), "0.6"),
    blockslipperiness(3, List.of(FLOAT, BLOCK_LIST), (String[]) null),
    falldamage(4, List.of(BOOLEAN), "true"),
    waterelevation(5, List.of(BOOLEAN), "false"),
    aircontrol(6, List.of(BOOLEAN), "false"),
    jumpforce(7, List.of(FLOAT), "0.0"),
    boatgravity(9, List.of(DOUBLE), "-0.03999999910593033"),
    setyawaccel(10, List.of(FLOAT), "1.0"),
    setforwardaccel(11, List.of(FLOAT), "0.04"),
    setbackwardaccel(12, List.of(FLOAT), "0.005"),
    setturnforwardaccel(13, List.of(FLOAT), "0.005"),
    allowaccelstacking(14, List.of(BOOLEAN), "false"),
    underwatercontrol(16, List.of(BOOLEAN), "false"),
    surfacewatercontrol(17, List.of(BOOLEAN), "false"),
    coyotetime(19, List.of(INT), "0"),
    waterjumping(20, List.of(BOOLEAN), "false"),
    swimforce(21, List.of(FLOAT), "0.0"),
    removeblockslipperiness(22, List.of(BLOCK_LIST), (String[]) null),
    clearslipperiness(23, List.of(), (String[]) null),
    setblocksetting(26, List.of(SETTING_ENUM, FLOAT, BLOCK_LIST), (String[]) null),
    collisionmode(27, List.of(COLLISION_ENUM), "VANILLA"),
    stepwhilefalling(28, List.of(BOOLEAN), "false"),
    setinterpolationten(29, List.of(BOOLEAN), "false"),
    setcollisionresolution(30, List.of(BYTE), "1"),
    addcollisionfilter(31, List.of(ENTITY_LIST), (String[]) null),
    clearcollisionfilter(32, List.of(), (String[]) null),
    setwalltapmultiplier(34, List.of(FLOAT), "0.0"),
    setjumps(35, List.of(INT), "1"),
    setscale(36, List.of(FLOAT), "1.0"),
    setstepupslipperiness(37, List.of(FLOAT), "1.0"),
    setresetonworldload(38, List.of(BOOLEAN), "true"),
    fixdoublewaterelevation(39, List.of(BOOLEAN), "false"),
    setlateralslipperiness(40, List.of(FLOAT), "1.0"),
    setbrakeslipperiness(41, List.of(FLOAT), "1.0"),
    applyimpulse(42, List.of(DOUBLE, DOUBLE, DOUBLE), List.of("x", "y", "z")),
    applyimpulserelative(43, List.of(DOUBLE, DOUBLE, DOUBLE), List.of("x", "y", "z")),
    setmultistepping(44, List.of(BOOLEAN), "false"),
    setmaxspeed(45, List.of(FLOAT), "-1.0"),
    setmaxspeedresistance(46, List.of(FLOAT), "0.0"),
    sethoneycompat(47, List.of(BOOLEAN), "false");

    public static final String CONTEXT_PERSONAL = "wake:personal";
    public static final String CONTEXT_EMPTY = "wake:empty";
    public static final String CHANNEL_SETTINGS = "openboatutils:settings";
    public static final String CHANNEL_CONTEXT = "openboatutils:context";
    public static final String CHANNEL_CONFIGURATION = "openboatutils:configuration";
    public static final String CHANNEL_HANDSHAKE = "openboatutils:handshake";
    public static final short PACKET_RESEND_VERSION = 15;
    public static final int LATEST_SUPPORTED_VERSION = 22;
    public static final Set<Integer> REJECTED_VERSIONS = Set.of(8, 12, 15, 20, 21);
    private final int id;
    private final List<SettingType> types;
    private final List<String> argNames;
    private final String[] defaultValues;

    OBUDefinition(int id, List<SettingType> types, String... defaultValues) {
        this(id, types, null, defaultValues);
    }
    OBUDefinition(int id, List<SettingType> types, List<String> argNames, String... defaultValues) {
        this.id = id;
        this.types = types;
        this.argNames = argNames;
        this.defaultValues = (defaultValues == null || defaultValues.length == 0 || defaultValues[0] == null) ? null : defaultValues;
    }

    public int id() {
        return id;
    }

    public List<SettingType> types() {
        return types;
    }

    public @Nullable List<String> argNames() {
        return argNames;
    }

    public String @Nullable [] defaultValues() {
        return defaultValues;
    }

    @Contract(pure = true)
    public @NonNull String commandName() {
        return this == reset ? "-reset" : name();
    }

    public @NonNull @Unmodifiable List<String> splitInvocation(String raw) {
        if (types.size() <= 1) {
            return List.of(raw.trim());
        }
        return List.of(raw.trim().split("\\s+", types.size()));
    }


    public boolean isContextSetting() {
        return !isActionSetting();
    }

    public boolean isGlobalSetting() {
        return this == setinterpolationten || this == setresetonworldload;
    }

    public boolean isActionSetting() {
        return this == applyimpulse || this == applyimpulserelative;
    }

    private boolean canRepeat() {
        return this == blockslipperiness || this == removeblockslipperiness || this == setblocksetting || this == addcollisionfilter;
    }

    private static final Map<Integer, OBUDefinition> BY_ID = new HashMap<>();
    private static final Map<String, OBUDefinition> BY_NAME = new HashMap<>();
    private static final Set<String> REGISTERED_NAMES;

    static {
        for (OBUDefinition def : values()) {
            BY_ID.put(def.id(), def);
            BY_NAME.put(def.commandName(), def);
        }
        REGISTERED_NAMES = Collections.unmodifiableSet(BY_NAME.keySet());
    }

    public static @Nullable OBUDefinition getById(int id) {
        return BY_ID.get(id);
    }

    public static @Nullable OBUDefinition get(@Nullable String commandName) {
        if (commandName == null) return null;
        return BY_NAME.get(commandName.toLowerCase(Locale.ROOT));
    }

    public static Set<String> getRegisteredNames() {
        return REGISTERED_NAMES;
    }

    public @NonNull String generateUniqueKey(@NonNull List<String> args) {
        if (canRepeat() && !args.isEmpty()) {
            if (this == blockslipperiness) {
                return id + ":" + (args.size() > 1 ? args.get(1) : "");
            } else if (this == removeblockslipperiness) {
                return id + ":" + args.getFirst();
            } else if (this == setblocksetting) {
                return id + ":" + args.get(0) + ":" + (args.size() > 2 ? args.get(2) : "");
            } else if (this == addcollisionfilter) {
                return id + ":" + args.getFirst();
            }
        }
        return String.valueOf(id);
    }

    public enum PerBlockSetting {
        JUMP_FORCE(0),
        FORWARDS_ACCEL(1),
        BACKWARDS_ACCEL(2),
        YAW_ACCEL(3),
        TURN_FORWARDS_ACCEL(4),
        WALLTAP_MULTIPLIER(5),
        JUMPS(6),
        COYOTE_TIME(7),
        STEP_UP_SLIPPERINESS(8),
        LATERAL_SLIPPERINESS(9),
        BRAKE_SLIPPERINESS(10),
        MAX_SPEED(11),
        MAX_SPEED_RESISTANCE(12);
        private final short id;
        PerBlockSetting(int id) { this.id = (short) id; }

        public static short parse(@NonNull String arg) {
            try {
                return valueOf(arg.toUpperCase(Locale.ROOT)).id;
            } catch (IllegalArgumentException e) {
                return -1;
            }
        }
    }

    public enum CollisionMode {
        VANILLA(0),
        NO_BOATS_OR_PLAYERS(1),
        NO_ENTITIES(2),
        ENTITYTYPE_FILTER(3),
        NO_BOATS_OR_PLAYERS_PLUS_FILTER(4);
        private final short id;
        CollisionMode(int id) { this.id = (short) id; }

        public static short parse(@NonNull String arg) {
            try {
                return valueOf(arg.toUpperCase(Locale.ROOT)).id;
            } catch (IllegalArgumentException e) {
                return -1;
            }
        }
    }

    public enum ContextPacket {
        RESET_CONTEXT(0),
        SWITCH_CONTEXT(1),
        DROP_CONTEXT(2),
        STORE_CONTEXT(3),
        ENTITY_CONTEXT(4);
        private final int id;
        ContextPacket(int id) { this.id = id; }

        public int getId() { return id; }
    }
}