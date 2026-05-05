import { Hono } from 'hono';

export const configRoutes = new Hono();

const VALID_ENVS = new Set(['test', 'prod']);

const objectKey = (env) => `configs/${env}.xml`;
const etagKey = (env) => `etag:${env}`;

/**
 * GET /config/:env
 * Liefert die aktuelle Config-XML. Unterstützt If-None-Match → 304.
 * Public - keine Auth (Geräte fragen ohne Token).
 */
configRoutes.get('/:env', async (c) => {
  const env = c.req.param('env');
  if (!VALID_ENVS.has(env)) {
    return c.json({ error: 'invalid_environment' }, 400);
  }

  const cachedEtag = await c.env.ETAGS.get(etagKey(env));
  const ifNoneMatch = c.req.header('If-None-Match');

  if (cachedEtag && ifNoneMatch === cachedEtag) {
    return new Response(null, {
      status: 304,
      headers: { ETag: cachedEtag },
    });
  }

  const obj = await c.env.CONFIGS.get(objectKey(env));
  if (!obj) {
    return c.json({ error: 'config_not_found', env }, 404);
  }

  const xml = await obj.text();
  const etag = obj.httpEtag || obj.etag || cachedEtag;

  if (etag) {
    await c.env.ETAGS.put(etagKey(env), etag);
  }

  return new Response(xml, {
    status: 200,
    headers: {
      'Content-Type': 'application/xml; charset=utf-8',
      'Cache-Control': 'no-cache, must-revalidate',
      ETag: etag || '',
    },
  });
});
