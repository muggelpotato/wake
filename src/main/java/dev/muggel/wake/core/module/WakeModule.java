package dev.muggel.wake.core.module;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.database.StateDao;
import dev.muggel.wake.core.database.WakeDao;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Every Wake module. <br>
 * Do the setup in {@link #onModuleEnable()} through {@code register...} helpers and teardown is automatic. <br>
 * Anything registered by hand must be undone in {@link #onModuleDisable()}. <br>
 * A module must survive disable &rarr; enable with no leaks and no duplicates. <br>
 * Also carries the data plumbing behind {@code /wake database} <br>
 * See the package documentation for the rules.
 */
public abstract class WakeModule {
    private static final int EXPORT_FORMAT = 1;
    protected final Wake plugin;
    protected final String statePrefix;
    private final String id;
    private final List<Listener> bukkitListeners = new ArrayList<>();
    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<WakeDao> daos = new ArrayList<>();
    private final SequencedMap<Class<?>, Object> services = new LinkedHashMap<>();
    private @Nullable Supplier<@Nullable Boolean> deferredSeed;
    protected WakeModule(@NonNull Wake plugin, @NonNull String id) {
        this.plugin = plugin;
        this.id = id;
        this.statePrefix = id + ".";
    }

    public final @NonNull String getId() {
        return id;
    }

    /** Override to make a module stay disabled when e.g. a third-party plugin it needs is missing */
    public boolean isCompatible() {
        return true;
    }

    public @Nullable CommandNode buildCommands() {
        return null;
    }

    protected abstract void onModuleEnable();

    protected void onModuleDisable() {}

    /** Refreshes this module's caches from the database (on {@code /wake reload} and cross-server sync) */
    public void reload() {}

    final void enable() {
        try {
            onModuleEnable();
        } catch (Throwable failed) {
            try {
                disable();
            } catch (Throwable reversalFailed) {
                failed.addSuppressed(reversalFailed);
            }
            throw failed;
        }
    }

    final void disable() {
        try {
            onModuleDisable();
        } finally {
            deferredSeed = null;
            services.reversed().forEach(plugin.getServiceRegistry()::unregister);
            services.clear();
            for (BukkitTask task : tasks.reversed()) {
                task.cancel();
            }
            tasks.clear();
            for (Listener listener : bukkitListeners.reversed()) {
                HandlerList.unregisterAll(listener);
            }
            bukkitListeners.clear();
            for (PacketListenerCommon listener : packetListeners.reversed()) {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            }
            packetListeners.clear();
            for (WakeDao dao : daos.reversed()) {
                dao.releaseMirrors();
            }
            daos.clear();
        }
    }

    protected final void registerListener(@NonNull Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        bukkitListeners.add(listener);
    }

    protected final void registerPacketListener(@NonNull PacketListenerCommon listener) {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        packetListeners.add(listener);
    }

    protected final void registerTask(@NonNull BukkitTask task) {
        tasks.removeIf(BukkitTask::isCancelled);
        tasks.add(task);
    }

    protected final <T extends WakeDao> @NonNull T registerDao(@NonNull T dao) {
        daos.add(dao);
        return dao;
    }

    /** Publishes an {@code api/} service for other modules to resolve */
    @SuppressWarnings("SameParameterValue")
    protected final <T> void registerService(@NonNull Class<T> type, @NonNull T service) {
        plugin.getServiceRegistry().register(type, service);
        services.put(type, service);
    }

    public final int seedData() throws SQLException, IOException {
        String resource = "defaults/" + id + "_default.yml";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                plugin.getLogger().warning("Default data file not found in jar: " + resource);
                return 0;
            }
            return apply(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    protected final void seedDataIfEmpty(@NonNull Supplier<@Nullable Boolean> storeIsEmpty) {
        Boolean empty = storeIsEmpty.get();
        deferredSeed = empty == null ? storeIsEmpty : null;
        if (!Boolean.TRUE.equals(empty)) return;
        try {
            plugin.getLogger().info("Auto-seeded " + seedData() + " " + id + " records from jar");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to auto-seed " + id + " data", e);
        }
    }

    final void seedIfDeferred() {
        Supplier<@Nullable Boolean> deferred = deferredSeed;
        if (deferred != null) {
            seedDataIfEmpty(deferred);
        }
    }

    public final int exportData(@NonNull File exportDir) throws SQLException, IOException {
        plugin.getDatabaseManager().awaitWrites();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", EXPORT_FORMAT);
        int count = onExportData(yaml);
        File target = dataFile(exportDir);
        Path staged = target.toPath().resolveSibling(target.getName() + ".tmp");
        yaml.save(staged.toFile());
        Files.move(staged, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return count;
    }

    public final int importData(@NonNull File importDir) throws SQLException, IOException {
        File inFile = dataFile(importDir);
        if (!inFile.exists()) return 0;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(inFile);
        int format = yaml.getInt("version", 1);
        if (format > EXPORT_FORMAT) {
            throw new IOException(inFile.getName() + " is export format v" + format + " but this build reads v" + EXPORT_FORMAT + ": update the Wake jar on this server");
        }
        return apply(yaml);
    }

    private int apply(@NonNull YamlConfiguration yaml) throws SQLException {
        plugin.getDatabaseManager().awaitWrites();
        int count = onImportData(yaml);
        plugin.getDatabaseManager().publishScope(id);
        return count;
    }

    public final void resetDatabase() {
        for (WakeDao dao : daos) {
            dao.resetTables();
        }
        plugin.getStateDao().clearPrefix(statePrefix);
        plugin.getDatabaseManager().awaitWrites();
        reload();
        plugin.getDatabaseManager().publishScope(id);
    }

    protected int onExportData(@NonNull YamlConfiguration yaml) throws SQLException {
        return 0;
    }

    protected int onImportData(@NonNull YamlConfiguration yaml) throws SQLException {
        return 0;
    }

    protected final int exportState(@NonNull YamlConfiguration yaml) throws SQLException {
        StateDao stateDao = plugin.getStateDao();
        if (!stateDao.isLoaded()) {
            throw new SQLException("Module state could not be read");
        }
        Map<String, Object> entries = stateDao.snapshot(statePrefix);
        entries.forEach(yaml::set);
        return entries.size();
    }

    protected final int importState(@NonNull YamlConfiguration yaml) throws SQLException {
        StateDao stateDao = plugin.getStateDao();
        int count = 0;
        for (String key : yaml.getKeys(true)) {
            if (!key.startsWith(statePrefix) || yaml.isConfigurationSection(key)) {
                continue;
            }
            Object value = yaml.get(key);
            if (value == null) {
                continue;
            }
            stateDao.importValue(key, value);
            count++;
        }
        return count;
    }

    private @NonNull File dataFile(@NonNull File dir) {
        return new File(dir, id + "_data.yml");
    }
}