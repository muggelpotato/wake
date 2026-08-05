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

/** The path a vehicle took within a tick, rather than the straight line from start to end its move event reports */
public final class VehiclePath implements Listener {
    private static final int MAX_POINTS = 8;
    private final Wake plugin;
    private final Map<UUID, List<Vector>> points = new HashMap<>();
    private int claims;
    public VehiclePath(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    public void claim() {
        if (claims++ == 0) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    public void release() {
        if (claims > 0 && --claims == 0) {
            HandlerList.unregisterAll(this);
            points.clear();
        }
    }

    public @NonNull Legs legs(@NonNull VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        Vector end = to.toVector();
        List<Vector> recorded = points.get(event.getVehicle().getUniqueId());
        boolean sameWorld = from.getWorld() == to.getWorld();
        Legs legs = Legs.of(sameWorld && recorded != null ? recorded : List.of(), sameWorld ? from.toVector() : end, end);
        if (recorded != null) {
            recorded.clear();
        }
        return legs;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(@NonNull PlayerMoveEvent event) {
        if (!(event.getPlayer().getVehicle() instanceof Vehicle vehicle)) {
            return;
        }
        List<Vector> recorded = points.computeIfAbsent(vehicle.getUniqueId(), key -> new ArrayList<>(MAX_POINTS));
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

    @EventHandler
    public void onEntityRemove(@NonNull EntityRemoveEvent event) {
        points.remove(event.getEntity().getUniqueId());
    }

    /** A tick's path as the boundaries between its legs. Where the vehicle started, every position it was seen at in between, and where it ended */
    public record Legs(@NonNull List<Vector> boundaries) {
        public Legs {
            boundaries = List.copyOf(boundaries);
        }

        static @NonNull Legs of(@NonNull List<Vector> recorded, @NonNull Vector start, @NonNull Vector end) {
            List<Vector> boundaries = new ArrayList<>(recorded.size() + 2);
            boundaries.add(start);
            for (Vector point : recorded) {
                if (!point.equals(start) && !point.equals(end)) {
                    boundaries.add(point);
                }
            }
            boundaries.add(end);
            return new Legs(boundaries);
        }

        public int count() {
            return boundaries.size() - 1;
        }

        public @NonNull Vector at(int boundary) {
            return boundaries.get(boundary);
        }

        public double progress(int leg, double fraction) {
            return (leg + fraction) / count();
        }
    }
}