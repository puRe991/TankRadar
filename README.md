# TankRadar - German Fuel Price Tracker & Predictor

TankRadar is a production-ready Python application that tracks gasoline prices (E5, E10, Diesel) from German gas stations using the Tankerkönig API and predicts the best time to refuel using machine learning (Prophet).

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

2. **API Key**:
   Obtain a free API key from [Tankerkönig](https://creativecommons.tankerkoenig.de/).
   Add it to `config.py` or as an environment variable `TANKERKOENIG_API_KEY`.

3. **Run the Application**:
   ```bash
   python main.py
   ```

4. **View Dashboard**:
   Open `http://127.0.0.1:8050` in your browser.

## Prediction Logic
The system uses the Prophet library to analyze daily and weekly cycles in fuel prices. It requires at least 48-72 hours of data to start providing accurate forecasts. The dashboard highlights the predicted cheapest point in the next 24 hours.
