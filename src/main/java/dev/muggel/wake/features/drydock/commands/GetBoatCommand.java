package dev.muggel.wake.features.drydock.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.commands.arguments.KeyArgumentType;
import dev.muggel.wake.core.commands.arguments.WakeEnumArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.logging.Level;

public class GetBoatCommand {
    private enum Variant { PARKOUR }
    private enum Oars { OARS, NOOARS }
    private static final int AIR_OARS = 1;
    private static final int AIR_NO_OARS = 2;

    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("getboat")
                .withPreset(PermissionPreset.PLAYER)
                .arguments(
                        CommandNode.argument("boat_type", KeyArgumentType.boatType()),
                        CommandNode.argument("variant", WakeEnumArgumentType.wakeEnum(Variant.class))
                                .executesPlayer((ctx, player) -> executeGive(ctx, player, plugin, false)),
                        CommandNode.argument("oars", WakeEnumArgumentType.wakeEnum(Oars.class))
                                .executesPlayer((ctx, player) -> executeGive(ctx, player, plugin, true)));
    }

    private static int executeGive(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Player p, Wake plugin, boolean hasOarsArg) {
        CommandSender sender = ctx.getSource().getSender();
        String boatType = ctx.getArgument("boat_type", String.class);
        boolean oars = !hasOarsArg || Oars.valueOf(ctx.getArgument("oars", String.class)) == Oars.OARS;
        return giveBoat(plugin, p, sender, boatType, oars) ? Command.SINGLE_SUCCESS : 0;
    }

    private static boolean giveBoat(Wake plugin, @NonNull Player player, @NonNull CommandSender audience, @NonNull String boatType, boolean oars) {
        boolean is1_21_2 = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("oak_boat")) != null;
        if (!is1_21_2) {
            plugin.getMessageManager().send(audience, "commands.requires_version");
            return false;
        }
        String boatId = CommandHelper.stripNamespace(boatType);
        ItemStack item;
        try {
            String itemStr = String.format(Locale.ROOT, "minecraft:%s[minecraft:entity_data={id:\"minecraft:%s\",Air:%d},minecraft:enchantment_glint_override=true]", boatId, boatId, oars ? AIR_OARS : AIR_NO_OARS);
            item = Bukkit.getItemFactory().createItemStack(itemStr);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse boat item component string", e);
            plugin.getMessageManager().send(audience, "commands.drydock.getboat.fail");
            return false;
        }
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
        }
        String variantKey = oars ? "commands.drydock.getboat.variant_oars" : "commands.drydock.getboat.variant_no_oars";
        plugin.getMessageManager().send(audience, "commands.drydock.getboat.success", Placeholder.unparsed("boat", boatId), Placeholder.component("variant", plugin.getMessageManager().getComponent(variantKey)));
        return true;
    }
}