from datetime import datetime

import config
from cloud_sync import CloudPriceRow, parse_cloud_price_csv
from database import DatabaseManager, FuelPrice, Station


def test_parse_cloud_price_csv_accepts_bom_header_and_current_format():
    csv_text = (
        "\ufefftimestamp,station_id,station_name,brand,city,fuel_type,price\n"
        "2026-06-06T10:00:00,station-1,Test Station,Brand,City,e10,1.759\n"
    )

    rows, malformed = parse_cloud_price_csv(csv_text)

    assert malformed == 0
    assert rows == [
        CloudPriceRow(
            timestamp=datetime(2026, 6, 6, 10, 0),
            station_id="station-1",
            station_name="Test Station",
            brand="Brand",
            city="City",
            fuel_type="e10",
            price=1.759,
        )
    ]


def test_bulk_import_cloud_prices_imports_older_missing_cloud_rows(tmp_path, monkeypatch):
    db_path = tmp_path / "tankradar.db"
    monkeypatch.setattr(config, "DATABASE_URL", f"sqlite:///{db_path}")
    db = DatabaseManager()

    newer_local_timestamp = datetime(2026, 6, 6, 12, 0)
    older_cloud_timestamp = datetime(2026, 6, 6, 10, 0)
    session = db.Session()
    try:
        session.add(Station(id="local-station", name="Local Station"))
        session.add(
            FuelPrice(
                station_id="local-station",
                fuel_type="e10",
                price=1.80,
                timestamp=newer_local_timestamp,
            )
        )
        session.commit()
    finally:
        session.close()

    rows = [
        CloudPriceRow(
            timestamp=older_cloud_timestamp,
            station_id="cloud-station",
            station_name="Cloud Station",
            brand="Cloud Brand",
            city="Cloud City",
            fuel_type="diesel",
            price=1.65,
        )
    ]

    stats = db.bulk_import_cloud_prices(rows)

    assert stats["inserted"] == 1
    assert stats["stations_upserted"] == 1
    assert stats["skipped_duplicate"] == 0

    session = db.Session()
    try:
        imported_price = session.query(FuelPrice).filter_by(station_id="cloud-station").one()
        imported_station = session.get(Station, "cloud-station")
        assert imported_price.timestamp == older_cloud_timestamp
        assert imported_price.fuel_type == "diesel"
        assert imported_price.price == 1.65
        assert imported_station.name == "Cloud Station"
        assert imported_station.brand == "Cloud Brand"
        assert imported_station.city == "Cloud City"
    finally:
        session.close()


def test_bulk_import_cloud_prices_skips_duplicates_but_keeps_station_repair(tmp_path, monkeypatch):
    db_path = tmp_path / "tankradar.db"
    monkeypatch.setattr(config, "DATABASE_URL", f"sqlite:///{db_path}")
    db = DatabaseManager()

    timestamp = datetime(2026, 6, 6, 10, 0)
    session = db.Session()
    try:
        session.add(Station(id="station-1", name="Station station-1"))
        session.add(
            FuelPrice(
                station_id="station-1",
                fuel_type="e10",
                price=1.75,
                timestamp=timestamp,
            )
        )
        session.commit()
    finally:
        session.close()

    rows = [
        CloudPriceRow(
            timestamp=timestamp,
            station_id="station-1",
            station_name="Real Station Name",
            brand="Real Brand",
            city="Real City",
            fuel_type="e10",
            price=1.75,
        )
    ]

    stats = db.bulk_import_cloud_prices(rows)

    assert stats["inserted"] == 0
    assert stats["stations_upserted"] == 1
    assert stats["skipped_duplicate"] == 1

    session = db.Session()
    try:
        prices = session.query(FuelPrice).filter_by(station_id="station-1").all()
        station = session.get(Station, "station-1")
        assert len(prices) == 1
        assert station.name == "Real Station Name"
        assert station.brand == "Real Brand"
        assert station.city == "Real City"
    finally:
        session.close()
