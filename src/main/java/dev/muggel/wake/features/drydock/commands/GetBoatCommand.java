package dev.muggel.wake.features.drydock.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.commands.arguments.BoatTypeArgumentType;
import dev.muggel.wake.core.commands.arguments.WakeEnumArgumentType;
import dev.muggel.wake.features.drydock.api.DrydockService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class GetBoatCommand {
    private enum Variant { PARKOUR }
    private enum Oars { OARS, NOOARS }

    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("getboat")
                .withPreset(PermissionPreset.PLAYER)
                .arguments(
                        CommandNode.argument("boat_type", BoatTypeArgumentType.boatType()),
                        CommandNode.argument("variant", WakeEnumArgumentType.wakeEnum(Variant.class))
                                .executesPlayer((ctx, player) -> executeGive(ctx, player, plugin, false)),
                        CommandNode.argument("oars", WakeEnumArgumentType.wakeEnum(Oars.class))
                                .executesPlayer((ctx, player) -> executeGive(ctx, player, plugin, true)));
    }

    private static int executeGive(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Player p, Wake plugin, boolean hasOarsArg) {
        CommandSender sender = ctx.getSource().getSender();
        DrydockService service = DrydockCommandHelper.requireService(plugin, sender);
        if (service == null) return 0;
        String boatType = ctx.getArgument("boat_type", String.class);
        Variant variant = Variant.valueOf(ctx.getArgument("variant", String.class));
        boolean oars = !hasOarsArg || Oars.valueOf(ctx.getArgument("oars", String.class)) == Oars.OARS;
        service.giveDrydockBoat(p, sender, boatType, getVariantId(variant, oars));
        return Command.SINGLE_SUCCESS;
    }

    private static int getVariantId(@NonNull Variant variant, boolean oars) {
        return switch (variant) {
            case PARKOUR -> oars ? 1 : 2;
        };
    }
}