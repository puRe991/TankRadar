"""Backtesting and model-quality helpers for fuel-price forecasts.

The evaluator uses historical rows from ``FuelPrice`` (or equivalent DataFrame
exports) and simulates forecasts from past timestamps.  For every cutoff it
trains only on data known at that point, predicts the next 24 hours and compares
those predictions with the actual historical observations in the same horizon.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import sqrt
from typing import Any

import pandas as pd

import config
from prediction_model import FuelPredictionModel


@dataclass(frozen=True)
class ModelBacktestResult:
    """Compact metrics for one forecasting approach."""

    label: str
    mae: float | None
    rmse: float | None
    cheapest_window_accuracy: float | None
    evaluated_windows: int
    status: str = "ok"
    message: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "label": self.label,
            "mae": self.mae,
            "rmse": self.rmse,
            "cheapest_window_accuracy": self.cheapest_window_accuracy,
            "evaluated_windows": self.evaluated_windows,
            "status": self.status,
            "message": self.message,
        }


class FuelModelEvaluator:
    """Evaluate price prediction models with rolling historical backtests."""

    MODEL_LABELS = {
        "prophet": "Prophet",
        "adaptive_daily_pattern": "Adaptives Tagesmuster",
        "hourly_baseline": "Stundenmittel-Fallback",
        "last_price": "Naive Baseline: letzter Preis",
        "typical_daily_minimum": "Naive Baseline: typisches Tagesminimum",
    }

    def __init__(self, model: FuelPredictionModel | None = None):
        self.model = model or FuelPredictionModel()

    def evaluate_from_database(
        self,
        db_manager,
        station_id: str,
        fuel_type: str,
        days: int = 90,
        **kwargs,
    ) -> dict[str, Any]:
        """Load ``FuelPrice`` history via ``DatabaseManager`` and backtest it."""
        if not station_id or not fuel_type:
            return self._empty_summary("Station und Kraftstofftyp werden benötigt.")

        history = db_manager.get_historical_data(station_id, days=days)
        if history.empty:
            return self._empty_summary("Keine historischen FuelPrice-Daten vorhanden.")

        fuel_history = history[history["fuel_type"] == fuel_type].copy() if "fuel_type" in history else history
        return self.evaluate(fuel_history, **kwargs)

    def evaluate(
        self,
        df: pd.DataFrame,
        horizon_hours: int = config.PREDICTION_HORIZON_HOURS,
        max_cutoffs: int = 12,
        min_train_points: int = config.MIN_DATA_POINTS_FOR_ML,
    ) -> dict[str, Any]:
        """Run a rolling-origin backtest and return dashboard-ready metrics.

        MAE/RMSE are computed against the nearest actual price for each forecast
        hour. The cheapest-window hit rate counts a success when the predicted
        cheapest hour falls into the same clock hour as the actual cheapest
        observation in the evaluation horizon.
        """
        prepared = self.model._prepare_price_data(df)  # Shared validation keeps eval aligned with production forecasts.
        if prepared is None:
            return self._empty_summary("Zu wenige valide Preispunkte für ein Backtesting.")

        cutoffs = self._build_cutoffs(prepared, horizon_hours, max_cutoffs, min_train_points)
        if not cutoffs:
            return self._empty_summary("Zu wenig Historie mit nachfolgendem 24h-Fenster für ein Backtesting.")

        methods = ["prophet", "adaptive_daily_pattern", "hourly_baseline", "last_price", "typical_daily_minimum"]
        results = []
        for method in methods:
            results.append(
                self._evaluate_method(prepared, method, cutoffs, horizon_hours).as_dict()
            )

        available_results = [item for item in results if item["evaluated_windows"] > 0 and item["mae"] is not None]
        best_result = max(
            available_results,
            key=lambda item: (
                item["cheapest_window_accuracy"] if item["cheapest_window_accuracy"] is not None else -1,
                -item["mae"],
            ),
            default=None,
        )

        return {
            "status": "ok" if available_results else "unavailable",
            "message": None if available_results else "Kein Modell konnte auf den historischen Fenstern bewertet werden.",
            "horizon_hours": horizon_hours,
            "cutoff_count": len(cutoffs),
            "models": results,
            "best_model": best_result,
        }

    def _evaluate_method(
        self,
        prepared: pd.DataFrame,
        method: str,
        cutoffs: list[pd.Timestamp],
        horizon_hours: int,
    ) -> ModelBacktestResult:
        errors: list[float] = []
        cheapest_hits = 0
        evaluated_windows = 0
        skipped_reason = None

        for cutoff in cutoffs:
            train = prepared[prepared["timestamp"] <= cutoff]
            actual = prepared[
                (prepared["timestamp"] > cutoff)
                & (prepared["timestamp"] <= cutoff + pd.Timedelta(hours=horizon_hours))
            ]
            if len(train) < config.MIN_DATA_POINTS_FOR_ML or actual.empty:
                continue

            prediction = self.model.predict_next_24h_with_method(train, method)
            if not prediction or prediction.get("forecast") is None:
                skipped_reason = "Modell nicht verfügbar oder Prognose fehlgeschlagen."
                continue

            forecast = prediction["forecast"][["ds", "yhat"]].copy()
            joined = self._align_forecast_with_actuals(forecast, actual)
            if joined.empty:
                skipped_reason = "Keine passenden Ist-Werte im Prognosefenster."
                continue

            errors.extend((joined["yhat"] - joined["actual_price"]).abs().tolist())
            if self._cheapest_window_hit(forecast, actual):
                cheapest_hits += 1
            evaluated_windows += 1

        if not errors or evaluated_windows == 0:
            return ModelBacktestResult(
                label=self.MODEL_LABELS[method],
                mae=None,
                rmse=None,
                cheapest_window_accuracy=None,
                evaluated_windows=0,
                status="unavailable",
                message=skipped_reason or "Nicht genügend auswertbare Prognosefenster.",
            )

        squared_errors = [error * error for error in errors]
        return ModelBacktestResult(
            label=self.MODEL_LABELS[method],
            mae=round(float(sum(errors) / len(errors)), 4),
            rmse=round(float(sqrt(sum(squared_errors) / len(squared_errors))), 4),
            cheapest_window_accuracy=round(float(cheapest_hits / evaluated_windows), 4),
            evaluated_windows=evaluated_windows,
        )

    def _align_forecast_with_actuals(self, forecast: pd.DataFrame, actual: pd.DataFrame) -> pd.DataFrame:
        if forecast.empty or actual.empty:
            return pd.DataFrame(columns=["ds", "yhat", "actual_price"])

        hourly_actuals = actual.copy()
        hourly_actuals["ds"] = hourly_actuals["timestamp"].dt.floor("h")
        hourly_actuals = hourly_actuals.groupby("ds", as_index=False)["price"].mean()
        hourly_actuals = hourly_actuals.rename(columns={"price": "actual_price"})

        aligned_forecast = forecast.copy()
        aligned_forecast["ds"] = pd.to_datetime(aligned_forecast["ds"], errors="coerce").dt.floor("h")
        aligned_forecast["yhat"] = pd.to_numeric(aligned_forecast["yhat"], errors="coerce")
        aligned_forecast = aligned_forecast.dropna(subset=["ds", "yhat"])

        return aligned_forecast.merge(hourly_actuals, on="ds", how="inner")

    def _cheapest_window_hit(self, forecast: pd.DataFrame, actual: pd.DataFrame) -> bool:
        if forecast.empty or actual.empty:
            return False

        predicted = forecast.dropna(subset=["yhat"]).sort_values("yhat").iloc[0]["ds"]
        actual_cheapest = actual.dropna(subset=["price"]).sort_values("price").iloc[0]["timestamp"]
        return pd.Timestamp(predicted).floor("h") == pd.Timestamp(actual_cheapest).floor("h")

    def _build_cutoffs(
        self,
        prepared: pd.DataFrame,
        horizon_hours: int,
        max_cutoffs: int,
        min_train_points: int,
    ) -> list[pd.Timestamp]:
        if len(prepared) < min_train_points + 2:
            return []

        latest_cutoff = prepared["timestamp"].max() - pd.Timedelta(hours=horizon_hours)
        candidates = prepared.iloc[min_train_points - 1 :]["timestamp"]
        candidates = candidates[candidates <= latest_cutoff].drop_duplicates().sort_values()
        if candidates.empty:
            return []

        if max_cutoffs <= 1:
            return [pd.Timestamp(candidates.iloc[-1])]
        if len(candidates) <= max_cutoffs:
            return [pd.Timestamp(value) for value in candidates]

        # Evenly spread cutoffs across history to avoid over-weighting dense scrape periods.
        positions = pd.Series(range(len(candidates))).quantile(
            [index / (max_cutoffs - 1) for index in range(max_cutoffs)]
        )
        selected_indices = sorted({int(round(pos)) for pos in positions})
        return [pd.Timestamp(candidates.iloc[index]) for index in selected_indices]

    def _empty_summary(self, message: str) -> dict[str, Any]:
        return {
            "status": "unavailable",
            "message": message,
            "horizon_hours": config.PREDICTION_HORIZON_HOURS,
            "cutoff_count": 0,
            "models": [],
            "best_model": None,
        }
