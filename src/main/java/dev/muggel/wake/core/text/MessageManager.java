package dev.muggel.wake.core.text;

import dev.muggel.wake.Wake;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.intellij.lang.annotations.Subst;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves message keys from the configured language file into components and sends them. <br>
 * Understands MiniMessage, Wake's semantic color tags ({@code <primary>}, {@code <danger>}, ...) and the {@code templates} section. <br>
 * {@code $primary}, {@code $secondary} etc. expand to the same colors hex values, for tags that take a color argument. <br>
 * The palette is the file's {@code colors} section. A color it leaves out or spells wrong falls back to {@link WakeColors}. <br>
 * A name two of those claim is answered by the nearest: a caller's placeholder, then a template, then the palette, then MiniMessage itself. <br>
 * See the package documentation for the text rules.
 */
public class MessageManager {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final String COLOR_KEY = "colors.";
    private static final String TEMPLATE_KEY = "templates.";
    private static final String TEMPLATE_TEXT = "<text>";
    private static final Pattern PALETTE_VARIABLE = Pattern.compile("\\$([a-z_]+)");
    private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern OPENING_TAG = Pattern.compile("<([a-z][a-z0-9_]*)[:>]");
    private final Wake plugin;
    private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();
    private volatile Language language;
    public MessageManager(@NonNull Wake plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String defaultResource = langResource(DEFAULT_LANGUAGE);
        if (!new File(plugin.getDataFolder(), defaultResource).exists()) {
            plugin.saveResource(defaultResource, false);
        }
        Map<String, String> entries = new HashMap<>();
        try (InputStream bundled = plugin.getResource(defaultResource)) {
            if (bundled != null) {
                collect(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)), entries);
            }
        } catch (IOException ignored) {
        }
        String configured = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        File langFile = new File(plugin.getDataFolder(), langResource(configured));
        if (langFile.exists()) {
            if (collect(YamlConfiguration.loadConfiguration(langFile), entries) == 0) {
                plugin.getLogger().warning("Language " + langResource(configured) + " carried no messages, using bundled " + DEFAULT_LANGUAGE);
            }
        } else if (!DEFAULT_LANGUAGE.equals(configured)) {
            plugin.getLogger().warning("Language " + langResource(configured) + " not found, using bundled " + DEFAULT_LANGUAGE);
        }
        Map<WakeColors, TextColor> palette = palette(entries);
        MiniMessage miniMessage = miniMessage(palette);
        Map<String, String> templates = templates(entries, palette);
        warnedMissingKeys.clear();
        this.language = new Language(templates, shared(templates), miniMessage);
    }

    private @NonNull TagResolver shared(@NonNull Map<String, String> templates) {
        TagResolver.Builder builder = TagResolver.builder();
        templates.forEach((key, raw) -> {
            if (!key.startsWith(TEMPLATE_KEY)) {
                return;
            }
            @Subst("row") String name = key.substring(TEMPLATE_KEY.length());
            if (!TEMPLATE_NAME.matcher(name).matches()) {
                plugin.getLogger().warning("Skipping template '" + name + "': a template has to match " + TEMPLATE_NAME.pattern());
                return;
            }
            if (taken(name)) {
                plugin.getLogger().warning("Skipping template '" + name + "': that name is already a color or MiniMessage tag");
                return;
            }
            if (leansOnItself(templates, name, name, new HashSet<>())) {
                plugin.getLogger().warning("Skipping template '" + name + "': it leads back to itself and would loop indefinitely");
                return;
            }
            builder.tag(name, (args, ctx) -> Tag.preProcessParsed(
                    raw.replace(TEMPLATE_TEXT, args.hasNext() ? args.pop().value() : "")));
        });
        return builder.build();
    }

    private static boolean taken(@NonNull String name) {
        for (WakeColors color : WakeColors.values()) {
            if (color.tag().equals(name)) {
                return true;
            }
        }
        return StandardTags.defaults().has(name);
    }

    private static boolean leansOnItself(@NonNull Map<String, String> templates, @NonNull String start, @NonNull String name, @NonNull Set<String> seen) {
        if (!seen.add(name) || taken(name)) {
            return false;
        }
        String raw = templates.get(TEMPLATE_KEY + name);
        if (raw == null) {
            return false;
        }
        for (Matcher match = OPENING_TAG.matcher(raw); match.find(); ) {
            String used = match.group(1);
            if (used.equals(start) || leansOnItself(templates, start, used, seen)) {
                return true;
            }
        }
        return false;
    }

    private static int collect(@NonNull YamlConfiguration source, @NonNull Map<String, String> into) {
        int taken = 0;
        for (String key : source.getKeys(true)) {
            if (source.get(key) instanceof String entry) {
                into.put(key, entry);
                taken++;
            }
        }
        return taken;
    }

    private @NonNull Map<WakeColors, TextColor> palette(@NonNull Map<String, String> entries) {
        Map<WakeColors, TextColor> palette = new EnumMap<>(WakeColors.class);
        for (WakeColors color : WakeColors.values()) {
            String spelled = entries.get(COLOR_KEY + color.tag());
            TextColor parsed = spelled == null ? null : TextColor.fromCSSHexString(spelled.trim());
            if (spelled != null && parsed == null) {
                plugin.getLogger().warning("Malformed color '" + COLOR_KEY + color.tag() + "': " + spelled + ", using " + color.color().asHexString());
            }
            palette.put(color, parsed == null ? color.color() : parsed);
        }
        return palette;
    }

    private static @NonNull MiniMessage miniMessage(@NonNull Map<WakeColors, TextColor> palette) {
        TagResolver.Builder wake = TagResolver.builder();
        palette.forEach((color, value) -> wake.tag(color.tag(), Tag.styling(value)));
        if (!shadowSupported()) {
            wake.tag(Set.of("shadow", "!shadow"), (args, ctx) -> Tag.styling());
        }
        return MiniMessage.builder().tags(TagResolver.resolver(StandardTags.defaults(), wake.build())).build();
    }

    private static @NonNull Map<String, String> templates(@NonNull Map<String, String> entries, @NonNull Map<WakeColors, TextColor> palette) {
        Map<String, String> variables = new HashMap<>();
        palette.forEach((color, value) -> variables.put(color.tag(), value.asHexString()));
        Map<String, String> templates = new HashMap<>();
        entries.forEach((key, entry) -> {
            if (!key.startsWith(COLOR_KEY)) {
                templates.put(key, expand(entry, variables));
            }
        });
        return templates;
    }

    private static @NonNull String langResource(@NonNull String language) {
        return "lang/" + language + ".yml";
    }

    public @NonNull Component getComponent(@NonNull String key, TagResolver... resolvers) {
        Language loaded = this.language;
        String template = loaded.templates().get(key);
        if (template == null) {
            if (warnedMissingKeys.add(key)) {
                plugin.getLogger().warning("Missing message key: " + key);
            }
            return Component.text("<" + key + ">");
        }
        return render(loaded.miniMessage(), key, template, TagResolver.resolver(loaded.shared(), TagResolver.resolver(resolvers)));
    }

    public void send(@NonNull CommandSender sender, @NonNull String key, TagResolver... resolvers) {
        sender.sendMessage(getComponent(key, resolvers));
    }

    private @NonNull Component render(@NonNull MiniMessage miniMessage, @NonNull String key, @NonNull String template, TagResolver... resolvers) {
        try {
            return miniMessage.deserialize(template, resolvers);
        } catch (ParsingException e) {
            plugin.getLogger().warning("Malformed message template '" + key + "': " + e.getMessage());
            return Component.text(template);
        }
    }

    private static @NonNull String expand(@NonNull String raw, @NonNull Map<String, String> variables) {
        return PALETTE_VARIABLE.matcher(raw).replaceAll(match -> {
            String hex = variables.get(match.group(1));
            return hex != null ? hex : Matcher.quoteReplacement(match.group());
        });
    }

    private static boolean shadowSupported() {
        try {
            Class.forName("net.kyori.adventure.text.format.ShadowColor");
            return true; // 1.21.4+
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private record Language(Map<String, String> templates, TagResolver shared, MiniMessage miniMessage) {
        Language {
            templates = Map.copyOf(templates);
        }
    }
}