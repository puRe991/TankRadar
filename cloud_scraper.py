import requests
import json
import csv
import os
from datetime import datetime

# --- Configuration ---
# You can change these OR use environment variables in GitHub Secrets
DEFAULT_PLZ = os.environ.get("TANKRADAR_PLZ", "35444")
DISTANCE = 10
FUEL_TYPES = ["Super", "Super E10", "Super Plus", "Diesel"]
CSV_FILE = "prices_history.csv"

# Mapping from ADAC fuelType names to our internal fuel_type keys
FUEL_TYPE_MAP = {
    "Super E10": "e10",
    "Super":     "e5",
    "Super Plus":"e5p",
    "Diesel":    "diesel",
}

BFF_URL = "https://www.adac.de/bff/"
PERSISTED_QUERY_HASH = "4a2fa0e59f195625260721f98dbd6a6d376093b44b7633a40b9a1b5a9c144164"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "application/json",
    "x-portal-env": "prod",
    "content-type": "application/json",
}

def fetch_stations(plz, fuel_type, distance):
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
        extensions = {"persistedQuery": {"version": 1, "sha256Hash": PERSISTED_QUERY_HASH}}
        params = {
            "operationName": "FuelStationsFinder",
            "variables": json.dumps(variables, separators=(",", ":")),
            "extensions": json.dumps(extensions, separators=(",", ":")),
        }
        resp = requests.get(BFF_URL, params=params, headers=HEADERS, timeout=20)
        resp.raise_for_status()
        data = resp.json()
        fuel_stations = data.get("data", {}).get("fuelStations", {})
        items = fuel_stations.get("items", [])
        total = fuel_stations.get("total", 0)
        if not items: break
        all_items.extend(items)
        if len(all_items) >= total: break
        page += 1
    return all_items

def main():
    print(f"[{datetime.now()}] Starting Cloud Scraper for PLZ {DEFAULT_PLZ}...")
    
    file_exists = os.path.isfile(CSV_FILE)
    
    with open(CSV_FILE, mode='a', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        if not file_exists:
            writer.writerow(["timestamp", "station_id", "station_name", "brand", "city", "fuel_type", "price"])
        
        timestamp = datetime.now().isoformat()
        
        for adac_fuel in FUEL_TYPES:
            print(f"Scraping {adac_fuel}...")
            try:
                items = fetch_stations(DEFAULT_PLZ, adac_fuel, DISTANCE)
                internal_fuel = FUEL_TYPE_MAP.get(adac_fuel, "e5")
                
                for item in items:
                    price = float(item.get("price", "0").replace(",", "."))
                    writer.writerow([
                        timestamp,
                        item.get("id"),
                        f"{item.get('operator')} {item.get('city')}",
                        item.get("operator"),
                        item.get("city"),
                        internal_fuel,
                        price
                    ])
                print(f"  Got {len(items)} items.")
            except Exception as e:
                print(f"  Error scraping {adac_fuel}: {e}")

    print(f"[{datetime.now()}] Scraping complete. Data saved to {CSV_FILE}")

if __name__ == "__main__":
    main()
