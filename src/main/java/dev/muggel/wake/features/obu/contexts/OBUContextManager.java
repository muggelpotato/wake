package dev.muggel.wake.features.obu.contexts;

import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.database.SqlStatement;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.contexts.OBUContext.ContextType;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.OBUDao;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class OBUContextManager {
    public static final String DEFAULT_CONTEXT = "default";
    private final OBUDao dao;
    private final CachedStore<OBUContext> contexts;
    public OBUContextManager(OBUDao dao) {
        this.dao = dao;
        this.contexts = dao.contexts();
        loadContexts();
    }

    public void loadContexts() {
        contexts.load();
        ensureDefault();
    }

    public void reloadAsync(@Nullable Consumer<Set<String>> afterApply) {
        contexts.reloadAsync(changed -> {
            ensureDefault();
            if (afterApply != null) {
                afterApply.accept(changed);
            }
        });
    }

    private void ensureDefault() {
        if (contexts.isLoaded() && !contexts.containsKey(DEFAULT_CONTEXT)) {
            dao.saveContext(new OBUContext(DEFAULT_CONTEXT, ContextType.SERVER, null, List.of()), List.of());
        }
    }

    public boolean isLoaded() {
        return contexts.isLoaded();
    }

    public Set<String> getContextNames() {
        return contexts.keys();
    }

    public record ContextCounts(int serverContexts, int sandboxes) {
        public int total() {
            return serverContexts + sandboxes;
        }
    }

    public @Nullable ContextCounts countContexts() {
        if (!contexts.isLoaded()) {
            return null;
        }
        int serverContexts = 0;
        int sandboxes = 0;
        for (OBUContext context : contexts.view().values()) {
            if (isInternal(context.name())) {
                continue;
            }
            if (context.isSandbox()) {
                sandboxes++;
            } else {
                serverContexts++;
            }
        }
        return new ContextCounts(serverContexts, sandboxes);
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
        dao.saveContext(new OBUContext(lower, ContextType.SANDBOX, ownerUuid, List.of()), List.of());
        return true;
    }

    public void deleteContext(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (isReserved(lower)) return;
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
        dao.renameContext(lower, new OBUContext(display, ContextType.SERVER, null, context.settings()));
        return true;
    }

    public void updateSandboxSetting(@NonNull String name, OBUSetting setting) {
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contexts.get(lower);
        if (context == null) return;
        List<OBUSetting> settings = new ArrayList<>(context.settings());
        settings.removeIf(s -> s.getUniqueKey().equals(setting.getUniqueKey()));
        settings.add(setting);
        dao.saveContext(new OBUContext(lower, context.type(), context.ownerUuid(), settings), List.of(dao.settingUpsert(lower, setting)));
    }

    public void addSettings(@NonNull String name, @NonNull List<OBUSetting> newSettings) {
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contexts.get(lower);
        if (context == null || newSettings.isEmpty()) return;
        LinkedHashMap<String, OBUSetting> merged = new LinkedHashMap<>();
        for (OBUSetting s : context.settings()) merged.put(s.getUniqueKey(), s);
        for (OBUSetting s : newSettings) merged.put(s.getUniqueKey(), s);
        List<SqlStatement> settingWrites = new ArrayList<>();
        for (OBUSetting s : newSettings) {
            settingWrites.add(dao.settingUpsert(lower, s));
        }
        dao.saveContext(new OBUContext(lower, context.type(), context.ownerUuid(), new ArrayList<>(merged.values())), settingWrites);
    }

    public boolean removeContextSetting(@NonNull String name, String uniqueKey) {
        String lower = name.toLowerCase(Locale.ROOT);
        OBUContext context = contexts.get(lower);
        if (context == null) return false;
        List<OBUSetting> settings = new ArrayList<>(context.settings());
        if (!settings.removeIf(s -> s.getUniqueKey().equals(uniqueKey))) {
            return false;
        }
        dao.saveContext(new OBUContext(lower, context.type(), context.ownerUuid(), settings),
                List.of(dao.settingDelete(lower, uniqueKey)));
        return true;
    }

    public static boolean isReserved(@NonNull String lower) {
        return lower.equals(DEFAULT_CONTEXT) || isInternal(lower);
    }

    public static boolean isInternal(@NonNull String name) {
        return name.equals(OBUDefinition.CONTEXT_EMPTY) || name.equals(OBUDefinition.CONTEXT_PERSONAL);
    }

    public static boolean inheritsDefault(@NonNull String name) {
        return !name.equalsIgnoreCase(DEFAULT_CONTEXT) && !name.equals(OBUDefinition.CONTEXT_EMPTY);
    }

    public static boolean inheritsDefault(@NonNull OBUContext context) {
        return inheritsDefault(context.name());
    }
}