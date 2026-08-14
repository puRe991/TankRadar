# TankRadar - German Fuel Price Tracker & Predictor

TankRadar is a production-ready Python application that monitors real-time gasoline prices (E5, E10, Diesel) at German gas stations using ADAC and PLZ 10KM data sources. It uses machine learning (Meta Prophet) to predict the optimal time to refuel based on historical price patterns.

## Features

- **Real-time Price Monitoring**: Fetches fuel prices every 15 minutes from major German gas stations
- **Intelligent Predictions**: Uses Prophet time-series forecasting to identify the cheapest refueling times
- **Historical Analysis**: Stores and analyzes price trends with statistical insights
- **Interactive Dashboard**: Visualize prices, trends, and predictions with Dash/Plotly

## Project Structure

- `adac_scraper.py`: Scheduled ADAC price scraping used by `main.py`
- `cloud_scraper.py`: Standalone scraper invoked by the GitHub Actions cloud sync workflow
- `cloud_sync.py`: Parses the cloud CSV export for the dashboard's "Cloud Sync" import
- `database.py`: PostgreSQL/SQLite interface for storing historical data
- `analysis_engine.py`: Data processing and statistical analysis
- `prediction_model.py`: Time-series forecasting using Meta Prophet (with a non-Prophet fallback)
- `model_evaluation.py`: Backtesting/quality metrics for the prediction models
- `compliance_report.py`: Price-change-after-cutoff detection and PDF export
- `visualization_dashboard.py`: Interactive Dash/Plotly dashboard
- `main.py`: Main entry point
- `config.py`: Configuration and station settings
- `data_collector.py`: Legacy Tankerkönig-API collector, superseded by `adac_scraper.py`; not used by `main.py`
- `android/`: Standalone Android app — scrapes, stores and forecasts entirely on the phone (see `android/README.md`)
- `tools/generate_icons.py`: Regenerates the app icons in `assets/icons/`

## Setup

### Konfiguration

TankRadar läuft ohne eigene Konfiguration mit SQLite und öffnet das Dashboard
standardmäßig in einem eigenen Anwendungsfenster (via `pywebview`), nicht im
Browser; die Oberfläche wird dabei intern weiterhin unter
`http://127.0.0.1:8050` bereitgestellt. Für Release- und Produktionsumgebungen
sollte `.env.example` nach `.env` kopiert und dort angepasst werden. Wichtige
Optionen:

- `DATABASE_URL`: optionaler Wechsel von SQLite zu PostgreSQL.
- `TANKRADAR_PLZ`: Standard-PLZ für lokale und geplante Scrapes.
- `TANKRADAR_SCRAPE_INTERVAL_MINUTES`: Intervall des Hintergrund-Scrapers.
- `TANKRADAR_DASH_HOST`, `TANKRADAR_DASH_PORT`, `TANKRADAR_DASH_DEBUG`:
  Dashboard-Bind-Adresse, Port und Debug-Modus.
- `TANKRADAR_NATIVE_WINDOW`: `false` deaktiviert das native Fenster und öffnet
  TankRadar stattdessen klassisch im Standardbrowser (z.B. wenn `pywebview`
  auf dem System nicht lauffähig ist).
- `TANKRADAR_WINDOW_TITLE`, `TANKRADAR_WINDOW_WIDTH`, `TANKRADAR_WINDOW_HEIGHT`:
  Titel und Startgröße des Anwendungsfensters.
- `GITHUB_CSV_URL`: Cloud-CSV-Quelle für Forks oder eigene Deployments.
- `TANKRADAR_CLOUD_CSV_NAIVE_TZ`: Zeitzone für Cloud-CSV-Zeitstempel ohne
  UTC-Offset. Standard `utc`, weil der GitHub-Workflow in UTC läuft; `local`
  importiert sie unverändert.

### Windows (automatisch)

1. `install_tankradar.bat` doppelt anklicken. Das Skript installiert bei Bedarf Python, erstellt eine virtuelle Umgebung und installiert alle benoetigten Python-Pakete. Auf 32-Bit-Windows nutzt es automatisch ein kompatibles Python-3.11-/x86-Profil ohne Prophet; die Preisprognose verwendet dort einen einfachen Fallback statt des Prophet-Modells.
2. Anschliessend `start_tankradar.bat` doppelt anklicken. Das Startskript versucht bei jedem Start zuerst ein `git pull --ff-only`, damit die lokale Installation auf dem neuesten Stand des konfigurierten Git-Branches bleibt. Wenn Git fehlt, kein Git-Checkout vorliegt oder das Update fehlschlaegt, startet TankRadar mit der vorhandenen lokalen Version weiter.

### Manuell

1. **Install Dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

2. **Quality Gates vor einem Release**:
   ```bash
   python -m compileall -q .
   pytest -q
   python -m pyright
   ```

Weitere Schritte stehen in `RELEASE_CHECKLIST.md`.

## Android

Unter `android/` liegt eine **eigenständige Android-App**. Sie braucht weder einen
TankRadar-Server noch einen eingeschalteten PC: Preisabruf, Datenbank, Prognose und
PDF-Export laufen vollständig auf dem Telefon.

- Kotlin, Jetpack Compose, Room, WorkManager — Preisabruf direkt beim ADAC
- Preisliste mit Favoriten, 30-Tage-Verlauf, 24-h-Tankzeit-Prognose
- Tank-Tagebuch und Preis-Prüffälle inklusive PDF-Export in den Downloads-Ordner
- Alle Daten bleiben auf dem Gerät

Die Prognose nutzt dort das *Adaptive Tagesmuster* statt Prophet, das auf Android nicht
lauffähig ist — dasselbe Modell, auf das die Python-Version auf 32-Bit-Windows zurückfällt.
Build-Anleitung, Aufbau und der genaue Prüfstand stehen in `android/README.md`.

### Dashboard auf dem Handy (ohne App)

Unabhängig davon ist das Dash-Dashboard mobiltauglich: Viewport-Meta-Tag, responsives
Layout mit einklappbarer Seitenleiste, Touch-taugliche Bedienelemente sowie ein
Web-App-Manifest mit Service Worker. TankRadar auf dem Rechner mit
`TANKRADAR_DASH_HOST=0.0.0.0` starten, im Chrome des Handys `http://<rechner-ip>:8050`
öffnen und „Zum Startbildschirm hinzufügen“ wählen.

> Mit `TANKRADAR_DASH_HOST=0.0.0.0` ist das Dashboard unverschlüsselt und ohne
> Anmeldung für alle Geräte im selben Netzwerk erreichbar. Nur in vertrauenswürdigen
> Netzen verwenden und den Port nicht im Router freigeben.

## Preisänderungs-Prüffälle

Die Ansicht **Preis-Prüffälle** dokumentiert tatsächliche Änderungen eines Kraftstoffpreises nach 12:00 Uhr für die letzten 30 Tage. Sie zeigt Vorgangsnummer, sekundengenauen Zeitpunkt, Tankstelle, Anschrift, Kraftstoffart, Vorpreis, neuen Preis, Differenz und – soweit vorhanden – Koordinaten. Über **Beschwerdeanlage als PDF** können dieselben Nachweisdaten als neutrale Anlage exportiert werden.

Eine Preisänderung nach 12:00 Uhr wird dabei ausdrücklich nur als Prüffall markiert; die zeitliche Einordnung allein ist kein Nachweis eines Rechtsverstoßes.
