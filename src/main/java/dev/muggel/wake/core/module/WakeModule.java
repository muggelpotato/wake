package dev.muggel.wake.core.module;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

/**
 * The contract for every Wake module (enable, disable, reload, and the data hooks behind {@code /wake database}). <br>
 * Extend {@link AbstractModule} instead of implementing this directly. It provides automatic teardown and the shared data plumbing. <br>
 * See the package documentation for the module rules.
 */
public interface WakeModule {
    void onEnable(Wake plugin);

    void onDisable();

    /**
     * Refreshes this module's caches from the database. <br>
     * Runs on {@code /wake reload} and on cross-server sync. It can fire at any time, so it must never block the main thread on I/O. <br>
     * Read through {@code DatabaseManager.readAsync} and apply on the main thread.
     */
    void reload();

    String getId();

    default boolean isCompatible() {
        return true;
    }

    default @Nullable CommandNode buildCommands(Wake plugin) {
        return null;
    }

    int exportData(File exportDir) throws SQLException, IOException;

    int importData(File importDir) throws SQLException;

    void resetDatabase();

    int seedData() throws SQLException, IOException;
}