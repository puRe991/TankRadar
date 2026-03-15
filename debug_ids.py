from database import DatabaseManager

db = DatabaseManager()
stations = db.get_all_stations()
print(f"Total stations: {len(stations)}")
for s in stations:
    print(f"ID: {s.id} | Name: {s.name}")

latest = db.get_latest_prices()
print("\nLatest prices count:", len(latest))
if not latest.empty:
    for idx, row in latest.iterrows():
        print(f"Station: {row['station_id']} | Fuel: {row['fuel_type']} | Price: {row['price']}")
