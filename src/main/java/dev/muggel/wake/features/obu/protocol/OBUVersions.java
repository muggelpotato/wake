package dev.muggel.wake.features.obu.protocol;

import dev.muggel.wake.features.obu.protocol.OBUDefinition.CollisionMode;
import dev.muggel.wake.features.obu.protocol.OBUDefinition.PerBlockSetting;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

public final class OBUVersions {
    public static final int MINIMUM_SUPPORTED = 19;
    public static final int LATEST_SUPPORTED = 22;
    private static final Set<Integer> BROKEN = Set.of(20, 21);
    private record Ceiling(int packetId, short perBlockId, short collisionId) {}
    private static final NavigableMap<Integer, Ceiling> CEILINGS = new TreeMap<>(Map.of(
            19, new Ceiling(OBUDefinition.setresetonworldload.id(), PerBlockSetting.STEP_UP_SLIPPERINESS.id(),
                    CollisionMode.NO_BOATS_OR_PLAYERS_PLUS_FILTER.id()),
            20, new Ceiling(OBUDefinition.sethoneycompat.id(), PerBlockSetting.MAX_SPEED_RESISTANCE.id(),
                    CollisionMode.NO_BOATS_OR_PLAYERS_PLUS_FILTER.id())));
    private OBUVersions() {}

    public static boolean isSupported(int clientVersion) {
        return clientVersion >= MINIMUM_SUPPORTED && !BROKEN.contains(clientVersion);
    }

    public static boolean isPastCeiling(@NonNull OBUSetting setting, int clientVersion) {
        Ceiling ceiling = ceilingFor(clientVersion);
        if (ceiling == null || setting.definition().id() > ceiling.packetId()) {
            return true;
        }
        List<SettingType> types = setting.definition().types();
        List<String> args = setting.args();
        try {
            for (int i = 0; i < types.size() && i < args.size(); i++) {
                if (isPastCeiling(types.get(i), args.get(i), ceiling)) {
                    return true;
                }
            }
        } catch (IllegalArgumentException notWritable) {
            // not a version issue. packetwriter drops a row it cannot write
        }
        return false;
    }

    private static boolean isPastCeiling(@NonNull SettingType type, @NonNull String arg, @NonNull Ceiling ceiling) {
        return switch (type) {
            case SETTING_ENUM -> PerBlockSetting.valueOf(type.canonical(arg)).id() > ceiling.perBlockId();
            case COLLISION_ENUM -> CollisionMode.valueOf(type.canonical(arg)).id() > ceiling.collisionId();
            case BOOLEAN, FLOAT, DOUBLE, INT, BYTE, BLOCK_LIST, ENTITY_LIST -> false;
        };
    }

    private static @Nullable Ceiling ceilingFor(int clientVersion) {
        Map.Entry<Integer, Ceiling> mark = CEILINGS.floorEntry(clientVersion);
        return mark == null ? null : mark.getValue();
    }
}