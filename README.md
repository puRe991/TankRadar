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

1. **Install Dependencies**:
   ```bash
   pip install -r requirements.txt
