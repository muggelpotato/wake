-- Reference schema for IDE SQL inspection only (see WakeDao.getTableSchemas() implementations
-- for the statements Wake actually executes at runtime). Bind this file as an IntelliJ DDL data
-- source so injected SQL resolves without a live database connection. Keep in sync by hand when a
-- DAO's getTableSchemas() changes.

CREATE TABLE IF NOT EXISTS wake_schema_version (
    module VARCHAR(64) PRIMARY KEY,
    version INT NOT NULL
);

CREATE TABLE IF NOT EXISTS wake_state (
    state_key VARCHAR(255) PRIMARY KEY,
    state_value TEXT
);

CREATE TABLE IF NOT EXISTS wake_axiom_displays (
    model_key VARCHAR(255) PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS wake_drydock_boostpads (
    block_key VARCHAR(255) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    force_x DOUBLE NOT NULL,
    force_y DOUBLE NOT NULL,
    force_z DOUBLE NOT NULL,
    delay_ms BIGINT NOT NULL,
    padding DOUBLE NOT NULL
);

CREATE TABLE IF NOT EXISTS wake_obu_contexts (
    name VARCHAR(255) PRIMARY KEY,
    type VARCHAR(32) DEFAULT 'SERVER',
    owner_uuid VARCHAR(36) NULL,
    last_accessed_at BIGINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wake_obu_settings (
    context_name VARCHAR(255) NOT NULL,
    unique_key VARCHAR(255) NOT NULL,
    definition_name VARCHAR(255) NOT NULL,
    args TEXT NOT NULL,
    PRIMARY KEY (context_name, unique_key)
);

CREATE TABLE IF NOT EXISTS wake_obu_player_states (
    player_uuid VARCHAR(36) PRIMARY KEY,
    active_sandbox VARCHAR(255) NULL,
    active_context VARCHAR(255) NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);