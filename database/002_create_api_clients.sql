-- ============================================================================
-- Migration 002: Esquema para control de clientes y API Keys
-- ============================================================================

CREATE TABLE IF NOT EXISTS api_clients (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id TEXT UNIQUE NOT NULL,             -- Identificador legible único (ej. 'demo_app')
    client_name TEXT NOT NULL,                  -- Nombre del cliente o desarrollador
    api_key_hash TEXT NOT NULL,                 -- Hash SHA-256 de la API Key en minúsculas
    role TEXT NOT NULL DEFAULT 'CONSUMER',      -- 'CONSUMER' (solo lectura) o 'ADMIN' (refresh permitido)
    rate_limit_rpm INTEGER NOT NULL DEFAULT 60, -- Cuota máxima de peticiones por minuto
    is_active INTEGER NOT NULL DEFAULT 1,       -- 1 = Activo, 0 = Revocado
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'utc'))
);

CREATE INDEX IF NOT EXISTS idx_api_clients_hash ON api_clients(api_key_hash);

-- Datos de ejemplo iniciales (Semillas)
-- Clave de prueba CONSUMER: 'bolivia_live_demo12345'
-- Hash SHA-256: 033a630338bf54be1fe195daf2b0b315b2db559ba0d89a1b8d897e85e5120b13
INSERT OR IGNORE INTO api_clients (client_id, client_name, api_key_hash, role, rate_limit_rpm)
VALUES (
    'client_demo_bolivia',
    'Aplicación Demo Desarrollador',
    '033a630338bf54be1fe195daf2b0b315b2db559ba0d89a1b8d897e85e5120b13',
    'CONSUMER',
    60
);

-- Clave de prueba ADMIN: 'bolivia_admin_master999'
-- Hash SHA-256: b4867b65385f3aefeab49ec41833d8b9bd06a7fdb5dca70aa385aa3c1488cfac
INSERT OR IGNORE INTO api_clients (client_id, client_name, api_key_hash, role, rate_limit_rpm)
VALUES (
    'admin_local_system',
    'Administrador del Sistema',
    'b4867b65385f3aefeab49ec41833d8b9bd06a7fdb5dca70aa385aa3c1488cfac',
    'ADMIN',
    600
);
