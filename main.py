import os
os.environ["POLARS_SKIP_CPU_CHECK"] = "1"
from visualization_dashboard import TankRadarDashboard
from database import DatabaseManager
from adac_scraper import ADACScraper
from apscheduler.schedulers.background import BackgroundScheduler
import config
import logging

logger = logging.getLogger("TankRadar.Main")

def run_scrape_job():
    """Background job: scrape all fuel types from ADAC."""
    try:
        db = DatabaseManager()
        scraper = ADACScraper(db)
        plz = getattr(config, 'DEFAULT_SCRAPE_LOCATION', '35037')
        results = scraper.scrape_all_fuel_types(plz=plz)
        total = sum(len(v) for v in results.values())
        logger.info(f"Scheduled scrape complete: {total} records saved.")
    except Exception as e:
        logger.error(f"Scheduled scrape failed: {e}")

def main():
    print("--- TankRadar Starting ---")

    # If using Dash debug mode with reloader, background tasks should only start
    # in the child process to avoid duplicate jobs and threading conflicts.
    if os.environ.get("WERKZEUG_RUN_MAIN") == "true" or not getattr(config, 'DASH_DEBUG', True):
        # Start the background scraper (every 15 minutes)
        scheduler = BackgroundScheduler()
        interval = getattr(config, 'SCRAPE_INTERVAL_MINUTES', 15)
        scheduler.add_job(
            run_scrape_job,
            trigger='interval',
            minutes=interval,
            id='adac_scraper',
            name='ADAC Price Scraper',
            replace_existing=True,
        )
        scheduler.start()
        print(f"[OK] ADAC Scraper scheduled (every {interval} min)")

        # Run an initial scrape immediately in the background
        print("[INFO] Running initial scrape in background...")
        import threading
        threading.Thread(target=run_scrape_job, daemon=True).start()
    else:
        print("[INFO] Main process waiting for reloader child...")

    # Start the Dashboard (blocking)
    print("Starting Dashboard on http://127.0.0.1:8050")
    dashboard = TankRadarDashboard()
    dashboard.run(debug=True, port=8050)

if __name__ == "__main__":
    main()
