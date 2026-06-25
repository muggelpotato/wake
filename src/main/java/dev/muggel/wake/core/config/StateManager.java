package dev.muggel.wake.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.muggel.wake.Wake;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public class StateManager {
    private final Wake plugin;
    private final File file;
    private final Gson gson;
    private Map<String, Object> state = new HashMap<>();

    public StateManager(@NonNull Wake plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "state.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public void load() {
        if (!file.exists()) {
            state = new HashMap<>();
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> loaded = gson.fromJson(reader, Map.class);
            state = Objects.requireNonNullElseGet(loaded, HashMap::new);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load state.json", e);
            state = new HashMap<>();
        }
    }

    public void save() {
        createParentDirs();
        final Map<String, Object> stateCopy = new HashMap<>(state);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToFile(stateCopy));
    }

    public void saveSync() {
        createParentDirs();
        writeToFile(new HashMap<>(state));
    }

    private void createParentDirs() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create directory: " + parent.getAbsolutePath());
        }
    }

    private void writeToFile(Map<String, Object> stateCopy) {
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(stateCopy, writer);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save state.json", e);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object val = state.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (defaultValue instanceof Boolean && val instanceof Boolean) {
            return (T) val;
        }
        if (defaultValue instanceof Integer && val instanceof Number) {
            return (T) Integer.valueOf(((Number) val).intValue());
        }
        if (defaultValue instanceof Double && val instanceof Number) {
            return (T) Double.valueOf(((Number) val).doubleValue());
        }
        if (defaultValue instanceof Long && val instanceof Number) {
            return (T) Long.valueOf(((Number) val).longValue());
        }
        if (defaultValue instanceof String && val instanceof String) {
            return (T) val;
        }
        try {
            return (T) val;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    public void set(String key, Object value) {
        state.put(key, value);
        save();
    }
}
