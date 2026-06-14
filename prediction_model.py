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
        return self._predict_with_adaptive_daily_pattern(prepared_df)

    def predict_next_24h_with_method(self, df, method):
        """Predict the next 24 hours with a specific forecasting approach.

        This explicit entry point is used by the evaluation layer so backtests
        compare the production Prophet forecast, the installed hourly fallback
        and naive baselines under the same input validation rules.
        """
        prepared_df = self._prepare_price_data(df)
        if prepared_df is None:
            return None

        if method == "prophet":
            if not self._prophet_available:
                return None
            return self._predict_with_prophet(prepared_df)
        if method == "adaptive_daily_pattern":
            return self._predict_with_adaptive_daily_pattern(prepared_df)
        if method == "hourly_baseline":
            return self._predict_with_hourly_baseline(prepared_df)
        if method == "last_price":
            return self._predict_with_last_price_baseline(prepared_df)
        if method == "typical_daily_minimum":
            return self._predict_with_typical_daily_minimum_baseline(prepared_df)
        raise ValueError(f"Unknown prediction method: {method}")

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

        return self._format_prediction_result(result, model_name="Prophet")

    def _predict_with_adaptive_daily_pattern(self, df):
        """Forecast using recency-weighted daily price patterns.

        Fuel prices usually follow station-specific intraday cycles, while the
        absolute price level can shift quickly.  This model therefore learns
        hourly deviations from each day's median price and applies that shape to
        the latest observed level.  Recent observations receive higher weight so
        the cheapest-hour recommendation adapts when a station changes its
        pricing rhythm.
        """
        now = df["timestamp"].max()
        current_price = float(df["price"].iloc[-1])

        features = df.copy()
        features["hour"] = features["timestamp"].dt.hour
        features["date"] = features["timestamp"].dt.date
        features["weekday"] = features["timestamp"].dt.weekday
        features["daily_median"] = features.groupby("date")["price"].transform("median")
        features["hourly_delta"] = features["price"] - features["daily_median"]

        age_hours = (now - features["timestamp"]).dt.total_seconds() / 3600
        # Seven-day half-life: old data still helps with 1,140 points, but the
        # most recent pricing pattern dominates the recommendation.
        features["weight"] = 0.5 ** (age_hours / (24 * 7))

        global_delta = self._weighted_mean(features["hourly_delta"], features["weight"], default=0.0)
        hourly_delta = self._weighted_group_mean(features, "hour", "hourly_delta", "weight")
        weekday_hour_delta = self._weighted_group_mean(
            features, ["weekday", "hour"], "hourly_delta", "weight"
        )

        recent_window_start = now - pd.Timedelta(hours=6)
        recent_median = features.loc[features["timestamp"] >= recent_window_start, "price"].median()
        if pd.isna(recent_median):
            recent_median = current_price
        level = (float(recent_median) * 0.7) + (current_price * 0.3)

        residuals = []
        for _, row in features.iterrows():
            key = (int(row["weekday"]), int(row["hour"]))
            pattern_delta = weekday_hour_delta.get(key, hourly_delta.get(int(row["hour"]), global_delta))
            residuals.append(float(row["hourly_delta"]) - float(pattern_delta))
        residual_series = pd.Series(residuals, dtype=float).abs().dropna()
        residual_quantile = float(residual_series.quantile(0.75)) if not residual_series.empty else 0.03
        base_uncertainty = max(0.02, min(0.12, residual_quantile))

        forecast_rows = []
        for offset in range(1, 25):
            forecast_time = now + timedelta(hours=offset)
            key = (forecast_time.weekday(), forecast_time.hour)
            hour_delta = hourly_delta.get(forecast_time.hour, global_delta)
            delta = (weekday_hour_delta.get(key, hour_delta) * 0.65) + (hour_delta * 0.35)
            predicted_price = max(1.0, min(3.0, level + float(delta)))
            forecast_rows.append(
                {
                    "ds": forecast_time,
                    "yhat": predicted_price,
                    "yhat_lower": max(1.0, predicted_price - base_uncertainty),
                    "yhat_upper": min(3.0, predicted_price + base_uncertainty),
                }
            )

        result = pd.DataFrame(forecast_rows)
        return self._format_prediction_result(result, model_name="Adaptives Tagesmuster")

    def _weighted_group_mean(self, df, group_columns, value_column, weight_column):
        grouped_values = {}
        for group_key, group in df.groupby(group_columns):
            if not isinstance(group_key, tuple):
                group_key = int(group_key)
            grouped_values[group_key] = self._weighted_mean(
                group[value_column], group[weight_column], default=float(group[value_column].mean())
            )
        return grouped_values

    def _weighted_mean(self, values, weights, default=0.0):
        valid = pd.DataFrame({"value": values, "weight": weights}).dropna()
        valid = valid[valid["weight"] > 0]
        if valid.empty:
            return default
        weight_sum = float(valid["weight"].sum())
        if weight_sum <= 0:
            return default
        return float((valid["value"] * valid["weight"]).sum() / weight_sum)

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
        return self._format_prediction_result(result, model_name="Stundenmittel-Fallback")

    def _predict_with_last_price_baseline(self, df):
        """Naive forecast that assumes the latest valid price remains stable."""
        now = df["timestamp"].max()
        current_price = max(1.0, min(3.0, float(df["price"].iloc[-1])))

        forecast_rows = []
        for offset in range(1, 25):
            forecast_rows.append(
                {
                    "ds": now + timedelta(hours=offset),
                    "yhat": current_price,
                    "yhat_lower": max(1.0, current_price - 0.02),
                    "yhat_upper": min(3.0, current_price + 0.02),
                }
            )

        result = pd.DataFrame(forecast_rows)
        return self._format_prediction_result(result, model_name="Naive Baseline: letzter Preis")

    def _predict_with_typical_daily_minimum_baseline(self, df):
        """Naive forecast using the historically cheapest hour as a simple pattern."""
        now = df["timestamp"].max()
        current_price = float(df["price"].iloc[-1])
        global_mean = float(df["price"].mean())
        hourly_minima = df.assign(hour=df["timestamp"].dt.hour).groupby("hour")["price"].min()

        forecast_rows = []
        for offset in range(1, 25):
            forecast_time = now + timedelta(hours=offset)
            typical_min = float(hourly_minima.get(forecast_time.hour, global_mean))
            predicted_price = (typical_min * 0.8) + (current_price * 0.2)
            predicted_price = max(1.0, min(3.0, predicted_price))
            forecast_rows.append(
                {
                    "ds": forecast_time,
                    "yhat": predicted_price,
                    "yhat_lower": max(1.0, predicted_price - 0.04),
                    "yhat_upper": min(3.0, predicted_price + 0.04),
                }
            )

        result = pd.DataFrame(forecast_rows)
        return self._format_prediction_result(result, model_name="Naive Baseline: typisches Tagesminimum")

    def _format_prediction_result(self, result, model_name=None):
        if result.empty or result["yhat"].isna().all():
            return None

        min_row = result.loc[result["yhat"].idxmin()]
        has_interval = {"yhat_lower", "yhat_upper"}.issubset(result.columns)
        interval_width = (result["yhat_upper"] - result["yhat_lower"]).dropna() if has_interval else pd.Series(dtype=float)
        uncertainty = round(float(interval_width.mean()), 3) if not interval_width.empty else None
        best_uncertainty = None
        best_price_lower = None
        best_price_upper = None
        if has_interval:
            best_price_lower = round(float(min_row["yhat_lower"]), 3)
            best_price_upper = round(float(min_row["yhat_upper"]), 3)
            best_uncertainty = round(float(min_row["yhat_upper"] - min_row["yhat_lower"]), 3)

        return {
            "forecast": result,
            "best_time": min_row["ds"],
            "best_price": round(float(min_row["yhat"]), 3),
            "best_price_lower": best_price_lower,
            "best_price_upper": best_price_upper,
            "uncertainty": uncertainty,
            "best_uncertainty": best_uncertainty,
            "model_name": model_name,
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
Uncertainty: ±{(prediction_data.get('best_uncertainty') or 0) / 2:.3f} €/L
Model: {prediction_data.get('model_name') or 'N/A'}
        """
        return summary.strip()
