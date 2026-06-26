package dev.muggel.wake.features.drydock.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.DrydockService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Boat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class DrydockGetBoatCommand {
    private static final List<String> SUPPORTED_VARIANTS = List.of("parkour");
    private static final List<String> SUPPORTED_OARS = List.of("oars", "nooars");

    public static void register(@NonNull LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(Commands.literal("getboat")
                .then(Commands.argument("boat_type", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemaining().toLowerCase();
                            Registry.ENTITY_TYPE.stream()
                                    .filter(EntityType::isSpawnable)
                                    .filter(e -> e.getEntityClass() != null && Boat.class.isAssignableFrom(e.getEntityClass()))
                                    .map(e -> e.getKey().getKey())
                                    .filter(name -> name.startsWith(remaining) || name.contains(remaining))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("variant", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String remaining = builder.getRemaining().toLowerCase();
                                    SUPPORTED_VARIANTS.stream()
                                            .filter(v -> v.startsWith(remaining))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> executeGive(ctx, plugin, null))
                                .then(Commands.argument("oars", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String remaining = builder.getRemaining().toLowerCase();
                                            SUPPORTED_OARS.stream()
                                                    .filter(v -> v.startsWith(remaining))
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String oarsStr = StringArgumentType.getString(ctx, "oars");
                                            return executeGive(ctx, plugin, oarsStr);
                                        })
                                )
                        )
                )
        );
    }

    private static int executeGive(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin, String oarsStr) {
        if (!(ctx.getSource().getSender() instanceof Player p)) {
            plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.only_players");
            return 0;
        }

        DrydockService service = Wake.getServiceRegistry().get(DrydockService.class);
        if (service == null) {
            return 0;
        }

        String boatTypeStr = StringArgumentType.getString(ctx, "boat_type");
        NamespacedKey key = NamespacedKey.minecraft(boatTypeStr.toLowerCase());
        EntityType type = Registry.ENTITY_TYPE.get(key);

        if (type == null || type.getEntityClass() == null || !Boat.class.isAssignableFrom(type.getEntityClass())) {
            plugin.getMessageManager().send(p, "commands.drydock.invalid_boat");
            return 0;
        }

        String variantStr = StringArgumentType.getString(ctx, "variant");
        if (SUPPORTED_VARIANTS.stream().noneMatch(v -> v.equalsIgnoreCase(variantStr))) {
            plugin.getMessageManager().send(p, "commands.drydock.invalid_variant");
            return 0;
        }

        boolean oars = true;
        if (oarsStr != null) {
            if (SUPPORTED_OARS.stream().noneMatch(o -> o.equalsIgnoreCase(oarsStr))) {
                plugin.getMessageManager().send(p, "commands.drydock.invalid_oars");
                return 0;
            }
            oars = oarsStr.equalsIgnoreCase("oars");
        }

        int variantId = getVariantId(variantStr, oars);

        service.giveDrydockBoat(p, type, variantId);
        return Command.SINGLE_SUCCESS;
    }

    private static int getVariantId(@NonNull String variantName, boolean oars) {
        if (variantName.equalsIgnoreCase("parkour")) {
            return oars ? 1 : 2;
        }
        return 1;
    }
}
