package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.Map;

public class OBUDefaultsCommand {

    public static @NonNull CommandNode getNode(Wake plugin) {
        CommandNode defaultsNode = CommandNode.literal("-defaults")
                .withModule(OBUModule.class);

        CommandNode settingArg = CommandNode.argument("setting", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase();
                    Arrays.stream(OBUDefinition.values())
                            .map(OBUDefinition::commandName)
                            .filter(name -> name.startsWith(remaining))
                            .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executesSender((ctx, sender) -> {
                    String settingName = StringArgumentType.getString(ctx, "setting");
                    OBUDefinition def = OBUDefinition.get(settingName);

                    if (def == null || def.defaultValues() == null) {
                        plugin.getMessageManager().send(sender, "commands.obu.defaults.missing", Placeholder.parsed("setting", settingName));
                        return 0;
                    }

                    String defValueStr = String.join(" ", def.defaultValues());

                    if (sender instanceof Player player) {
                        OBUModule obuModule = plugin.getModule(OBUModule.class);
                        if (obuModule == null) {
                            plugin.getMessageManager().send(sender, "commands.obu.not_loaded");
                            return 0;
                        }

                        plugin.getMessageManager().send(sender, "commands.obu.defaults.vanilla",
                                Placeholder.parsed("setting", settingName),
                                Placeholder.parsed("value", defValueStr));
                        
                        OBUService service = obuModule.getObuService();
                        OBUContextManager contextManager = obuModule.getContextManager();
                        
                        String sandboxName = service.getPlayerActiveSandbox(player);
                        String baseName = service.getActiveContextName(player);
                        Map<String, OBUSetting> overrides = service.getSyncManager().getLocalOverrides(player.getUniqueId());
                        
                        OBUSetting effectiveSetting;
                        boolean isServerDefault = false;
                        
                        int id = def.id();
                        effectiveSetting = overrides.values().stream().filter(s -> s.definition().id() == id).findFirst().orElse(null);
                        if (effectiveSetting == null && sandboxName != null) {
                            OBUContext sb = contextManager.getContext(sandboxName);
                            if (sb != null) {
                                for (OBUSetting s : sb.getSettings()) {
                                    if (s.definition().id() == id) {
                                        effectiveSetting = s;
                                        break;
                                    }
                                }
                            }
                        }
                        
                        if (effectiveSetting == null && baseName != null) {
                            OBUContext base = contextManager.getContext(baseName);
                            if (base != null) {
                                for (OBUSetting s : base.getSettings()) {
                                    if (s.definition().id() == id) {
                                        effectiveSetting = s;
                                        isServerDefault = true;
                                        break;
                                    }
                                }
                            }
                        }
                        
                        if (effectiveSetting != null) {
                            String activeValue = String.join(", ", effectiveSetting.args());
                            Component button;
                            if (isServerDefault && sandboxName == null) {
                                button = plugin.getMessageManager().getComponent("commands.obu.defaults.blocked_btn");
                            } else {
                                button = plugin.getMessageManager().getComponent("commands.obu.defaults.clear_btn", Placeholder.parsed("setting", settingName));
                            }
                            plugin.getMessageManager().send(sender, "commands.obu.defaults.custom",
                                    Placeholder.parsed("value", activeValue),
                                    Placeholder.component("button", button));
                        } else {
                            plugin.getMessageManager().send(sender, "commands.obu.defaults.active",
                                    Placeholder.parsed("value", defValueStr));
                        }
                    } else {
                        plugin.getMessageManager().send(sender, "commands.obu.defaults.vanilla",
                                Placeholder.parsed("setting", settingName),
                                Placeholder.parsed("value", defValueStr));
                    }
                    return Command.SINGLE_SUCCESS;
                });

        defaultsNode.addSubcommand(settingArg);
        return defaultsNode;
    }
}