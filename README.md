# TankRadar - German Fuel Price Tracker & Predictor

TankRadar is a production-ready Python application that monitors real-time gasoline prices (E5, E10, Diesel) at German gas stations using ADAC and PLZ 10KM data sources. It uses machine learning (Meta Prophet) to predict the optimal time to refuel based on historical price patterns.

## Features

- **Real-time Price Monitoring**: Fetches fuel prices every 15 minutes from major German gas stations
- **Intelligent Predictions**: Uses Prophet time-series forecasting to identify the cheapest refueling times
- **Historical Analysis**: Stores and analyzes price trends with statistical insights
- **Interactive Dashboard**: Visualize prices, trends, and predictions with Dash/Plotly

## Project Structure

- `data_collector.py`: Background service fetching prices every 5 minutes
- `database.py`: PostgreSQL/SQLite interface for storing historical data
- `analysis_engine.py`: Data processing and statistical analysis
- `prediction_model.py`: Time-series forecasting using Meta Prophet
- `visualization_dashboard.py`: Interactive Dash/Plotly dashboard
- `main.py`: Main entry point
- `config.py`: Configuration and station settings

## Setup

### Windows (automatisch)

1. `install_tankradar.bat` doppelt anklicken. Das Skript installiert bei Bedarf Python, erstellt eine virtuelle Umgebung und installiert alle benoetigten Python-Pakete.
2. Anschliessend `start_tankradar.bat` doppelt anklicken.

### Manuell

1. **Install Dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

## Preisänderungs-Prüffälle

Die Ansicht **Preis-Prüffälle** dokumentiert tatsächliche Änderungen eines Kraftstoffpreises nach 12:00 Uhr für die letzten 30 Tage. Sie zeigt Vorgangsnummer, sekundengenauen Zeitpunkt, Tankstelle, Anschrift, Kraftstoffart, Vorpreis, neuen Preis, Differenz und – soweit vorhanden – Koordinaten. Über **Beschwerdeanlage als PDF** können dieselben Nachweisdaten als neutrale Anlage exportiert werden.

Eine Preisänderung nach 12:00 Uhr wird dabei ausdrücklich nur als Prüffall markiert; die zeitliche Einordnung allein ist kein Nachweis eines Rechtsverstoßes.
