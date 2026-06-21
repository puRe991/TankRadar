import os
from importlib.util import find_spec
from pathlib import Path

if find_spec("dotenv") is not None:
    from dotenv import load_dotenv

    load_dotenv()

BASE_DIR = Path(__file__).resolve().parent
LOG_DIR = BASE_DIR / "logs"
DATABASE_LOG_FILE = LOG_DIR / "database.log"
ASSETS_DIR = BASE_DIR / "assets"
PRICE_HISTORY_CSV = Path(
    os.getenv("TANKRADAR_CSV", str(BASE_DIR / "prices_history.csv"))
).expanduser()

# Database Configuration
# Use SQLite by default for easy setup, or PostgreSQL if URL is provided
DATABASE_URL = os.getenv("DATABASE_URL", f"sqlite:///{BASE_DIR / 'tankradar.db'}")

# Update Interval in minutes (Still used for dashboard refresh interval)
UPDATE_INTERVAL = 5

# ML Settings
PREDICTION_HORIZON_HOURS = 24
MIN_DATA_POINTS_FOR_ML = 10

# Default Station IDs (optional fallback)
STATION_IDS = []

# Scraper Settings
DEFAULT_SCRAPE_LOCATION = "35444"  # Biebertal as default
SCRAPE_INTERVAL_MINUTES = 15      # Auto-scrape every N minutes

# Cloud Sync Settings (GitHub)
# Raw CSV written by .github/workflows/scraper.yml. Override via GITHUB_CSV_URL for forks.
DEFAULT_GITHUB_CSV_URL = "https://raw.githubusercontent.com/puRe991/TankRadar/main/prices_history.csv"
GITHUB_CSV_URL = os.getenv("GITHUB_CSV_URL", DEFAULT_GITHUB_CSV_URL)
