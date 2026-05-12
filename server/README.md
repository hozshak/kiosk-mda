# Kiosk-MDA Self-Hosted Server

Node.js-Server für **lokale** Verwaltung der Kiosk-MDA-Geräte ohne Cloudflare.

## Was er kann

- **Web-Admin-UI** (`/admin/`) – Bookmarks editieren, Start-URL setzen, PIN festlegen, Geräte sehen
- **XML-Config-API** (`GET /config/{env}`) – Geräte ziehen Config (mit ETag-Caching)
- **WebSocket-Push** (`WS /ws/{env}`) – Sofort-Push bei Config-Änderungen
- **Geräte-Tracking** – jedes Gerät registriert sich beim ersten WebSocket-Connect
- **SQLite** – einzelne Datei `data/kiosk.db`, kein DB-Server nötig

## Schnellster Weg: Docker

```bash
cd /opt
git clone https://github.com/hozshak/kiosk-mda.git
cd kiosk-mda/server

# Admin-Passwort setzen
echo "ADMIN_PASSWORD=$(openssl rand -base64 18)" > .env
echo "ADMIN_USER=admin" >> .env
cat .env  # ← Passwort merken!

docker compose up -d
docker compose logs -f
```

Erreichbar: `http://192.168.115.177:3000/admin/`

## Variante 2: Native (systemd)

```bash
cd /opt
git clone https://github.com/hozshak/kiosk-mda.git
cd kiosk-mda/server
sudo bash deploy/install.sh
```

Das installiert:
- Node.js 20 (falls nicht da)
- System-User `kiosk`
- App nach `/opt/kiosk-mda/server`
- Env-File `/etc/kiosk-mda.env` mit zufälligem Admin-Passwort
- systemd-Service `kiosk-mda`

## Variante 3: Manuell (Dev)

```bash
cd server
npm install
ADMIN_PASSWORD=geheim npm start
```

## Geräte-Anbindung

In der Kiosk-MDA-App im Admin-Menü:
1. **Config-URL überschreiben** → `http://192.168.115.177:3000/config/prod`
2. **Speichern**
3. App holt beim nächsten Sync die Config vom Server
4. WebSocket-Push wird automatisch geöffnet auf `ws://192.168.115.177:3000/ws/prod`

## Verzeichnisstruktur

```
server/
├── src/
│   ├── server.js          # Entry, HTTP+WS init
│   ├── routes.js          # Hono-Routen
│   ├── ws.js              # WebSocket pro Environment
│   ├── storage.js         # XML-Dateien lesen/schreiben mit ETag-Cache
│   ├── db.js              # SQLite (Geräte, Audit-Log)
│   └── auth.js            # Login + Session
├── public/                # Statisches Admin-UI (HTML/CSS/JS)
├── data/                  # XML-Configs + SQLite (Bind-Mount in Docker)
├── deploy/
│   ├── install.sh         # Native Linux-Install
│   └── kiosk-mda.service  # systemd Unit
├── Dockerfile
├── docker-compose.yml
└── package.json
```

## API

### Public (für Geräte)
| Methode | Pfad | Zweck |
|---|---|---|
| `GET` | `/health` | Healthcheck |
| `GET` | `/config/:env` | XML mit ETag-Caching |
| `WS` | `/ws/:env` | Push-Verbindung |

### Admin (Bearer-Token oder Basic-Auth oder Session-Cookie)
| Methode | Pfad | Zweck |
|---|---|---|
| `POST` | `/api/login` | Login mit user/password |
| `POST` | `/api/logout` | Session beenden |
| `GET` | `/api/me` | Aktueller User |
| `GET` | `/api/envs` | Liste Environments + WS-Stats |
| `GET` | `/api/config/:env` | XML lesen |
| `PUT` | `/api/config/:env` | XML schreiben + auto-broadcast |
| `POST` | `/api/config/:env/push` | Manueller Push ohne Änderung |
| `POST` | `/api/hash-pin` | PIN zu SHA-256-Hex |
| `GET` | `/api/devices` | Liste registrierter Geräte |

## Sicherheit

- Login via SHA-256 password hash + Timing-safe Compare
- Session-Cookie HttpOnly, 24h Laufzeit
- PIN-Hash für Geräte SHA-256, nie Klartext
- **TLS:** für Produktion HTTPS via nginx-Proxy davorschalten (siehe `nginx-example.conf`)

## TLS-Proxy mit nginx (optional)

```nginx
server {
    listen 443 ssl http2;
    server_name kiosk.firma.intern;
    ssl_certificate     /etc/letsencrypt/live/kiosk/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/kiosk/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400s;
    }
}
```

Geräte verbinden dann auf `https://kiosk.firma.intern/config/prod` und `wss://kiosk.firma.intern/ws/prod`.

## Logs

```bash
# Docker
docker compose logs -f

# systemd
journalctl -u kiosk-mda -f
```

## Backup

Nur das Verzeichnis `data/` sichern – enthält:
- `data/configs/test.xml` + `data/configs/prod.xml`
- `data/kiosk.db` (Geräte-Liste + Audit-Log)
