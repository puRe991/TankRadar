import pandas as pd
import numpy as np
from database import DatabaseManager

class AnalysisEngine:
    def __init__(self):
        self.db = DatabaseManager()

    def process_station_data(self, station_id, days=365):
        """Loads historical data and calculates basic statistics."""
        df = self.db.get_historical_data(station_id, days)
        
        if df.empty:
            return None

        # Ensure timestamp is datetime
        df['timestamp'] = pd.to_datetime(df['timestamp'])
        
        # Group by fuel type for analysis
        analysis_results = {}
        for fuel_type in df['fuel_type'].unique():
            fuel_df = df[df['fuel_type'] == fuel_type].copy()
            
            stats = {
                "current_price": fuel_df['price'].iloc[-1],
                "avg_price": fuel_df['price'].mean(),
                "min_price_24h": fuel_df[fuel_df['timestamp'] > (pd.Timestamp.now() - pd.Timedelta(days=1))]['price'].min(),
                "volatility": fuel_df['price'].std(),
                "trend": self._calculate_trend(fuel_df)
            }
            analysis_results[fuel_type] = stats
            
        return analysis_results

    def _calculate_trend(self, df):
        """Simple trend calculation: comparison of last 5 prices to previous 5."""
        if len(df) < 10:
            return "neutral"
        
        recent = df['price'].tail(5).mean()
        previous = df['price'].iloc[-10:-5].mean()
        
        diff = recent - previous
        if diff > 0.01:
            return "rising"
        elif diff < -0.01:
            return "falling"
        else:
            return "stable"

    def get_daily_cycle(self, df):
        """Identifies typical price dips throughout the day."""
        if df.empty:
            return None
        
        # Extract hour and minute to detect cycles
        temp_df = df.copy()
        temp_df['hour'] = temp_df['timestamp'].dt.hour
        
        # Mean price per hour
        hourly_pattern = temp_df.groupby('hour')['price'].mean()
        return hourly_pattern

    def get_cheapest_weekday(self, station_id, fuel_type, days=30):
        """Identifies the cheapest day of the week for a specific station."""
        df = self.db.get_historical_data(station_id, days)
        if df.empty:
            return None
        
        fuel_df = df[df['fuel_type'] == fuel_type].copy()
        if fuel_df.empty:
            return None
            
        fuel_df['timestamp'] = pd.to_datetime(fuel_df['timestamp'])
        fuel_df['weekday'] = fuel_df['timestamp'].dt.day_name()
        
        # Mapping to German
        mapping = {
            'Monday': 'Montag', 'Tuesday': 'Dienstag', 'Wednesday': 'Mittwoch',
            'Thursday': 'Donnerstag', 'Friday': 'Freitag', 'Saturday': 'Samstag', 'Sunday': 'Sonntag'
        }
        
        avg_per_day = fuel_df.groupby('weekday')['price'].mean()
        cheapest_day_en = avg_per_day.idxmin()
        
        return {
            "day": mapping.get(cheapest_day_en, cheapest_day_en),
            "price": round(avg_per_day.min(), 3)
        }

    def get_best_time_of_day(self, station_id, fuel_type, days=14):
        """Identifies the cheapest hour of the day for a specific station."""
        df = self.db.get_historical_data(station_id, days)
        if df.empty:
            return None
            
        fuel_df = df[df['fuel_type'] == fuel_type].copy()
        if fuel_df.empty:
            return None
            
        fuel_df['timestamp'] = pd.to_datetime(fuel_df['timestamp'])
        fuel_df['hour'] = fuel_df['timestamp'].dt.hour
        
        avg_per_hour = fuel_df.groupby('hour')['price'].mean()
        best_hour = avg_per_hour.idxmin()
        
        return {
            "hour": int(best_hour),
            "price": round(avg_per_hour.min(), 3)
        }

    def get_city_comparison(self, station_id, fuel_type):
        """Compares a station's current price to the city average."""
        all_latest = self.db.get_latest_prices()
        if all_latest.empty:
            return None
            
        stations = {s.id: s for s in self.db.get_all_stations()}
        target_station = stations.get(station_id)
        if not target_station or not target_station.city:
            return None
            
        # Get all latest prices for the same city
        city_stations_ids = [s.id for s in stations.values() if s.city == target_station.city]
        city_prices = all_latest[(all_latest['station_id'].isin(city_stations_ids)) & (all_latest['fuel_type'] == fuel_type)]
        
        if city_prices.empty:
            return None
            
        city_avg = city_prices['price'].mean()
        station_price_row = all_latest[(all_latest['station_id'] == station_id) & (all_latest['fuel_type'] == fuel_type)]
        
        if station_price_row.empty:
            return None
            
        station_price = station_price_row.iloc[0]['price']
        diff = station_price - city_avg
        
        return {
            "city": target_station.city,
            "city_avg": round(city_avg, 3),
            "station_price": round(station_price, 3),
            "difference": round(diff, 3),
            "is_cheaper": diff < 0
        }
