import { serve } from '@hono/node-server';
import { Hono } from 'hono';
import { initDb } from './db.js';
import { initStorage, readConfig, writeConfig } from './storage.js';
import { initAuth } from './auth.js';
import { initWebSocket } from './ws.js';
import { initApkStore } from './apk.js';
import { buildRouter } from './routes.js';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve, extname } from 'node:path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const SERVER_ROOT = resolve(__dirname, '..');

const PORT = Number(process.env.PORT || 8989);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = resolve(process.env.DATA_DIR || join(SERVER_ROOT, 'data'));
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin';

if (ADMIN_PASSWORD === 'admin') {
  console.warn('⚠️  ADMIN_PASSWORD nicht gesetzt - nutze unsicheres Default "admin". Setze in .env oder als Env-Var.');
}

mkdirSync(DATA_DIR, { recursive: true });
initDb(join(DATA_DIR, 'kiosk.db'));
initStorage(DATA_DIR);
initApkStore(DATA_DIR);
initAuth({ user: ADMIN_USER, password: ADMIN_PASSWORD });

// Default-Configs anlegen falls leer
for (const env of ['test', 'prod']) {
  if (!readConfig(env)) {
    writeConfig(env, defaultConfigXml(env));
    console.log(`Initial config created for env=${env}`);
  }
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const app = new Hono();

// Statisches Admin-UI unter /admin/*
const publicDir = join(SERVER_ROOT, 'public');
app.get('/admin', (c) => c.redirect('/admin/'));
app.get('/admin/', (c) => serveFile(c, join(publicDir, 'index.html')));
app.get('/admin/*', (c) => {
  const rel = c.req.path.replace(/^\/admin\//, '');
  const file = join(publicDir, rel);
  if (file.indexOf(publicDir) !== 0) return c.notFound();
  return serveFile(c, file);
});

const api = buildRouter();
app.route('/', api);

function serveFile(c, file) {
  if (!existsSync(file)) return c.notFound();
  const data = readFileSync(file);
  const ext = extname(file).toLowerCase();
  const mime = MIME[ext] || 'application/octet-stream';
  return new Response(data, {
    status: 200,
    headers: { 'Content-Type': mime, 'Cache-Control': 'no-cache' },
  });
}

const httpServer = serve(
  { fetch: app.fetch, port: PORT, hostname: HOST },
  (info) => {
    console.log(`Kiosk-MDA-Server läuft`);
    console.log(`  Web-Admin:   http://${info.address}:${info.port}/admin/`);
    console.log(`  Geräte API:  http://${info.address}:${info.port}/config/{test|prod}`);
    console.log(`  WebSocket:   ws://${info.address}:${info.port}/ws/{test|prod}`);
    console.log(`  Data Dir:    ${DATA_DIR}`);
    console.log(`  Login:       ${ADMIN_USER} / ${ADMIN_PASSWORD === 'admin' ? 'admin (UNSICHER!)' : '***'}`);
  }
);

initWebSocket(httpServer);

function defaultConfigXml(env) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<config version="1" environment="${env}">
    <browser>
        <startUrl>https://duckduckgo.com</startUrl>
        <bookmarks>
            <bookmark name="DuckDuckGo" url="https://duckduckgo.com" />
            <bookmark name="Wikipedia" url="https://de.m.wikipedia.org" />
        </bookmarks>
        <clearCacheOnExit>false</clearCacheOnExit>
        <javaScriptEnabled>true</javaScriptEnabled>
        <oskEnabled>false</oskEnabled>
        <oskToggleVisible>true</oskToggleVisible>
    </browser>
    <device>
        <orientation>auto</orientation>
        <displayTimeout>0</displayTimeout>
    </device>
    <admin>
        <pinHash></pinHash>
    </admin>
    <server>
        <configUrl></configUrl>
        <pollIntervalSec>900</pollIntervalSec>
    </server>
</config>
`;
}
