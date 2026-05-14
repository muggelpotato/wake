package dev.muggel.wake.obu;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OBUProtocol {
    
    private static final Map<String, Definition> COMMANDS = Map.ofEntries(
        // Contexts
        entry("resetcontext", 0, "context", List.of()),
        entry("switchcontext", 1, "context", List.of("context_id")),
        entry("dropcontext", 2, "context", List.of("context_id")),

        // Settings
        entry("reset", 0, "settings", List.of()),
        entry("stepsize", 1, "settings", List.of("float")),
        entry("defaultslipperiness", 2, "settings", List.of("float")),
        entry("blockslipperiness", 3, "settings", List.of("float", "block_list")),
        entry("falldamage", 4, "settings", List.of("boolean")),
        entry("waterelevation", 5, "settings", List.of("boolean")),
        entry("aircontrol", 6, "settings", List.of("boolean")),
        entry("jumpforce", 7, "settings", List.of("float")),
        entry("boatgravity", 9, "settings", List.of("double")),
        entry("setyawaccel", 10, "settings", List.of("float")),
        entry("setforwardaccel", 11, "settings", List.of("float")),
        entry("setbackwardaccel", 12, "settings", List.of("float")),
        entry("setturnforwardaccel", 13, "settings", List.of("float")),
        entry("allowaccelstacking", 14, "settings", List.of("boolean")),
        entry("underwatercontrol", 16, "settings", List.of("boolean")),
        entry("surfacewatercontrol", 17, "settings", List.of("boolean")),
        entry("coyotetime", 19, "settings", List.of("int")),
        entry("waterjumping", 20, "settings", List.of("boolean")),
        entry("swimforce", 21, "settings", List.of("float")),
        entry("removeblockslipperiness", 22, "settings", List.of("block_list")),
        entry("clearslipperiness", 23, "settings", List.of()),
        entry("setblocksetting", 26, "settings", List.of("setting_enum", "float", "block_list")),
        entry("collisionmode", 27, "settings", List.of("collision_enum")),
        entry("stepwhilefalling", 28, "settings", List.of("boolean")),
        entry("setinterpolationten", 29, "settings", List.of("boolean")),
        entry("setcollisionresolution", 30, "settings", List.of("byte")),
        entry("addcollisionfilter", 31, "settings", List.of("entity_list")),
        entry("clearcollisionfilter", 32, "settings", List.of()),
        entry("setwalltapmultiplier", 34, "settings", List.of("float")),
        entry("setjumps", 35, "settings", List.of("int")),
        entry("setscale", 36, "settings", List.of("float")),
        entry("setstepupslipperiness", 37, "settings", List.of("float")),
        entry("setresetonworldload", 38, "settings", List.of("boolean"))
    );

    private static Map.Entry<String, Definition> entry(String name, int id, String channel, List<String> types) {
        return Map.entry(name.toLowerCase(Locale.ROOT), new Definition(name, id, channel, types));
    }

    public static Set<String> getRegisteredNames() {
        return COMMANDS.keySet();
    }

    public static Definition get(String name) {
        if (name == null) return null;
        return COMMANDS.get(name.toLowerCase(Locale.ROOT));
    }

    public record Definition(String name, int id, String channel, List<String> types) {
        public String getPermission() {
            return "wake.obu.commands." + name.toLowerCase(Locale.ROOT);
        }
    }
}
