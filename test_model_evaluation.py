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
    assert "Adaptives Tagesmuster" in labels
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


def test_evaluate_from_database_filters_requested_fuel_type():
    class FakeDb:
        def get_historical_data(self, station_id, days=90):
            assert station_id == "station-1"
            assert days == 14
            history = _evaluation_history(days=5)
            other = history.copy()
            other["fuel_type"] = "diesel"
            other["price"] = other["price"] + 0.25
            return pd.concat([history, other], ignore_index=True)

    model = FuelPredictionModel()
    model._prophet_available = False
    evaluator = FuelModelEvaluator(model)

    summary = evaluator.evaluate_from_database(FakeDb(), "station-1", "e10", days=14, max_cutoffs=3)

    assert summary["status"] == "ok"
    assert summary["cutoff_count"] == 3


def test_evaluate_from_database_rejects_missing_station_or_fuel_type():
    evaluator = FuelModelEvaluator(FuelPredictionModel())

    assert evaluator.evaluate_from_database(object(), "", "e10")["status"] == "unavailable"
    assert evaluator.evaluate_from_database(object(), "station-1", "")["status"] == "unavailable"


def test_alignment_groups_actuals_by_hour_and_drops_invalid_forecast_rows():
    evaluator = FuelModelEvaluator(FuelPredictionModel())
    forecast = pd.DataFrame(
        [
            {"ds": "2026-06-01 10:20", "yhat": "1.70"},
            {"ds": "invalid", "yhat": "1.99"},
            {"ds": "2026-06-01 11:00", "yhat": "bad"},
        ]
    )
    actual = pd.DataFrame(
        [
            {"timestamp": pd.Timestamp("2026-06-01 10:05"), "price": 1.60},
            {"timestamp": pd.Timestamp("2026-06-01 10:55"), "price": 1.80},
        ]
    )

    joined = evaluator._align_forecast_with_actuals(forecast, actual)

    assert len(joined) == 1
    assert round(float(joined.iloc[0]["actual_price"]), 3) == 1.70
    assert joined.iloc[0]["yhat"] == 1.70


def test_build_cutoffs_handles_edge_cases_and_even_spacing():
    evaluator = FuelModelEvaluator(FuelPredictionModel())
    prepared = _evaluation_history(days=4)[["timestamp", "price"]]

    assert evaluator._build_cutoffs(prepared.head(5), 24, 3, 10) == []
    one = evaluator._build_cutoffs(prepared, 24, 1, 10)
    many = evaluator._build_cutoffs(prepared, 24, 5, 10)

    assert len(one) == 1
    assert len(many) == 5
    assert many == sorted(many)
