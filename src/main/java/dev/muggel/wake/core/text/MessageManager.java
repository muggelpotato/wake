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
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Resolves message keys from the configured language file into components and sends them. <br>
 * Understands MiniMessage, Wake's semantic color tags ({@code <primary>}, {@code <danger>}, ...) and the {@code <prefix>} placeholder
 * {@code $primary}, {@code $secondary} etc. expand to the {@link WakeColors} hex values. <br>
 * See the package documentation for the text rules.
 */
public class MessageManager {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final String DEFAULT_PREFIX = "<gray>[<blue>Wake</blue>] </gray>";
    private static final Pattern SHADOW_TAG = Pattern.compile("</?shadow(:[^>]*)?>");
    private final Wake plugin;
    private final MiniMessage miniMessage;
    private final boolean shadowSupported;
    private final List<ColorVariable> colorVariables;
    private final Set<String> warnedMissingKeys = ConcurrentHashMap.newKeySet();
    private volatile YamlConfiguration config;
    private volatile TagResolver prefixResolver;
    public MessageManager(Wake plugin) {
        this.plugin = plugin;
        this.colorVariables = buildColorVariables();
        this.shadowSupported = detectShadowSupport();
        TagResolver.Builder colors = TagResolver.builder();
        for (WakeColors color : WakeColors.values()) {
            colors.tag(color.tag(), Tag.styling(color.color()));
        }
        this.miniMessage = MiniMessage.builder()
                .tags(TagResolver.resolver(StandardTags.defaults(), colors.build()))
                .build();
        reload();
    }

    public void reload() {
        String defaultResource = "lang/" + DEFAULT_LANGUAGE + ".yml";
        File defaultFile = new File(plugin.getDataFolder(), defaultResource);
        if (!defaultFile.exists()) {
            try {
                plugin.saveResource(defaultResource, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Bundled " + defaultResource + " is missing from the jar");
            }
        }
        String language = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        File langFile = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!langFile.exists() && !language.equals(DEFAULT_LANGUAGE)) {
            plugin.getLogger().warning("Language lang/" + language + ".yml not found, using bundled " + DEFAULT_LANGUAGE);
        }
        YamlConfiguration loaded = langFile.exists()
                ? YamlConfiguration.loadConfiguration(langFile)
                : new YamlConfiguration();
        try (InputStream bundled = plugin.getResource(defaultResource)) {
            if (bundled != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
        }
        this.config = loaded;
        this.prefixResolver = Placeholder.component("prefix", deserialize(loaded.getString("prefix", DEFAULT_PREFIX)));
    }

    public boolean hasKey(String key) {
        return config.getString(key) != null;
    }

    public Component getComponent(String key, TagResolver... resolvers) {
        String raw = config.getString(key);
        if (raw == null) {
            if (warnedMissingKeys.add(key)) {
                plugin.getLogger().warning("Missing message key: " + key);
            }
            return Component.text("<" + key + ">");
        }
        return deserialize(raw, TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers)));
    }

    public void send(@NonNull CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(getComponent(key, resolvers));
    }

    /** Expands {@code $palette} variables, strips unsupported {@code <shadow>} tags, then parses as MiniMessage */
    private Component deserialize(String raw, TagResolver... resolvers) {
        String prepared = preprocess(raw);
        try {
            return miniMessage.deserialize(prepared, resolvers);
        } catch (ParsingException e) {
            plugin.getLogger().warning("Malformed message template '" + raw + "': " + e.getMessage());
            return Component.text(prepared);
        }
    }

    private String preprocess(String raw) {
        String out = raw;
        if (out.indexOf('$') >= 0) {
            for (ColorVariable variable : colorVariables) {
                out = out.replace(variable.token(), variable.hex());
            }
        }
        if (!shadowSupported) {
            out = SHADOW_TAG.matcher(out).replaceAll("");
        }
        return out;
    }

    public static @NonNull String stripNamespace(@NonNull String key) {
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    /** A {@code $tag} palette variable and the hex string it expands to */
    private record ColorVariable(String token, String hex) {
    }

    private static @NonNull @Unmodifiable List<ColorVariable> buildColorVariables() {
        return Arrays.stream(WakeColors.values())
                .sorted(Comparator.comparingInt((WakeColors color) -> color.tag().length()).reversed())
                .map(color -> new ColorVariable("$" + color.tag(), color.asHexString()))
                .toList();
    }

    private static boolean detectShadowSupport() {
        try {
            Class.forName("net.kyori.adventure.text.format.ShadowColor");
            return true; // 1.21.4+
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}