package dev.muggel.wake.features.obu.clients;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BoatLagInterceptor extends PacketListenerAbstract implements Listener {
    private final Set<UUID> boatRiders = ConcurrentHashMap.newKeySet();
    private final ClientRegistry clients;
    public BoatLagInterceptor(@NonNull ClientRegistry clients) {
        this.clients = clients;
    }

    public void adoptRider(@NonNull Player player) {
        if (player.getVehicle() instanceof Boat) {
            boatRiders.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(@NonNull VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Boat && event.getEntered() instanceof Player player) {
            boatRiders.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(@NonNull VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            boatRiders.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(@NonNull PlayerQuitEvent event) {
        boatRiders.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPacketSend(@NonNull PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.VEHICLE_MOVE || boatRiders.isEmpty()) {
            return;
        }
        UUID uuid = event.getUser().getUUID();
        if (boatRiders.contains(uuid) && clients.isDriven(uuid)) {
            event.setCancelled(true);
        }
    }
}