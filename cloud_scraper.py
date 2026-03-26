import requests
import json
import csv
import os
import logging
import time
from datetime import datetime
from typing import Optional

# --- Logging statt print ---
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger(__name__)

# --- Konfiguration ---
DEFAULT_PLZ   = os.environ.get("TANKRADAR_PLZ", "35444")
DISTANCE      = int(os.environ.get("TANKRADAR_DISTANCE", "10"))
CSV_FILE      = os.environ.get("TANKRADAR_CSV", "prices_history.csv")
MAX_RETRIES   = 3
RETRY_DELAY   = 5   # Sekunden zwischen Retries
PAGE_DELAY    = 0.5 # Sekunden zwischen Seiten-Requests (Rate-Limit-Schutz)

FUEL_TYPES: list[str] = ["Super", "Super E10", "Super Plus", "Diesel"]

FUEL_TYPE_MAP: dict[str, str] = {
    "Super E10":  "e10",
    "Super":      "e5",
    "Super Plus": "e5p",
    "Diesel":     "diesel",
}

BFF_URL = "https://www.adac.de/bff/"
PERSISTED_QUERY_HASH = (
    "4a2fa0e59f195625260721f98dbd6a6d376093b44b7633a40b9a1b5a9c144164"
)
HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "application/json",
    "x-portal-env": "prod",
    "content-type": "application/json",
}

CSV_COLUMNS = [
    "timestamp", "station_id", "station_name",
    "brand", "city", "fuel_type", "price",
]


# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

def parse_price(raw: str) -> Optional[float]:
    """Parst den Preis-String und gibt None zurück, wenn er ungültig ist."""
    try:
        value = float(str(raw).replace(",", ".").strip())
        # Preise außerhalb [0.50 €, 5.00 €] sind fast sicher fehlerhafte Daten
        return value if 0.50 <= value <= 5.00 else None
    except (ValueError, TypeError):
        return None


def fetch_page(plz: str, fuel_type: str, distance: int, page: int) -> dict:
    """Holt eine einzelne Ergebnis-Seite mit Retry-Logik."""
    variables = {
        "stationsFilter": {
            "query": plz,
            "distance": distance,
            "pageNumber": page,
            "fuelType": fuel_type,
            "sort": "PRICE_ASC",
        }
    }
    extensions = {
        "persistedQuery": {"version": 1, "sha256Hash": PERSISTED_QUERY_HASH}
    }
    params = {
        "operationName": "FuelStationsFinder",
        "variables":   json.dumps(variables,   separators=(",", ":")),
        "extensions":  json.dumps(extensions,  separators=(",", ":")),
    }

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            resp = requests.get(
                BFF_URL, params=params, headers=HEADERS, timeout=20
            )
            resp.raise_for_status()
            return resp.json()
        except requests.exceptions.HTTPError as exc:
            # 429 = Too Many Requests → länger warten
            if exc.response is not None and exc.response.status_code == 429:
                wait = RETRY_DELAY * attempt * 2
                log.warning("Rate-limited (429). Warte %ds …", wait)
                time.sleep(wait)
            elif attempt == MAX_RETRIES:
                raise
            else:
                log.warning("HTTP-Fehler (Versuch %d/%d): %s", attempt, MAX_RETRIES, exc)
                time.sleep(RETRY_DELAY * attempt)
        except requests.exceptions.RequestException as exc:
            if attempt == MAX_RETRIES:
                raise
            log.warning("Netzwerkfehler (Versuch %d/%d): %s", attempt, MAX_RETRIES, exc)
            time.sleep(RETRY_DELAY * attempt)

    raise RuntimeError("Unerreichbar – alle Versuche fehlgeschlagen")


def fetch_stations(plz: str, fuel_type: str, distance: int) -> list[dict]:
    """Holt alle Seiten für einen Kraftstofftyp und gibt eine flache Liste zurück."""
    all_items: list[dict] = []
    page = 1

    while True:
        data = fetch_page(plz, fuel_type, distance, page)
        fuel_stations = data.get("data", {}).get("fuelStations", {})
        items = fuel_stations.get("items", [])
        total = fuel_stations.get("total", 0)

        if not items:
            break

        all_items.extend(items)
        log.debug("  Seite %d: %d/%d Einträge geladen", page, len(all_items), total)

        if len(all_items) >= total:
            break

        page += 1
        time.sleep(PAGE_DELAY)   # ← Rate-Limit-Schutz zwischen Seiten

    return all_items


def build_row(timestamp: str, item: dict, internal_fuel: str) -> Optional[list]:
    """
    Wandelt ein API-Item in eine CSV-Zeile um.
    Gibt None zurück, wenn der Preis ungültig ist (keine Null-Einträge im CSV).
    """
    price = parse_price(item.get("price", ""))
    if price is None:
        return None

    station_id   = item.get("id", "")
    operator     = item.get("operator", "").strip()
    city         = item.get("city", "").strip()
    station_name = f"{operator} {city}".strip()

    return [timestamp, station_id, station_name, operator, city, internal_fuel, price]


# ---------------------------------------------------------------------------
# Hauptprogramm
# ---------------------------------------------------------------------------

def main() -> None:
    plz = DEFAULT_PLZ.strip()
    log.info("Starte Scraper für PLZ %s (Radius: %d km) …", plz, DISTANCE)

    # Existiert das CSV schon? Wenn nicht, Header schreiben
    file_exists = os.path.isfile(CSV_FILE)
    rows_written = 0
    rows_skipped = 0
    errors       = 0

    with open(CSV_FILE, mode="a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)

        if not file_exists:
            writer.writerow(CSV_COLUMNS)
            log.info("Neue CSV-Datei angelegt: %s", CSV_FILE)

        timestamp = datetime.now().isoformat(timespec="seconds")

        for adac_fuel in FUEL_TYPES:
            internal_fuel = FUEL_TYPE_MAP.get(adac_fuel, "e5")
            log.info("Scraping %s (%s) …", adac_fuel, internal_fuel)

            try:
                items = fetch_stations(plz, adac_fuel, DISTANCE)
            except Exception as exc:
                log.error("  Fehler beim Abrufen von %s: %s", adac_fuel, exc)
                errors += 1
                continue

            for item in items:
                row = build_row(timestamp, item, internal_fuel)
                if row is None:
                    rows_skipped += 1
                    continue
                writer.writerow(row)
                rows_written += 1

            log.info(
                "  %d Stationen abgerufen, %d Zeilen geschrieben.",
                len(items), rows_written,
            )

    log.info(
        "Fertig. %d Zeilen geschrieben, %d übersprungen (ungültige Preise), %d Fehler.",
        rows_written, rows_skipped, errors,
    )

    # Nicht-null Exit-Code bei vollständigem Fehler → GitHub Actions schlägt an
    if errors == len(FUEL_TYPES):
        raise SystemExit("Alle Kraftstofftypen fehlgeschlagen – Scraper bricht ab.")


if __name__ == "__main__":
    main()
