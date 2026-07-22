package dev.muggel.wake.features.obu.service;

import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUContext.ContextType;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.OBUDao;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OBUContextManager {
    private final OBUDao dao;
    private volatile Map<String, OBUContext> contexts = Map.of();
    private volatile Set<String> sandboxes = Set.of();
    public OBUContextManager(OBUDao dao) {
        this.dao = dao;
        loadContexts();
    }

    public void loadContexts() {
        Map<String, OBUContext> loaded = new ConcurrentHashMap<>(dao.loadAllContexts());
        if (!loaded.containsKey("default")) {
            loaded.put("default", new OBUContext("default", ContextType.SERVER, null, List.of()));
            dao.saveContext("default", ContextType.SERVER, null);
        }
        loaded.put(OBUDefinition.CONTEXT_EMPTY, new OBUContext(OBUDefinition.CONTEXT_EMPTY, ContextType.SERVER, null, List.of()));
        Set<String> loadedSandboxes = ConcurrentHashMap.newKeySet();
        for (OBUContext ctx : loaded.values()) {
            if (ctx.isSandbox()) {
                loadedSandboxes.add(ctx.name());
            }
        }
        this.contexts = loaded;
        this.sandboxes = loadedSandboxes;
    }

    public Set<String> getContextNames() {
        return Collections.unmodifiableSet(contexts.keySet());
    }

    public @Nullable OBUContext getContext(@NonNull String name) {
        return contexts.get(name.toLowerCase(Locale.ROOT));
    }

    @Contract(pure = true)
    public static @NonNull String sandboxKey(@NonNull String name, @NonNull UUID owner) {
        return name.toLowerCase(Locale.ROOT) + "@" + owner;
    }

    public static @NonNull String displayName(@NonNull String contextName) {
        int at = contextName.indexOf('@');
        return at == -1 ? contextName : contextName.substring(0, at);
    }

    public boolean createSandbox(@NonNull String key, UUID ownerUuid) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (isReserved(displayName(lower)) || contexts.containsKey(lower)) {
            return false;
        }
        contexts.put(lower, new OBUContext(lower, ContextType.SANDBOX, ownerUuid, List.of()));
        sandboxes.add(lower);
        dao.saveContext(lower, ContextType.SANDBOX, ownerUuid);
        dao.updateSandboxAccessTime(lower);
        return true;
    }

    public void deleteContext(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (isReserved(lower)) return;
        contexts.remove(lower);
        sandboxes.remove(lower);
        dao.deleteContext(lower);
    }

    public boolean publishSandbox(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contexts.get(lower);
        if (context == null || !context.isSandbox()) return false;
        String display = displayName(lower);
        if (!display.equals(lower) && (isReserved(display) || contexts.containsKey(display))) {
            return false;
        }

        contexts.remove(lower);
        sandboxes.remove(lower);
        contexts.put(display, new OBUContext(display, ContextType.SERVER, null, context.settings()));
        dao.deleteContext(lower);
        dao.saveContext(display, ContextType.SERVER, null);
        for (OBUSetting setting : context.settings()) {
            dao.saveSetting(display, setting);
        }
        return true;
    }

    public void updateSandboxSetting(@NonNull String name, OBUSetting setting) {
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contexts.get(lower);
        if (context == null) return;
        List<OBUSetting> settings = new ArrayList<>(context.settings());
        settings.removeIf(s -> s.getUniqueKey().equals(setting.getUniqueKey()));
        settings.add(setting);
        contexts.put(lower, new OBUContext(lower, context.type(), context.ownerUuid(), settings));
        dao.saveSetting(lower, setting);
        if (sandboxes.contains(lower)) {
            dao.updateSandboxAccessTime(lower);
        }
    }

    public void addSettings(@NonNull String name, @NonNull List<OBUSetting> newSettings) {
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contexts.get(lower);
        if (context == null || newSettings.isEmpty()) return;
        LinkedHashMap<String, OBUSetting> merged = new LinkedHashMap<>();
        for (OBUSetting s : context.settings()) merged.put(s.getUniqueKey(), s);
        for (OBUSetting s : newSettings) merged.put(s.getUniqueKey(), s);
        contexts.put(lower, new OBUContext(lower, context.type(), context.ownerUuid(), new ArrayList<>(merged.values())));
        for (OBUSetting s : newSettings) {
            dao.saveSetting(lower, s);
        }
        if (sandboxes.contains(lower)) {
            dao.updateSandboxAccessTime(lower);
        }
    }

    public boolean removeContextSetting(@NonNull String name, String uniqueKey) {
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contexts.get(lower);
        if (context == null) return false;
        List<OBUSetting> settings = new ArrayList<>(context.settings());
        boolean removed = settings.removeIf(s -> s.getUniqueKey().equals(uniqueKey));
        if (removed) {
            contexts.put(lower, new OBUContext(lower, context.type(), context.ownerUuid(), settings));
            dao.deleteSetting(lower, uniqueKey);
            return true;
        }
        return false;
    }

    public static boolean isReserved(@NonNull String lower) {
        return lower.equals("default") || lower.equals(OBUDefinition.CONTEXT_EMPTY) || lower.equals(OBUDefinition.CONTEXT_PERSONAL);
    }

    public static boolean inheritsDefault(@NonNull OBUContext context) {
        return !context.isSandbox()
                && !context.name().equalsIgnoreCase("default")
                && !context.name().equals(OBUDefinition.CONTEXT_EMPTY);
    }
}