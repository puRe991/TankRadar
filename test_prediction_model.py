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


def test_prepare_price_data_filters_bad_rows_and_sorts_chronologically():
    model = FuelPredictionModel()
    rows = [
        {"timestamp": "2026-06-01 12:00", "price": "1.80"},
        {"timestamp": "not-a-date", "price": "1.70"},
        {"timestamp": "2026-06-01 09:00", "price": "0.40"},
        {"timestamp": "2026-06-01 10:00", "price": "5.10"},
    ]
    for index in range(config.MIN_DATA_POINTS_FOR_ML):
        rows.append({"timestamp": datetime(2026, 6, 1, index), "price": 1.60 + index * 0.01})

    prepared = model._prepare_price_data(pd.DataFrame(rows))

    assert prepared is not None
    assert prepared["timestamp"].is_monotonic_increasing
    assert prepared["price"].between(0.5, 5.0).all()
    assert prepared["timestamp"].notna().all()


def test_prepare_price_data_rejects_missing_required_columns():
    model = FuelPredictionModel()

    assert model._prepare_price_data(pd.DataFrame({"timestamp": [datetime(2026, 6, 1)]})) is None
    assert model._prepare_price_data(pd.DataFrame({"price": [1.70]})) is None


def test_weighted_mean_ignores_invalid_or_non_positive_weights():
    model = FuelPredictionModel()

    result = model._weighted_mean(
        pd.Series([1.0, 2.0, 100.0, None]),
        pd.Series([1.0, 3.0, 0.0, 10.0]),
        default=-1.0,
    )

    assert result == 1.75
    assert model._weighted_mean(pd.Series([None]), pd.Series([0]), default=9.0) == 9.0


def test_unknown_prediction_method_raises_clear_error():
    model = FuelPredictionModel()

    try:
        model.predict_next_24h_with_method(_price_history(), "does_not_exist")
    except ValueError as exc:
        assert "Unknown prediction method" in str(exc)
    else:
        raise AssertionError("Unknown prediction methods must raise ValueError")


def test_prediction_summary_handles_missing_prediction_data():
    model = FuelPredictionModel()

    assert model.get_prediction_summary("Station", "e10", None) == "Not enough data for prediction."
