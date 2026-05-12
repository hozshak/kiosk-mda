#!/bin/bash
# Install-Skript fuer Linux-Server (Debian/Ubuntu).
# Aufruf als root:
#   sudo bash install.sh
#
# Voraussetzungen: Node.js 18+ installiert (oder Skript installiert automatisch via nodesource)

set -e

INSTALL_DIR="/opt/kiosk-mda"
SERVICE_USER="kiosk"
ENV_FILE="/etc/kiosk-mda.env"
PORT="${PORT:-8989}"

echo "==> Kiosk-MDA-Server Installation"

# Node.js installieren falls fehlt
if ! command -v node >/dev/null; then
    echo "Node.js nicht gefunden, installiere via NodeSource..."
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y nodejs
fi

NODE_VERSION=$(node -v)
echo "Node: $NODE_VERSION"

# User anlegen
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
    useradd --system --shell /usr/sbin/nologin --home "$INSTALL_DIR" --create-home "$SERVICE_USER"
    echo "User '$SERVICE_USER' angelegt"
fi

# Verzeichnis vorbereiten
mkdir -p "$INSTALL_DIR/server"
cp -r ./src ./public ./package.json "$INSTALL_DIR/server/"

# Build-Tools für native modules
apt-get install -y python3 make g++ build-essential || true

# Dependencies installieren
cd "$INSTALL_DIR/server"
npm install --omit=dev

# Data-Verzeichnis
mkdir -p "$INSTALL_DIR/server/data"
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

# Env-File anlegen falls fehlt
if [ ! -f "$ENV_FILE" ]; then
    PASS=$(openssl rand -base64 18 | tr -d '/+=' | head -c 20)
    cat > "$ENV_FILE" <<EOF
PORT=$PORT
HOST=0.0.0.0
DATA_DIR=$INSTALL_DIR/server/data
ADMIN_USER=admin
ADMIN_PASSWORD=$PASS
EOF
    chmod 600 "$ENV_FILE"
    echo ""
    echo "==> Admin-Passwort generiert: $PASS"
    echo "    (gespeichert in $ENV_FILE)"
fi

# systemd Service installieren
cp ./deploy/kiosk-mda.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable kiosk-mda
systemctl restart kiosk-mda

# Firewall (falls ufw installiert)
if command -v ufw >/dev/null; then
    ufw allow $PORT/tcp || true
fi

sleep 2
systemctl status kiosk-mda --no-pager || true

IP=$(hostname -I | awk '{print $1}')
echo ""
echo "==> Installation fertig"
echo ""
echo "Web-Admin:   http://$IP:$PORT/admin/"
echo "Login:       admin / (siehe $ENV_FILE)"
echo "Geräte-API:  http://$IP:$PORT/config/{test|prod}"
echo "WebSocket:   ws://$IP:$PORT/ws/{test|prod}"
echo ""
echo "Logs:        journalctl -u kiosk-mda -f"
echo "Restart:     systemctl restart kiosk-mda"
