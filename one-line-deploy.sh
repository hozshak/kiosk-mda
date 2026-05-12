#!/bin/bash
# Komplett-Deployment Kiosk-MDA-Server in einem Schritt.
# Aufruf als root auf 192.168.115.177:
#   curl -fsSL https://raw.githubusercontent.com/hozshak/kiosk-mda/main/one-line-deploy.sh | sudo bash
# ODER (mit lokal-clone):
#   sudo bash one-line-deploy.sh

set -e

INSTALL_DIR="/opt/kiosk-mda"
PORT="${PORT:-8989}"

echo "==> Kiosk-MDA Komplett-Deployment"

# Voraussetzungen
apt-get update -qq
apt-get install -y -qq git curl ca-certificates openssl >/dev/null

# Docker installieren falls fehlt
if ! command -v docker >/dev/null; then
    echo "==> Docker wird installiert..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker
fi

# Repo holen / aktualisieren
if [ -d "$INSTALL_DIR/.git" ]; then
    echo "==> Repo aktualisieren..."
    git -C "$INSTALL_DIR" pull --rebase
else
    echo "==> Repo klonen..."
    git clone https://github.com/hozshak/kiosk-mda.git "$INSTALL_DIR"
fi

cd "$INSTALL_DIR/server"

# Admin-Passwort generieren falls noch keine .env
if [ ! -f .env ]; then
    PASS=$(openssl rand -base64 18 | tr -d '/+=' | head -c 20)
    cat > .env <<EOF
ADMIN_USER=admin
ADMIN_PASSWORD=$PASS
EOF
    chmod 600 .env
    echo "==> Admin-Passwort generiert: $PASS"
    echo "    (gespeichert in $INSTALL_DIR/server/.env)"
fi

# Firewall öffnen
if command -v ufw >/dev/null; then
    ufw allow $PORT/tcp >/dev/null 2>&1 || true
fi

# Container bauen + starten
echo "==> Docker Compose..."
docker compose up -d --build

sleep 5

# Healthcheck
IP=$(hostname -I | awk '{print $1}')
echo ""
if curl -fsS "http://localhost:$PORT/health" >/dev/null 2>&1; then
    echo "==> ✅ Server läuft"
    echo ""
    echo "Web-Admin:   http://$IP:$PORT/admin/"
    echo "Geräte-API:  http://$IP:$PORT/config/{test|prod}"
    echo "WebSocket:   ws://$IP:$PORT/ws/{test|prod}"
    echo ""
    echo "Login:       admin / (siehe $INSTALL_DIR/server/.env)"
    echo ""
    echo "Logs:        docker compose -f $INSTALL_DIR/server/docker-compose.yml logs -f"
    echo "Restart:     docker compose -f $INSTALL_DIR/server/docker-compose.yml restart"
else
    echo "==> ❌ Server antwortet nicht. Logs:"
    docker compose logs --tail=30
    exit 1
fi
