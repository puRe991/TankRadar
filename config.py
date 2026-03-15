import os
from dotenv import load_dotenv

load_dotenv()

# Database Configuration
# Use SQLite by default for easy setup, or PostgreSQL if URL is provided
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///tankradar.db")

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
# Replace with your own raw URL once repo is set up
GITHUB_CSV_URL = os.getenv("GITHUB_CSV_URL", "")
