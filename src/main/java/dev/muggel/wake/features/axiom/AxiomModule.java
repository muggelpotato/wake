package dev.muggel.wake.features.axiom;

import dev.muggel.wake.core.module.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public class AxiomModule extends AbstractModule {
    private static final String DEFAULT_NAME_FORMAT = "<accent><bold><!italic>%s";
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
        List<String> models = dao.loadDisplays();
        boolean wasEmpty = models.isEmpty();
        String nameFormat = getPlugin().getStateDao().get("axiom.format", DEFAULT_NAME_FORMAT);
        if (!models.isEmpty()) {
            registerDisplays(nameFormat, models);
        }
        seedDataIfEmpty(wasEmpty, "axiom_default.yml", "Axiom Displays");
    }

    @SuppressWarnings("PatternValidation")
    private void registerDisplays(String nameFormat, List<String> models) {
        try {
            Class<?> apiClass = Class.forName("com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI");
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
                    String formattedName = String.format(nameFormat, displayName);
                    Component nameComponent = getPlugin().getMessageManager().deserialize(formattedName);
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
        if (!Bukkit.getPluginManager().isPluginEnabled("AxiomPaper")) return;
        try {
            Class<?> apiClass = Class.forName("com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI");
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
        if (currentDao == null || !Bukkit.getPluginManager().isPluginEnabled("AxiomPaper")) return;
        if (getPlugin().getDatabaseManager().isDegraded()) return;
        unregisterDisplays();
        String nameFormat = getPlugin().getStateDao().get("axiom.format", DEFAULT_NAME_FORMAT);
        List<String> models = currentDao.loadDisplays();
        if (!models.isEmpty()) {
            registerDisplays(nameFormat, models);
        }
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
    protected int onExportData(YamlConfiguration yaml) {
        if (dao == null) return 0;
        List<String> models = dao.loadDisplays();
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