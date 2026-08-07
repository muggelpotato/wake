package dev.muggel.wake.features.drydock.integration;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.PlayerHitBoostpadEvent;
import dev.muggel.wake.features.obu.api.OBUService;
import org.bukkit.entity.Boat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

public class OBUBoostpadIntegration implements Listener {
    private final Wake plugin;
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
        OBUService service = plugin.getServiceRegistry().get(OBUService.class);
        if (service == null) return;
        service.applyRelativeImpulse(
                event.getPlayer(),
                event.getForceX(),
                event.getForceY(),
                event.getForceZ()
        );
    }
}