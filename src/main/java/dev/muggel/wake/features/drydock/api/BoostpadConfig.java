package dev.muggel.wake.features.drydock.api;

import org.jspecify.annotations.NonNull;

public record BoostpadConfig(
        @NonNull String blockKey,
        boolean enabled,
        double forceX,
        double forceY,
        double forceZ,
        long delayMs,
        int hitboxPercent
) {
    public static final int MAX_HITBOX_PERCENT = 245;
    public static final int DEFAULT_HITBOX_PERCENT = 99; // boats clip slightly into walls, an issue when colliding with a wall made of boostpads

    public BoostpadConfig {
        forceX = Double.isFinite(forceX) ? forceX : 0.0;
        forceY = Double.isFinite(forceY) ? forceY : 0.0;
        forceZ = Double.isFinite(forceZ) ? forceZ : 0.0;
        delayMs = Math.max(0L, delayMs);
        hitboxPercent = Math.clamp(hitboxPercent, 0, MAX_HITBOX_PERCENT);
    }

    public double offsetMultiplier() {
        return (hitboxPercent / 100.0) - 1.0;
    }
}