import cloud_scraper


def test_build_row_handles_null_operator_and_city():
    """The ADAC BFF returns JSON null for unknown operators, not an empty string."""
    row = cloud_scraper.build_row(
        "2026-06-06T10:00:00+00:00",
        {"id": "123", "operator": None, "city": None, "price": "1,799"},
        "e5",
    )

    assert row == ["2026-06-06T10:00:00+00:00", "123", "", "", "", "e5", 1.799]


def test_build_row_strips_and_joins_station_name():
    row = cloud_scraper.build_row(
        "2026-06-06T10:00:00+00:00",
        {"id": " 123 ", "operator": " ARAL ", "city": " Lahnau ", "price": "1,799"},
        "diesel",
    )

    assert row == ["2026-06-06T10:00:00+00:00", "123", "ARAL Lahnau", "ARAL", "Lahnau", "diesel", 1.799]


def test_build_row_skips_rows_without_station_id():
    assert cloud_scraper.build_row("2026-06-06T10:00:00+00:00", {"price": "1,799"}, "e5") is None


def test_build_row_skips_implausible_prices():
    item = {"id": "123", "operator": "ARAL", "city": "Lahnau", "price": "0,00"}

    assert cloud_scraper.build_row("2026-06-06T10:00:00+00:00", item, "e5") is None
