# TankRadar - German Fuel Price Tracker & Predictor

TankRadar is a production-ready Python application that tracks gasoline prices (E5, E10, Diesel) from German gas stations using the ADAC Website and PLZ 10KM and predicts the best time to refuel using machine learning (Prophet).

## Project Structure

- `data_collector.py`: Background service fetching prices every 5 minutes.
- `database.py`: PostgreSQL/SQLite interface for storing historical data.
- `analysis_engine.py`: Data processing and statistical analysis.
- `prediction_model.py`: Time-series forecasting using Meta Prophet.
- `visualization_dashboard.py`: Interactive Dash/Plotly dashboard.
- `main.py`: Main entry point.
- `config.py`: Configuration and station settings.

## Setup

1. **Install Dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

2. Scraps Daten from ADAC


3. **Run the Application**:
   ```bash
   python main.py
   ```

4. **View Dashboard**:
   Open Tankradar Window after starting

## Prediction Logic
The system uses the Prophet library to analyze daily and weekly cycles in fuel prices. It requires at least 48-72 hours of data to start providing accurate forecasts. The dashboard highlights the predicted cheapest point in the next 24 hours.
