/**
 * The module system. Every feature in Wake is a module. <br>
 * An admin can switch it off in {@code config.yml}, and a developer can delete it without unpicking the rest.
 *
 * <h2>Writing a module</h2>
 * 1. Extend {@link dev.muggel.wake.core.module.WakeModule} and add it to the list {@code Wake} hands {@link dev.muggel.wake.core.module.ModuleManager} <br>
 * 2. Do all setup in {@code onModuleEnable()}, and register everything through the helpers ({@code registerListener}, {@code registerPacketListener}, {@code registerTask}, {@code registerDao}, {@code registerService}) so teardown on disable is automatic <br>
 * 3. Anything registered by hand must be undone in {@code onModuleDisable()} <br>
 * A module must survive disable &rarr; enable with no leaks and no duplicates. An enable that throws part-way is reversed as if it had never run, its failure never reaches the modules after it, and the next {@code /wake reload} tries again.
 *
 * <h2>Talking to other modules</h2>
 * Modules never reach into each other's internals. Two channels only:
 * <ul>
 *   <li><b>Services:</b> a module publishes a small interface from its {@code api/} package with {@code registerService(...)}. Consumers resolve it through the {@link dev.muggel.wake.core.module.ServiceRegistry} on every use and no-op when it is absent (never cache the reference)</li>
 *   <li><b>Events:</b> fire and observe events for actions</li>
 * </ul>
 * {@code getModule(...)} only ever answers with a module that is running, so a consumer cannot reach one that is switched off.
 *
 * <h2>Lifecycle</h2>
 * {@link dev.muggel.wake.core.module.ModuleManager} compares {@code config.yml} with what is running and enables, disables, or reloads each module to match (at boot and on {@code /wake reload}). <br>
 * Override {@code isCompatible()} to keep a module off when a required third-party plugin is missing (reach such plugins via reflection, don't hard import). <br>
 * The set of modules is fixed when the manager is built, because the command tree is declared from it once and never again.
 *
 * <h2>Data</h2>
 * Module data lives in the database (see {@code core/database}).
 * {@code seedDataIfEmpty(...)} applies bundled defaults when the store was read and found empty (the export/import/reset hooks back {@code /wake database}). <br>
 * {@code exportState}/{@code importState} sweep the module's state prefix, so a setting added later is carried without naming it anywhere. <br>
 * A seed, an import and a reset all announce the module's scope, because another server may share the database they write to.
 */
package dev.muggel.wake.core.module;