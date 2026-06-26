package dev.muggel.wake.features.drydock.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.DrydockService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.NonNull;

public class DrydockServiceImpl implements DrydockService {
    private final Wake plugin;

    public DrydockServiceImpl(Wake plugin) {
        this.plugin = plugin;
    }

    @Override
    public void giveDrydockBoat(Player player, @NonNull EntityType boatType, int variant) {
        if (boatType.getEntityClass() != null && Boat.class.isAssignableFrom(boatType.getEntityClass())) {
            String boatId = boatType.getKey().toString();
            String command = String.format(Locale.US, "minecraft:give %s %s[minecraft:entity_data={id:\"%s\",Air:%ds},enchantment_glint_override=true] 1",
                    player.getName(), boatId, boatId, variant);
            
            Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
            
            String variantName = getVariantName(variant);
            plugin.getMessageManager().send(player, "commands.drydock.give",
                    Placeholder.parsed("boat", boatType.name().toLowerCase()),
                    Placeholder.parsed("variant", variantName));
        } else {
            plugin.getMessageManager().send(player, "commands.drydock.invalid_boat");
        }
    }

    private String getVariantName(int variant) {
        return switch (variant) {
            case 1 -> "parkour (with oars)";
            case 2 -> "parkour (no oars)";
            default -> "unknown (" + variant + ")";
        };
    }
}
