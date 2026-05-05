# Kiosk-MDA-Lite

Dedizierte Android-Kiosk-App für Zebra-MDA-Geräte mit zentraler XML-Konfiguration über Cloudflare Workers.

## Architektur

```
┌──────────────────┐    HTTPS GET    ┌─────────────────────┐
│  Zebra MDA       │ ──────────────▶ │ Cloudflare Worker   │
│  Kiosk-APK       │   /config/{env} │  + R2 (XML Storage) │
│  (Kotlin)        │ ◀────────────── │                     │
└──────────────────┘    XML-Config   └─────────────────────┘
        │
        ├─ HOME Launcher (Lock Task Mode)
        ├─ WebView (Chromium)
        ├─ Polling alle 5 Min ODER FCM-Push
        └─ PIN-geschütztes Admin-Menü
```

## Kern-Features

- **Launcher-Modus** – ersetzt Android-Home, kein Zugriff auf Settings/Statusleiste
- **Vollbild-WebView** – Chromium-basiert, kontrollierte Browser-Umgebung
- **XML-Config remote** – Test/Prod-Profile, Push-Update ohne Reboot
- **StageNow-Ready** – kompatibel mit Zebra MX-Profile-Provisioning
- **Admin-PIN** – verstecktes Exit-Menü mit SHA-256-PIN

## Verzeichnisse

| Pfad | Inhalt |
|---|---|
| `android/` | Kotlin-Android-App (Gradle) |
| `backend/` | Cloudflare Workers (Hono) für XML-Config |
| `stagenow/` | Zebra StageNow MX-Profile für Erstinstallation |
| `.github/workflows/` | APK-Build-Pipeline |

## Quick Start

### 1. Backend deployen
```bash
cd backend
npm install
npx wrangler login
npx wrangler deploy
```

### 2. Android-App bauen
```bash
cd android
./gradlew assembleRelease
# APK liegt unter app/build/outputs/apk/release/app-release.apk
```

### 3. Auf Zebra-Gerät installieren

#### Variante A – ADB (Entwicklung)
```bash
adb install -r app-release.apk
adb shell dpm set-device-owner com.kiosk.mda/.admin.KioskDeviceAdminReceiver
```

#### Variante B – StageNow (Produktion)
1. Profile `stagenow/kiosk-staging-profile.xml` in StageNow importieren
2. Barcode generieren, mit Zebra-Gerät scannen
3. Gerät installiert APK + setzt Device-Owner automatisch

## Config-XML-Schema

Siehe [backend/migrations/0001_initial.sql](backend/migrations/0001_initial.sql) und [Beispiel-Config](backend/sample-config.xml).

## Entwicklungs-Workflow

1. XML-Config anpassen → via `PUT /config/{env}` an Backend
2. Geräte holen Update beim nächsten Poll (max 5 Min) oder via FCM-Push sofort
3. Bei App-Update: APK über StageNow oder MDM verteilen

## Sicherheit

- PIN wird **nur als SHA-256-Hash** in XML übertragen, nie im Klartext
- HTTPS-only (Cloudflare-erzwungen)
- Backend-Admin-API mit Bearer-Token gesichert
- WebView mit `setAllowFileAccess(false)`, `setJavaScriptEnabled(true)` (konfigurierbar)
