package dev.muggel.wake.features.obu.service;

import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.context.OBUSetting;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class VehicleScaleCache {
    private static final double UNSCALED = 1.0;
    private final Map<UUID, Double> scales = new ConcurrentHashMap<>();

    double scaleOf(@NonNull UUID uuid) {
        return scales.getOrDefault(uuid, UNSCALED);
    }

    void update(@NonNull UUID uuid, @NonNull List<OBUSetting> truth) {
        double scale = scaleIn(truth);
        if (scale == UNSCALED) {
            scales.remove(uuid);
        } else {
            scales.put(uuid, scale);
        }
    }

    void forget(@NonNull UUID uuid) {
        scales.remove(uuid);
    }

    private static double scaleIn(@NonNull List<OBUSetting> truth) {
        for (OBUSetting setting : truth) {
            if (setting.definition() == OBUDefinition.setscale && !setting.args().isEmpty()) {
                try {
                    return Double.parseDouble(setting.args().getFirst());
                } catch (NumberFormatException ignored) {}
            }
        }
        return UNSCALED;
    }
}