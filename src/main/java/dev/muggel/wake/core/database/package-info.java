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
 * 3. Register it with the module's {@code registerDao(...)} so reset covers it <br>
 * 4. Mirror a table with {@code mirror(table, loader)} and let the returned {@link dev.muggel.wake.core.database.CachedStore} carry every read and write of it <br>
 * 5. Write anything no store mirrors through {@code asyncUpdate(...)} <br>
 * For simple flags and settings use {@link dev.muggel.wake.core.database.StateDao} with keys prefixed {@code <module>.}
 *
 * <h2>Mirrored tables</h2>
 * A {@link dev.muggel.wake.core.database.CachedStore} is the contract for a mirrored table. <br>
 * {@code save} and {@code delete} update the cache and queue the statements together. <br>
 * Once the write queue drains, touched keys are published for another server to re-read. <br>
 * A loader is handed the keys to read, or {@code null} for the whole table, and lets its {@code SQLException} out rather than answering an empty result: an empty table is not an unreachable one. <br>
 * The store turns that into the failed read it leaves the cache alone for, so no DAO has to remember the convention. <br>
 * A key that was asked for and does not come back has been deleted. <br>
 * An announcement that lands while a read is already in flight is not covered by that read, so the key stays dirty and is read again rather than lost. <br>
 *
 * <h2>Reloads</h2>
 * A cache reload (module {@code reload()}, cross-server sync) should never block the main thread. <br>
 * {@code CachedStore.reloadAsync(afterApply)} reads back only the keys this server was told about and merges them on the main thread. <br>
 * {@code afterApply} is handed the keys that actually moved (empty when found nothing new) so a dependent rebuilds only what a change reaches rather than everything it owns on every announcement in its scope. <br>
 * For a read that is not a mirrored table, use {@code DatabaseManager.readAsync(read, applyOnMain)}. It drains queued writes, runs the read off-thread, and applies the result on the main thread. <br>
 *
 * <h2>Resilience</h2>
 * When the database is unreachable, {@link dev.muggel.wake.core.database.DatabaseManager} journals writes to disk and replays them on recovery. <br>
 * A write the disk will not take either is disowned rather than left in the cache, and a replay announces a full resync because nothing told the other servers about the rows it just landed. <br>
 * After writes, dirty scopes are published for cross-server cache invalidation (see {@code core/sync}). <br>
 * DAOs get all of this just by using {@code asyncUpdate(...)} or a mirrored store.
 */
package dev.muggel.wake.core.database;