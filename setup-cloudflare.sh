#!/bin/bash
# Einmal-Setup für Cloudflare-Backend.
# Usage: bash setup-cloudflare.sh
#
# Voraussetzungen:
# - Node.js installiert
# - Cloudflare-Account mit aktiviertem R2 (Kreditkarte hinterlegt, kostenlos)

set -e

cd "$(dirname "$0")/backend"

echo "=== Schritt 1: Wrangler-Login (Browser öffnet sich) ==="
npx --yes wrangler login

echo ""
echo "=== Schritt 2: R2 Bucket anlegen ==="
npx wrangler r2 bucket create kiosk-mda-configs || echo "  (Bucket existiert bereits, ok)"

echo ""
echo "=== Schritt 3: KV Namespace anlegen ==="
KV_OUTPUT=$(npx wrangler kv namespace create ETAGS 2>&1)
echo "$KV_OUTPUT"

KV_ID=$(echo "$KV_OUTPUT" | grep -oE 'id = "[^"]+"' | head -1 | sed 's/id = "//;s/"//')

if [ -n "$KV_ID" ]; then
  echo ""
  echo "  → KV-ID gefunden: $KV_ID"
  echo "  → wrangler.toml wird aktualisiert"
  sed -i.bak "s/ERSETZEN_NACH_NAMESPACE_CREATE/$KV_ID/" wrangler.toml
  rm -f wrangler.toml.bak
else
  echo "  ⚠️  KV-ID nicht automatisch gefunden — bitte manuell in wrangler.toml eintragen"
fi

echo ""
echo "=== Schritt 4: Admin-Token generieren ==="
TOKEN=$(node -e "console.log(require('crypto').randomBytes(32).toString('hex'))")
echo "  → Token (BITTE KOPIEREN UND SICHER ABLEGEN):"
echo "  $TOKEN"
echo ""
echo "$TOKEN" | npx wrangler secret put ADMIN_TOKEN

echo ""
echo "=== Schritt 5: Worker deployen ==="
npx wrangler deploy

echo ""
echo "=== Schritt 6: Initial-Config hochladen (test + prod) ==="

# Worker-URL aus deploy-output holen
WORKER_URL=$(npx wrangler deployments list 2>&1 | grep -oE 'https://[^ ]+\.workers\.dev' | head -1)

if [ -z "$WORKER_URL" ]; then
  echo "  ⚠️  Worker-URL nicht automatisch gefunden — bitte manuell setzen:"
  echo "     export KIOSK_API_BASE=\"https://kiosk-mda.<dein-account>.workers.dev\""
  echo "     export KIOSK_ADMIN_TOKEN=\"$TOKEN\""
  echo "     node scripts/upload-config.mjs prod sample-config.xml"
  echo "     node scripts/upload-config.mjs test sample-config.xml"
else
  echo "  → Worker erreichbar unter: $WORKER_URL"
  export KIOSK_API_BASE="$WORKER_URL"
  export KIOSK_ADMIN_TOKEN="$TOKEN"

  node scripts/upload-config.mjs prod sample-config.xml
  node scripts/upload-config.mjs test sample-config.xml
fi

echo ""
echo "=== ✅ FERTIG ==="
echo ""
echo "Worker-URL:    $WORKER_URL"
echo "Admin-Token:   $TOKEN"
echo "Test-Config:   curl $WORKER_URL/config/prod"
echo ""
echo "Nächste Schritte:"
echo "1. android/app/build.gradle.kts: DEFAULT_CONFIG_URL anpassen auf:"
echo "     $WORKER_URL/config/prod"
echo "2. backend/sample-config.xml: <configUrl> auf $WORKER_URL/config/prod setzen"
echo "3. PIN setzen via:"
echo "     curl -X POST $WORKER_URL/admin/hash-pin \\"
echo "       -H 'Authorization: Bearer $TOKEN' \\"
echo "       -H 'Content-Type: application/json' \\"
echo "       -d '{\"pin\":\"DEINE_PIN\"}'"
