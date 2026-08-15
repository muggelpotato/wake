package dev.muggel.wake.core;

import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;

/** Continuous collision math via raycasting */
public final class CollisionGeometry {
    private static final long MAX_SWEPT_BLOCKS = 4096;
    private static final double PARALLEL = 1e-9;
    private static final double MISS = -1.0;
    private CollisionGeometry() {}

    public record BlockSweep(@NonNull Vector from, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private boolean oversized() {
            long cap = MAX_SWEPT_BLOCKS + 1;
            return Math.min(maxX - (long) minX + 1, cap) * Math.min(maxY - (long) minY + 1, cap) * Math.min(maxZ - (long) minZ + 1, cap) > MAX_SWEPT_BLOCKS;
        }
    }

    /**
     * The blocks {@code from -> to} sweeps, widened by {@code reach} on X and Z. <br>
     * {@code yOffset} shifts the Y bounds to test a band at one face rather than a hull instead. <br>
     * Past {@link #MAX_SWEPT_BLOCKS} the sweep gives up on the path and samples where it landed
     */
    public static @NonNull BlockSweep sweep(@NonNull Vector from, @NonNull Vector to, double reach, double yOffset) {
        BlockSweep swept = range(from, to, reach, yOffset);
        return swept.oversized() ? range(to, to, reach, yOffset) : swept;
    }

    private static @NonNull BlockSweep range(@NonNull Vector from, @NonNull Vector to, double reach, double yOffset) {
        return new BlockSweep(from,
                (int) Math.floor(Math.min(from.getX(), to.getX()) - reach),
                (int) Math.floor(Math.max(from.getX(), to.getX()) + reach),
                (int) Math.floor(Math.min(from.getY(), to.getY()) + yOffset),
                (int) Math.floor(Math.max(from.getY(), to.getY()) + yOffset),
                (int) Math.floor(Math.min(from.getZ(), to.getZ()) - reach),
                (int) Math.floor(Math.max(from.getZ(), to.getZ()) + reach));
    }

    /** How far along {@code from -> to} the segment first touches the box, as a fraction in {@code [0,1]}, or negative when it never does */
    public static double intersectionFraction(@NonNull Vector from, @NonNull Vector to, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double tMin = 0.0;
        double tMax = 1.0;

        double fromX = from.getX();
        double dirX = to.getX() - fromX;
        if (Math.abs(dirX) < PARALLEL) {
            if (fromX < minX || fromX > maxX) return MISS;
        } else {
            double t1 = (minX - fromX) / dirX;
            double t2 = (maxX - fromX) / dirX;
            double near = Math.min(t1, t2);
            double far = Math.max(t1, t2);
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
        }

        double fromY = from.getY();
        double dirY = to.getY() - fromY;
        if (Math.abs(dirY) < PARALLEL) {
            if (fromY < minY || fromY > maxY) return MISS;
        } else {
            double t1 = (minY - fromY) / dirY;
            double t2 = (maxY - fromY) / dirY;
            double near = Math.min(t1, t2);
            double far = Math.max(t1, t2);
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
        }

        double fromZ = from.getZ();
        double dirZ = to.getZ() - fromZ;
        if (Math.abs(dirZ) < PARALLEL) {
            if (fromZ < minZ || fromZ > maxZ) return MISS;
        } else {
            double t1 = (minZ - fromZ) / dirZ;
            double t2 = (maxZ - fromZ) / dirZ;
            double near = Math.min(t1, t2);
            double far = Math.max(t1, t2);
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
        }
        return tMin <= tMax ? tMin : MISS;
    }
}