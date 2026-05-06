# Kiosk-MDA Config Backend

Cloudflare Worker, der XML-Configs pro Environment (`test`, `prod`) in R2 hält und über HTTPS an die MDA-Geräte ausliefert.

## Setup

### 1. Cloudflare-Resourcen anlegen
```bash
npx wrangler login

# R2 Bucket
npx wrangler r2 bucket create kiosk-mda-configs

# KV Namespace für ETag-Cache
npx wrangler kv:namespace create ETAGS
# → ID in wrangler.toml unter [[kv_namespaces]] eintragen
```

### 2. Secrets setzen
```bash
# Generiere zufälliges Token
openssl rand -hex 32 | npx wrangler secret put ADMIN_TOKEN
```

### 3. Deployen
```bash
npm install
npx wrangler deploy
```

### 4. Erste Config hochladen
```bash
export KIOSK_API_BASE="https://kiosk-mda.<deine-subdomain>.workers.dev"
export KIOSK_ADMIN_TOKEN="<dein-token>"

node scripts/upload-config.mjs prod sample-config.xml
node scripts/upload-config.mjs test sample-config.xml
```

## API

| Methode | Pfad | Auth | Zweck |
|---|---|---|---|
| `GET` | `/config/:env` | – | Geräte holen Config (mit ETag-Caching) |
| `WS` | `/ws/:env` | – | WebSocket-Verbindung — Server pingt bei Config-Update |
| `GET` | `/ws/:env/stats` | – | Anzahl aktive Push-Verbindungen |
| `PUT` | `/admin/config/:env` | Bearer | Admin lädt neue XML hoch (broadcastet automatisch) |
| `GET` | `/admin/config/:env` | Bearer | Admin liest aktuelle XML |
| `POST` | `/admin/hash-pin` | Bearer | Wandelt PIN in SHA-256-Hash |
| `GET` | `/health` | – | Healthcheck |

## Push-Architektur

```
                    ┌─────────────────────────┐
                    │   Cloudflare Worker     │
                    │                         │
   PUT  /admin/...  │   ┌──────────────────┐  │   WS  /ws/prod
        ────────────┼─→ │ ConfigBroadcaster│ ←┼────────── Geräte (N)
   (Admin)          │   │ (Durable Object) │  │
                    │   └────────┬─────────┘  │
                    │            │ broadcast  │
                    │            ▼            │
                    │     [WebSocket-Sessions]│
                    └─────────────────────────┘

1. Admin pusht neue XML via PUT /admin/config/prod
2. Worker speichert in R2 + invalidiert ETag
3. Worker holt ConfigBroadcaster-DO für "prod"
4. DO sendet "config-updated" an alle offenen WebSockets
5. Geräte triggern sofortigen Pull via /config/prod
```

Verbundene Geräte sind über `GET /ws/:env/stats` einsehbar.

## PIN-Hash erzeugen
```bash
curl -X POST "$KIOSK_API_BASE/admin/hash-pin" \
  -H "Authorization: Bearer $KIOSK_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"pin":"1234"}'
# {"pin":"1234","hash":"03ac674216..."}
```

Den Hash in `<admin><pinHash>...</pinHash></admin>` der XML eintragen.
