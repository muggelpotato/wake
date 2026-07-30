package dev.muggel.wake.features.axiom;

import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.module.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public class AxiomModule extends AbstractModule {
    private static final String DISPLAY_API_CLASS = "com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI";
    private AxiomDao dao;
    public AxiomModule() {
        super("axiom");
    }

    @Override
    public boolean isCompatible() {
        return Bukkit.getPluginManager().isPluginEnabled("AxiomPaper");
    }

    @Override
    protected void onModuleEnable() {
        dao = new AxiomDao(getPlugin());
        dao.initTables();
        registerDao(dao);
        CachedStore<String> displays = dao.displays();
        boolean read = displays.load();
        registerDisplays(Set.copyOf(displays.keys()));
        seedDataIfEmpty(read ? displays.keys().isEmpty() : null, "defaults/axiom_default.yml", "Axiom Displays");
    }

    @SuppressWarnings("PatternValidation")
    private void registerDisplays(Set<String> models) {
        if (models.isEmpty()) return;
        try {
            Class<?> apiClass = Class.forName(DISPLAY_API_CLASS);
            Object apiInstance = apiClass.getMethod("getAPI").invoke(null);
            Method createMethod = apiClass.getMethod("create", Key.class, String.class, ItemStack.class);
            Method registerMethod = apiClass.getMethod("register", Plugin.class, createMethod.getReturnType());
            for (String model : models) {
                try {
                    NamespacedKey modelKey = NamespacedKey.fromString(model.toLowerCase(Locale.ROOT));
                    if (modelKey == null) {
                        getPlugin().getLogger().warning("Skipping invalid Axiom model key: " + model);
                        continue;
                    }
                    String namespace = modelKey.getNamespace();
                    String itemId = modelKey.getKey();
                    String displayName = formatDisplayName(itemId);
                    Component nameComponent = getPlugin().getMessageManager().getComponent("axiom.display_name", Placeholder.unparsed("name", displayName));
                    ItemStack item = new ItemStack(Material.PAPER);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        try {
                            meta.setItemModel(modelKey);
                        } catch (LinkageError err) {
                            getPlugin().getLogger().warning("Custom item models are not supported on this server version (requires Paper 1.21.2+)");
                        }
                        meta.displayName(nameComponent);
                        item.setItemMeta(meta);
                    }
                    Key axiomKey = Key.key("wake", namespace + "_" + itemId);
                    Object builder = createMethod.invoke(apiInstance, axiomKey, model.toLowerCase(Locale.ROOT), item);
                    registerMethod.invoke(apiInstance, getPlugin(), builder);
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.SEVERE, "Failed to register Axiom model " + model, e);
                }
            }
        } catch (Exception e) {
            getPlugin().getLogger().log(Level.SEVERE, "Failed to initialize Axiom API integration", e);
        }
    }

    private void unregisterDisplays() {
        if (!isCompatible()) return;
        try {
            Class<?> apiClass = Class.forName(DISPLAY_API_CLASS);
            Object apiInstance = apiClass.getMethod("getAPI").invoke(null);
            apiClass.getMethod("unregisterAll", Plugin.class).invoke(apiInstance, getPlugin());
        } catch (Exception e) {
            getPlugin().getLogger().log(Level.SEVERE, "Failed to unregister Axiom displays", e);
        }
    }

    @Override
    protected void onModuleDisable() {
        unregisterDisplays();
        dao = null;
    }

    @Override
    public void reload() {
        AxiomDao currentDao = this.dao;
        if (currentDao == null || !isCompatible()) return;
        if (getPlugin().getDatabaseManager().isDegraded()) return;
        currentDao.displays().reloadAsync(ignored -> {
            if (this.dao != currentDao) return;
            unregisterDisplays();
            registerDisplays(Set.copyOf(currentDao.displays().keys()));
        });
    }

    private @NonNull String formatDisplayName(@NonNull String itemId) {
        String[] parts = itemId.split("[_-]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    @Override
    protected int onExportData(YamlConfiguration yaml) throws SQLException {
        if (dao == null) return 0;
        CachedStore<String> displays = dao.displays();
        if (!displays.isLoaded()) {
            throw new SQLException("Axiom displays could not be read");
        }
        List<String> models = List.copyOf(displays.keys());
        yaml.set("displays", models);
        return models.size();
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) {
        List<String> displays = yaml.getStringList("displays");
        if (displays.isEmpty()) return 0;
        if (dao == null) return 0;
        int count = 0;
        for (String display : displays) {
            try {
                dao.importDisplay(display);
                count++;
            } catch (SQLException e) {
                getPlugin().getLogger().log(Level.SEVERE, "Failed to import axiom display " + display, e);
            }
        }
        reload();
        return count;
    }
}