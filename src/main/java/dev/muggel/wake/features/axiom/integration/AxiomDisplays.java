package dev.muggel.wake.features.axiom.integration;

import dev.muggel.wake.Wake;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class AxiomDisplays {
    private static final String PLUGIN_NAME = "AxiomPaper";
    private static final String DISPLAY_API_CLASS = "com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI";
    private final Wake plugin;
    public AxiomDisplays(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
    }

    @SuppressWarnings("PatternValidation")
    public void register(@NonNull Set<String> models) {
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
                        plugin.getLogger().warning("Skipping invalid Axiom model key: " + model);
                        continue;
                    }
                    String namespace = modelKey.getNamespace();
                    String itemId = modelKey.getKey();
                    String displayName = formatDisplayName(itemId);
                    Component nameComponent = plugin.getMessageManager().getComponent("axiom.display_name", Placeholder.unparsed("name", displayName));
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
                    Object builder = createMethod.invoke(apiInstance, axiomKey, model.toLowerCase(Locale.ROOT), item);
                    registerMethod.invoke(apiInstance, plugin, builder);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to register Axiom model " + model, e);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Axiom API integration", e);
        }
    }

    public void unregisterAll() {
        if (!isAvailable()) return;
        try {
            Class<?> apiClass = Class.forName(DISPLAY_API_CLASS);
            Object apiInstance = apiClass.getMethod("getAPI").invoke(null);
            apiClass.getMethod("unregisterAll", Plugin.class).invoke(apiInstance, plugin);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to unregister Axiom displays", e);
        }
    }

    private static @NonNull String formatDisplayName(@NonNull String itemId) {
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
}