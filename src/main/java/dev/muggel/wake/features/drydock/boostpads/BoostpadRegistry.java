package dev.muggel.wake.features.drydock.boostpads;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.features.drydock.DrydockDao;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import org.bukkit.Material;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class BoostpadRegistry {
    private final Wake plugin;
    private final DrydockDao dao;
    private final CachedStore<BoostpadConfig> boostpads;
    private volatile Map<Material, BoostpadConfig> materialConfigs = Collections.emptyMap();
    private volatile Map<String, BoostpadConfig> publishedConfigs = Collections.emptyMap();
    private volatile double cachedMaxPadding = 0.0;
    private Runnable onReloadCallback;
    public BoostpadRegistry(Wake plugin, @NonNull DrydockDao dao) {
        this.plugin = plugin;
        this.dao = dao;
        this.boostpads = dao.boostpads();
        boostpads.load();
        rebuildDerived();
        if (!boostpads.isLoaded()) {
            boostpads.reloadAsync(changed -> rebuildDerived());
        }
    }

    public boolean isLoaded() {
        return boostpads.isLoaded();
    }

    public void setOnReloadCallback(@Nullable Runnable callback) {
        this.onReloadCallback = callback;
    }

    public void reloadBoostpads() {
        if (plugin.getDatabaseManager().isDegraded()) {
            return;
        }
        boostpads.reloadAsync(changed -> rebuildDerived());
    }

    private void rebuildDerived() {
        Map<String, BoostpadConfig> snapshot = Map.copyOf(boostpads.view());
        Map<Material, BoostpadConfig> newConfigs = new HashMap<>();
        double maxPadding = 0.0;
        for (Map.Entry<String, BoostpadConfig> entry : snapshot.entrySet()) {
            BoostpadConfig cfg = entry.getValue();
            if (cfg.enabled()) {
                NamespacedKey nsk = NamespacedKey.fromString(entry.getKey());
                Material mat = nsk != null ? Registry.MATERIAL.get(nsk) : Material.matchMaterial(entry.getKey());
                if (mat != null) {
                    newConfigs.put(mat, cfg);
                }
                maxPadding = Math.max(maxPadding, cfg.padding());
            }
        }
        this.publishedConfigs = snapshot;
        this.materialConfigs = Map.copyOf(newConfigs);
        this.cachedMaxPadding = maxPadding;
        if (this.onReloadCallback != null) {
            this.onReloadCallback.run();
        }
    }

    public @NonNull Map<Material, BoostpadConfig> getBoostpadConfigs() {
        return materialConfigs;
    }

    public double getMaxPadding() {
        return cachedMaxPadding;
    }

    public void saveBoostpadConfig(@NonNull BoostpadConfig config) {
        dao.saveBoostpad(config);
        rebuildDerived();
    }

    public void deleteBoostpadConfig(@NonNull String blockKey) {
        dao.deleteBoostpad(blockKey);
        rebuildDerived();
    }

    public @NonNull Map<String, BoostpadConfig> cachedBoostpads() {
        return publishedConfigs;
    }

    public void refreshRegistration() {
        if (onReloadCallback != null) {
            onReloadCallback.run();
        }
    }
}