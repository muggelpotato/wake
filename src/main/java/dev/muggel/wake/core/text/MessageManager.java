package dev.muggel.wake.core.text;

import dev.muggel.wake.Wake;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;

import java.io.File;

public class MessageManager {
    private final Wake plugin;
    private YamlConfiguration config;
    private final MiniMessage miniMessage;
    private TagResolver cachedPrefixResolver;

    public MessageManager(Wake plugin) {
        this.plugin = plugin;
        TagResolver colorResolver = TagResolver.builder()
                .tag("primary", Tag.styling(WakeColors.PRIMARY))
                .tag("secondary", Tag.styling(WakeColors.SECONDARY))
                .tag("accent", Tag.styling(WakeColors.ACCENT))
                .tag("danger", Tag.styling(WakeColors.ERROR))
                .tag("neutral", Tag.styling(WakeColors.NEUTRAL))
                .tag("info", Tag.styling(WakeColors.INFO))
                .tag("muted_dark", Tag.styling(WakeColors.MUTED_DARK))
                .tag("overridden", Tag.styling(WakeColors.OVERRIDDEN))
                .build();
                
        this.miniMessage = MiniMessage.builder()
                .tags(TagResolver.builder()
                        .resolver(StandardTags.defaults())
                        .resolver(colorResolver)
                        .build())
                .build();
                
        reload();
    }

    public void reload() {
        File langFile = new File(plugin.getDataFolder(), "lang/en_us.yml");
        if (!langFile.exists()) {
            try {
                plugin.saveResource("lang/en_us.yml", false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Default lang/en_us.yml not found in plugin resources");
            }
        }
        if (langFile.exists()) {
            this.config = YamlConfiguration.loadConfiguration(langFile);
        } else {
            this.config = new YamlConfiguration();
        }
        
        String prefixRaw = config.getString("prefix", "<gray>[<blue>Wake</blue>] </gray>");
        this.cachedPrefixResolver = Placeholder.component("prefix", miniMessage.deserialize(prefixRaw));
    }

    public Component getComponent(String key, TagResolver... resolvers) {
        String raw = config.getString(key);
        if (raw == null) {
            return Component.text("Missing message: " + key);
        }
        if (raw.contains("<prefix>") && !key.equals("prefix") && cachedPrefixResolver != null) {
            TagResolver[] newResolvers = new TagResolver[resolvers.length + 1];
            newResolvers[0] = cachedPrefixResolver;
            System.arraycopy(resolvers, 0, newResolvers, 1, resolvers.length);
            return miniMessage.deserialize(raw, newResolvers);
        }
        return miniMessage.deserialize(raw, resolvers);
    }

    public void send(@NonNull CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(getComponent(key, resolvers));
    }
}
