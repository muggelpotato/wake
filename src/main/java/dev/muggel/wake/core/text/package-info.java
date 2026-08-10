/**
 * Player-facing text. <br>
 * Every string a player sees comes from the language file ({@code lang/en_us.yml}) through {@link dev.muggel.wake.core.text.MessageManager}. <br>
 * Never hardcode text or build chat components ad hoc.
 *
 * <ul>
 *   <li>Send with {@code plugin.getMessageManager().send(sender, "your.key", resolvers)}</li>
 *   <li>In the language file, use the semantic color tags from {@link dev.muggel.wake.core.text.WakeColors} ({@code <primary>}, {@code <danger>}, ...)</li>
 *   <li>Insert every value with {@code Placeholder.unparsed(...)} so none of them can inject markup</li>
 *   <li>{@code Placeholder.parsed(...)} only where the placeholder sits <i>inside a tag argument</i> ({@code <click:run_command:'... <setting>'>}), which a component cannot fill. Anywhere else it is the same text with the guard taken off</li>
 *   <li>Never name a placeholder after a color tag or a MiniMessage tag. A placeholder answers first, so it would silently take that name over</li>
 * </ul>
 */
package dev.muggel.wake.core.text;