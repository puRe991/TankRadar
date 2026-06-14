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


def test_bulk_import_cloud_prices_defers_flush_until_commit(tmp_path, monkeypatch):
    from sqlalchemy import event
    from sqlalchemy.orm import Session as SqlAlchemySession

    db_path = tmp_path / "tankradar.db"
    monkeypatch.setattr(config, "DATABASE_URL", f"sqlite:///{db_path}")
    db = DatabaseManager()

    flush_count = 0

    def count_flush(session, flush_context, instances):
        nonlocal flush_count
        flush_count += 1

    event.listen(SqlAlchemySession, "before_flush", count_flush)
    try:
        rows = [
            CloudPriceRow(
                timestamp=datetime(2026, 6, 6, 10, 0),
                station_id="station-1",
                station_name="Station One",
                brand="Brand A",
                city="City A",
                fuel_type="e10",
                price=1.75,
            ),
            CloudPriceRow(
                timestamp=datetime(2026, 6, 6, 10, 5),
                station_id="station-2",
                station_name="Station Two",
                brand="Brand B",
                city="City B",
                fuel_type="diesel",
                price=1.65,
            ),
        ]

        stats = db.bulk_import_cloud_prices(rows)
    finally:
        event.remove(SqlAlchemySession, "before_flush", count_flush)

    assert stats["inserted"] == 2
    assert stats["stations_upserted"] == 2
    assert flush_count == 1


def test_database_manager_uses_sqlite_connect_args_and_pragmas(tmp_path, monkeypatch):
    import database

    db_path = tmp_path / "tankradar.db"
    engine = object()
    create_engine_calls = []
    pragma_registered = []

    def fake_create_engine(*args, **kwargs):
        create_engine_calls.append((args, kwargs))
        return engine

    monkeypatch.setattr(config, "DATABASE_URL", f"sqlite:///{db_path}")
    monkeypatch.setattr(database, "create_engine", fake_create_engine)
    monkeypatch.setattr(database.Base.metadata, "create_all", lambda *args, **kwargs: None)
    monkeypatch.setattr(database.DatabaseManager, "_migrate_schema", lambda self: None)
    monkeypatch.setattr(
        database.DatabaseManager,
        "_register_sqlite_pragmas",
        lambda self: pragma_registered.append(self.engine),
    )

    db = database.DatabaseManager()

    assert db.engine is engine
    assert create_engine_calls == [
        (
            (f"sqlite:///{db_path}",),
            {"connect_args": {"check_same_thread": False, "timeout": 30}},
        )
    ]
    assert pragma_registered == [engine]


def test_database_manager_omits_sqlite_options_for_postgresql_url(monkeypatch):
    import database

    database_url = "postgresql://user:pass@localhost/db"
    engine = object()
    create_engine_calls = []
    pragma_registered = []

    def fake_create_engine(*args, **kwargs):
        create_engine_calls.append((args, kwargs))
        return engine

    monkeypatch.setattr(config, "DATABASE_URL", database_url)
    monkeypatch.setattr(database, "create_engine", fake_create_engine)
    monkeypatch.setattr(database.Base.metadata, "create_all", lambda *args, **kwargs: None)
    monkeypatch.setattr(database.DatabaseManager, "_migrate_schema", lambda self: None)
    monkeypatch.setattr(
        database.DatabaseManager,
        "_register_sqlite_pragmas",
        lambda self: pragma_registered.append(self.engine),
    )

    db = database.DatabaseManager()

    assert db.engine is engine
    assert create_engine_calls == [((database_url,), {})]
    assert pragma_registered == []
