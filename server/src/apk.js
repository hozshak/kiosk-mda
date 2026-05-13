/**
 * APK-Verwaltung: Upload, Versionsabfrage, Download.
 *
 * Storage: data/apk/
 *   - kiosk-mda-{versionCode}.apk
 *   - latest.json  ({ versionCode, versionName, fileName, sha256, size, uploadedAt })
 */
import { Hono } from 'hono';
import { mkdirSync, writeFileSync, existsSync, readFileSync, statSync, createReadStream, readdirSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { requireAuth } from './auth.js';
import { logAudit } from './db.js';
import { broadcast } from './ws.js';

let apkDir;
let metaPath;

export function initApkStore(dataDir) {
  apkDir = join(dataDir, 'apk');
  metaPath = join(apkDir, 'latest.json');
  mkdirSync(apkDir, { recursive: true });
}

export function readLatestMeta() {
  if (!existsSync(metaPath)) return null;
  try {
    return JSON.parse(readFileSync(metaPath, 'utf8'));
  } catch {
    return null;
  }
}

function writeLatestMeta(meta) {
  writeFileSync(metaPath, JSON.stringify(meta, null, 2));
}

/** Hilfs-Router (wird in routes.js gemounted). */
export function buildApkRouter() {
  const app = new Hono();

  // ===== Public: Devices fragen latest.json + APK ab =====
  app.get('/latest.json', (c) => {
    const meta = readLatestMeta();
    if (!meta) return c.json({ available: false });
    return c.json({ available: true, ...meta });
  });

  app.get('/:fileName{.+\\.apk}', (c) => {
    const fileName = c.req.param('fileName');
    if (fileName.includes('/') || fileName.includes('..')) {
      return c.json({ error: 'invalid_filename' }, 400);
    }
    const filePath = join(apkDir, fileName);
    if (!existsSync(filePath)) return c.json({ error: 'not_found' }, 404);
    const data = readFileSync(filePath);
    return new Response(data, {
      status: 200,
      headers: {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': String(data.length),
        'Content-Disposition': `attachment; filename="${fileName}"`,
        'Cache-Control': 'public, max-age=300',
      },
    });
  });

  return app;
}

/** Admin-Router (protected). */
export function buildApkAdminRouter() {
  const app = new Hono();
  app.use('*', requireAuth);

  app.get('/latest', (c) => {
    const meta = readLatestMeta();
    return c.json({ meta, files: listFiles() });
  });

  /**
   * POST /api/apk/upload
   * multipart/form-data:
   *   apk:          .apk-Datei
   *   versionCode:  Integer (Pflicht)
   *   versionName:  String (Pflicht)
   */
  app.post('/upload', async (c) => {
    let formData;
    try {
      formData = await c.req.formData();
    } catch (e) {
      return c.json({ error: 'invalid_multipart', message: e.message }, 400);
    }

    const apk = formData.get('apk');
    const versionCode = parseInt(formData.get('versionCode'), 10);
    const versionName = String(formData.get('versionName') || '').trim();

    if (!apk || typeof apk === 'string') {
      return c.json({ error: 'no_apk_file' }, 400);
    }
    if (!versionCode || !versionName) {
      return c.json({ error: 'missing_version', hint: 'versionCode (int) + versionName (string) required' }, 400);
    }

    const buf = Buffer.from(await apk.arrayBuffer());
    if (buf.length < 1024) {
      return c.json({ error: 'apk_too_small' }, 400);
    }

    // ZIP-Magic prüfen (APK ist ein ZIP)
    if (buf[0] !== 0x50 || buf[1] !== 0x4b) {
      return c.json({ error: 'not_an_apk', hint: 'file is not a zip/apk' }, 400);
    }

    const sha256 = createHash('sha256').update(buf).digest('hex');
    const fileName = `kiosk-mda-${versionCode}.apk`;
    const filePath = join(apkDir, fileName);
    writeFileSync(filePath, buf);

    const meta = {
      versionCode,
      versionName,
      fileName,
      sha256,
      size: buf.length,
      uploadedAt: new Date().toISOString(),
    };
    writeLatestMeta(meta);

    logAudit(c.get('user'), 'apk_upload', { versionCode, versionName, size: buf.length });

    // Broadcast an alle verbundenen Geräte (beide Envs)
    const payload = JSON.stringify({ type: 'apk-update', ...meta });
    let delivered = 0;
    delivered += broadcast('prod', JSON.parse(payload));
    delivered += broadcast('test', JSON.parse(payload));

    return c.json({ ok: true, meta, delivered });
  });

  app.delete('/:fileName{.+\\.apk}', (c) => {
    const fileName = c.req.param('fileName');
    if (fileName.includes('/') || fileName.includes('..')) {
      return c.json({ error: 'invalid_filename' }, 400);
    }
    const filePath = join(apkDir, fileName);
    if (!existsSync(filePath)) return c.json({ error: 'not_found' }, 404);
    unlinkSync(filePath);

    // Wenn das die Datei der latest.json war, latest.json löschen
    const meta = readLatestMeta();
    if (meta && meta.fileName === fileName) {
      unlinkSync(metaPath);
    }
    logAudit(c.get('user'), 'apk_delete', { fileName });
    return c.json({ ok: true });
  });

  return app;
}

function listFiles() {
  if (!existsSync(apkDir)) return [];
  return readdirSync(apkDir)
    .filter((f) => f.endsWith('.apk'))
    .map((f) => {
      const s = statSync(join(apkDir, f));
      return { fileName: f, size: s.size, mtime: s.mtime.toISOString() };
    })
    .sort((a, b) => b.mtime.localeCompare(a.mtime));
}
