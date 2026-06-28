package dev.muggel.wake.features.drydock.api.events;

import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

public class PlayerHitBoostpadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Boat boat;
    private final Block block;
    private final double forceX;
    private final double forceY;
    private final double forceZ;

    public PlayerHitBoostpadEvent(Player player, Boat boat, Block block, double forceX, double forceY, double forceZ) {
        this.player = player;
        this.boat = boat;
        this.block = block;
        this.forceX = forceX;
        this.forceY = forceY;
        this.forceZ = forceZ;
    }

    public Player getPlayer() {
        return player;
    }

    public Boat getBoat() {
        return boat;
    }

    public Block getBlock() {
        return block;
    }

    public double getForceX() {
        return forceX;
    }

    public double getForceY() {
        return forceY;
    }

    public double getForceZ() {
        return forceZ;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
