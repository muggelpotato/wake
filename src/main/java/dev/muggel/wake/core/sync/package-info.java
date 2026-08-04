/**
 * Cross-server cache invalidation. <br>
 * One announcement per write batch over Valkey/Redis pub-sub, active only on a shared MariaDB. <br>
 * Wake runs identically with the bus off, misconfigured, or gone mid-session
 *
 * <h2>The mechanism</h2>
 * {@link dev.muggel.wake.core.sync.SyncService} is the whole surface. <br>
 * The database layer publishes through it ({@code publish} for a scope, {@code publishKeys} for the rows a write moved) and never touches the classes behind it. <br>
 * A payload is a scope, optionally followed by the mirrored table and the exact keys in it that changed. A scope on its own means the whole of it, which is what an oversized key set, a key holding the separator, and a journal replay all fall back to. <br>
 * Announcements are tagged with the sender, so a server ignores its own.
 *
 * <h2>What a peer does with one</h2>
 * The keys are marked stale on the stores that mirror that table, and the scopes announced since the last tick are reloaded together on the main thread. <br>
 * Repeat announcements of one scope collapse into one reload, and a module is only reloaded when a key it owns actually moved. <br>
 * Reading back is always safe: a peer re-reads its own database, never trusts a payload's contents.
 *
 * <h2>Resyncs</h2>
 * A full resync is the recovery for everything this package cannot deliver: <br>
 * A subscribe that follows a refused or lost connection (announcements were made while this server could not hear them) <br>
 * A publish that failed (peers were not told) <br>
 * A database that came back (rows moved under the cache while it was unreachable)
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Payloads arrive on Lettuce network threads and reloads happen on the main one.</li>
 *   <li>Lettuce lives here and nowhere else. A module that wants to hear about a change observes an event, it does not open a connection</li>
 *   <li>{@code state} and {@code full} are reserved scope names. Every other scope is a module id, and {@code ModuleManager} refuses a module that claims either</li>
 * </ul>
 */
package dev.muggel.wake.core.sync;