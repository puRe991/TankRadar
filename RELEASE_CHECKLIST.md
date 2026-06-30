# TankRadar Release Checklist

Use this checklist before publishing the first full release.

## 1. Version and branch hygiene

- Work from a clean branch and run `git status --short`.
- Decide the release version and tag format, for example `v1.0.0`.
- Confirm no local runtime files are staged: databases, logs, virtual environments, caches, or `.env` files.

## 2. Configuration

- Copy `.env.example` to `.env` for local overrides.
- Confirm `DATABASE_URL` points to SQLite for desktop use or PostgreSQL for hosted use.
- Confirm scraper defaults: `TANKRADAR_PLZ`, `TANKRADAR_DISTANCE`, and `TANKRADAR_SCRAPE_INTERVAL_MINUTES`.
- Confirm the cloud CSV source through `GITHUB_CSV_URL` when using a fork.

## 3. Quality gates

Run these checks before tagging:

```bash
python -m compileall -q .
pytest -q
python -m pyright
```

If a dependency is missing, install from `requirements.txt` in a fresh virtual environment and repeat the checks.

## 4. Smoke test

- Start the app with `python main.py`.
- Open `http://127.0.0.1:8050`.
- Verify latest prices, price-change cases, predictions, and refuel log views load without tracebacks.
- Stop the app cleanly with `Ctrl+C`.

## 5. Release notes

Document:

- Supported Python versions and platforms.
- Data sources and known fragility of the ADAC persisted-query hash.
- Privacy note: local database and refuel log remain on the user's machine unless explicitly synced.
- Known limitations of forecasts: they are statistical estimates, not guaranteed cheapest-price decisions.

## 6. Tag and publish

```bash
git tag -a v1.0.0 -m "TankRadar v1.0.0"
git push origin v1.0.0
```

Create the release from the tag and attach any Windows installer/start scripts if needed.
