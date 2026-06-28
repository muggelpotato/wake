package dev.muggel.wake.features.drydock.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.drydock.listeners.BoostpadDetectorListener;
import dev.muggel.wake.features.drydock.listeners.BoostpadDetectorListener.BoostpadConfig;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DrydockBoostpadCommand {
    public static final String STATE_KEY_ENABLED = "drydock_boostpads_enabled";
    public static final String STATE_KEY_CONFIGS = "drydock_boostpads";

    private static List<String> cachedBlockKeys;

    public static void register(@NonNull LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(WakeCommandBuilder.literal("boostpad", "wake.drydock.commands.boostpad")
                .then(WakeCommandBuilder.literal("add", "wake.drydock.commands.boostpad.add")
                        .then(Commands.argument("block", ArgumentTypes.namespacedKey())
                                .suggests((ctx, builder) -> suggestBlockKeys(builder))
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("delay_ms", IntegerArgumentType.integer(0))
                                                                .executes(ctx -> executeAdd(ctx, plugin, 100))
                                                                .then(Commands.argument("hitbox_percent", IntegerArgumentType.integer(0, 245))
                                                                        .executes(ctx -> executeAdd(ctx, plugin, IntegerArgumentType.getInteger(ctx, "hitbox_percent")))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(WakeCommandBuilder.literal("remove", "wake.drydock.commands.boostpad.remove")
                        .then(Commands.argument("block", ArgumentTypes.namespacedKey())
                                .suggests((ctx, builder) -> suggestConfiguredBlocks(plugin, builder))
                                .executes(ctx -> executeRemove(ctx, plugin))
                        )
                )
                .then(WakeCommandBuilder.literal("list", "wake.drydock.commands.boostpad.list")
                        .executes(ctx -> executeList(ctx, plugin))
                )
                .then(WakeCommandBuilder.literal("toggle", "wake.drydock.commands.boostpad.toggle")
                        .executes(ctx -> executeToggleGlobal(ctx, plugin))
                        .then(Commands.argument("block", ArgumentTypes.namespacedKey())
                                .suggests((ctx, builder) -> suggestConfiguredBlocks(plugin, builder))
                                .executes(ctx -> executeToggleBlock(ctx, plugin))
                        )
                )
        );
    }

    private static CompletableFuture<Suggestions> suggestBlockKeys(@NonNull SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String key : getBlockKeys()) {
            if (key.startsWith(remaining) || key.contains(remaining)) {
                builder.suggest(key);
            }
            String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            if (!remaining.contains(":") && (path.startsWith(remaining) || path.contains(remaining)) && !path.equals(key)) {
                builder.suggest(path);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestConfiguredBlocks(Wake plugin, @NonNull SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String key : getConfiguredBoostpads(plugin).keySet()) {
            if (key.startsWith(remaining) || key.contains(remaining)) {
                builder.suggest(key);
            }
            String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            if (!remaining.contains(":") && (path.startsWith(remaining) || path.contains(remaining)) && !path.equals(key)) {
                builder.suggest(path);
            }
        }
        return builder.buildFuture();
    }

    private static int executeAdd(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin, int hitboxPercent) {
        CommandSender sender = ctx.getSource().getSender();
        NamespacedKey key = ctx.getArgument("block", NamespacedKey.class);
        Material material = Registry.MATERIAL.get(key);

        if (material == null || !material.isBlock()) {
            plugin.getMessageManager().send(sender, "commands.drydock.invalid_block");
            return 0;
        }

        String blockKey = key.toString();
        Map<String, Map<String, Object>> currentConfigs = getRawConfigMap(plugin);
        
        Map<String, Object> cfgData = new LinkedHashMap<>();
        cfgData.put("forceX", DoubleArgumentType.getDouble(ctx, "x"));
        cfgData.put("forceY", DoubleArgumentType.getDouble(ctx, "y"));
        cfgData.put("forceZ", DoubleArgumentType.getDouble(ctx, "z"));
        cfgData.put("delayMs", IntegerArgumentType.getInteger(ctx, "delay_ms"));
        cfgData.put("hitboxPercent", hitboxPercent);
        cfgData.put("enabled", true);

        currentConfigs.put(blockKey, cfgData);
        plugin.getStateManager().set(STATE_KEY_CONFIGS, currentConfigs);
        BoostpadDetectorListener.reloadAllCaches();
        plugin.getMessageManager().send(sender, "commands.drydock.block_added");
        return Command.SINGLE_SUCCESS;
    }

    private static int executeRemove(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        NamespacedKey key = ctx.getArgument("block", NamespacedKey.class);

        Map<String, Map<String, Object>> currentConfigs = getRawConfigMap(plugin);
        String matchedKey = findMatchingKey(currentConfigs, key.toString());

        if (matchedKey == null || currentConfigs.remove(matchedKey) == null) {
            plugin.getMessageManager().send(sender, "commands.drydock.block_not_found");
            return 0;
        }

        plugin.getStateManager().set(STATE_KEY_CONFIGS, currentConfigs);
        BoostpadDetectorListener.reloadAllCaches();
        plugin.getMessageManager().send(sender, "commands.drydock.block_removed");
        return Command.SINGLE_SUCCESS;
    }

    private static int executeList(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        Map<String, BoostpadConfig> configs = getConfiguredBoostpads(plugin);
        boolean enabled = plugin.getStateManager().get(STATE_KEY_ENABLED, true);

        Component blocksComp;
        if (configs.isEmpty()) {
            blocksComp = plugin.getMessageManager().getComponent("commands.drydock.boostpad_empty");
        } else {
            Component builder = Component.empty();
            boolean first = true;
            for (BoostpadConfig cfg : configs.values()) {
                if (!first) {
                    builder = builder.append(Component.newline());
                }
                first = false;
                String itemKey = cfg.enabled() ? "commands.drydock.boostpad_item_enabled" : "commands.drydock.boostpad_item_disabled";
                Component itemComp = plugin.getMessageManager().getComponent(itemKey,
                        Placeholder.parsed("block", cfg.blockKey()),
                        Placeholder.parsed("x", formatNum(cfg.forceX())),
                        Placeholder.parsed("y", formatNum(cfg.forceY())),
                        Placeholder.parsed("z", formatNum(cfg.forceZ())),
                        Placeholder.parsed("delay", String.valueOf(cfg.delayMs())),
                        Placeholder.parsed("hitbox", String.valueOf(cfg.hitboxPercent()))
                );
                builder = builder.append(itemComp);
            }
            blocksComp = builder;
        }

        plugin.getMessageManager().send(sender, "commands.drydock.boostpad_status",
                Placeholder.parsed("enabled", String.valueOf(enabled)),
                Placeholder.component("blocks", blocksComp)
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int executeToggleGlobal(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        boolean currentState = plugin.getStateManager().get(STATE_KEY_ENABLED, true);
        boolean newState = !currentState;
        plugin.getStateManager().set(STATE_KEY_ENABLED, newState);
        BoostpadDetectorListener.reloadAllCaches();

        plugin.getMessageManager().send(sender, newState ? "commands.drydock.boostpad_enabled" : "commands.drydock.boostpad_disabled");
        return Command.SINGLE_SUCCESS;
    }

    private static int executeToggleBlock(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        NamespacedKey key = ctx.getArgument("block", NamespacedKey.class);

        Map<String, Map<String, Object>> currentConfigs = getRawConfigMap(plugin);
        String matchedKey = findMatchingKey(currentConfigs, key.toString());
        Map<String, Object> cfgMap = matchedKey != null ? currentConfigs.get(matchedKey) : null;

        if (cfgMap == null) {
            plugin.getMessageManager().send(sender, "commands.drydock.block_not_found");
            return 0;
        }

        boolean newState = !parseBoolean(cfgMap.get("enabled"));
        cfgMap.put("enabled", newState);

        plugin.getStateManager().set(STATE_KEY_CONFIGS, currentConfigs);
        BoostpadDetectorListener.reloadAllCaches();

        plugin.getMessageManager().send(sender, newState ? "commands.drydock.boostpad_block_enabled" : "commands.drydock.boostpad_block_disabled",
                Placeholder.parsed("block", matchedKey));
        return Command.SINGLE_SUCCESS;
    }

    private static String findMatchingKey(Map<String, Map<String, Object>> map, String input) {
        if (input == null) return null;
        String clean = input.toLowerCase();
        String full = clean.contains(":") ? clean : "minecraft:" + clean;
        for (String key : map.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.equals(clean) || lowerKey.equals(full) || lowerKey.endsWith(":" + clean)) {
                return key;
            }
        }
        return null;
    }

    private static @NonNull Map<String, Map<String, Object>> getRawConfigMap(@NonNull Wake plugin) {
        Object val = plugin.getStateManager().get(STATE_KEY_CONFIGS, Collections.emptyMap());
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (val instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> cfgMap) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (String k : List.of("forceX", "forceY", "forceZ", "delayMs", "hitboxPercent", "enabled")) {
                        Object obj = cfgMap.get(k);
                        if (obj != null) copy.put(k, obj);
                    }
                    for (Map.Entry<?, ?> e2 : cfgMap.entrySet()) {
                        String k = e2.getKey().toString();
                        if (!copy.containsKey(k)) copy.put(k, e2.getValue());
                    }
                    result.put(entry.getKey().toString().toLowerCase(), copy);
                }
            }
        }
        return result;
    }

    public static @NonNull Map<String, BoostpadConfig> getConfiguredBoostpads(@NonNull Wake plugin) {
        Map<String, Map<String, Object>> raw = getRawConfigMap(plugin);
        Map<String, BoostpadConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : raw.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> cfgMap = entry.getValue();
            try {
                boolean enabled = parseBoolean(cfgMap.get("enabled"));
                double fx = parseDouble(cfgMap.get("forceX"));
                double fy = parseDouble(cfgMap.get("forceY"));
                double fz = parseDouble(cfgMap.get("forceZ"));
                long delay = parseLong(cfgMap.get("delayMs"));
                int hitboxPercent = (int) parseLong(cfgMap.get("hitboxPercent"));
                result.put(key, new BoostpadConfig(key, enabled, fx, fy, fz, delay, hitboxPercent));
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static @NonNull String formatNum(double d) {
        return d == (long) d ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static boolean parseBoolean(Object obj) {
        if (obj == null) return true;
        return obj instanceof Boolean b ? b : Boolean.parseBoolean(obj.toString());
    }

    private static double parseDouble(Object obj) {
        if (obj == null) return 0.0;
        return obj instanceof Number num ? num.doubleValue() : Double.parseDouble(obj.toString());
    }

    private static long parseLong(Object obj) {
        if (obj == null) return 0L;
        return obj instanceof Number num ? num.longValue() : (long) Double.parseDouble(obj.toString());
    }

    private static @NonNull List<String> getBlockKeys() {
        if (cachedBlockKeys == null) {
            cachedBlockKeys = Registry.MATERIAL.stream()
                    .filter(Material::isBlock)
                    .map(m -> m.getKey().toString())
                    .toList();
        }
        return cachedBlockKeys;
    }
}
