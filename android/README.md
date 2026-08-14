# TankRadar für Android

Dieses Verzeichnis enthält die native Android-App zu TankRadar.

## Was die App ist – und was nicht

TankRadar besteht aus einem Python-Backend (Scraper, SQLite/PostgreSQL, Prophet-Prognose)
und einem Dash-Frontend. Dieser Stack läuft nicht auf einem Telefon. Die Android-App ist
deshalb ein **Client für deine eigene TankRadar-Installation**: Sie zeigt das Dashboard des
Rechners an, auf dem TankRadar läuft, und ergänzt es um das, was ein Browser-Tab nicht kann:

- eigenes Launcher-Icon und Vollbild ohne Browser-Adressleiste
- Pull-to-Refresh und Hardware-Zurück-Taste
- funktionierender PDF-Export (siehe *Downloads* unten)
- verständlicher Offline-Zustand statt Chromes Dino-Seite
- Server-Adresse einmal eintragen statt jedes Mal eine IP zu tippen

Scraping, Datenbank und Prognose bleiben auf dem Server. Ohne laufenden TankRadar-Server
zeigt die App nur den Offline-Bildschirm.

**Alternative ohne App:** Das Dashboard ist inzwischen auch eine installierbare PWA. Über
Chrome auf dem Handy → Menü → „Zum Startbildschirm hinzufügen“ bekommst du Icon und
Vollbild ohne APK. Die App lohnt sich vor allem wegen des PDF-Exports und des
Offline-Bildschirms.

## Server vorbereiten

Standardmäßig lauscht TankRadar nur auf `127.0.0.1` und ist damit vom Handy aus nicht
erreichbar. Auf dem Rechner, der TankRadar ausführt:

```bash
# .env im TankRadar-Verzeichnis
TANKRADAR_DASH_HOST=0.0.0.0
TANKRADAR_NATIVE_WINDOW=false   # optional: kein Desktop-Fenster, nur Server
```

Danach TankRadar neu starten und die lokale IP des Rechners ermitteln
(`ipconfig` unter Windows, `ip addr` unter Linux). Die Adresse lautet dann z.B.
`192.168.1.20:8050`.

> **Sicherheitshinweis:** Mit `0.0.0.0` ist das Dashboard für jedes Gerät im selben
> Netzwerk erreichbar – unverschlüsselt und ohne Anmeldung. Nutze das nur in einem
> Netzwerk, dem du vertraust, und gib den Port nicht im Router frei. Für den Zugriff
> von unterwegs gehört ein VPN oder ein HTTPS-Reverse-Proxy davor; die App
> akzeptiert dann auch eine `https://`-Adresse.

## Bauen

Voraussetzungen: Android Studio (Ladybug oder neuer) oder ein Android SDK mit JDK 17.

```bash
cd android
./gradlew assembleDebug        # APK unter app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest
```

Das Gradle-Wrapper-JAR liegt bewusst nicht im Repository. Beim ersten Öffnen in
Android Studio wird es erzeugt; auf der Kommandozeile einmalig mit einem lokal
installierten Gradle 8.9:

```bash
cd android && gradle wrapper
```

Für eine Release-APK wird ein eigener Signing-Key benötigt; `app/build.gradle.kts`
enthält bewusst keine Keystore-Zugangsdaten.

- `minSdk 29` (Android 10). Der PDF-Export schreibt über MediaStore in den
  Downloads-Ordner, was erst ab API 29 ohne Speicher-Berechtigung möglich ist.
- `targetSdk 35`, `compileSdk 35`.

## Erste Einrichtung auf dem Gerät

Beim ersten Start fragt die App nach der Server-Adresse (`192.168.1.20:8050`).
Fehlt das Schema, wird `http://` ergänzt; fehlt der Port, wird `8050` angenommen.
Später ist die Adresse über das Menü in der Toolbar änderbar – nützlich, wenn der
Rechner per DHCP eine neue IP bekommt.

## Downloads

Der Button „Beschwerdeanlage als PDF“ erzeugt die Datei über Dashs
`dcc.Download`-Komponente im Browser und startet sie über einen `blob:`-Link. Ein
WebView löst dafür **kein** `DownloadListener`-Ereignis aus – ohne Gegenmaßnahme
würde der Button auf Android also wirkungslos bleiben.

`DownloadBridge` schließt diese Lücke: Nach jedem Seitenaufbau wird JavaScript
injiziert, das `blob:`- und `data:`-Downloads abfängt, die Daten über eine
`@JavascriptInterface`-Brücke an Kotlin reicht und dort per MediaStore im
Downloads-Ordner ablegt. Normale HTTP-Downloads laufen unverändert über den
System-DownloadManager.

## Struktur

| Datei | Zweck |
| --- | --- |
| `MainActivity.kt` | WebView-Host: Pull-to-Refresh, Zurück-Navigation, Fehlerzustand, Download-Weiterleitung |
| `SetupActivity.kt` | Eingabe und Änderung der Server-Adresse |
| `ServerConfig.kt` | Persistenz und Normalisierung der Adresse |
| `DownloadBridge.kt` | `blob:`/`data:`-Downloads → Downloads-Ordner |
| `res/xml/network_security_config.xml` | Erlaubt Klartext-HTTP ins lokale Netz (siehe Sicherheitshinweis) |

## Status

Das Projekt ist vollständig und in sich konsistent, wurde in der Entwicklungsumgebung
dieses Commits aber **nicht kompiliert** – dort war kein Android SDK verfügbar. Der
erste `./gradlew assembleDebug`-Lauf sollte deshalb bewusst durchgeführt und geprüft
werden, bevor die App weiterverteilt wird.
