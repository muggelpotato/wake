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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;

public class OBUClearCommand {

    public static @NonNull CommandNode getNode(Wake plugin) {
        CommandNode clearNode = CommandNode.literal("-clear")
                .withModule(OBUModule.class);

        CommandNode settingArg = CommandNode.argument("setting", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase();
                    if (ctx.getSource().getSender() instanceof Player player) {
                        OBUModule obuModule = plugin.getModule(OBUModule.class);
                        if (obuModule != null) {
                            OBUService service = obuModule.getObuService();
                            OBUContextManager contextManager = obuModule.getContextManager();
                            String sandboxName = service.getPlayerActiveSandbox(player);
                            Map<String, OBUSetting> active = new HashMap<>();
                            if (sandboxName != null) {
                                OBUContext base = contextManager.getContext(sandboxName);
                                if (base != null) {
                                    for (OBUSetting s : base.getSettings()) active.put(s.getUniqueKey(), s);
                                }
                            }
                            active.putAll(service.getSyncManager().getLocalOverrides(player.getUniqueId()));
                            active.values().stream()
                                    .map(s -> s.definition().name())
                                    .distinct()
                                    .filter(name -> name.toLowerCase().startsWith(remaining))
                                    .forEach(builder::suggest);
                        }
                    }
                    return builder.buildFuture();
                })
                .executesEntity((ctx, target) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String settingName = StringArgumentType.getString(ctx, "setting");
                    OBUDefinition def = OBUDefinition.get(settingName);
                    CommandSender sender = ctx.getSource().getSender();

                    if (def == null) {
                        plugin.getMessageManager().send(sender, "commands.obu.clear.unknown", Placeholder.parsed("setting", settingName));
                        return 0;
                    }

                    var overrides = service.getSyncManager().getLocalOverrides(target.getUniqueId());
                    boolean cleared = false;

                    if (target instanceof Player player) {
                        String sandboxName = service.getPlayerActiveSandbox(player);

                        boolean hasOverride = overrides.values().stream().anyMatch(s -> s.definition().id() == def.id());

                        if (hasOverride) {
                            service.getSyncManager().removeLocalOverride(player.getUniqueId(), def.id());
                            String targetStr = player.equals(sender) ? "your" : player.getName() + "'s";
                            plugin.getMessageManager().send(sender, "commands.obu.clear.temp", Placeholder.parsed("setting", def.name()), Placeholder.parsed("target", targetStr));
                            cleared = true;
                        } else if (sandboxName != null) {
                            if (contextManager.removeContextSetting(sandboxName, def.id())) {
                                plugin.getMessageManager().send(sender, "commands.obu.clear.sandbox", Placeholder.parsed("setting", def.name()), Placeholder.parsed("sandbox", sandboxName));
                                cleared = true;
                            }
                        } else {
                            String baseName = service.getActiveContextName(player);
                            if (baseName != null) {
                                OBUContext base = contextManager.getContext(baseName);
                                if (base != null) {
                                    boolean isBase = base.getSettings().stream().anyMatch(s -> s.definition().id() == def.id());
                                    if (isBase) {
                                        plugin.getMessageManager().send(sender, "commands.obu.clear.base_blocked", Placeholder.parsed("setting", def.name()));
                                        return 0;
                                    }
                                }
                            }
                        }

                        if (cleared) {
                            service.getSyncManager().syncPlayer(player);
                        }
                    } else if (target instanceof Boat boat) {
                        boolean hasOverride = overrides.values().stream().anyMatch(s -> s.definition().id() == def.id());
                        if (hasOverride) {
                            service.getSyncManager().removeLocalOverride(boat.getUniqueId(), def.id());
                            plugin.getMessageManager().send(sender, "commands.obu.clear.temp", Placeholder.parsed("setting", def.name()), Placeholder.parsed("target", "the boat's"));
                            cleared = true;
                        }

                        if (cleared) {
                            service.getSyncManager().broadcastSync(boat);
                        }
                    }

                    if (!cleared) {
                        String targetString = target instanceof Player p ? (p.equals(sender) ? "your" : p.getName() + "'s") : (target instanceof Boat ? "the boat's" : target.getName() + "'s");
                        plugin.getMessageManager().send(sender, "commands.obu.clear.missing", Placeholder.parsed("setting", def.name()), Placeholder.parsed("target", targetString));
                        return 0;
                    }

                    return Command.SINGLE_SUCCESS;
                });

        clearNode.addSubcommand(settingArg);
        return clearNode;
    }
}
