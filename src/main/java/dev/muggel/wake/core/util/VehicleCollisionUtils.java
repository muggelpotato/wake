package dev.muggel.wake.core.util;

/** Continuous collision math for vehicles via raycasting */
public final class VehicleCollisionUtils {
    private VehicleCollisionUtils() {}

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