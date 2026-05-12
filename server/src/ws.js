/**
 * WebSocket-Server für Geräte-Push.
 * Path: /ws/:env  (test | prod)
 *
 * Geräte senden initial: {"type":"hello","deviceId":"...","model":"...","appVersion":"..."}
 * Server broadcastet bei Config-Update: {"type":"config-updated","env":"prod","ts":...}
 */
import { WebSocketServer } from 'ws';
import { upsertDevice, markDeviceDisconnected } from './db.js';

const VALID_ENVS = new Set(['test', 'prod']);
/** @type {Map<string, Set<WebSocket>>}   env -> sessions */
const sessions = new Map();
/** @type {Map<WebSocket, {env: string, deviceId: string|null}>} */
const meta = new Map();

let wss;

export function initWebSocket(httpServer) {
  wss = new WebSocketServer({ noServer: true });

  httpServer.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const match = url.pathname.match(/^\/ws\/(test|prod)$/);
    if (!match) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }
    const env = match[1];
    wss.handleUpgrade(req, socket, head, (ws) => {
      handleConnection(ws, env, req);
    });
  });

  // Inaktive Sessions als disconnected markieren alle 30s
  setInterval(checkAlive, 30_000);
}

function handleConnection(ws, env, req) {
  const ip = req.socket.remoteAddress?.replace(/^::ffff:/, '') || 'unknown';
  if (!sessions.has(env)) sessions.set(env, new Set());
  sessions.get(env).add(ws);
  meta.set(ws, { env, deviceId: null, ip, alive: true });

  send(ws, { type: 'connected', env, ts: Date.now() });

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());
      handleMessage(ws, msg);
    } catch {}
  });

  ws.on('pong', () => {
    const m = meta.get(ws);
    if (m) m.alive = true;
  });

  ws.on('close', () => {
    const m = meta.get(ws);
    sessions.get(env)?.delete(ws);
    meta.delete(ws);
    if (m?.deviceId) markDeviceDisconnected(m.deviceId);
  });

  ws.on('error', () => {});
}

function handleMessage(ws, msg) {
  const m = meta.get(ws);
  if (!m) return;
  switch (msg.type) {
    case 'hello':
      m.deviceId = msg.deviceId;
      upsertDevice({
        deviceId: msg.deviceId,
        model: msg.model,
        appVersion: msg.appVersion,
        androidSdk: msg.androidSdk,
        env: m.env,
        ip: m.ip,
      });
      send(ws, { type: 'hello-ack', ts: Date.now() });
      break;
    case 'ping':
      send(ws, { type: 'pong', ts: Date.now() });
      break;
  }
}

function send(ws, obj) {
  try {
    ws.send(JSON.stringify(obj));
  } catch {}
}

function checkAlive() {
  for (const [ws, m] of meta) {
    if (!m.alive) {
      try { ws.terminate(); } catch {}
      continue;
    }
    m.alive = false;
    try { ws.ping(); } catch {}
  }
}

export function broadcast(env, payload) {
  if (!VALID_ENVS.has(env)) return 0;
  const set = sessions.get(env);
  if (!set) return 0;
  const msg = JSON.stringify(payload);
  let count = 0;
  for (const ws of set) {
    try {
      ws.send(msg);
      count++;
    } catch {}
  }
  return count;
}

export function stats(env) {
  return {
    env,
    connections: sessions.get(env)?.size || 0,
  };
}

export function allStats() {
  const out = {};
  for (const env of VALID_ENVS) {
    out[env] = sessions.get(env)?.size || 0;
  }
  return out;
}
