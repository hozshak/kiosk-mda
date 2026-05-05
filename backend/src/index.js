import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { configRoutes } from './routes/config.js';
import { adminRoutes } from './routes/admin.js';

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

app.notFound((c) => c.json({ error: 'not_found' }, 404));

app.onError((err, c) => {
  console.error('Worker error:', err);
  return c.json({ error: 'internal', message: err.message }, 500);
});

export default app;
