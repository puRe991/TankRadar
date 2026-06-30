import logging
import os
import threading

from apscheduler.schedulers.background import BackgroundScheduler

import config
from adac_scraper import ADACScraper
from database import DatabaseManager
from visualization_dashboard import TankRadarDashboard

os.environ["POLARS_SKIP_CPU_CHECK"] = "1"

logger = logging.getLogger("TankRadar.Main")


def run_scrape_job():
    """Background job: scrape all fuel types from ADAC."""
    try:
        db = DatabaseManager()
        scraper = ADACScraper(db)
        plz = getattr(config, "DEFAULT_SCRAPE_LOCATION", "35037")
        results = scraper.scrape_all_fuel_types(plz=plz)
        total = sum(len(v) for v in results.values())
        logger.info("Scheduled scrape complete: %s records saved.", total)
    except Exception as e:
        logger.exception("Scheduled scrape failed: %s", e)


def main():
    print("--- TankRadar Starting ---")
    debug = bool(getattr(config, "DASH_DEBUG", False))
    host = getattr(config, "DASH_HOST", "127.0.0.1")
    port = getattr(config, "DASH_PORT", 8050)

    # If using Dash debug mode with reloader, background tasks should only start
    # in the child process to avoid duplicate jobs and threading conflicts.
    if os.environ.get("WERKZEUG_RUN_MAIN") == "true" or not debug:
        # Start the background scraper (every 15 minutes)
        scheduler = BackgroundScheduler()
        interval = getattr(config, "SCRAPE_INTERVAL_MINUTES", 15)
        scheduler.add_job(
            run_scrape_job,
            trigger="interval",
            minutes=interval,
            id="adac_scraper",
            name="ADAC Price Scraper",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )
        scheduler.start()
        print(f"[OK] ADAC Scraper scheduled (every {interval} min)")

        # Run an initial scrape immediately in the background
        print("[INFO] Running initial scrape in background...")
        threading.Thread(target=run_scrape_job, daemon=True).start()
    else:
        print("[INFO] Main process waiting for reloader child...")

    # Start the Dashboard (blocking)
    print(f"Starting Dashboard on http://{host}:{port}")
    dashboard = TankRadarDashboard()
    dashboard.run(debug=debug, host=host, port=port)


if __name__ == "__main__":
    main()
