import requests
import json
import logging
from datetime import datetime
import config

logger = logging.getLogger("TankRadar.Scraper")

# Mapping from ADAC fuelType names to our internal fuel_type keys
FUEL_TYPE_MAP = {
    "Super E10": "e10",
    "Super":     "e5",
    "Super Plus":"e5p",
    "Diesel":    "diesel",
}

class ADACScraper:
    """Scrapes fuel station prices from the ADAC GraphQL BFF endpoint."""

    BFF_URL = "https://www.adac.de/bff/"

    # Persisted query hash for the FuelStationsFinder operation.
    # If ADAC rotates this hash, it may need to be updated.
    PERSISTED_QUERY_HASH = "4a2fa0e59f195625260721f98dbd6a6d376093b44b7633a40b9a1b5a9c144164"

    HEADERS = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/124.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json",
        "Accept-Language": "de-DE,de;q=0.9",
        "x-portal-env": "prod",
        "content-type": "application/json",
        "Referer": "https://www.adac.de/verkehr/tanken-kraftstoff-antrieb/kraftstoffpreise/",
        "Origin": "https://www.adac.de",
    }

    def __init__(self, db_manager):
        self.db = db_manager

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def scrape_by_plz(self, plz=None, fuel_type="Super", distance=10):
        """
        Fetch fuel station data for a given PLZ via the ADAC BFF.

        Args:
            plz: Postcode string (e.g. "35444"). Falls back to config default.
            fuel_type: ADAC fuel type name – "Super", "Super E10", or "Diesel".
            distance: Search radius in km (default 10).

        Returns:
            list[dict] of station records on success, empty list on failure.
        """
        if not plz:
            plz = getattr(config, "DEFAULT_SCRAPE_LOCATION", "35037")

        logger.info(f"Scraping ADAC for PLZ={plz}, fuel={fuel_type}, distance={distance}km")

        try:
            items = self._fetch_stations(plz, fuel_type, distance)
            if not items:
                logger.warning("ADAC BFF returned no station items.")
                return []

            saved = self._save_to_db(items, fuel_type)
            logger.info(f"Scraped and saved {saved} station records.")
            return items

        except Exception as e:
            logger.error(f"Scraper failed: {e}", exc_info=True)
            return []

    def scrape_all_fuel_types(self, plz=None, distance=10):
        """Convenience: scrape Super, Super E10, and Diesel in one go."""
        results = {}
        for adac_fuel in ["Super", "Super E10", "Super Plus", "Diesel"]:
            items = self.scrape_by_plz(plz=plz, fuel_type=adac_fuel, distance=distance)
            results[adac_fuel] = items
        return results

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _fetch_stations(self, plz, fuel_type, distance):
        """Call the ADAC GraphQL BFF and return the list of station dicts across all pages."""
        all_items = []
        page = 1
        
        while True:
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
                "persistedQuery": {
                    "version": 1,
                    "sha256Hash": self.PERSISTED_QUERY_HASH,
                }
            }
            params = {
                "operationName": "FuelStationsFinder",
                "variables": json.dumps(variables, separators=(",", ":")),
                "extensions": json.dumps(extensions, separators=(",", ":")),
            }

            resp = requests.get(
                self.BFF_URL,
                params=params,
                headers=self.HEADERS,
                timeout=20,
            )
            resp.raise_for_status()

            data = resp.json()

            # Navigate into the GraphQL response
            fuel_stations = data.get("data", {}).get("fuelStations", {})
            items = fuel_stations.get("items", [])
            total = fuel_stations.get("total", 0)

            if not items:
                # Log the raw response for debugging if empty
                if page == 1:
                    logger.debug(f"Raw BFF response: {json.dumps(data, indent=2)[:500]}")
                break
                
            all_items.extend(items)
            
            # If we've fetched all items, stop
            if len(all_items) >= total:
                break
                
            page += 1

        return all_items

    def _save_to_db(self, items, adac_fuel_type):
        """Persist a list of ADAC station items into the database."""
        internal_fuel = FUEL_TYPE_MAP.get(adac_fuel_type, "e5")
        saved_count = 0

        for item in items:
            try:
                station_id = str(item.get("id", ""))
                operator   = item.get("operator", "Unbekannt")
                street     = item.get("street", "")
                zipcode    = item.get("zipcode", "")
                city       = item.get("city", "")
                lat        = item.get("lat")
                lon        = item.get("lon")
                price_str  = item.get("price", "0")

                # Build a readable station name from operator + city
                station_name = f"{operator} {city}".strip() or f"Station {station_id}"

                # Parse price: ADAC uses comma as decimal separator (e.g. "1,679")
                price = float(price_str.replace(",", "."))

                # --- Upsert station ---
                self._upsert_station(
                    station_id=station_id,
                    name=station_name,
                    brand=operator,
                    street=street,
                    post_code=zipcode,
                    city=city,
                    lat=lat,
                    lon=lon,
                )

                # --- Add price record ---
                self.db.add_price(station_id, internal_fuel, price)
                saved_count += 1

            except Exception as e:
                logger.error(f"Error saving station {item.get('id')}: {e}")

        return saved_count

    def _upsert_station(self, station_id, name, brand, street, post_code, city, lat, lon):
        """Create or update a station in the database."""
        from database import Station
        session = self.db.Session()
        try:
            station = Station(
                id=station_id,
                name=name,
                brand=brand,
                street=street,
                post_code=post_code,
                city=city,
                latitude=lat,
                longitude=lon,
            )
            session.merge(station)
            session.commit()
        except Exception as e:
            session.rollback()
            logger.error(f"Error upserting station {station_id}: {e}")
        finally:
            session.close()
