package dev.muggel.wake.core.text;

import dev.muggel.wake.Wake;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves message keys from the configured language file into components and sends them. <br>
 * Understands MiniMessage, Wake's semantic color tags ({@code <primary>}, {@code <danger>}, ...) and the {@code <prefix>} placeholder. <br>
 * {@code $primary}, {@code $secondary} etc. expand to the {@link WakeColors} hex values for tags that take a color argument. <br>
 * A name two of those claim is answered by the nearest: a caller's placeholder, then {@code <prefix>}, then the palette, then MiniMessage itself. <br>
 * See the package documentation for the text rules.
 */
public class MessageManager {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Pattern PALETTE_VARIABLE = Pattern.compile("\\$([a-z_]+)");
    private static final Map<String, String> PALETTE = Arrays.stream(WakeColors.values())
            .collect(Collectors.toUnmodifiableMap(WakeColors::tag, color -> color.color().asHexString()));
    private final Wake plugin;
    private final MiniMessage miniMessage;
    private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();
    private volatile Language language;
    public MessageManager(@NonNull Wake plugin) {
        this.plugin = plugin;
        TagResolver.Builder wake = TagResolver.builder();
        for (WakeColors color : WakeColors.values()) {
            wake.tag(color.tag(), Tag.styling(color.color()));
        }
        if (!shadowSupported()) {
            wake.tag(Set.of("shadow", "!shadow"), (args, ctx) -> Tag.styling());
        }
        this.miniMessage = MiniMessage.builder()
                .tags(TagResolver.resolver(StandardTags.defaults(), wake.build()))
                .build();
        reload();
    }

    public void reload() {
        String defaultResource = langResource(DEFAULT_LANGUAGE);
        if (!new File(plugin.getDataFolder(), defaultResource).exists()) {
            plugin.saveResource(defaultResource, false);
        }
        Map<String, String> templates = new HashMap<>();
        try (InputStream bundled = plugin.getResource(defaultResource)) {
            if (bundled != null) {
                collect(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)), templates);
            }
        } catch (IOException ignored) {
        }
        String configured = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        File langFile = new File(plugin.getDataFolder(), langResource(configured));
        if (langFile.exists()) {
            if (collect(YamlConfiguration.loadConfiguration(langFile), templates) == 0) {
                plugin.getLogger().warning("Language " + langResource(configured) + " carried no messages, using bundled " + DEFAULT_LANGUAGE);
            }
        } else if (!DEFAULT_LANGUAGE.equals(configured)) {
            plugin.getLogger().warning("Language " + langResource(configured) + " not found, using bundled " + DEFAULT_LANGUAGE);
        }
        warnedMissingKeys.clear();
        this.language = new Language(templates, Placeholder.component("prefix", render("prefix", templates.getOrDefault("prefix", ""))));
    }

    private static int collect(@NonNull YamlConfiguration source, @NonNull Map<String, String> into) {
        int taken = 0;
        for (String key : source.getKeys(true)) {
            if (source.get(key) instanceof String template) {
                into.put(key, preprocess(template));
                taken++;
            }
        }
        return taken;
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
        return render(key, template, TagResolver.resolver(loaded.prefix(), TagResolver.resolver(resolvers)));
    }

    public void send(@NonNull CommandSender sender, @NonNull String key, TagResolver... resolvers) {
        sender.sendMessage(getComponent(key, resolvers));
    }

    private @NonNull Component render(@NonNull String key, @NonNull String template, TagResolver... resolvers) {
        try {
            return miniMessage.deserialize(template, resolvers);
        } catch (ParsingException e) {
            plugin.getLogger().warning("Malformed message template '" + key + "': " + e.getMessage());
            return Component.text(template);
        }
    }

    private static @NonNull String preprocess(@NonNull String raw) {
        return PALETTE_VARIABLE.matcher(raw).replaceAll(match -> {
            String hex = PALETTE.get(match.group(1));
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

    private record Language(Map<String, String> templates, TagResolver prefix) {
        Language {
            templates = Map.copyOf(templates);
        }
    }
}