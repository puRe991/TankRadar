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


def _get_int_env(name: str, default: int, *, minimum: int | None = None, maximum: int | None = None) -> int:
    """Read an integer environment variable with safe fallback bounds."""
    raw_value = os.getenv(name)
    if raw_value is None or raw_value.strip() == "":
        return default

    try:
        value = int(raw_value)
    except ValueError:
        return default

    if minimum is not None:
        value = max(minimum, value)
    if maximum is not None:
        value = min(maximum, value)
    return value


def _get_bool_env(name: str, default: bool) -> bool:
    """Read a boolean environment variable using common true/false spellings."""
    raw_value = os.getenv(name)
    if raw_value is None:
        return default

    normalized = raw_value.strip().lower()
    if normalized in {"1", "true", "yes", "y", "on"}:
        return True
    if normalized in {"0", "false", "no", "n", "off"}:
        return False
    return default


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
DEFAULT_SCRAPE_LOCATION = os.getenv("TANKRADAR_PLZ", "35444").strip() or "35444"
SCRAPE_INTERVAL_MINUTES = _get_int_env("TANKRADAR_SCRAPE_INTERVAL_MINUTES", 15, minimum=1, maximum=1440)

# Dashboard Settings
DASH_HOST = os.getenv("TANKRADAR_DASH_HOST", "127.0.0.1").strip() or "127.0.0.1"
DASH_PORT = _get_int_env("TANKRADAR_DASH_PORT", 8050, minimum=1, maximum=65535)
DASH_DEBUG = _get_bool_env("TANKRADAR_DASH_DEBUG", False)

# Cloud Sync Settings (GitHub)
# Raw CSV written by .github/workflows/scraper.yml. Override via GITHUB_CSV_URL for forks.
DEFAULT_GITHUB_CSV_URL = "https://raw.githubusercontent.com/puRe991/TankRadar/main/prices_history.csv"
GITHUB_CSV_URL = os.getenv("GITHUB_CSV_URL", DEFAULT_GITHUB_CSV_URL)
