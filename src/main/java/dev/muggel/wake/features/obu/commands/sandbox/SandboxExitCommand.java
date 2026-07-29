package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Objects;

public class SandboxExitCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("exit")
                .executesPlayer((ctx, player) -> execute(ctx, player, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Player player, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String sandbox = service.getPlayerActiveSandbox(player);
        if (sandbox == null) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.none_active");
            return 0;
        }
        service.setPlayerActiveSandbox(player, null);
        OBUContext context = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
        service.getSyncManager().clearLocalOverrides(player.getUniqueId());
        service.applyContext(player, Objects.requireNonNullElseGet(context, () -> new OBUContext(OBUContextManager.DEFAULT_CONTEXT, new ArrayList<>())));
        if (player.getVehicle() instanceof Boat boat) {
            service.getSyncManager().broadcastSync(boat);
        }
        service.getSyncManager().syncPlayer(player);
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.exited");
        return Command.SINGLE_SUCCESS;
    }
}