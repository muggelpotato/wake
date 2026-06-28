package dev.muggel.wake.features.drydock.integration.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.events.PlayerHitBoostpadEvent;
import dev.muggel.wake.features.obu.api.OBUService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public class OBUBoostpadIntegration implements Listener {
    public static double getVehicleScale(@NonNull UUID uuid) {
        OBUService obuService = Wake.getServiceRegistry().get(OBUService.class);
        return obuService != null ? obuService.getVehicleScale(uuid) : 1.0;
    }

    @EventHandler
    public void onBoostpadHit(@NonNull PlayerHitBoostpadEvent event) {
        OBUService obuService = Wake.getServiceRegistry().get(OBUService.class);
        if (obuService == null) {
            return;
        }

        obuService.applyRelativeImpulse(
                event.getPlayer(),
                event.getForceX(),
                event.getForceY(),
                event.getForceZ()
        );
    }
}
