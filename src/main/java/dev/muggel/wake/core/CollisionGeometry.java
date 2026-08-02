package dev.muggel.wake.core;

import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;

/** Continuous collision math via raycasting */
public final class CollisionGeometry {
    private static final long MAX_SWEPT_BLOCKS = 4096;
    private CollisionGeometry() {}

    public record BlockSweep(@NonNull Vector from, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public long blocks() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    public static @NonNull BlockSweep sweep(@NonNull Vector from, @NonNull Vector to, double reachX, double reachZ, double yOffset) {
        BlockSweep swept = range(from, to, reachX, reachZ, yOffset);
        return swept.blocks() <= MAX_SWEPT_BLOCKS ? swept : range(to, to, reachX, reachZ, yOffset);
    }

    private static @NonNull BlockSweep range(@NonNull Vector from, @NonNull Vector to, double reachX, double reachZ, double yOffset) {
        return new BlockSweep(from,
                (int) Math.floor(Math.min(from.getX(), to.getX()) - reachX),
                (int) Math.floor(Math.max(from.getX(), to.getX()) + reachX),
                (int) Math.floor(Math.min(from.getY(), to.getY()) + yOffset),
                (int) Math.floor(Math.max(from.getY(), to.getY()) + yOffset),
                (int) Math.floor(Math.min(from.getZ(), to.getZ()) - reachZ),
                (int) Math.floor(Math.max(from.getZ(), to.getZ()) + reachZ));
    }

    @SuppressWarnings("MathClampMigration")
    public static double intersectionFraction(
            double fromX, double fromY, double fromZ,
            double toX, double toY, double toZ,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        double tMin = 0.0;
        double tMax = 1.0;

        double dirX = toX - fromX;
        if (Math.abs(dirX) < 1e-9) {
            if (fromX < minX || fromX > maxX) return -1;
        } else {
            double t1 = (minX - fromX) / dirX;
            double t2 = (maxX - fromX) / dirX;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return -1;
        }

        double dirY = toY - fromY;
        if (Math.abs(dirY) < 1e-9) {
            if (fromY < minY || fromY > maxY) return -1;
        } else {
            double t1 = (minY - fromY) / dirY;
            double t2 = (maxY - fromY) / dirY;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return -1;
        }

        double dirZ = toZ - fromZ;
        if (Math.abs(dirZ) < 1e-9) {
            if (fromZ < minZ || fromZ > maxZ) return -1;
        } else {
            double t1 = (minZ - fromZ) / dirZ;
            double t2 = (maxZ - fromZ) / dirZ;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return -1;
        }
        return tMin;
    }
}