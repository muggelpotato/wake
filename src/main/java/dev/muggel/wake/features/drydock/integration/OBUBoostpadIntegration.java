package dev.muggel.wake.features.drydock.integration;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.PlayerHitBoostpadEvent;
import dev.muggel.wake.features.obu.api.OBUService;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OBUBoostpadIntegration implements Listener {
    private final Wake plugin;
    private final Map<UUID, Impulse> pending = new HashMap<>();
    public OBUBoostpadIntegration(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    public static double getVehicleScale(@NonNull Wake plugin, @NonNull Boat boat) {
        OBUService service = plugin.getServiceRegistry().get(OBUService.class);
        if (service == null) return 1.0;
        return service.getVehicleScale(boat);
    }

    @EventHandler
    public void onBoostpadHit(@NonNull PlayerHitBoostpadEvent event) {
        pending.merge(event.getPlayer().getUniqueId(),
                new Impulse(event.getForceX(), event.getForceY(), event.getForceZ()),
                Impulse::plus);
    }

    @EventHandler
    public void onTickEnd(@NonNull ServerTickEndEvent event) {
        if (pending.isEmpty()) {
            return;
        }
        OBUService service = plugin.getServiceRegistry().get(OBUService.class);
        if (service != null) {
            for (Map.Entry<UUID, Impulse> entry : pending.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                Impulse impulse = entry.getValue();
                if (player != null) {
                    service.applyRelativeImpulse(player, impulse.x(), impulse.y(), impulse.z());
                }
            }
        }
        pending.clear();
    }

    private record Impulse(double x, double y, double z) {
        private @NonNull Impulse plus(@NonNull Impulse other) {
            return new Impulse(x + other.x, y + other.y, z + other.z);
        }
    }
}