package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.context.OBUSetting;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import dev.muggel.wake.features.obu.context.OBUContext;
import org.jspecify.annotations.NonNull;

public class OBUDefaultsCommand {

    public static void register(@NonNull LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(WakeCommandBuilder.literal("-defaults", "wake.obu.commands.defaults")
                .then(Commands.argument("setting", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            String remaining = builder.getRemaining().toLowerCase();
                            Arrays.stream(OBUDefinition.values())
                                    .map(OBUDefinition::commandName)
                                    .filter(name -> name.startsWith(remaining))
                                    .forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String settingName = StringArgumentType.getString(ctx, "setting");
                            OBUDefinition def = OBUDefinition.get(settingName);

                            if (def == null || def.defaultValues() == null) {
                                plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.obu.defaults.missing", Placeholder.parsed("setting", settingName));
                                return 0;
                            }

                            String defValueStr = String.join(" ", def.defaultValues());
                            CommandSender sender = ctx.getSource().getSender();

                            if (sender instanceof Player player) {
                                plugin.getMessageManager().send(sender, "commands.obu.defaults.vanilla",
                                        Placeholder.parsed("setting", settingName),
                                        Placeholder.parsed("value", defValueStr));
                                
                                OBUService service = Objects.requireNonNull(plugin.getModule(OBUModule.class)).getObuService();
                                OBUContextManager contextManager = Objects.requireNonNull(plugin.getModule(OBUModule.class)).getContextManager();
                                
                                String sandboxName = service.getPlayerActiveSandbox(player);
                                String baseName = service.getActiveContextName(player);
                                Map<String, OBUSetting> overrides = service.getSyncManager().getLocalOverrides(player.getUniqueId());
                                
                                OBUSetting effectiveSetting = null;
                                boolean isServerDefault = false;
                                
                                OBUDefinition protocolDef = OBUDefinition.get(settingName);
                                if (protocolDef != null) {
                                    int id = protocolDef.id();
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
                        })));
    }
}

