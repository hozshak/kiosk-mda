import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

let db;

export function initDb(path) {
  mkdirSync(dirname(path), { recursive: true });
  db = new Database(path);
  db.pragma('journal_mode = WAL');
  db.pragma('foreign_keys = ON');

  db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      device_id   TEXT PRIMARY KEY,
      model       TEXT,
      app_version TEXT,
      android_sdk INTEGER,
      env         TEXT DEFAULT 'prod',
      ip          TEXT,
      first_seen  INTEGER NOT NULL,
      last_seen   INTEGER NOT NULL,
      connected   INTEGER DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS audit_log (
      id        INTEGER PRIMARY KEY AUTOINCREMENT,
      ts        INTEGER NOT NULL,
      actor     TEXT,
      action    TEXT,
      details   TEXT
    );

    CREATE INDEX IF NOT EXISTS idx_devices_env ON devices(env);
    CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen);
  `);

  return db;
}

export function getDb() {
  if (!db) throw new Error('DB not initialized');
  return db;
}

// === Devices ===

export function upsertDevice({ deviceId, model, appVersion, androidSdk, env, ip }) {
  const now = Date.now();
  const stmt = getDb().prepare(`
    INSERT INTO devices (device_id, model, app_version, android_sdk, env, ip, first_seen, last_seen, connected)
    VALUES (@device_id, @model, @app_version, @android_sdk, @env, @ip, @now, @now, 1)
    ON CONFLICT(device_id) DO UPDATE SET
      model        = COALESCE(@model, model),
      app_version  = COALESCE(@app_version, app_version),
      android_sdk  = COALESCE(@android_sdk, android_sdk),
      env          = COALESCE(@env, env),
      ip           = @ip,
      last_seen    = @now,
      connected    = 1
  `);
  stmt.run({
    device_id: deviceId,
    model: model || null,
    app_version: appVersion || null,
    android_sdk: androidSdk || null,
    env: env || null,
    ip: ip || null,
    now,
  });
}

export function markDeviceDisconnected(deviceId) {
  getDb().prepare('UPDATE devices SET connected = 0, last_seen = ? WHERE device_id = ?')
    .run(Date.now(), deviceId);
}

export function listDevices() {
  return getDb().prepare('SELECT * FROM devices ORDER BY last_seen DESC').all();
}

export function logAudit(actor, action, details) {
  getDb().prepare('INSERT INTO audit_log (ts, actor, action, details) VALUES (?, ?, ?, ?)')
    .run(Date.now(), actor, action, JSON.stringify(details || null));
}
