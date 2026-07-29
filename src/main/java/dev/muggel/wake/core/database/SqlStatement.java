package dev.muggel.wake.core.database;

import org.jspecify.annotations.NonNull;

/** One parameterized statement, so everything a single logical write touches can be queued as one unit */
public record SqlStatement(@NonNull String sql, Object @NonNull [] params) {
    public SqlStatement {
        params = params.clone();
    }

    @Override
    public Object @NonNull [] params() {
        return params.clone();
    }
}