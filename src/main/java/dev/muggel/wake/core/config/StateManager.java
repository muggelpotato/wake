package dev.muggel.wake.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.muggel.wake.Wake;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class StateManager {
    private final Wake plugin;
    private final File file;
    private final Gson gson;
    private Map<String, Object> state = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wake-state-saver");
        thread.setDaemon(true);
        return thread;
    });
    private final Object writeLock = new Object();

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
        executor.submit(() -> writeToFile(stateCopy));
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
        synchronized (writeLock) {
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(stateCopy, writer);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save state.json", e);
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
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
        if (defaultValue instanceof List && val instanceof List) {
            return (T) val;
        }
        if (defaultValue instanceof Map && val instanceof Map) {
            return (T) val;
        }
        if (defaultValue != null && !defaultValue.getClass().isInstance(val)) {
            return defaultValue;
        }
        return (T) val;
    }

    public void set(String key, Object value) {
        state.put(key, value);
        save();
    }
}
