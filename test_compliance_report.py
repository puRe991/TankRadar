from datetime import datetime

from compliance_report import build_complaint_pdf, detect_price_change_cases, format_address


def sample_data():
    # Stored timestamps are naive UTC; on this summer date Germany is UTC+2.
    prices = [
        {"id": 1, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 9, 55), "fuel_type": "e10", "price": 1.699},
        {"id": 2, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 10, 0), "fuel_type": "e10", "price": 1.729},
        {"id": 5, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 10, 5), "fuel_type": "e10", "price": 1.729},
        {"id": 3, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 10, 10), "fuel_type": "e10", "price": 1.759},
        {"id": 4, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 10, 20), "fuel_type": "e10", "price": 1.719},
    ]
    stations = [{"id": "s1", "name": "Muster-Tankstelle", "brand": "Radar", "street": "Testweg", "house_number": "7", "post_code": "12345", "city": "Berlin", "latitude": 52.5, "longitude": 13.4}]
    return prices, stations


def test_detects_only_actual_changes_after_german_local_cutoff():
    prices, stations = sample_data()
    cases = detect_price_change_cases(prices, stations, now=datetime(2026, 6, 3, 10), cutoff_hour=12, days=30)
    assert list(cases["event_id"]) == ["TR-00000004", "TR-00000003"]
    assert list(cases["difference"].round(3)) == [-0.040, 0.030]
    assert cases.iloc[0]["timestamp"].strftime("%Y-%m-%d %H:%M %Z") == "2026-06-02 12:20 CEST"
    assert format_address(cases.iloc[0]) == "Testweg 7, 12345 Berlin"


def test_applies_german_cutoff_across_daylight_saving_time():
    prices = [
        {"id": 1, "station_id": "s1", "timestamp": datetime(2026, 1, 2, 10, 55), "fuel_type": "e10", "price": 1.699},
        {"id": 2, "station_id": "s1", "timestamp": datetime(2026, 1, 2, 11, 5), "fuel_type": "e10", "price": 1.709},
        {"id": 3, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 9, 55), "fuel_type": "e10", "price": 1.709},
        {"id": 4, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 10, 5), "fuel_type": "e10", "price": 1.719},
    ]
    cases = detect_price_change_cases(prices, [], now=datetime(2026, 6, 3), days=365)
    assert list(cases["event_id"]) == ["TR-00000004", "TR-00000002"]
    assert list(cases["timestamp"].map(lambda value: value.hour)) == [12, 12]


def test_ignores_first_different_price_after_observation_gap():
    prices = [
        {"id": 1, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 9, 0), "fuel_type": "e10", "price": 1.699},
        {"id": 2, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 10, 10), "fuel_type": "e10", "price": 1.729},
        {"id": 3, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 10, 20), "fuel_type": "e10", "price": 1.759},
    ]
    cases = detect_price_change_cases(prices, [], now=datetime(2026, 6, 3), max_observation_gap_minutes=30)
    assert list(cases["event_id"]) == ["TR-00000003"]


def test_pdf_contains_a_valid_pdf_document():
    prices, stations = sample_data()
    cases = detect_price_change_cases(prices, stations, now=datetime(2026, 6, 3, 10))
    pdf = build_complaint_pdf(cases, generated_at=datetime(2026, 6, 3, 10))
    assert pdf.startswith(b"%PDF-")
    assert len(pdf) > 1500
