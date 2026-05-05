import { Hono } from 'hono';

export const adminRoutes = new Hono();

const VALID_ENVS = new Set(['test', 'prod']);
const objectKey = (env) => `configs/${env}.xml`;
const etagKey = (env) => `etag:${env}`;

/**
 * Bearer-Token-Check. Token wird via wrangler secret gesetzt:
 *   echo "..." | npx wrangler secret put ADMIN_TOKEN
 */
async function requireAdmin(c) {
  const token = c.env.ADMIN_TOKEN;
  if (!token) {
    return c.json({ error: 'admin_token_not_configured' }, 500);
  }
  const auth = c.req.header('Authorization') || '';
  const provided = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (provided !== token) {
    return c.json({ error: 'unauthorized' }, 401);
  }
  return null;
}

/**
 * PUT /admin/config/:env
 * Body: rohe XML. Speichert in R2, invalidiert ETag.
 */
adminRoutes.put('/config/:env', async (c) => {
  const auth = await requireAdmin(c);
  if (auth) return auth;

  const env = c.req.param('env');
  if (!VALID_ENVS.has(env)) {
    return c.json({ error: 'invalid_environment' }, 400);
  }

  const xml = await c.req.text();
  if (!xml || !xml.trim().startsWith('<')) {
    return c.json({ error: 'invalid_xml_body' }, 400);
  }

  await c.env.CONFIGS.put(objectKey(env), xml, {
    httpMetadata: { contentType: 'application/xml; charset=utf-8' },
  });
  await c.env.ETAGS.delete(etagKey(env));

  return c.json({ ok: true, env, bytes: xml.length });
});

/**
 * GET /admin/config/:env (mit Auth - für Verifikation aus Admin-UI)
 */
adminRoutes.get('/config/:env', async (c) => {
  const auth = await requireAdmin(c);
  if (auth) return auth;

  const env = c.req.param('env');
  if (!VALID_ENVS.has(env)) {
    return c.json({ error: 'invalid_environment' }, 400);
  }

  const obj = await c.env.CONFIGS.get(objectKey(env));
  if (!obj) {
    return c.json({ error: 'config_not_found' }, 404);
  }

  return new Response(await obj.text(), {
    status: 200,
    headers: { 'Content-Type': 'application/xml; charset=utf-8' },
  });
});

/**
 * POST /admin/hash-pin
 * Body: { "pin": "1234" }
 * Gibt SHA-256-Hex zurück damit Admin den Wert in die XML einfügen kann.
 */
adminRoutes.post('/hash-pin', async (c) => {
  const auth = await requireAdmin(c);
  if (auth) return auth;

  const body = await c.req.json().catch(() => ({}));
  const pin = String(body.pin ?? '');
  if (!pin) return c.json({ error: 'missing_pin' }, 400);

  const data = new TextEncoder().encode(pin);
  const digest = await crypto.subtle.digest('SHA-256', data);
  const hex = Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');

  return c.json({ pin, hash: hex });
});
