import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { configRoutes } from './routes/config.js';
import { adminRoutes } from './routes/admin.js';

export { ConfigBroadcaster } from './durable-objects/ConfigBroadcaster.js';

const app = new Hono();

app.use('*', cors({
  origin: '*',
  allowMethods: ['GET', 'PUT', 'POST', 'DELETE', 'OPTIONS'],
  allowHeaders: ['Content-Type', 'Authorization', 'If-None-Match'],
  exposeHeaders: ['ETag'],
}));

app.get('/', (c) =>
  c.json({
    service: 'kiosk-mda-config',
    version: '1.0.0',
    environments: ['test', 'prod'],
  })
);

app.get('/health', (c) => c.text('ok'));

app.route('/config', configRoutes);
app.route('/admin', adminRoutes);

// WebSocket-Endpoint pro Environment
const VALID_ENVS = new Set(['test', 'prod']);
app.get('/ws/:env', (c) => {
  const env = c.req.param('env');
  if (!VALID_ENVS.has(env)) {
    return c.json({ error: 'invalid_environment' }, 400);
  }
  const id = c.env.CONFIG_BROADCASTER.idFromName(env);
  const stub = c.env.CONFIG_BROADCASTER.get(id);
  return stub.fetch(new Request('https://internal/ws', c.req.raw));
});

app.get('/ws/:env/stats', (c) => {
  const env = c.req.param('env');
  if (!VALID_ENVS.has(env)) {
    return c.json({ error: 'invalid_environment' }, 400);
  }
  const id = c.env.CONFIG_BROADCASTER.idFromName(env);
  const stub = c.env.CONFIG_BROADCASTER.get(id);
  return stub.fetch(new Request('https://internal/stats'));
});

app.notFound((c) => c.json({ error: 'not_found' }, 404));

app.onError((err, c) => {
  console.error('Worker error:', err);
  return c.json({ error: 'internal', message: err.message }, 500);
});

export default app;
