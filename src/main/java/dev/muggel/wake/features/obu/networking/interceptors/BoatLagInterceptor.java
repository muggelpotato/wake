package dev.muggel.wake.features.obu.networking.interceptors;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;

public class BoatLagInterceptor extends PacketListenerAbstract {
    // disables servers vehicle anti cheat
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.VEHICLE_MOVE) {
            event.setCancelled(true);
        }
    }
}
