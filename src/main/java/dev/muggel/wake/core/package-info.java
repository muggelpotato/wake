/**
 * The framework every Wake module is built on. <br>
 *
 * <h2>The sub-packages</h2>
 * Each carries its own {@code package-info.java} with the rules for writing there: <br>
 * <ul>
 *   <li>{@code commands} for anything a player types </li>
 *   <li>{@code text} for anything a player reads </li>
 *   <li>{@code database} for anything that outlives the session </li>
 *   <li>{@code module} for the lifecycle </li>
 *   <li>{@code sync} for the server next door </li>
 * </ul>
 * A package is named for a subject two or more files share. Put anything else loosely in {@link dev.muggel.wake.core}
 */
package dev.muggel.wake.core;