package dev.muggel.wake.features.obu;

import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public enum OBUDefinition {
    reset(0, "settings", List.of(), (String[]) null),
    stepsize(1, "settings", List.of("float"), "0.0"),
    defaultslipperiness(2, "settings", List.of("float"), "0.6"),
    blockslipperiness(3, "settings", List.of("float", "block_list"), (String[]) null),
    falldamage(4, "settings", List.of("boolean"), "true"),
    waterelevation(5, "settings", List.of("boolean"), "false"),
    aircontrol(6, "settings", List.of("boolean"), "false"),
    jumpforce(7, "settings", List.of("float"), "0.0"),
    boatgravity(9, "settings", List.of("double"), "-0.03999999910593033"),
    setyawaccel(10, "settings", List.of("float"), "1.0"),
    setforwardaccel(11, "settings", List.of("float"), "0.04"),
    setbackwardaccel(12, "settings", List.of("float"), "0.005"),
    setturnforwardaccel(13, "settings", List.of("float"), "0.005"),
    allowaccelstacking(14, "settings", List.of("boolean"), "false"),
    underwatercontrol(16, "settings", List.of("boolean"), "false"),
    surfacewatercontrol(17, "settings", List.of("boolean"), "false"),
    coyotetime(19, "settings", List.of("int"), "0"),
    waterjumping(20, "settings", List.of("boolean"), "false"),
    swimforce(21, "settings", List.of("float"), "0.0"),
    removeblockslipperiness(22, "settings", List.of("block_list"), (String[]) null),
    clearslipperiness(23, "settings", List.of(), (String[]) null),
    setblocksetting(26, "settings", List.of("setting_enum", "float", "block_list"), (String[]) null),
    collisionmode(27, "settings", List.of("collision_enum"), "VANILLA"),
    stepwhilefalling(28, "settings", List.of("boolean"), "false"),
    setinterpolationten(29, "settings", List.of("boolean"), "false"),
    setcollisionresolution(30, "settings", List.of("byte"), "1"),
    addcollisionfilter(31, "settings", List.of("entity_list"), (String[]) null),
    clearcollisionfilter(32, "settings", List.of(), (String[]) null),
    setwalltapmultiplier(34, "settings", List.of("float"), "0.0"),
    setjumps(35, "settings", List.of("int"), "1"),
    setscale(36, "settings", List.of("float"), "1.0"),
    setstepupslipperiness(37, "settings", List.of("float"), "1.0"),
    setresetonworldload(38, "settings", List.of("boolean"), "true"),
    fixdoublewaterelevation(39, "settings", List.of("boolean"), "false"),
    setlateralslipperiness(40, "settings", List.of("float"), "1.0"),
    setbrakeslipperiness(41, "settings", List.of("float"), "1.0"),
    applyimpulse(42, "settings", List.of("double", "double", "double"), (String[]) null),
    applyimpulserelative(43, "settings", List.of("double", "double", "double"), (String[]) null),
    setmultistepping(44, "settings", List.of("boolean"), "false"),
    setmaxspeed(45, "settings", List.of("float"), "-1.0"),
    setmaxspeedresistance(46, "settings", List.of("float"), "0.0"),
    sethoneycompat(47, "settings", List.of("boolean"), "false");

    public static final String CHANNEL_SETTINGS = "openboatutils:settings";
    public static final String CHANNEL_CONTEXT = "openboatutils:context";
    public static final String CHANNEL_CONFIGURATION = "openboatutils:configuration";
    public static final String CHANNEL_HANDSHAKE = "openboatutils:handshake";

    private final int id;
    private final String channel;
    private final List<String> types;
    private final String[] defaultValues;

    OBUDefinition(int id, String channel, List<String> types, String... defaultValues) {
        this.id = id;
        this.channel = channel;
        this.types = types;
        this.defaultValues = (defaultValues == null || defaultValues.length == 0 || defaultValues[0] == null) ? null : defaultValues;
    }

    public int id() {
        return id;
    }

    public String channel() {
        return channel;
    }

    public List<String> types() {
        return types;
    }

    public String[] defaultValues() {
        return defaultValues;
    }

    public String commandName() {
        return name();
    }

    public String getPermission() {
        return "wake.obu.commands." + commandName();
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

    public boolean canRepeat() {
        return this == blockslipperiness || this == removeblockslipperiness || this == setblocksetting || this == addcollisionfilter;
    }

    public static OBUDefinition get(String commandName) {
        if (commandName == null) return null;
        String clean = commandName.toLowerCase(Locale.ROOT);
        for (OBUDefinition def : values()) {
            if (def.commandName().equals(clean)) {
                return def;
            }
        }
        return null;
    }

    public static Set<String> getRegisteredNames() {
        return Arrays.stream(values())
                .map(OBUDefinition::commandName)
                .collect(Collectors.toSet());
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

        private final int id;
        PerBlockSetting(int id) { this.id = id; }
        public int getId() { return id; }

        public static short parse(@NonNull String arg) {
            return (short) valueOf(arg.toUpperCase(Locale.ROOT)).id;
        }
    }

    public enum CollisionMode {
        VANILLA(0),
        NO_BOATS_OR_PLAYERS(1),
        NO_ENTITIES(2),
        ENTITYTYPE_FILTER(3),
        NO_BOATS_OR_PLAYERS_PLUS_FILTER(4);

        private final int id;
        CollisionMode(int id) { this.id = id; }
        public int getId() { return id; }

        public static short parse(@NonNull String arg) {
            return (short) valueOf(arg.toUpperCase(Locale.ROOT)).id;
        }
    }

    public enum ContextPacket {
        RESET_CONTEXT(0),
        SWITCH_CONTEXT(1),
        DROP_CONTEXT(2),
        STORE_CONTEXT(3),
        ENTITY_CONTEXT(4),
        COMPOUND(5);

        private final int id;
        ContextPacket(int id) { this.id = id; }
        public int getId() { return id; }
    }
}
