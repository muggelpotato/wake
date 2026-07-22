package dev.muggel.wake.features.drydock.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.DrydockService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Locale;
import java.util.logging.Level;

import dev.muggel.wake.features.drydock.DrydockDao;
import dev.muggel.wake.features.drydock.api.BoostpadConfig;
import org.bukkit.Material;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;

public class DrydockServiceImpl implements DrydockService {
    private final Wake plugin;
    private final DrydockDao dao;
    private volatile Map<Material, BoostpadConfig> materialConfigs = Collections.emptyMap();
    private volatile Map<String, BoostpadConfig> stringConfigs = Collections.emptyMap();
    private volatile double cachedMaxOffsetMultiplier = 0.0;
    private Runnable onReloadCallback;
    public DrydockServiceImpl(Wake plugin, @NonNull DrydockDao dao) {
        this.plugin = plugin;
        this.dao = dao;
        processReload(dao.loadAllBoostpads());
    }

    @Override
    public void setOnReloadCallback(Runnable callback) {
        this.onReloadCallback = callback;
    }

    @Override
    public void reloadBoostpads() {
        if (plugin.getDatabaseManager().isDegraded()) {
            return;
        }
        plugin.getDatabaseManager().readAsync(dao::loadAllBoostpads, this::processReload);
    }

    private void processReload(Map<String, BoostpadConfig> configs) {
        this.stringConfigs = Map.copyOf(configs);
        Map<Material, BoostpadConfig> newConfigs = new HashMap<>();
        int maxPct = 100;
        for (Map.Entry<String, BoostpadConfig> entry : configs.entrySet()) {
            BoostpadConfig cfg = entry.getValue();
            if (cfg.enabled()) {
                NamespacedKey nsk = NamespacedKey.fromString(entry.getKey());
                Material mat = nsk != null ? Registry.MATERIAL.get(nsk) : Material.matchMaterial(entry.getKey());
                if (mat != null) {
                    newConfigs.put(mat, cfg);
                }
                if (cfg.hitboxPercent() > maxPct) {
                    maxPct = cfg.hitboxPercent();
                }
            }
        }
        this.materialConfigs = Map.copyOf(newConfigs);
        this.cachedMaxOffsetMultiplier = (maxPct / 100.0) - 1.0;
        if (this.onReloadCallback != null) {
            this.onReloadCallback.run();
        }
    }

    @Override
    public Map<Material, BoostpadConfig> getBoostpadConfigs() {
        return materialConfigs;
    }

    @Override
    public double getMaxOffsetMultiplier() {
        return cachedMaxOffsetMultiplier;
    }

    @Override
    public void giveDrydockBoat(Player player, String boatType, int variant) {
        boolean is1_21_2 = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("oak_boat")) != null;
        if (!is1_21_2) {
            plugin.getMessageManager().send(player, "commands.requires_version");
            return;
        }
        Material mat = Material.matchMaterial(boatType);
        if (mat == null) {
            plugin.getMessageManager().send(player, "commands.drydock.getboat.invalid_boat");
            return;
        }
        String boatId = mat.getKey().getKey();
        ItemStack item;
        try {
            String itemStr = String.format(Locale.ROOT, "minecraft:%s[minecraft:entity_data={id:\"minecraft:%s\",Air:%d},minecraft:enchantment_glint_override=true]", boatId, boatId, variant);
            item = Bukkit.getItemFactory().createItemStack(itemStr);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse boat item component string", e);
            plugin.getMessageManager().send(player, "commands.drydock.getboat.fail");
            return;
        }
        player.getInventory().addItem(item);
        plugin.getMessageManager().send(player, "commands.drydock.getboat.success",
                Placeholder.unparsed("boat", boatType),
                Placeholder.component("variant", plugin.getMessageManager().getComponent(variantKey(variant))));
    }

    @Contract(pure = true)
    private static @NonNull String variantKey(int variant) {
        return switch (variant) {
            case 1 -> "commands.drydock.getboat.variant_oars";
            case 2 -> "commands.drydock.getboat.variant_no_oars";
            default -> "commands.drydock.getboat.variant_unknown";
        };
    }

    @Override
    public void saveBoostpadConfig(BoostpadConfig config) {
        dao.saveBoostpad(config);
        Map<String, BoostpadConfig> current = new HashMap<>(this.stringConfigs);
        current.put(config.blockKey(), config);
        processReload(current);
    }

    @Override
    public void deleteBoostpadConfig(String blockKey) {
        dao.deleteBoostpad(blockKey);
        Map<String, BoostpadConfig> current = new HashMap<>(this.stringConfigs);
        current.remove(blockKey);
        processReload(current);
    }

    @Override
    public Map<String, BoostpadConfig> cachedBoostpads() {
        return this.stringConfigs;
    }

    @Override
    public void refreshRegistration() {
        if (onReloadCallback != null) {
            onReloadCallback.run();
        }
    }
}