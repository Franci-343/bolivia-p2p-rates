-- ============================================================================
-- Migration 001: Esquema inicial para almacenamiento de snapshots de tasas
-- ============================================================================

-- Tabla de control de versiones de migraciones
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    script_name TEXT NOT NULL,
    applied_at TEXT NOT NULL DEFAULT (datetime('now', 'utc'))
);

-- Tabla principal de snapshots agregados
CREATE TABLE IF NOT EXISTS rate_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    cycle_id INTEGER NOT NULL,
    captured_at TEXT NOT NULL,           -- Timestamp ISO-8601 UTC (ej. 2026-11-02T21:00:00Z)
    duration_ms INTEGER NOT NULL,
    is_stale INTEGER NOT NULL DEFAULT 0, -- 0 = false, 1 = true
    status TEXT NOT NULL,                -- 'OK', 'STALE'
    fiat TEXT NOT NULL DEFAULT 'BOB',
    asset TEXT NOT NULL DEFAULT 'USDT',

    -- Mercado de COMPRA (Usuario compra USDT con BOB)
    buy_reference_price TEXT NOT NULL,   -- Almacenado como TEXT para exactitud BigDecimal
    buy_average_price TEXT NOT NULL,
    buy_median_price TEXT NOT NULL,
    buy_min_price TEXT NOT NULL,
    buy_max_price TEXT NOT NULL,
    buy_valid_count INTEGER NOT NULL,
    buy_discarded_count INTEGER NOT NULL,
    buy_total_received INTEGER NOT NULL,
    buy_details_json TEXT NOT NULL,      -- JSON con lista de bancos y top offers

    -- Mercado de VENTA (Usuario vende USDT recibiendo BOB)
    sell_reference_price TEXT NOT NULL,
    sell_average_price TEXT NOT NULL,
    sell_median_price TEXT NOT NULL,
    sell_min_price TEXT NOT NULL,
    sell_max_price TEXT NOT NULL,
    sell_valid_count INTEGER NOT NULL,
    sell_discarded_count INTEGER NOT NULL,
    sell_total_received INTEGER NOT NULL,
    sell_details_json TEXT NOT NULL       -- JSON con lista de bancos y top offers
);

-- Índices para búsquedas cronológicas ultra-rápidas
CREATE INDEX IF NOT EXISTS idx_snapshots_captured_at ON rate_snapshots(captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_snapshots_cycle_id ON rate_snapshots(cycle_id DESC);
