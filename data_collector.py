import requests
import time
from datetime import datetime
from apscheduler.schedulers.background import BackgroundScheduler
import config
from database import DatabaseManager

class TankerkoenigClient:
    BASE_URL = "https://creativecommons.tankerkoenig.de/json/detail.php"
    LIST_URL = "https://creativecommons.tankerkoenig.de/json/list.php"
    PRICES_URL = "https://creativecommons.tankerkoenig.de/json/prices.php"

    def __init__(self, api_key):
        self.api_key = api_key

    def get_prices(self, station_ids):
        """Fetch current prices for a list of station IDs."""
        ids_str = ",".join(station_ids)
        params = {
            "ids": ids_str,
            "apikey": self.api_key
        }
        try:
            response = requests.get(self.PRICES_URL, params=params)
            response.raise_for_status()
            return response.json()
        except Exception as e:
            print(f"Error fetching prices: {e}")
            return None

class PriceCollector:
    def __init__(self):
        self.db = DatabaseManager()
        self.client = TankerkoenigClient(config.TANKERKOENIG_API_KEY)
        self.scheduler = BackgroundScheduler()

    def collect(self):
        print(f"[{datetime.now()}] Collecting fuel prices...")
        data = self.client.get_prices(config.STATION_IDS)
        
        if not data or not data.get("ok"):
            print("Failed to get data from Tankerkönig API")
            return

        prices = data.get("prices", {})
        for station_id, info in prices.items():
            if info.get("status") == "open":
                # Save each fuel type if available
                for fuel_type in ["e5", "e10", "diesel"]:
                    price = info.get(fuel_type)
                    if price is not None and price > 0:
                        self.db.add_price(
                            station_id=station_id,
                            fuel_type=fuel_type,
                            price=price,
                            timestamp=datetime.utcnow()
                        )
                        print(f"Stored {fuel_type} for {station_id}: {price}")
            else:
                print(f"Station {station_id} is currently closed.")

    def start(self):
        # Run once immediately
        self.collect()
        
        # Schedule every 5 minutes
        self.scheduler.add_job(
            self.collect, 
            'interval', 
            minutes=config.UPDATE_INTERVAL,
            id='fuel_price_collection'
        )
        self.scheduler.start()
        print(f"Collector started. Updating every {config.UPDATE_INTERVAL} minutes.")

    def stop(self):
        self.scheduler.shutdown()

if __name__ == "__main__":
    collector = PriceCollector()
    collector.collect()
