"""Cloud CSV import helpers for TankRadar.

The GitHub Action appends compact CSV rows, while older repository snapshots may
start with a database export header. These helpers normalize both formats before
writing them to SQLite/PostgreSQL.

Timestamps are normalized to *local naive* time, because that is what the rest of
TankRadar works with: ``FuelPriceSchema`` stamps local scrapes via
``datetime.now()`` and the price-change check compares against a local 12:00
cutoff. The cloud CSV is produced by a GitHub Action running in UTC, so importing
its timestamps verbatim shifted every cloud price 1-2 hours into the past for
German users and hid real price changes between 12:00 and 14:00 local time.
"""

from __future__ import annotations

import csv
import io
from dataclasses import dataclass
from datetime import datetime, timezone
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

# How to interpret cloud CSV timestamps that carry no UTC offset. Every row the
# GitHub Action wrote before it started emitting "+00:00" is UTC, so "utc" is the
# correct default. Set to "local" to keep the pre-fix behaviour of importing such
# timestamps verbatim.
NAIVE_TIMESTAMP_TZ_UTC = "utc"
NAIVE_TIMESTAMP_TZ_LOCAL = "local"


def _parse_timestamp(value: str, naive_timezone: str = NAIVE_TIMESTAMP_TZ_UTC) -> datetime:
    value = (value or "").strip()
    if not value:
        raise ValueError("missing timestamp")
    # GitHub CSV uses ISO timestamps. Older DB exports use a space separator.
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))

    if parsed.tzinfo is None:
        if naive_timezone == NAIVE_TIMESTAMP_TZ_LOCAL:
            return parsed
        parsed = parsed.replace(tzinfo=timezone.utc)

    # astimezone() without an argument converts to the system's local zone, which
    # is what the rest of TankRadar stores and displays.
    return parsed.astimezone().replace(tzinfo=None)


def _parse_price(value: str) -> float:
    price = float((value or "").strip().replace(",", "."))
    if price <= 0:
        raise ValueError("price must be positive")
    return price


def _normalize_row(
    raw: list[str], naive_timezone: str = NAIVE_TIMESTAMP_TZ_UTC
) -> Optional[CloudPriceRow]:
    if not raw or all(not cell.strip() for cell in raw):
        return None

    first = raw[0].lstrip("﻿").strip().lower()
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
            timestamp=_parse_timestamp(timestamp, naive_timezone),
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
            timestamp=_parse_timestamp(timestamp, naive_timezone),
            station_id=station_id.strip(),
            fuel_type=fuel_type,
            price=_parse_price(price),
        )

    raise ValueError(f"unsupported CSV row format with {len(raw)} columns")


def parse_cloud_price_csv(
    text: str, naive_timezone: str = NAIVE_TIMESTAMP_TZ_UTC
) -> tuple[list[CloudPriceRow], int]:
    """Return normalized rows and the number of malformed rows skipped.

    ``naive_timezone`` controls how timestamps without a UTC offset are read; see
    ``NAIVE_TIMESTAMP_TZ_UTC``.
    """
    reader = csv.reader(io.StringIO(text))
    rows: list[CloudPriceRow] = []
    malformed = 0
    seen = set()

    for raw in reader:
        try:
            row = _normalize_row(raw, naive_timezone)
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
