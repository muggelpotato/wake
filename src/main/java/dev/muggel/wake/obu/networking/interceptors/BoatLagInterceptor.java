package dev.muggel.wake.obu.networking.interceptors;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import dev.muggel.wake.Wake;
import org.bukkit.entity.Player;

public class BoatLagInterceptor extends PacketListenerAbstract {
    private final Wake plugin;
    public BoatLagInterceptor(Wake plugin) {
        this.plugin = plugin;
    }
    // disables servers vehicle anti cheat
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.VEHICLE_MOVE) {
            Player player = (Player) event.getPlayer();
            if (player != null && player.isInsideVehicle()) {
                event.setCancelled(true);
            }
        }
    }
}
