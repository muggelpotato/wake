package dev.muggel.wake.features.axiom;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class AxiomModule extends AbstractModule {
    private final AtomicLong reads = new AtomicLong();
    private long appliedRead;
    private AxiomDao dao;
    private static final String DISPLAY_API_CLASS = "com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI";
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
        if (models != null && !models.isEmpty()) {
            registerDisplays(models);
        }
        seedDataIfEmpty(models == null ? null : models.isEmpty(), "defaults/axiom_default.yml", "Axiom Displays");
    }

    @SuppressWarnings("PatternValidation")
    private void registerDisplays(List<String> models) {
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
        long ticket = reads.incrementAndGet();
        getPlugin().getDatabaseManager().readAsync(currentDao::loadDisplays, models -> {
            if (models == null || this.dao != currentDao || ticket <= appliedRead) return;
            appliedRead = ticket;
            unregisterDisplays();
            if (!models.isEmpty()) {
                registerDisplays(models);
            }
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
        List<String> models = dao.loadDisplays();
        if (models == null) {
            throw new SQLException("Axiom displays could not be read");
        }
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