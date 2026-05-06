/**
 * Durable Object pro Environment (test, prod).
 * Hält offene WebSocket-Verbindungen und broadcastet "config-updated" wenn
 * Admin eine neue Config hochlädt.
 *
 * Endpoints (intern):
 *   GET  /ws         → upgrade zu WebSocket, hält offen, sendet bei Update
 *   POST /broadcast  → Admin pingt nach config update, alle Clients bekommen Nachricht
 *   GET  /stats      → Anzahl aktive Verbindungen (Debug)
 */
export class ConfigBroadcaster {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    /** @type {Set<WebSocket>} */
    this.sessions = new Set();
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === '/ws') {
      const upgrade = request.headers.get('Upgrade')?.toLowerCase();
      if (upgrade !== 'websocket') {
        return new Response('Expected WebSocket', { status: 400 });
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.handleSession(server);
      return new Response(null, { status: 101, webSocket: client });
    }

    if (url.pathname === '/broadcast' && request.method === 'POST') {
      const msg = await request.text();
      const sent = this.broadcast(msg);
      return Response.json({ ok: true, delivered: sent });
    }

    if (url.pathname === '/stats') {
      return Response.json({ connections: this.sessions.size });
    }

    return new Response('not_found', { status: 404 });
  }

  handleSession(ws) {
    ws.accept();
    this.sessions.add(ws);

    // Welcome-Message damit Client weiß die Connection steht
    try {
      ws.send(JSON.stringify({ type: 'connected', ts: Date.now() }));
    } catch {}

    ws.addEventListener('close', () => this.sessions.delete(ws));
    ws.addEventListener('error', () => this.sessions.delete(ws));

    // Optional: Ping/Pong via JSON heartbeat
    ws.addEventListener('message', (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data?.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
        }
      } catch {}
    });
  }

  broadcast(msg) {
    let count = 0;
    for (const ws of [...this.sessions]) {
      try {
        ws.send(msg);
        count++;
      } catch {
        this.sessions.delete(ws);
      }
    }
    return count;
  }
}
