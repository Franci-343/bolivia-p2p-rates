-- ============================================================================
-- Migration 003: Esquema para el patron Transactional Outbox y Control de Replica
-- ============================================================================

-- 1. Agregar columna de confirmacion de sincronizacion a rate_snapshots
ALTER TABLE rate_snapshots ADD COLUMN synced_at TEXT DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_snapshots_synced_at ON rate_snapshots(synced_at);

-- 2. Metadatos de sincronizacion e identidad de nodo
CREATE TABLE IF NOT EXISTS sync_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Inicializar identificador unico y persistente de instancia/nodo
INSERT OR IGNORE INTO sync_metadata (key, value)
VALUES ('source_instance_id', 'inst_' || lower(hex(randomblob(8))));

-- 3. Tabla principal de eventos pendientes (Transactional Outbox)
CREATE TABLE IF NOT EXISTS sync_outbox (
    seq_id INTEGER PRIMARY KEY AUTOINCREMENT,    -- Secuencia monotonica local estricta
    event_id TEXT UNIQUE NOT NULL,              -- ID global estable: <source_instance_id>:<event_type>:<aggregate_id>:<local_id>
    event_type TEXT NOT NULL,                   -- 'RATE_SNAPSHOT', 'API_CLIENT_UPSERT', 'API_CLIENT_DELETE'
    aggregate_id TEXT NOT NULL,                 -- cycle_id para snapshots, client_id para clientes
    payload_json TEXT NOT NULL,                 -- Contenido serializado del evento
    status TEXT NOT NULL DEFAULT 'PENDING',      -- 'PENDING', 'SENT', 'FAILED'
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    processed_at TEXT DEFAULT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending_seq ON sync_outbox(status, seq_id ASC);

-- 4. Backfill: Encolar snapshots historicos preexistentes en SQLite
INSERT OR IGNORE INTO sync_outbox (event_id, event_type, aggregate_id, payload_json, status, created_at)
SELECT
    (SELECT value FROM sync_metadata WHERE key = 'source_instance_id') || ':RATE_SNAPSHOT:' || cycle_id || ':' || id,
    'RATE_SNAPSHOT',
    CAST(cycle_id AS TEXT),
    json_object(
        'local_id', id,
        'cycle_id', cycle_id,
        'captured_at', captured_at,
        'duration_ms', duration_ms,
        'is_stale', is_stale,
        'status', status,
        'fiat', fiat,
        'asset', asset,
        'buy_reference_price', buy_reference_price,
        'buy_average_price', buy_average_price,
        'buy_median_price', buy_median_price,
        'buy_min_price', buy_min_price,
        'buy_max_price', buy_max_price,
        'buy_valid_count', buy_valid_count,
        'buy_discarded_count', buy_discarded_count,
        'buy_total_received', buy_total_received,
        'buy_details_json', buy_details_json,
        'sell_reference_price', sell_reference_price,
        'sell_average_price', sell_average_price,
        'sell_median_price', sell_median_price,
        'sell_min_price', sell_min_price,
        'sell_max_price', sell_max_price,
        'sell_valid_count', sell_valid_count,
        'sell_discarded_count', sell_discarded_count,
        'sell_total_received', sell_total_received,
        'sell_details_json', sell_details_json
    ),
    'PENDING',
    captured_at
FROM rate_snapshots
WHERE synced_at IS NULL;

-- 5. Backfill: Encolar clientes preexistentes en SQLite (api_clients)
INSERT OR IGNORE INTO sync_outbox (event_id, event_type, aggregate_id, payload_json, status, created_at)
SELECT
    (SELECT value FROM sync_metadata WHERE key = 'source_instance_id') || ':API_CLIENT_UPSERT:' || client_id || ':' || id,
    'API_CLIENT_UPSERT',
    client_id,
    json_object(
        'client_id', client_id,
        'client_name', client_name,
        'api_key_hash', api_key_hash,
        'role', role,
        'rate_limit_rpm', rate_limit_rpm,
        'is_active', is_active,
        'created_at', created_at
    ),
    'PENDING',
    created_at
FROM api_clients;
