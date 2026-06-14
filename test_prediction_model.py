from datetime import datetime, timedelta

import pandas as pd

import config
from prediction_model import FuelPredictionModel


def _price_history(rows=None):
    row_count = rows or max(config.MIN_DATA_POINTS_FOR_ML, 24)
    start = datetime(2026, 6, 1, 8)
    return pd.DataFrame(
        [
            {"timestamp": start + timedelta(hours=index), "price": 1.70 + (index % 5) * 0.01}
            for index in range(row_count)
        ]
    )


def test_adaptive_pattern_prediction_without_prophet():
    model = FuelPredictionModel()
    model._prophet_available = False

    prediction = model.predict_next_24h(_price_history())

    assert prediction is not None
    assert len(prediction["forecast"]) == 24
    assert 1.0 <= prediction["best_price"] <= 3.0
    assert {"ds", "yhat", "yhat_lower", "yhat_upper"}.issubset(prediction["forecast"].columns)


def test_prediction_rejects_invalid_input_data():
    model = FuelPredictionModel()
    model._prophet_available = False

    prediction = model.predict_next_24h(pd.DataFrame({"timestamp": ["ungueltig"], "price": ["n/a"]}))

    assert prediction is None


def test_explicit_baseline_prediction_contains_uncertainty_metadata():
    model = FuelPredictionModel()

    prediction = model.predict_next_24h_with_method(_price_history(), "last_price")

    assert prediction is not None
    assert prediction["model_name"] == "Naive Baseline: letzter Preis"
    assert prediction["best_price_lower"] <= prediction["best_price"] <= prediction["best_price_upper"]
    assert prediction["best_uncertainty"] is not None


def test_adaptive_daily_pattern_uses_recurring_cheapest_hour():
    model = FuelPredictionModel()
    start = datetime(2026, 6, 1)
    rows = []
    for hour in range(7 * 24):
        timestamp = start + timedelta(hours=hour)
        price = 1.80 + abs(timestamp.hour - 3) * 0.006
        rows.append({"timestamp": timestamp, "price": price})

    prediction = model.predict_next_24h_with_method(pd.DataFrame(rows), "adaptive_daily_pattern")

    assert prediction is not None
    assert prediction["model_name"] == "Adaptives Tagesmuster"
    assert prediction["best_time"].hour == 3
    assert prediction["best_uncertainty"] is not None
