package dev.muggel.wake.features.drydock.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.DrydockService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Locale;

public class DrydockServiceImpl implements DrydockService {
    private final Wake plugin;

    public DrydockServiceImpl(Wake plugin) {
        this.plugin = plugin;
    }

    @Override
    public void giveDrydockBoat(Player player, String boatType, int variant) {
        boolean is1_21_2 = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("oak_boat")) != null;
        if (!is1_21_2) {
            plugin.getMessageManager().send(player, "commands.requires_version");
            return;
        }
        String itemKey = "minecraft:" + boatType;
        String command = String.format(Locale.ROOT, "minecraft:give %s %s[minecraft:entity_data={id:\"%s\",Air:%d},enchantment_glint_override=true] 1",
                player.getName(), itemKey, itemKey, variant);
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
        String variantName = getVariantName(variant);
        plugin.getMessageManager().send(player, "commands.drydock.give",
                Placeholder.parsed("boat", boatType),
                Placeholder.parsed("variant", variantName));
    }

    private String getVariantName(int variant) {
        return switch (variant) {
            case 1 -> "parkour (with oars)";
            case 2 -> "parkour (no oars)";
            default -> "unknown (" + variant + ")";
        };
    }
}
