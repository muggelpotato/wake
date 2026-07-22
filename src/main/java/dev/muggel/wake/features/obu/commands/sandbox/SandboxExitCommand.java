package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Objects;

public class SandboxExitCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("exit")
                .executesPlayer((ctx, player) -> execute(player, plugin));
    }

    private static int execute(Player player, Wake plugin) {
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String sandbox = service.getPlayerActiveSandbox(player);
        if (sandbox == null) {
            plugin.getMessageManager().send(player, "commands.obu.sandbox.none_active");
            return 0;
        }
        service.setPlayerActiveSandbox(player, null);
        OBUContext context = contextManager.getContext("default");
        service.resetPlayer(player);
        service.applyContext(player, Objects.requireNonNullElseGet(context, () -> new OBUContext("default", new ArrayList<>())));
        if (player.getVehicle() instanceof Boat boat) {
            service.broadcastBoatContext(boat);
        }
        service.getSyncManager().syncPlayer(player);
        plugin.getMessageManager().send(player, "commands.obu.sandbox.exited");
        return Command.SINGLE_SUCCESS;
    }
}