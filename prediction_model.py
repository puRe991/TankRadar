from datetime import timedelta
import importlib.util

import pandas as pd

import config


class FuelPredictionModel:
    def __init__(self):
        self._prophet_available = importlib.util.find_spec("prophet") is not None

    def predict_next_24h(self, df):
        """
        Predict fuel prices for the next 24 hours.

        Prophet is used when available. On 32-bit Windows the installer omits
        Prophet because current binary wheels are not reliably available; in
        that case a lightweight hourly baseline keeps TankRadar usable without
        local C/C++ compilation.
        """
        if df.empty or len(df) < config.MIN_DATA_POINTS_FOR_ML:
            return None

        prepared_df = self._prepare_price_data(df)
        if prepared_df is None:
            return None

        if self._prophet_available:
            return self._predict_with_prophet(prepared_df)
        return self._predict_with_hourly_baseline(prepared_df)

    def _prepare_price_data(self, df):
        required_columns = {"timestamp", "price"}
        if not required_columns.issubset(df.columns):
            return None

        prepared = df[["timestamp", "price"]].copy()
        prepared["timestamp"] = pd.to_datetime(prepared["timestamp"], errors="coerce")
        prepared["price"] = pd.to_numeric(prepared["price"], errors="coerce")
        prepared = prepared.dropna(subset=["timestamp", "price"]).sort_values("timestamp")
        prepared = prepared[(prepared["price"] >= 0.5) & (prepared["price"] <= 5.0)]

        if len(prepared) < config.MIN_DATA_POINTS_FOR_ML:
            return None
        return prepared

    def _predict_with_prophet(self, df):
        from prophet import Prophet

        prophet_df = df.rename(columns={"timestamp": "ds", "price": "y"})
        prophet_df["floor"] = 1.0
        prophet_df["cap"] = 3.0

        model = Prophet(
            daily_seasonality=True,
            weekly_seasonality=False,
            yearly_seasonality=False,
            changepoint_prior_scale=0.01,  # More conservative to prevent spikes
            growth="logistic",  # Use logistic to honor cap/floor
        )

        model.fit(prophet_df)

        future = model.make_future_dataframe(periods=24, freq="h")
        future["floor"] = 1.0
        future["cap"] = 3.0

        forecast = model.predict(future)
        result = forecast[["ds", "yhat", "yhat_lower", "yhat_upper"]].tail(24).copy()
        result["yhat"] = result["yhat"].clip(lower=1.0, upper=3.0)
        result["yhat_lower"] = result["yhat_lower"].clip(lower=1.0, upper=3.0)
        result["yhat_upper"] = result["yhat_upper"].clip(lower=1.0, upper=3.0)

        return self._format_prediction_result(result)

    def _predict_with_hourly_baseline(self, df):
        """Fallback forecast based on recent hourly averages and the current price level."""
        now = df["timestamp"].max()
        current_price = float(df["price"].iloc[-1])
        global_mean = float(df["price"].mean())
        hourly_means = df.assign(hour=df["timestamp"].dt.hour).groupby("hour")["price"].mean()

        forecast_rows = []
        for offset in range(1, 25):
            forecast_time = now + timedelta(hours=offset)
            hourly_price = float(hourly_means.get(forecast_time.hour, global_mean))
            # Blend hourly pattern with the latest observed price to avoid abrupt jumps on sparse data.
            predicted_price = (hourly_price * 0.7) + (current_price * 0.3)
            predicted_price = max(1.0, min(3.0, predicted_price))
            forecast_rows.append(
                {
                    "ds": forecast_time,
                    "yhat": predicted_price,
                    "yhat_lower": max(1.0, predicted_price - 0.03),
                    "yhat_upper": min(3.0, predicted_price + 0.03),
                }
            )

        result = pd.DataFrame(forecast_rows)
        return self._format_prediction_result(result)

    def _format_prediction_result(self, result):
        if result.empty or result["yhat"].isna().all():
            return None

        min_row = result.loc[result["yhat"].idxmin()]
        return {
            "forecast": result,
            "best_time": min_row["ds"],
            "best_price": round(float(min_row["yhat"]), 3),
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
