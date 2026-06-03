from datetime import datetime

from compliance_report import build_complaint_pdf, detect_price_change_cases, format_address


def sample_data():
    prices = [
        {"id": 1, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 11, 55), "fuel_type": "e10", "price": 1.699},
        {"id": 2, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 12, 0), "fuel_type": "e10", "price": 1.729},
        {"id": 5, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 12, 5), "fuel_type": "e10", "price": 1.729},
        {"id": 3, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 12, 10), "fuel_type": "e10", "price": 1.759},
        {"id": 4, "station_id": "s1", "timestamp": datetime(2026, 6, 2, 18, 10), "fuel_type": "e10", "price": 1.719},
    ]
    stations = [{"id": "s1", "name": "Muster-Tankstelle", "brand": "Radar", "street": "Testweg", "house_number": "7", "post_code": "12345", "city": "Berlin", "latitude": 52.5, "longitude": 13.4}]
    return prices, stations


def test_detects_only_actual_changes_after_cutoff():
    prices, stations = sample_data()
    cases = detect_price_change_cases(prices, stations, now=datetime(2026, 6, 3, 10), cutoff_hour=12, days=30)
    assert list(cases["event_id"]) == ["TR-00000004", "TR-00000003"]
    assert list(cases["difference"].round(3)) == [-0.040, 0.030]
    assert format_address(cases.iloc[0]) == "Testweg 7, 12345 Berlin"


def test_pdf_contains_a_valid_pdf_document():
    prices, stations = sample_data()
    cases = detect_price_change_cases(prices, stations, now=datetime(2026, 6, 3, 10))
    pdf = build_complaint_pdf(cases, generated_at=datetime(2026, 6, 3, 10))
    assert pdf.startswith(b"%PDF-")
    assert len(pdf) > 1500
