package dev.muggel.wake.core.module;

import dev.muggel.wake.Wake;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import dev.muggel.wake.core.database.WakeDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * The base class modules extend. <br>
 * Register listeners, tasks, and DAOs through the {@code register...} helpers and cleanup on disable is automatic. <br>
 * Anything registered by hand must be undone in {@code onModuleDisable()}. <br>
 * Also provides the shared data plumbing: seeding bundled defaults when the store is empty, and the export/import/reset flow used by {@code /wake database}.
 */
public abstract class AbstractModule implements WakeModule {
    private final String id;
    private Wake plugin;
    private final List<Listener> bukkitListeners = new ArrayList<>();
    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<WakeDao> daos = new ArrayList<>();
    protected AbstractModule(String id) {
        this.id = id;
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final void onEnable(Wake plugin) {
        this.plugin = plugin;
        onModuleEnable();
    }

    @Override
    public final void onDisable() {
        try {
            onModuleDisable();
        } finally {
            for (BukkitTask task : tasks) {
                task.cancel();
            }
            tasks.clear();
            for (Listener listener : bukkitListeners) {
                HandlerList.unregisterAll(listener);
            }
            bukkitListeners.clear();
            for (PacketListenerCommon listener : packetListeners) {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            }
            packetListeners.clear();
            for (WakeDao dao : daos) {
                dao.releaseMirrors();
            }
            daos.clear();
        }
    }

    @Override
    public void reload() {}

    public final Wake getPlugin() {
        return plugin;
    }

    protected abstract void onModuleEnable();

    protected void onModuleDisable() {}

    protected final void registerListener(Listener listener) {
        if (plugin == null) {
            throw new IllegalStateException("Cannot register listener before module is enabled");
        }
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        bukkitListeners.add(listener);
    }

    protected final void registerPacketListener(PacketListenerCommon listener) {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        packetListeners.add(listener);
    }

    protected final void registerTask(BukkitTask task) {
        tasks.removeIf(BukkitTask::isCancelled);
        tasks.add(task);
    }

    protected final int seedData(String defaultFileName, String logNoun) throws SQLException, IOException {
        try (InputStream in = plugin.getResource(defaultFileName)) {
            if (in == null) {
                plugin.getLogger().warning("Default " + logNoun + " data file not found in jar: " + defaultFileName);
                return 0;
            }
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
            plugin.getDatabaseManager().awaitWrites();
            return onImportData(yaml);
        }
    }

    protected final void seedDataIfEmpty(@Nullable Boolean wasEmpty, String defaultFileName, String logNoun) {
        if (wasEmpty == null || !wasEmpty) return;
        try {
            int count = seedData(defaultFileName, logNoun);
            plugin.getLogger().info("Auto-seeded " + count + " " + logNoun + " items from jar");
        } catch (Exception e) {
            getPlugin().getLogger().log(Level.WARNING, "Failed to auto-seed " + logNoun + " data", e);
        }
    }

    @Override
    public final int exportData(File exportDir) throws SQLException, IOException {
        plugin.getDatabaseManager().awaitWrites();
        File outFile = new File(exportDir, getExportFileName());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        int count = onExportData(yaml);
        yaml.save(outFile);
        return count;
    }

    @Override
    public final int importData(File importDir) throws SQLException {
        File inFile = new File(importDir, getExportFileName());
        if (!inFile.exists()) return 0;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(inFile);
        plugin.getDatabaseManager().awaitWrites();
        int count = onImportData(yaml);
        publishSync();
        return count;
    }

    private void publishSync() {
        plugin.getDatabaseManager().publishScope(getId());
    }

    protected String getExportFileName() {
        return getId() + "_data.yml";
    }

    protected int onExportData(YamlConfiguration yaml) throws SQLException {
        return 0;
    }

    protected int onImportData(YamlConfiguration yaml) throws SQLException {
        return 0;
    }

    /** Writes every state value the module owns */
    protected final int exportState(YamlConfiguration yaml) {
        Map<String, Object> entries = plugin.getStateDao().snapshot(getId() + ".");
        entries.forEach(yaml::set);
        return entries.size();
    }

    /** Restores every {@code <id>.} key the file carries */
    protected final int importState(YamlConfiguration yaml) {
        String prefix = getId() + ".";
        int count = 0;
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key) || !key.startsWith(prefix)) {
                continue;
            }
            Object value = yaml.get(key);
            if (value == null) {
                continue;
            }
            try {
                plugin.getStateDao().importValue(key, value);
                count++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to import state " + key, e);
            }
        }
        return count;
    }

    protected final void registerDao(WakeDao dao) {
        daos.add(dao);
    }

    @Override
    public void resetDatabase() {
        for (WakeDao dao : daos) {
            dao.resetTables();
        }
        plugin.getStateDao().clearPrefix(getId() + ".");
        plugin.getDatabaseManager().awaitWrites();
        reload();
        publishSync();
    }

    @Override
    public int seedData() throws SQLException, IOException {
        String defaultFileName = getDefaultDataFileName();
        if (defaultFileName == null) return 0;
        return seedData(defaultFileName, getId());
    }

    protected String getDefaultDataFileName() {
        return "defaults/" + getId() + "_default.yml";
    }
}