# Zebra StageNow Provisioning

## Was StageNow macht

StageNow ist Zebras Tool für die automatisierte Erstinstallation von MDA-Geräten. Du kannst per Barcode-Scan oder NFC-Tag in einem Zug:
- Wifi konfigurieren
- APK herunterladen + installieren
- Device-Owner setzen
- Power/UI-Lockdown anwenden
- App starten

## Workflow

1. **StageNow Studio** auf einem Windows-PC installieren (kostenlos bei Zebra)
2. **Profile importieren**: `kiosk-staging-profile.xml` öffnen
3. **Werte anpassen**:
   - Wifi SSID + Passwort
   - APK-URL (muss vom Gerät erreichbar sein)
4. **Profile exportieren**: als 1D/2D-Barcode (PDF)
5. **Gerät vorbereiten**: Factory Reset → in StageNow-Modus booten
6. **Barcode scannen**: Gerät führt Profile aus

## APK-Hosting

Die APK muss per HTTPS erreichbar sein. Optionen:
- **Cloudflare R2** (Public Bucket im selben Account)
- **GitHub Release Asset** (bei privaten Repos: ohne Auth nicht möglich → Token in URL)
- **eigener Webserver / nginx**

Nach jedem APK-Update neue Version ins Hosting schieben und Profile-XML versionieren.

## Device-Owner-Setup ohne StageNow

Falls StageNow nicht verfügbar (Test-Gerät, Nicht-Zebra):

```bash
adb install -r kiosk-mda.apk
adb shell dpm set-device-owner com.kiosk.mda/.admin.KioskDeviceAdminReceiver
```

**Achtung:** Geht nur, wenn noch kein anderes Konto auf dem Gerät registriert ist (Factory-Reset-State).

## Default Launcher festlegen

Wenn Lock-Task-Mode nicht funktioniert (kein Device-Owner), als Fallback:
```bash
adb shell cmd package set-home-activity com.kiosk.mda/.MainActivity
```
