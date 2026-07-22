/**
 * The module system. Every feature in Wake is a module. <br>
 * An admin can switch it off in {@code config.yml}, and a developer can delete it without unpicking the rest.
 *
 * <h2>Writing a module</h2>
 * 1. Extend {@link dev.muggel.wake.core.module.AbstractModule} and register it in {@code Wake} <br>
 * 2. Do all setup in {@code onModuleEnable()}, and register everything through the helpers ({@code registerListener}, {@code registerPacketListener}, {@code registerDao}) so teardown on disable is automatic <br>
 * 3. Anything registered by hand must be undone in {@code onModuleDisable()} <br>
 * A module must survive disable &rarr; enable with no leaks and no duplicates.
 *
 * <h2>Talking to other modules</h2>
 * Modules never reach into each other's internals. Two channels only:
 * <ul>
 *   <li><b>Services:</b> a module publishes a small interface from its {@code api/} package in the {@code ServiceRegistry} on enable and unregisters it on disable. Consumers resolve it on every use and no-op when it is absent (never cache the reference)</li>
 *   <li><b>Events:</b> fire and observe events for actions</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * {@link dev.muggel.wake.core.module.ModuleManager} compares {@code config.yml} with what is running and enables, disables, or reloads each module to match (at boot and on {@code /wake reload}). <br>
 * Override {@code isCompatible()} to keep a module off when a required third-party plugin is missing (reach such plugins via reflection, don't hard import).
 *
 * <h2>Data</h2>
 * Module data lives in the database (see {@code core/database}).
 * {@code seedDataIfEmpty(...)} applies bundled defaults when the store is empty (the export/import/reset hooks back {@code /wake database}).
 */
package dev.muggel.wake.core.module;