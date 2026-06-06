from datetime import datetime, timedelta

import pandas as pd

from model_evaluation import FuelModelEvaluator
from prediction_model import FuelPredictionModel


def _evaluation_history(days=5):
    start = datetime(2026, 6, 1)
    rows = []
    for hour in range(days * 24):
        timestamp = start + timedelta(hours=hour)
        # Deterministic daily curve: cheapest around 03:00, costlier in the afternoon.
        daily_offset = abs(timestamp.hour - 3) * 0.004
        rows.append(
            {
                "station_id": "station-1",
                "fuel_type": "e10",
                "timestamp": timestamp,
                "price": 1.62 + daily_offset,
            }
        )
    return pd.DataFrame(rows)


def test_backtest_compares_fallback_and_naive_models_without_prophet():
    model = FuelPredictionModel()
    model._prophet_available = False
    evaluator = FuelModelEvaluator(model)

    summary = evaluator.evaluate(_evaluation_history(), max_cutoffs=4)

    assert summary["status"] == "ok"
    assert summary["cutoff_count"] == 4
    labels = {item["label"] for item in summary["models"]}
    assert "Prophet" in labels
    assert "Stundenmittel-Fallback" in labels
    assert "Naive Baseline: letzter Preis" in labels
    assert summary["best_model"]["mae"] >= 0
    assert summary["best_model"]["rmse"] >= summary["best_model"]["mae"]
    assert 0 <= summary["best_model"]["cheapest_window_accuracy"] <= 1


def test_backtest_rejects_too_short_history():
    model = FuelPredictionModel()
    evaluator = FuelModelEvaluator(model)

    summary = evaluator.evaluate(_evaluation_history(days=1), max_cutoffs=4)

    assert summary["status"] == "unavailable"
    assert summary["models"] == []
