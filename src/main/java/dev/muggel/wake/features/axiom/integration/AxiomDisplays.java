package dev.muggel.wake.features.axiom.integration;

import dev.muggel.wake.Wake;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class AxiomDisplays {
    private static final String PLUGIN_NAME = "AxiomPaper";
    private static final String DISPLAY_API_CLASS = "com.moulberry.axiom.paperapi.AxiomCustomDisplayAPI";
    private static final Comparator<NamespacedKey> PICKER_ORDER = Comparator.comparing(NamespacedKey::getKey).thenComparing(NamespacedKey::getNamespace);
    private static final boolean ITEM_MODELS = itemModelsSupported();
    private final Wake plugin;
    private final Object api;
    private final Method createMethod;
    private final Method registerMethod;
    private final Method unregisterMethod;
    private Set<String> registered = Set.of();
    public AxiomDisplays(@NonNull Wake plugin) {
        this.plugin = plugin;
        try {
            Class<?> apiClass = Class.forName(DISPLAY_API_CLASS);
            this.api = apiClass.getMethod("getAPI").invoke(null);
            this.createMethod = apiClass.getMethod("create", Key.class, String.class, ItemStack.class);
            this.registerMethod = apiClass.getMethod("register", Plugin.class, createMethod.getReturnType());
            this.unregisterMethod = apiClass.getMethod("unregisterAll", Plugin.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AxiomPaper is installed but its display API is not the one this build integrates with", e);
        }
        if (!ITEM_MODELS) {
            plugin.getLogger().warning("Custom item models are not supported on this server version (requires Paper 1.21.2+)");
        }
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
    }

    public void apply(@NonNull Set<String> models) {
        if (models.equals(registered)) return;
        unregisterAll();
        for (NamespacedKey model : ordered(models)) {
            try {
                registerMethod.invoke(api, plugin, createMethod.invoke(api, displayId(model), model.toString(), item(model)));
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to register Axiom model " + model, e);
                return;
            }
        }
        registered = Set.copyOf(models);
    }

    public void unregisterAll() {
        registered = Set.of();
        try {
            unregisterMethod.invoke(api, plugin);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to unregister Axiom displays", e);
        }
    }

    private @NonNull List<NamespacedKey> ordered(@NonNull Set<String> models) {
        return models.stream().map(this::parse).filter(Objects::nonNull).distinct().sorted(PICKER_ORDER).toList();
    }

    private @Nullable NamespacedKey parse(@NonNull String model) {
        NamespacedKey key = model.isEmpty() ? null : NamespacedKey.fromString(model.toLowerCase(Locale.ROOT));
        if (key == null) {
            plugin.getLogger().warning("Skipping invalid Axiom model key: " + model);
        }
        return key;
    }

    private @NonNull ItemStack item(@NonNull NamespacedKey model) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> {
            if (ITEM_MODELS) {
                meta.setItemModel(model);
            }
            meta.displayName(plugin.getMessageManager().getComponent("axiom.display_name", Placeholder.unparsed("name", displayName(model.getKey()))));
        });
        return item;
    }

    @SuppressWarnings("PatternValidation")
    private static @NonNull Key displayId(@NonNull NamespacedKey model) {
        return Key.key("wake", model.getNamespace() + "/" + model.getKey());
    }

    private static @NonNull String displayName(@NonNull String itemId) {
        String name = Arrays.stream(itemId.split("[_/-]"))
                .filter(part -> !part.isEmpty())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? itemId : name;
    }

    private static boolean itemModelsSupported() {
        try {
            ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
            return true;
        } catch (NoSuchMethodException olderThanPaper1_21_2) {
            return false;
        }
    }
}