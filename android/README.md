# TankRadar für Android

Eine eigenständige Android-App. Sie braucht **keinen Server und keinen PC** — Preisabruf,
Datenbank, Prognose und PDF-Export laufen vollständig auf dem Telefon.

## Was die App kann

| Bereich | Umsetzung |
| --- | --- |
| **Preise** | Ruft die Preise für E5, E10, Super Plus und Diesel direkt beim ADAC ab, für eine PLZ und einen einstellbaren Umkreis. Liste nach Preis sortiert, günstigste Tankstelle markiert, Favoriten, 24-h-Trendlinie je Karte, Pull-to-Refresh. |
| **Verlauf & Prognose** | 30-Tage-Verlauf als Diagramm plus 24-h-Prognose mit empfohlener Tankzeit. |
| **Tank-Tagebuch** | Tankvorgänge erfassen, Monatsausgaben, getankte Menge, mengengewichteter Durchschnittspreis. |
| **Preis-Prüffälle** | Tatsächliche Preisänderungen nach 12:00 Uhr der letzten 30 Tage, exportierbar als PDF im Ordner „Downloads“. |
| **Hintergrund** | Aktualisiert die Preise per WorkManager in einstellbarem Intervall — auch nach Neustart des Geräts. |
| **Einstellungen** | PLZ, Umkreis, bevorzugte Kraftstoffart, Aktualisierungsintervall, Aufbewahrungsdauer des Verlaufs. |

Alle Daten bleiben auf dem Gerät. Die App spricht ausschließlich mit dem ADAC-Endpunkt;
es gibt keinen TankRadar-Server, kein Konto und keine Übertragung an Dritte.

## Unterschiede zur Desktop-Version

- **Prognose:** Prophet braucht eine C++/Stan-Toolchain und läuft nicht auf Android. Die App
  nutzt das *Adaptive Tagesmuster* — genau das Modell, auf das die Python-Version auf
  32-Bit-Windows ohnehin zurückfällt. Es lernt die typische Stundenabweichung vom
  Tagesmedian und gewichtet neuere Daten stärker (Halbwertszeit sieben Tage).
- **Datenbestand:** Die App startet mit leerer Historie. Prognose und Prüffälle werden
  erst mit einigen Tagen gesammelter Preise aussagekräftig; für eine Prognose sind
  mindestens 10 Messwerte je Tankstelle nötig.
- **Kein Cloud-Sync:** Der CSV-Import aus dem GitHub-Workflow ist nicht enthalten.
- **Intervall:** Android führt Hintergrundaufgaben frühestens alle 15 Minuten aus und
  verschiebt sie zur Akkuschonung. Das eingestellte Intervall ist ein Ziel, keine Garantie.

## Bauen

Voraussetzungen: JDK 17+ und ein Android SDK (Platform 35, Build-Tools 35). Mit Android
Studio (Ladybug oder neuer) genügt „Open“ auf dem Ordner `android/`.

```bash
cd android
echo "sdk.dir=/pfad/zum/android-sdk" > local.properties

./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 34 Unit-Tests
./gradlew lintDebug
./gradlew assembleRelease        # unsigniert; zum Verteilen eigenen Key einrichten
```

Das Gradle-Wrapper-JAR liegt nicht im Repository. Android Studio erzeugt es beim ersten
Öffnen; auf der Kommandozeile einmalig mit einem lokal installierten Gradle 8.9:

```bash
cd android && gradle wrapper
```

`minSdk 29` (Android 10): Der PDF-Export schreibt über MediaStore in den Downloads-Ordner,
was erst ab API 29 ohne Speicher-Berechtigung möglich ist. `targetSdk`/`compileSdk` 35.

## Aufbau

```
data/remote/AdacClient.kt      ADAC-GraphQL-Abfrage inkl. Paging und Retry
data/db/                       Room: Stationen, Preise, Tankvorgänge
data/PriceRepository.kt        Scrape → Speicherung → abgeleitete Sichten
data/Settings.kt               DataStore-Einstellungen
domain/Forecast.kt             24-h-Prognose (Adaptives Tagesmuster)
domain/PriceChangeCases.kt     Erkennung der Prüffälle
domain/ComplaintPdf.kt         PDF-Erzeugung ohne Fremdbibliothek
work/ScrapeWorker.kt           Periodische Aktualisierung (WorkManager)
ui/                            Compose-Oberfläche, vier Tabs
```

Die drei portierten Kernlogiken (`Forecast`, `PriceChangeCases`, `ComplaintPdf`) und das
Parsen der ADAC-Antwort sind durch Unit-Tests abgedeckt, weil sie die Aussagen der App
tragen: wann man tanken sollte und was in einem Beschwerde-PDF landet.

## Was verifiziert ist — und was nicht

Im Rahmen der Entwicklung wurden geprüft:

- `assembleDebug`, `assembleRelease` (inkl. R8) und `lintDebug` laufen fehlerfrei durch;
  Lint meldet außer Versions-Hinweisen nichts.
- 34 Unit-Tests grün (Prognosemodell, Prüffall-Erkennung, PDF-Struktur, ADAC-Parsing).
- Die handgeschriebene SQL-Abfrage für „Station mit aktuellem und vorherigem Preis“ wurde
  gegen das von Room exportierte Schema in echtem SQLite ausgeführt und liefert die
  erwarteten Werte, inklusive Stationen ohne Preis für die gewählte Sorte.

**Nicht verifiziert:** Die App wurde nie auf einem Gerät oder Emulator gestartet — in der
Entwicklungsumgebung stand keine Hardware-Virtualisierung zur Verfügung. Layout, Navigation,
der WorkManager-Zeitplan und der echte ADAC-Abruf sind also ungetestet. Der erste Lauf auf
einem Gerät gehört deshalb bewusst durchgeführt, bevor die App weitergegeben wird.
