import pandas as pd
from prophet import Prophet
import config
from datetime import datetime, timedelta

class FuelPredictionModel:
    def __init__(self):
        pass

    def predict_next_24h(self, df):
        """
        Uses Prophet to predict fuel prices for the next 24 hours.
        Expects a DataFrame with 'timestamp' and 'price' columns.
        """
        if df.empty or len(df) < config.MIN_DATA_POINTS_FOR_ML:
            return None

        # Prophet requires columns 'ds' (datestamp) and 'y' (value)
        prophet_df = df[['timestamp', 'price']].rename(columns={'timestamp': 'ds', 'price': 'y'})
        
        # Initialize and fit the model
        # We use a floor to prevent negative prices
        prophet_df['floor'] = 1.0
        prophet_df['cap'] = 3.0
        
        model = Prophet(
            daily_seasonality=True,
            weekly_seasonality=False,
            yearly_seasonality=False,
            changepoint_prior_scale=0.01, # More conservative to prevent spikes
            growth='logistic' # Use logistic to honor cap/floor
        )
        
        model.fit(prophet_df)
        
        # Create future dataframe for 24 hours
        future = model.make_future_dataframe(periods=24, freq='h')
        future['floor'] = 1.0
        future['cap'] = 3.0
        
        forecast = model.predict(future)
        
        # Extract relevant parts and ensure no negatives/extremes
        result = forecast[['ds', 'yhat', 'yhat_lower', 'yhat_upper']].tail(24)
        
        # Final safety clamp
        result['yhat'] = result['yhat'].clip(lower=1.0, upper=3.0)
        
        # Find lowest point
        min_row = result.loc[result['yhat'].idxmin()]
        
        return {
            "forecast": result,
            "best_time": min_row['ds'],
            "best_price": round(min_row['yhat'], 3)
        }

    def get_prediction_summary(self, station_name, fuel_type, prediction_data):
        if not prediction_data:
            return "Not enough data for prediction."
            
        summary = f"""
Predicted lowest price today
Station: {station_name}
Fuel: {fuel_type}
Time: {prediction_data['best_time'].strftime('%H:%M')}
Price: {prediction_data['best_price']} €/L
        """
        return summary.strip()
