package dev.muggel.wake.features.drydock.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.drydock.DrydockModule;
import dev.muggel.wake.features.drydock.api.DrydockService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Locale;

public class DrydockGetBoatCommand {
    private static final List<String> SUPPORTED_VARIANTS = List.of("parkour");
    private static final List<String> SUPPORTED_OARS = List.of("oars", "nooars");

    private static final List<String> BOAT_KEYS = Registry.MATERIAL.stream()
            .filter(Material::isItem)
            .map(m -> m.getKey().getKey())
            .filter(name -> name.endsWith("_boat") || name.endsWith("_raft"))
            .toList();

    public static @NonNull CommandNode getNode(Wake plugin) {
        CommandNode getBoatNode = CommandNode.literal("getboat")
                .withModule(DrydockModule.class);

        CommandNode boatTypeArg = CommandNode.argument("boat_type", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                    BOAT_KEYS.stream()
                            .filter(name -> name.startsWith(remaining) || name.contains(remaining))
                            .forEach(builder::suggest);
                    return builder.buildFuture();
                });

        CommandNode variantArg = CommandNode.argument("variant", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                    SUPPORTED_VARIANTS.stream()
                            .filter(v -> v.startsWith(remaining))
                            .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executesPlayer((ctx, player) -> executeGive(ctx, player, plugin, null));

        CommandNode oarsArg = CommandNode.argument("oars", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                    SUPPORTED_OARS.stream()
                            .filter(v -> v.startsWith(remaining))
                            .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executesPlayer((ctx, player) -> {
                    String oarsStr = StringArgumentType.getString(ctx, "oars");
                    return executeGive(ctx, player, plugin, oarsStr);
                });

        variantArg.addSubcommand(oarsArg);
        boatTypeArg.addSubcommand(variantArg);
        getBoatNode.addSubcommand(boatTypeArg);

        return getBoatNode;
    }

    private static int executeGive(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Player p, Wake plugin, String oarsStr) {
        DrydockService service = Wake.getServiceRegistry().get(DrydockService.class);
        if (service == null) {
            plugin.getLogger().warning("DrydockService is not registered!");
            return 0;
        }

        String boatTypeStr = StringArgumentType.getString(ctx, "boat_type").toLowerCase(Locale.ROOT);
        if (!BOAT_KEYS.contains(boatTypeStr)) {
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

        service.giveDrydockBoat(p, boatTypeStr, variantId);
        return Command.SINGLE_SUCCESS;
    }

    private static int getVariantId(@NonNull String variantName, boolean oars) {
        if (variantName.equalsIgnoreCase("parkour")) {
            return oars ? 1 : 2;
        }
        return 1;
    }
}
