import { Hono } from 'hono';
import { readConfig, writeConfig, listEnvs } from './storage.js';
import { broadcast, stats, allStats } from './ws.js';
import { requireAuth, verifyCredentials, createSession, destroySession } from './auth.js';
import { listDevices, logAudit } from './db.js';
import { createHash } from 'node:crypto';

const VALID_ENVS = new Set(['test', 'prod']);

export function buildRouter() {
  const app = new Hono();

  // ===== Public: Health =====
  app.get('/health', (c) => c.text('ok'));
  app.get('/', (c) => c.json({
    service: 'kiosk-mda-server',
    version: '1.0.0',
    envs: [...VALID_ENVS],
    stats: allStats(),
  }));

  // ===== Public: Geräte holen Config =====
  app.get('/config/:env', (c) => {
    const env = c.req.param('env');
    if (!VALID_ENVS.has(env)) return c.json({ error: 'invalid_env' }, 400);

    const entry = readConfig(env);
    if (!entry) return c.json({ error: 'config_not_found', env }, 404);

    const ifNoneMatch = c.req.header('If-None-Match');
    if (ifNoneMatch && ifNoneMatch === entry.etag) {
      return new Response(null, { status: 304, headers: { ETag: entry.etag } });
    }
    return new Response(entry.xml, {
      status: 200,
      headers: {
        'Content-Type': 'application/xml; charset=utf-8',
        'Cache-Control': 'no-cache, must-revalidate',
        ETag: entry.etag,
      },
    });
  });

  app.get('/ws/:env/stats', (c) => {
    const env = c.req.param('env');
    if (!VALID_ENVS.has(env)) return c.json({ error: 'invalid_env' }, 400);
    return c.json(stats(env));
  });

  // ===== Admin: Login =====
  app.post('/api/login', async (c) => {
    const body = await c.req.json().catch(() => ({}));
    if (!verifyCredentials(body.user, body.password)) {
      return c.json({ error: 'invalid_credentials' }, 401);
    }
    const token = createSession(body.user);
    return new Response(JSON.stringify({ ok: true, token }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
        'Set-Cookie': `session=${token}; HttpOnly; Path=/; Max-Age=86400; SameSite=Lax`,
      },
    });
  });

  app.post('/api/logout', (c) => {
    const cookie = c.req.header('Cookie') || '';
    const match = cookie.match(/session=([^;]+)/);
    if (match) destroySession(match[1]);
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
        'Set-Cookie': 'session=; HttpOnly; Path=/; Max-Age=0',
      },
    });
  });

  // ===== Admin API (protected) =====
  const api = new Hono();
  api.use('*', requireAuth);

  api.get('/me', (c) => c.json({ user: c.get('user') }));

  api.get('/envs', (c) => c.json({ envs: listEnvs(), stats: allStats() }));

  api.get('/config/:env', (c) => {
    const env = c.req.param('env');
    if (!VALID_ENVS.has(env)) return c.json({ error: 'invalid_env' }, 400);
    const entry = readConfig(env);
    if (!entry) return c.json({ xml: '', etag: null });
    return c.json({ xml: entry.xml, etag: entry.etag });
  });

  api.put('/config/:env', async (c) => {
    const env = c.req.param('env');
    if (!VALID_ENVS.has(env)) return c.json({ error: 'invalid_env' }, 400);

    const ct = c.req.header('Content-Type') || '';
    let xml;
    if (ct.includes('application/json')) {
      const body = await c.req.json();
      xml = body.xml;
    } else {
      xml = await c.req.text();
    }
    if (!xml || !xml.trim().startsWith('<')) {
      return c.json({ error: 'invalid_xml' }, 400);
    }
    const entry = writeConfig(env, xml);
    logAudit(c.get('user'), 'config_update', { env, bytes: xml.length });

    const delivered = broadcast(env, { type: 'config-updated', env, ts: Date.now() });
    return c.json({ ok: true, env, bytes: xml.length, etag: entry.etag, delivered });
  });

  api.post('/config/:env/push', (c) => {
    const env = c.req.param('env');
    if (!VALID_ENVS.has(env)) return c.json({ error: 'invalid_env' }, 400);
    const delivered = broadcast(env, { type: 'config-updated', env, ts: Date.now() });
    return c.json({ ok: true, delivered });
  });

  api.post('/hash-pin', async (c) => {
    const body = await c.req.json().catch(() => ({}));
    const pin = String(body.pin || '');
    if (!pin) return c.json({ error: 'missing_pin' }, 400);
    const hash = createHash('sha256').update(pin).digest('hex');
    return c.json({ pin, hash });
  });

  api.get('/devices', (c) => {
    return c.json({ devices: listDevices() });
  });

  app.route('/api', api);

  return app;
}
