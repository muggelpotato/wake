package dev.muggel.wake.features.drydock.api;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Map;

public interface DrydockService {
    void giveDrydockBoat(Player player, String boatType, int variant);
    
    void reloadBoostpads();
    
    Map<Material, BoostpadConfig> getBoostpadConfigs();
    
    double getMaxOffsetMultiplier();

    void saveBoostpadConfig(BoostpadConfig config);

    void deleteBoostpadConfig(String blockKey);

    Map<String, BoostpadConfig> cachedBoostpads();

    void refreshRegistration();

    void setOnReloadCallback(Runnable callback);
}