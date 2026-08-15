package dev.muggel.wake.core.database;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.muggel.wake.Wake;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Write-ahead journal used while the database is unreachable. <br>
 * One line is one queued write, whole: it lands or it doesn't, and replays through the same transaction the live path would have run. <br>
 * Replayed in order on recovery.
 */
class OutageJournal {
    private static final Gson GSON = new Gson();
    private final Wake plugin;
    private final File file;
    private volatile @Nullable FileOutputStream writer;
    OutageJournal(@NonNull Wake plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "outage-journal.jsonl");
    }

    boolean isEmpty() {
        return !file.isFile() || file.length() == 0;
    }

    boolean append(@NonNull List<SqlStatement> statements) {
        if (statements.isEmpty()) {
            return true;
        }
        JsonArray group = new JsonArray();
        for (SqlStatement statement : statements) {
            JsonObject entry = new JsonObject();
            entry.addProperty("q", statement.sql());
            JsonArray encoded = new JsonArray();
            for (Object param : statement.params()) {
                encoded.add(encode(param));
            }
            entry.add("p", encoded);
            group.add(entry);
        }
        JsonObject line = new JsonObject();
        line.add("s", group);
        try {
            FileOutputStream out = writer;
            if (out == null) {
                out = new FileOutputStream(file, true);
                writer = out;
            }
            out.write((GSON.toJson(line) + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to journal write for recovery (change will be lost): " + statements.getFirst().sql(), e);
            return false;
        }
    }

    int replay() {
        closeWriter();
        if (isEmpty()) {
            return 0;
        }
        int replayed = 0;
        long lineIndex = 0;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineIndex++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    List<SqlStatement> statements = new ArrayList<>();
                    for (JsonElement element : JsonParser.parseString(line).getAsJsonObject().getAsJsonArray("s")) {
                        JsonObject entry = element.getAsJsonObject();
                        JsonArray encoded = entry.getAsJsonArray("p");
                        Object[] params = new Object[encoded.size()];
                        for (int p = 0; p < params.length; p++) {
                            params[p] = decode(encoded.get(p).getAsJsonObject());
                        }
                        statements.add(new SqlStatement(entry.get("q").getAsString(), params));
                    }
                    DatabaseManager.execute(statements);
                    replayed++;
                } catch (Exception e) {
                    if (OutageMonitor.isRetryableFailure(e)) {
                        keepRemainderFrom(lineIndex - 1);
                        plugin.getLogger().warning("Database dropped out during journal replay (remaining entries kept for next attempt)");
                        return -1;
                    }
                    plugin.getLogger().log(Level.SEVERE, "Dropped unreplayable journal entry: " + line, e);
                }
            }
        } catch (IOException e) {
            // report failure so callers stay degraded and retry
            plugin.getLogger().log(Level.SEVERE, "Failed to read outage journal (will retry)", e);
            return -1;
        }
        deleteFile();
        return replayed;
    }

    private void keepRemainderFrom(long fromLine) {
        Path temp = file.toPath().resolveSibling(file.getName() + ".tmp");
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
            FileOutputStream out = new FileOutputStream(temp.toFile())) {
            BufferedWriter remainder = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            String line;
            long index = 0;
            while ((line = reader.readLine()) != null) {
                if (index++ >= fromLine) {
                    remainder.write(line);
                    remainder.newLine();
                }
            }
            remainder.flush();
            out.getFD().sync();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to rewrite outage journal", e);
            return;
        }
        try {
            Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to swap in rewritten outage journal", e);
        }
    }

    private void deleteFile() {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete replayed outage journal", e);
        }
    }

    void closeWriter() {
        FileOutputStream out = writer;
        writer = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private @NonNull JsonObject encode(@Nullable Object param) {
        JsonObject out = new JsonObject();
        switch (param) {
            case null -> out.addProperty("t", "n");
            case Boolean b -> { out.addProperty("t", "b"); out.addProperty("v", b); }
            case Integer i -> { out.addProperty("t", "i"); out.addProperty("v", i); }
            case Long l -> { out.addProperty("t", "l"); out.addProperty("v", l); }
            case Number d -> { out.addProperty("t", "d"); out.addProperty("v", d.doubleValue()); }
            default -> {
                if (!(param instanceof String)) {
                    plugin.getLogger().warning("Journaling non-string parameter as text " + param.getClass().getSimpleName());
                }
                out.addProperty("t", "s");
                out.addProperty("v", param.toString());
            }
        }
        return out;
    }

    private static @Nullable Object decode(@NonNull JsonObject encoded) {
        String type = encoded.get("t").getAsString();
        JsonElement value = encoded.get("v");
        return switch (type) {
            case "n" -> null;
            case "b" -> value.getAsBoolean();
            case "i" -> value.getAsInt();
            case "l" -> value.getAsLong();
            case "d" -> value.getAsDouble();
            default -> value.getAsString();
        };
    }
}