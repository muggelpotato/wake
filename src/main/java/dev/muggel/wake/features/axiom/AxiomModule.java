package dev.muggel.wake.features.axiom;

import dev.muggel.wake.core.module.AbstractModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Method;
import java.util.List;

public class AxiomModule extends AbstractModule {
    private static final String DEFAULT_NAME_FORMAT = "<#33B5FF><bold><!italic>%s";

    public AxiomModule() {
        super("axiom");
    }

    @Override
    public boolean isCompatible() {
        return Bukkit.getPluginManager().isPluginEnabled("AxiomPaper");
    }

    @Override
    @SuppressWarnings("PatternValidation")
    protected void onModuleEnable() {
        String nameFormat = plugin.getConfig().getString("axiom.format", DEFAULT_NAME_FORMAT);
        List<String> models = plugin.getConfig().getStringList("axiom.displays");
        if (models.isEmpty()) return;

        try {
            Class<?> apiClass = Class.forName("com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI");
            Object apiInstance = apiClass.getMethod("getAPI").invoke(null);
            Method createMethod = apiClass.getMethod("create", Key.class, String.class, ItemStack.class);
            Method registerMethod = apiClass.getMethod("register", Plugin.class, createMethod.getReturnType());

            for (String model : models) {
                try {
                    NamespacedKey modelKey = NamespacedKey.fromString(model.toLowerCase());
                    if (modelKey == null) {
                        plugin.getLogger().warning("Skipping invalid Axiom model key in config: " + model);
                        continue;
                    }

                    String namespace = modelKey.getNamespace();
                    String itemId = modelKey.getKey();

                    String displayName = formatDisplayName(itemId);
                    String formattedName = String.format(nameFormat, displayName);
                    Component nameComponent = MiniMessage.miniMessage().deserialize(formattedName);

                    ItemStack item = new ItemStack(Material.PAPER);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        try {
                            meta.setItemModel(modelKey);
                        } catch (LinkageError err) {
                            plugin.getLogger().warning("Custom item models are not supported on this server version (requires Paper 1.21.2+)");
                        }
                        
                        meta.displayName(nameComponent);
                        item.setItemMeta(meta);
                    }

                    Key axiomKey = Key.key("wake", namespace + "_" + itemId);
                    Object builder = createMethod.invoke(apiInstance, axiomKey, model.toLowerCase(), item);
                    registerMethod.invoke(apiInstance, plugin, builder);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to register Axiom model " + model + ": " + e.getMessage());
                    throw new RuntimeException("Failed to register Axiom model " + model, e);
                }
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            plugin.getLogger().severe("Failed to initialize Axiom API integration: " + e.getMessage());
            throw new RuntimeException("Failed to initialize Axiom API integration", e);
        }
    }

    @Override
    protected void onModuleDisable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("AxiomPaper")) return;
        try {
            Class<?> apiClass = Class.forName("com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI");
            Object apiInstance = apiClass.getMethod("getAPI").invoke(null);
            apiClass.getMethod("unregisterAll", Plugin.class).invoke(apiInstance, plugin);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to unregister Axiom displays: " + e.getMessage());
            throw new RuntimeException("Failed to unregister Axiom displays", e);
        }
    }

    @Override
    public void reload() {
        onModuleDisable();
        onModuleEnable();
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
                  .append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
