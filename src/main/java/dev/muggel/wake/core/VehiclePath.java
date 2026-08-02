package dev.muggel.wake.core;

import dev.muggel.wake.Wake;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The path of a vehicle in a tick. <br>
 * Not just the straight line from start to end
 */
public final class VehiclePath implements Listener {
    private static final int MAX_POINTS = 8;
    private final Wake plugin;
    private final Map<UUID, List<Vector>> points = new HashMap<>();
    private int consumers;
    public VehiclePath(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    public void claim() {
        if (consumers++ == 0) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    public void release() {
        if (--consumers == 0) {
            HandlerList.unregisterAll(this);
            points.clear();
        }
    }

    public @NonNull Legs legs(@NonNull VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        Vector end = to.toVector();
        List<Vector> recorded = points.get(event.getVehicle().getUniqueId());
        return new Legs(recorded == null ? List.of() : recorded, from.getWorld() == to.getWorld() ? from.toVector() : end, end);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(@NonNull PlayerMoveEvent event) {
        Entity vehicle = event.getPlayer().getVehicle();
        if (vehicle == null) {
            return;
        }
        List<Vector> recorded = points.computeIfAbsent(vehicle.getUniqueId(), key -> new ArrayList<>(4));
        if (recorded.size() < MAX_POINTS && !unmoved(recorded, vehicle)) {
            recorded.add(new Vector(vehicle.getX(), vehicle.getY(), vehicle.getZ()));
        }
    }

    // dedupe passenger movement data
    private static boolean unmoved(@NonNull List<Vector> recorded, @NonNull Entity vehicle) {
        if (recorded.isEmpty()) {
            return false;
        }
        Vector last = recorded.getLast();
        return last.getX() == vehicle.getX() && last.getY() == vehicle.getY() && last.getZ() == vehicle.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(@NonNull VehicleMoveEvent event) {
        List<Vector> recorded = points.get(event.getVehicle().getUniqueId());
        if (recorded != null) {
            recorded.clear();
        }
    }

    @EventHandler
    public void onEntityRemove(@NonNull EntityRemoveEvent event) {
        if (event.getEntity() instanceof Vehicle) {
            points.remove(event.getEntity().getUniqueId());
        }
    }

    public record Legs(@NonNull List<Vector> points, @NonNull Vector start, @NonNull Vector end) {
        public int count() {
            return Math.max(1, points.size());
        }

        public @NonNull Vector at(int boundary) {
            if (boundary == 0) {
                return start;
            }
            return boundary < count() ? points.get(boundary - 1) : end;
        }

        public double progress(int leg, double fraction) {
            return (leg + fraction) / count();
        }
    }
}