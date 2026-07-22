/**
 * Wake's command framework and the rules for writing commands.
 *
 * <h2>How it works</h2>
 * A command is a tree of {@link dev.muggel.wake.core.commands.CommandNode}s: <br>
 * {@code literal(...)} for fixed words, {@code argument(...)} for typed values. <br>
 * Chain positional arguments with {@code arguments(...)}; use {@code addSubcommand(...)} only for real branches. <br>
 * A module returns its root node from {@code buildCommands(Wake)} and the framework does the rest. <br>
 * Declare {@code .withModule(...)} once, on the root (every child inherits it). <br>
 * Re-declare only on a node that belongs to a different module than its parent.
 *
 * <h2>What is automatic</h2>
 * <ul>
 *   <li><b>Permissions.</b> Each literal becomes a permission node {@code wake.<module>.commands.<literal>...}. Arguments don't extend the path. Never declare permissions in {@code plugin.yml} or write permission strings by hand</li>
 *   <li><b>Module gating:</b> A command whose module is disabled is hidden and blocked with a localized message before the executor runs. Command bodies never re-check module presence</li>
 *   <li><b>Errors.</b> Exceptions from executors are caught, logged, and reported to the sender</li>
 * </ul>
 *
 * <h2>One class per command</h2>
 * Every command node is one class with {@code static CommandNode getNode(Wake plugin)}. <br>
 * {@code getNode} declares only the tree. Logic lives in private {@code execute(...)} methods, non-trivial suggesters in private {@code suggest...(...)} methods. <br>
 * A group parent composes its children by calling their {@code getNode}. <br>
 *
 * <h2>Where files go</h2>
 * <ul>
 *   <li><b>Standalone command</b> &rarr; one flat class in {@code <module>/commands/} (e.g. {@code ReloadCommand})</li>
 *   <li><b>Command group</b> (several substantial sub-commands) &rarr; its own package {@code <module>/commands/<group>/} with a {@code <Group>Command} parent and one {@code <Group><Action>Command} per sub-command</li>
 *   <li><b>Argument types</b> &rarr; {@code <module>/commands/arguments/}, named {@code <Thing>ArgumentType}</li>
 * </ul>
 *
 * <h2>Helpers ({@code ...CommandHelper})</h2>
 * <ul>
 *   <li>{@link dev.muggel.wake.core.commands.CommandHelper} wake's core cross-module helper</li>
 *   <li>{@code <Module>CommandHelper} public, in {@code <module>/commands/}: shared by every command of one module (e.g. {@code DrydockCommandHelper})</li>
 *   <li>{@code <Group>CommandHelper} package-private, inside the group package: shared only within that group (e.g. {@code SandboxCommandHelper})</li>
 * </ul>
 * A helper exists only when there is real shared logic.
 *
 * <h2>Argument types</h2>
 * Prefer Brigadier/Paper built-ins (bool, integer, player, ...). <br>
 * For Wake's domain values use the core types in {@code core/commands/arguments/}. <br>
 * Write a module-local type only for module-owned data (e.g. drydock's {@code BoostpadKeyArgumentType}). <br>
 * A good argument type validates at parse time with a localized error and suggests as you type, so executors never re-validate.
 *
 * <h2>Failure and text</h2>
 * Report failure to the sender as a localized message key, never an exception. <br>
 * All player-facing text follows the rules in {@code core/text}.
 */
package dev.muggel.wake.core.commands;