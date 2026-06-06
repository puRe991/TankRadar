"""Cloud CSV import helpers for TankRadar.

The GitHub Action appends compact CSV rows, while older repository snapshots may
start with a database export header. These helpers normalize both formats before
writing them to SQLite/PostgreSQL.
"""

from __future__ import annotations

import csv
import io
from dataclasses import dataclass
from datetime import datetime
from typing import Optional


@dataclass(frozen=True)
class CloudPriceRow:
    timestamp: datetime
    station_id: str
    fuel_type: str
    price: float
    station_name: Optional[str] = None
    brand: Optional[str] = None
    city: Optional[str] = None


VALID_FUEL_TYPES = {"e5", "e10", "e5p", "diesel"}


def _parse_timestamp(value: str) -> datetime:
    value = (value or "").strip()
    if not value:
        raise ValueError("missing timestamp")
    # GitHub CSV uses ISO timestamps. Older DB exports use a space separator.
    return datetime.fromisoformat(value.replace("Z", "+00:00")).replace(tzinfo=None)


def _parse_price(value: str) -> float:
    price = float((value or "").strip().replace(",", "."))
    if price <= 0:
        raise ValueError("price must be positive")
    return price


def _normalize_row(raw: list[str]) -> Optional[CloudPriceRow]:
    if not raw or all(not cell.strip() for cell in raw):
        return None

    first = raw[0].strip().lower()
    if first in {"id", "timestamp"}:
        return None

    # Current GitHub Action format:
    # timestamp,station_id,station_name,brand,city,fuel_type,price
    if len(raw) >= 7 and not raw[0].strip().isdigit():
        timestamp, station_id, station_name, brand, city, fuel_type, price = raw[:7]
        fuel_type = fuel_type.strip().lower()
        if fuel_type not in VALID_FUEL_TYPES:
            raise ValueError(f"invalid fuel type: {fuel_type}")
        return CloudPriceRow(
            timestamp=_parse_timestamp(timestamp),
            station_id=station_id.strip(),
            station_name=station_name.strip() or None,
            brand=brand.strip() or None,
            city=city.strip() or None,
            fuel_type=fuel_type,
            price=_parse_price(price),
        )

    # Older database export format:
    # id,station_id,timestamp,fuel_type,price,latitude,longitude,previous_price
    if len(raw) >= 5 and raw[0].strip().isdigit():
        _, station_id, timestamp, fuel_type, price = raw[:5]
        fuel_type = fuel_type.strip().lower()
        if fuel_type not in VALID_FUEL_TYPES:
            raise ValueError(f"invalid fuel type: {fuel_type}")
        return CloudPriceRow(
            timestamp=_parse_timestamp(timestamp),
            station_id=station_id.strip(),
            fuel_type=fuel_type,
            price=_parse_price(price),
        )

    raise ValueError(f"unsupported CSV row format with {len(raw)} columns")


def parse_cloud_price_csv(text: str) -> tuple[list[CloudPriceRow], int]:
    """Return normalized rows and the number of malformed rows skipped."""
    reader = csv.reader(io.StringIO(text))
    rows: list[CloudPriceRow] = []
    malformed = 0
    seen = set()

    for raw in reader:
        try:
            row = _normalize_row(raw)
        except (TypeError, ValueError):
            malformed += 1
            continue
        if row is None or not row.station_id:
            continue

        key = (row.station_id, row.fuel_type, row.timestamp, row.price)
        if key in seen:
            continue
        seen.add(key)
        rows.append(row)

    return rows, malformed
