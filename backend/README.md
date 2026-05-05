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
| `PUT` | `/admin/config/:env` | Bearer | Admin lädt neue XML hoch |
| `GET` | `/admin/config/:env` | Bearer | Admin liest aktuelle XML |
| `POST` | `/admin/hash-pin` | Bearer | Wandelt PIN in SHA-256-Hash |
| `GET` | `/health` | – | Healthcheck |

## PIN-Hash erzeugen
```bash
curl -X POST "$KIOSK_API_BASE/admin/hash-pin" \
  -H "Authorization: Bearer $KIOSK_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"pin":"1234"}'
# {"pin":"1234","hash":"03ac674216..."}
```

Den Hash in `<admin><pinHash>...</pinHash></admin>` der XML eintragen.
