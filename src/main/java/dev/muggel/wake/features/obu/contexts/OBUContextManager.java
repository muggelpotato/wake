package dev.muggel.wake.features.obu.contexts;

import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.database.SqlStatement;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.contexts.OBUContext.ContextType;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.SettingMerge;
import dev.muggel.wake.features.obu.protocol.SettingMerge.Removal;
import dev.muggel.wake.features.obu.protocol.SettingSelector;
import dev.muggel.wake.features.obu.OBUDao;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class OBUContextManager {
    public static final String DEFAULT_CONTEXT = "default";
    public static final String EMPTY_CONTEXT = "wake:empty";
    public static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private final OBUDao dao;
    private final CachedStore<OBUContext> contexts;
    public OBUContextManager(OBUDao dao) {
        this.dao = dao;
        this.contexts = dao.contexts();
        loadContexts();
    }

    public void loadContexts() {
        contexts.load();
    }

    public void reloadAsync(@NonNull Consumer<Set<String>> afterApply) {
        contexts.reloadAsync(afterApply);
    }

    public boolean isLoaded() {
        return contexts.isLoaded();
    }

    public @NonNull @Unmodifiable Collection<OBUContext> getContexts() {
        return contexts.view().values();
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
        for (OBUContext context : getContexts()) {
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
        return contexts.get(canonical(name));
    }

    public static @NonNull String canonical(@NonNull String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static @NonNull String sandboxKey(@NonNull String name, @NonNull UUID owner) {
        return canonical(name) + "@" + owner;
    }

    public static @NonNull String displayName(@NonNull String contextName) {
        int at = contextName.indexOf('@');
        return at == -1 ? contextName : contextName.substring(0, at);
    }

    public static boolean isUnaddressable(@NonNull String name, @NonNull ContextType type, @Nullable UUID owner) {
        String display = displayName(name);
        String key = type == ContextType.SANDBOX && owner != null ? sandboxKey(display, owner) : display;
        return !NAME_PATTERN.matcher(display).matches() || !name.equals(key);
    }

    public boolean createSandbox(@NonNull String key, @Nullable UUID ownerUuid) {
        String lower = canonical(key);
        if (isReserved(displayName(lower)) || contexts.containsKey(lower)) {
            return false;
        }
        dao.saveContext(new OBUContext(lower, ContextType.SANDBOX, ownerUuid, List.of()), List.of());
        return true;
    }

    public boolean deleteContext(@NonNull String name) {
        String lower = canonical(name);
        if (isReserved(lower)) return false;
        dao.deleteContext(lower);
        return true;
    }

    public boolean publishSandbox(@NonNull String name) {
        String lower = canonical(name);
        OBUContext context = contexts.get(lower);
        if (context == null || !context.isSandbox()) return false;
        String display = displayName(lower);
        if (isReserved(display) || (!display.equals(lower) && contexts.containsKey(display))) {
            return false;
        }
        dao.renameContext(lower, new OBUContext(display, ContextType.SERVER, null, context.settings()));
        return true;
    }

    public void addSettings(@NonNull String name, @NonNull List<OBUSetting> newSettings) {
        String lower = canonical(name);
        OBUContext context = contexts.get(lower);
        if (context == null || newSettings.isEmpty()) return;
        saveSettings(lower, context, SettingMerge.fold(context.settings(), newSettings));
    }

    public @NonNull Removal removeSettings(@NonNull String name, @NonNull SettingSelector selector) {
        String lower = canonical(name);
        OBUContext context = contexts.get(lower);
        if (context == null) return Removal.NOTHING;
        Removal removal = SettingMerge.subtract(context.settings(), selector);
        if (!removal.taken().isEmpty()) {
            saveSettings(lower, context, removal.kept());
        }
        return removal;
    }

    private void saveSettings(@NonNull String lower, @NonNull OBUContext context, @NonNull List<OBUSetting> settings) {
        LinkedHashMap<String, OBUSetting> stale = new LinkedHashMap<>();
        for (OBUSetting s : context.settings()) stale.put(s.uniqueKey(), s);
        List<SqlStatement> settingWrites = new ArrayList<>();
        for (OBUSetting s : settings) {
            if (!s.equals(stale.remove(s.uniqueKey()))) settingWrites.add(dao.settingUpsert(lower, s));
        }
        for (String key : stale.keySet()) settingWrites.add(dao.settingDelete(lower, key));
        dao.saveContext(new OBUContext(lower, context.type(), context.ownerUuid(), settings), settingWrites);
    }

    public static boolean isReserved(@NonNull String lower) {
        return lower.equals(DEFAULT_CONTEXT) || isInternal(lower);
    }

    public static boolean isInternal(@NonNull String name) {
        return name.equals(EMPTY_CONTEXT) || name.equals(OBUDefinition.CONTEXT_PERSONAL);
    }

    public static boolean inheritsDefault(@NonNull String name) {
        return !name.equals(DEFAULT_CONTEXT) && !name.equals(EMPTY_CONTEXT);
    }

    public static boolean inheritsDefault(@NonNull OBUContext context) {
        return inheritsDefault(context.name());
    }
}