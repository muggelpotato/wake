/**
 * The persistence layer. <br>
 * All runtime state and module data lives here, never in files ({@code config.yml} is boot/admin-only).
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>Reads are served from an in-memory cache. Writes update the cache immediately and reach the database asynchronously on a single writer thread</li>
 *   <li>Feature code never issues SQL. Each module owns a DAO extending {@link dev.muggel.wake.core.database.WakeDao}</li>
 *   <li>SQL is always parameterized ({@code ?}) and portable across SQLite and MariaDB</li>
 * </ul>
 *
 * <h2>Writing a DAO</h2>
 * 1. Extend {@link dev.muggel.wake.core.database.WakeDao} <br>
 * 2. Declare tables in {@code getTableSchemas()} <br>
 * 3. Cache rows in fields keyed by simple values (UUID, String) <br>
 * 4. Write through {@code asyncUpdate(...)} <br>
 * 5. Register it with the module's {@code registerDao(...)} so reset covers it <br>
 * For simple flags and settings use {@link dev.muggel.wake.core.database.StateDao} with keys prefixed {@code <module>.}
 *
 * <h2>Reloads</h2>
 * A cache reload (module {@code reload()}, cross-server sync) should never block the main thread. <br>
 * Use {@code DatabaseManager.readAsync(read, applyOnMain)}. It drains queued writes, runs the read off-thread, and applies the result on the main thread. <br>
 * Boot-time initial loads stay synchronous.
 *
 * <h2>Resilience</h2>
 * When the database is unreachable, {@link dev.muggel.wake.core.database.DatabaseManager} journals writes to disk and replays them on recovery. <br>
 * After writes, dirty scopes are published for cross-server cache invalidation (see {@code core/sync}). <br>
 * DAOs get all of this just by using {@code asyncUpdate(...)}.
 */
package dev.muggel.wake.core.database;