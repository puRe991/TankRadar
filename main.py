import logging
import os
import socket
import threading
import time

from apscheduler.schedulers.background import BackgroundScheduler

import config
from adac_scraper import ADACScraper
from database import DatabaseManager
from visualization_dashboard import TankRadarDashboard

os.environ["POLARS_SKIP_CPU_CHECK"] = "1"

logger = logging.getLogger("TankRadar.Main")


def _wait_for_server(host: str, port: int, timeout: float = 15.0) -> bool:
    """Poll the dashboard socket until it accepts connections or the timeout elapses."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return True
        except OSError:
            time.sleep(0.1)
    return False


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

    native_window = bool(getattr(config, "NATIVE_WINDOW", True))
    if native_window:
        try:
            import webview
        except ImportError:
            logger.warning("pywebview nicht installiert, oeffne im Browser stattdessen.")
            native_window = False

    # The Werkzeug reloader (debug mode) needs the main thread and re-execs the
    # process, so it is incompatible with running the server in a background
    # thread for the native window. Force it off in that case.
    if native_window and debug:
        logger.warning("TANKRADAR_DASH_DEBUG wird im Fenstermodus ignoriert (Reloader braucht den Hauptthread).")
        debug = False

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

    dashboard = TankRadarDashboard()

    if native_window:
        print(f"Starting Dashboard on http://{host}:{port}")
        server_thread = threading.Thread(
            target=dashboard.run, kwargs={"debug": debug, "host": host, "port": port}, daemon=True
        )
        server_thread.start()

        if not _wait_for_server(host, port):
            logger.warning("Dashboard-Server antwortet nicht rechtzeitig, oeffne Fenster trotzdem.")

        webview.create_window(
            config.WINDOW_TITLE,
            f"http://{host}:{port}",
            width=config.WINDOW_WIDTH,
            height=config.WINDOW_HEIGHT,
        )
        webview.start()
    else:
        # Start the Dashboard (blocking)
        print(f"Starting Dashboard on http://{host}:{port}")
        dashboard.run(debug=debug, host=host, port=port)


if __name__ == "__main__":
    main()
