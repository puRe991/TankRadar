import html
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


def _splash_html(title: str) -> str:
    """Standalone HTML for the startup splash window (no server dependency)."""
    return f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
    html, body {{
        margin: 0;
        height: 100%;
        background: #05050a;
        overflow: hidden;
        font-family: -apple-system, "Segoe UI", Roboto, Arial, sans-serif;
        -webkit-user-select: none;
        user-select: none;
    }}
    .splash {{
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        height: 100%;
        gap: 22px;
        background: radial-gradient(circle at 50% 30%, rgba(0, 242, 255, 0.12), transparent 60%), #05050a;
        border: 1px solid rgba(255, 255, 255, 0.08);
        box-sizing: border-box;
    }}
    .logo {{
        font-size: 44px;
        line-height: 1;
        filter: drop-shadow(0 0 12px rgba(0, 242, 255, 0.55));
    }}
    .title {{
        color: #f0f0f5;
        font-size: 22px;
        font-weight: 700;
        letter-spacing: 0.06em;
    }}
    .bar-track {{
        width: 260px;
        height: 6px;
        border-radius: 999px;
        background: rgba(255, 255, 255, 0.08);
        overflow: hidden;
    }}
    .bar-fill {{
        height: 100%;
        width: 0%;
        border-radius: 999px;
        background: linear-gradient(90deg, #00f2ff 0%, #00ffaa 100%);
        transition: width 0.25s ease-out;
    }}
    .status {{
        color: #9494b8;
        font-size: 12px;
        letter-spacing: 0.03em;
        min-height: 14px;
    }}
</style>
</head>
<body>
    <div class="splash">
        <div class="logo">⛽</div>
        <div class="title">{html.escape(title)}</div>
        <div class="bar-track"><div class="bar-fill" id="bar"></div></div>
        <div class="status" id="status">Initialisiere...</div>
    </div>
    <script>
        function trSetProgress(pct, label) {{
            document.getElementById('bar').style.width = Math.max(0, Math.min(100, pct)) + '%';
            if (label) {{
                document.getElementById('status').textContent = label;
            }}
        }}
    </script>
</body>
</html>"""


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
        splash = webview.create_window(
            config.WINDOW_TITLE,
            html=_splash_html(config.WINDOW_TITLE),
            width=420,
            height=260,
            frameless=True,
            easy_drag=True,
            on_top=True,
            background_color="#05050a",
        )

        def _start_and_wait():
            print(f"Starting Dashboard on http://{host}:{port}")
            server_thread = threading.Thread(
                target=dashboard.run, kwargs={"debug": debug, "host": host, "port": port}, daemon=True
            )
            server_thread.start()

            timeout = 15.0
            deadline = time.time() + timeout
            connected = False
            while time.time() < deadline:
                try:
                    with socket.create_connection((host, port), timeout=0.5):
                        connected = True
                        break
                except OSError:
                    elapsed = timeout - (deadline - time.time())
                    pct = min(95, int(elapsed / timeout * 95))
                    try:
                        splash.evaluate_js(f"trSetProgress({pct}, 'Starte Dashboard-Server...')")
                    except Exception:
                        pass
                    time.sleep(0.2)

            if not connected:
                logger.warning("Dashboard-Server antwortet nicht rechtzeitig, oeffne Fenster trotzdem.")

            try:
                splash.evaluate_js("trSetProgress(100, 'Fertig!')")
            except Exception:
                pass
            time.sleep(0.3)

            webview.create_window(
                config.WINDOW_TITLE,
                f"http://{host}:{port}",
                width=config.WINDOW_WIDTH,
                height=config.WINDOW_HEIGHT,
            )
            try:
                splash.destroy()
            except Exception:
                pass

        webview.start(_start_and_wait)
    else:
        # Start the Dashboard (blocking)
        print(f"Starting Dashboard on http://{host}:{port}")
        dashboard.run(debug=debug, host=host, port=port)


if __name__ == "__main__":
    main()
