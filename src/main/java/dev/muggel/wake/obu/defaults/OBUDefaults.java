package dev.muggel.wake.obu.defaults;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class OBUDefaults {
    private static final Map<String, OBUDefaultValue> DEFAULTS = Map.ofEntries(
        entry("falldamage", "true"),
        entry("waterelevation", "false"),
        entry("aircontrol", "false"),
        entry("defaultslipperiness", "0.6"),
        entry("jumpforce", "0.0"),
        entry("stepsize", "0.0"),
        entry("boatgravity", "-0.03999999910593033"),
        entry("setyawaccel", "1.0"),
        entry("setforwardaccel", "0.04"),
        entry("setbackwardaccel", "0.005"),
        entry("setturnforwardaccel", "0.005"),
        entry("allowaccelstacking", "false"),
        entry("underwatercontrol", "false"),
        entry("surfacewatercontrol", "false"),
        entry("coyotetime", "0"),
        entry("waterjumping", "false"),
        entry("swimforce", "0.0"),
        entry("collisionmode", "VANILLA"),
        entry("stepwhilefalling", "false"),
        entry("setcollisionresolution", "1"),
        entry("setwalltapmultiplier", "0.0"),
        entry("setjumps", "1"),
        entry("setscale", "1.0"),
        entry("setstepupslipperiness", "1.0"),
        entry("setresetonworldload", "false")
    );

    private static Map.Entry<String, OBUDefaultValue> entry(String name, String... values) {
        return Map.entry(name.toLowerCase(Locale.ROOT), new OBUDefaultValue(name, values));
    }

    public static Optional<OBUDefaultValue> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(DEFAULTS.get(name.toLowerCase(Locale.ROOT)));
    }

    public static Set<String> getNames() {
        return DEFAULTS.keySet();
    }
}
