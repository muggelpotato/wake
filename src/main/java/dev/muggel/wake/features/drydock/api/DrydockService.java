package dev.muggel.wake.features.drydock.api;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.Map;

public interface DrydockService {
    void giveDrydockBoat(@NonNull Player player, @NonNull CommandSender audience, @NonNull String boatType, int variant);

    void reloadBoostpads();

    @NonNull Map<Material, BoostpadConfig> getBoostpadConfigs();

    double getMaxOffsetMultiplier();

    void saveBoostpadConfig(@NonNull BoostpadConfig config);

    void deleteBoostpadConfig(@NonNull String blockKey);

    @NonNull Map<String, BoostpadConfig> cachedBoostpads();

    void refreshRegistration();

    void setOnReloadCallback(Runnable callback);
}