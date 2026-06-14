import logging
import os
import time
from sqlalchemy import create_engine, Column, Integer, Float, String, DateTime, Index, event
from sqlalchemy.engine import make_url
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import declarative_base, sessionmaker
from datetime import datetime
import config

# Setup professional logging
os.makedirs(config.LOG_DIR, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s',
    handlers=[
        logging.FileHandler(config.DATABASE_LOG_FILE),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("TankRadar.Database")

SQLITE_LOCK_RETRY_ATTEMPTS = 3
SQLITE_LOCK_RETRY_DELAY_SECONDS = 0.5

Base = declarative_base()

class Station(Base):
    __tablename__ = 'stations'

    id = Column(String(50), primary_key=True)
    name = Column(String(100), nullable=False)
    brand = Column(String(50))
    street = Column(String(100))
    house_number = Column(String(20))
    post_code = Column(String(10))
    city = Column(String(50))
    latitude = Column(Float)
    longitude = Column(Float)
    is_favorite = Column(Integer, default=0) # 0 for False, 1 for True

class FuelPrice(Base):
    __tablename__ = 'fuel_prices'

    id = Column(Integer, primary_key=True)
    station_id = Column(String(50), nullable=False)
    timestamp = Column(DateTime, default=datetime.utcnow, nullable=False)
    fuel_type = Column(String(10), nullable=False) # e5, e10, diesel
    price = Column(Float, nullable=False)
    latitude = Column(Float)
    longitude = Column(Float)

    # Index for faster queries on station and time
    __table_args__ = (
        Index('idx_station_timestamp', 'station_id', 'timestamp'),
    )

class RefuelLog(Base):
    __tablename__ = 'refuel_logs'

    id = Column(Integer, primary_key=True)
    station_id = Column(String(50), nullable=True) # Optional link to a known station
    station_name_fallback = Column(String(100), nullable=True) # If manual entry without known station
    timestamp = Column(DateTime, default=datetime.utcnow, nullable=False)
    fuel_type = Column(String(10), nullable=False)
    liters = Column(Float, nullable=False)
    price_per_liter = Column(Float, nullable=False)
    total_cost = Column(Float, nullable=False)
    odometer = Column(Integer, nullable=True) # In km
    notes = Column(String(250), nullable=True)

class DatabaseManager:
    SQLITE_CONNECT_ARGS = {"check_same_thread": False, "timeout": 30}

    def __init__(self):
        try:
            database_url = make_url(config.DATABASE_URL)
            engine_kwargs = {}

            if database_url.get_backend_name() == "sqlite":
                engine_kwargs["connect_args"] = self.SQLITE_CONNECT_ARGS

            self.engine = create_engine(config.DATABASE_URL, **engine_kwargs)

            if database_url.get_backend_name() == "sqlite":
                self._register_sqlite_pragmas()

            Base.metadata.create_all(self.engine, checkfirst=True)
            self._migrate_schema()
            self.Session = sessionmaker(bind=self.engine)
            logger.info(f"Database initialized at {config.DATABASE_URL}")
        except Exception as e:
            logger.error(f"Failed to initialize database: {e}")
            raise

    def _register_sqlite_pragmas(self):
        """Register SQLite-only PRAGMA settings for every new DB-API connection."""

        @event.listens_for(self.engine, "connect")
        def set_sqlite_pragma(dbapi_connection, connection_record):
            cursor = dbapi_connection.cursor()
            try:
                cursor.execute("PRAGMA journal_mode=WAL")
                cursor.execute("PRAGMA synchronous=NORMAL")
                cursor.execute("PRAGMA busy_timeout=30000")
            finally:
                cursor.close()

    def _migrate_schema(self):
        """Add any missing columns to existing tables (lightweight migration)."""
        from sqlalchemy import text, inspect as sa_inspect
        inspector = sa_inspect(self.engine)

        # Check the 'stations' table for missing columns
        if 'stations' in inspector.get_table_names():
            existing = {col['name'] for col in inspector.get_columns('stations')}
            migrations = {
                'is_favorite': 'INTEGER DEFAULT 0',
                'street':      'VARCHAR(100)',
                'house_number':'VARCHAR(20)',
                'post_code':   'VARCHAR(10)',
                'city':        'VARCHAR(50)',
                'latitude':    'FLOAT',
                'longitude':   'FLOAT',
            }
            with self.engine.connect() as conn:
                for col_name, col_type in migrations.items():
                    if col_name not in existing:
                        logger.info(f"Migrating: adding column '{col_name}' to 'stations'")
                        conn.execute(text(f'ALTER TABLE stations ADD COLUMN {col_name} {col_type}'))
                        conn.commit()

        # Check for refuel_logs table and create it directly via Base if missing, it's safer
        if 'refuel_logs' not in inspector.get_table_names():
            logger.info("Migrating: creating 'refuel_logs' table")
            RefuelLog.__table__.create(self.engine)

    def add_station(self, station_id: str, name: str, brand: str = None, city: str = None):
        from schemas import StationSchema
        try:
            # Validate with 2026-standard Pydantic
            data = StationSchema(id=station_id, name=name, brand=brand, city=city)
            session = self.Session()
            try:
                logger.info(f"Adding/Updating station: {data.name} ({data.id})")
                new_station = Station(
                    id=data.id,
                    name=data.name,
                    brand=data.brand,
                    city=data.city
                )
                session.merge(new_station)
                session.commit()
                logger.info(f"Successfully saved station {data.id}")
            except Exception as e:
                session.rollback()
                logger.error(f"Error adding station {data.id}: {e}")
            finally:
                session.close()
        except Exception as e:
            logger.error(f"Validation failed for station: {e}")

    def get_all_stations(self):
        session = self.Session()
        try:
            return session.query(Station).all()
        except Exception as e:
            logger.error(f"Error fetching stations: {e}")
            return []
        finally:
            session.close()

    def add_price(self, station_id: str, fuel_type: str, price: float, timestamp=None):
        from schemas import FuelPriceSchema
        try:
            # Validate with Pydantic. Cloud sync may pass the original scrape timestamp.
            payload = {"station_id": station_id, "fuel_type": fuel_type, "price": price}
            if timestamp is not None:
                payload["timestamp"] = timestamp
            data = FuelPriceSchema(**payload)
            session = self.Session()
            try:
                logger.info(f"Adding price: {data.price}€ for {data.fuel_type} at {data.station_id}")
                new_price = FuelPrice(
                    station_id=data.station_id,
                    fuel_type=data.fuel_type,
                    price=data.price,
                    timestamp=data.timestamp
                )
                session.add(new_price)
                session.commit()
                logger.info(f"Successfully saved price for {data.station_id}")
                return True
            except Exception as e:
                session.rollback()
                logger.error(f"Error adding price for {data.station_id}: {e}")
                return False
            finally:
                session.close()
        except Exception as e:
            logger.error(f"Validation failed for price: {e}")
            return False

    @staticmethod
    def _is_sqlite_locked_error(error):
        """Return True for SQLite lock errors raised through SQLAlchemy."""
        if not isinstance(error, OperationalError):
            return False
        return "database is locked" in str(error).lower()

    def bulk_import_cloud_prices(self, rows):
        """Import normalized cloud rows and create missing station records.

        Cloud rows are de-duplicated by station, fuel type and scrape timestamp.
        Do not skip rows only because the local database already contains newer
        prices: a local/manual scrape can be newer than the cloud CSV while the
        cloud CSV still contains missing historical station/fuel combinations.

        The import performs duplicate checks while new rows are pending in the
        SQLAlchemy session. Those reads must not trigger an autoflush, because
        SQLite allows only one writer at a time and a premature flush can fail
        with ``database is locked`` while the dashboard or scraper is writing.
        """
        rows = tuple(rows)
        last_error = None
        for attempt in range(1, SQLITE_LOCK_RETRY_ATTEMPTS + 1):
            session = self.Session()
            stats = {
                "inserted": 0,
                "stations_upserted": 0,
                "skipped_duplicate": 0,
                "skipped_invalid": 0,
                # Kept for older dashboard/log code that may still read this key.
                "skipped_old": 0,
            }
            try:
                station_cache = {}
                price_cache = set()

                with session.no_autoflush:
                    for row in sorted(rows, key=lambda item: item.timestamp):
                        if not row.station_id or row.fuel_type not in {"e5", "e10", "e5p", "diesel"} or row.price <= 0:
                            stats["skipped_invalid"] += 1
                            continue

                        # Always repair/create station metadata, even when the price row
                        # itself is already present. This fixes installations that have
                        # prices but no station rows to render cards.
                        if row.station_id not in station_cache:
                            station_cache[row.station_id] = session.get(Station, row.station_id)

                        station = station_cache[row.station_id]
                        if station is None:
                            station = Station(
                                id=row.station_id,
                                name=row.station_name or f"Station {row.station_id}",
                                brand=row.brand,
                                city=row.city,
                            )
                            session.add(station)
                            station_cache[row.station_id] = station
                            stats["stations_upserted"] += 1
                        else:
                            changed = False
                            if row.station_name and (not station.name or station.name.startswith("Station ")):
                                station.name = row.station_name
                                changed = True
                            if row.brand and not station.brand:
                                station.brand = row.brand
                                changed = True
                            if row.city and not station.city:
                                station.city = row.city
                                changed = True
                            if changed:
                                stats["stations_upserted"] += 1

                        price_key = (row.station_id, row.fuel_type, row.timestamp)
                        if price_key not in price_cache:
                            price_exists = session.query(FuelPrice.id).filter(
                                FuelPrice.station_id == row.station_id,
                                FuelPrice.fuel_type == row.fuel_type,
                                FuelPrice.timestamp == row.timestamp,
                            ).first()
                            if price_exists is not None:
                                price_cache.add(price_key)

                        if price_key in price_cache:
                            stats["skipped_duplicate"] += 1
                            continue

                        session.add(FuelPrice(
                            station_id=row.station_id,
                            fuel_type=row.fuel_type,
                            price=row.price,
                            timestamp=row.timestamp,
                        ))
                        price_cache.add(price_key)
                        stats["inserted"] += 1

                session.commit()
                logger.info(
                    "Cloud import completed: %(inserted)s prices, %(stations_upserted)s stations, "
                    "%(skipped_duplicate)s duplicate rows skipped, %(skipped_invalid)s invalid rows skipped",
                    stats,
                )
                return stats
            except Exception as e:
                session.rollback()
                if self._is_sqlite_locked_error(e) and attempt < SQLITE_LOCK_RETRY_ATTEMPTS:
                    last_error = e
                    logger.warning(
                        "Cloud import hit a locked SQLite database; retrying attempt %s/%s",
                        attempt + 1,
                        SQLITE_LOCK_RETRY_ATTEMPTS,
                    )
                    time.sleep(SQLITE_LOCK_RETRY_DELAY_SECONDS * attempt)
                    continue
                logger.error(f"Error importing cloud prices: {e}")
                raise
            finally:
                session.close()

        # Defensive fallback; the loop either returns or raises.
        if last_error is not None:
            raise last_error
        raise RuntimeError("Cloud import failed without an explicit database error")

    def get_historical_data(self, station_id, days=365):
        import pandas as pd
        from datetime import timedelta
        
        start_date = datetime.now() - timedelta(days=days)
        
        session = self.Session()
        try:
            query = session.query(FuelPrice).filter(
                FuelPrice.station_id == station_id,
                FuelPrice.timestamp >= start_date
            ).order_by(FuelPrice.timestamp.asc())
            
            results = query.all()
            columns = [c.name for c in FuelPrice.__table__.columns]
            
            data = []
            for item in results:
                d = {col: getattr(item, col) for col in columns}
                data.append(d)
            
            return pd.DataFrame(data, columns=columns)
        except Exception as e:
            logger.error(f"Error fetching historical data for {station_id}: {e}")
            return pd.DataFrame(columns=[c.name for c in FuelPrice.__table__.columns])
        finally:
            session.close()

    def get_price_change_cases(self, cutoff_hour=12, days=30, now=None):
        """Return changed prices after the cutoff, enriched with station details."""
        from compliance_report import detect_price_change_cases

        session = self.Session()
        try:
            price_rows = [
                {column.name: getattr(item, column.name) for column in FuelPrice.__table__.columns}
                for item in session.query(FuelPrice).order_by(FuelPrice.timestamp.asc()).all()
            ]
            stations = [
                {column.name: getattr(item, column.name) for column in Station.__table__.columns}
                for item in session.query(Station).all()
            ]
            return detect_price_change_cases(price_rows, stations, cutoff_hour=cutoff_hour, days=days, now=now)
        except Exception as e:
            logger.error(f"Error fetching price change cases: {e}")
            return detect_price_change_cases([], [], cutoff_hour=cutoff_hour, days=days, now=now)
        finally:
            session.close()

    def get_latest_prices(self):
        import pandas as pd
        session = self.Session()
        try:
            query = session.query(FuelPrice).order_by(FuelPrice.timestamp.desc())
            results = query.all()
            columns = [c.name for c in FuelPrice.__table__.columns]
            
            data = []
            for item in results:
                d = {col: getattr(item, col) for col in columns}
                data.append(d)
                
            df = pd.DataFrame(data, columns=columns)
            if df.empty:
                return df
            
            # Filter out prices for stations that no longer exist
            valid_station_ids = [s.id for s in session.query(Station).all()]
            df = df[df['station_id'].isin(valid_station_ids)]
            
            # Sort by timestamp ascending to process history
            df = df.sort_values(['station_id', 'fuel_type', 'timestamp'])
            
            # To find the true *previous different* price, we can drop consecutive duplicates first,
            # calculate the shifted price on that reduced set, and then merge it back.
            
            # 1. Reduce to only rows where the price changed
            changed_df = df.loc[df['price'] != df.groupby(['station_id', 'fuel_type'])['price'].shift(1)]
            
            # 2. Calculate previous price on the reduced set
            changed_df = changed_df.copy()
            changed_df['previous_price'] = changed_df.groupby(['station_id', 'fuel_type'])['price'].shift(1)
            
            # 3. We only need the very latest known state for the dashboard.
            # The easiest way is to take the *last* row of the full dataframe (current price),
            # and take the *last* row of the changed_df to see what it changed from.
            
            latest_full = df.drop_duplicates(subset=['station_id', 'fuel_type'], keep='last').copy()
            latest_changed = changed_df.drop_duplicates(subset=['station_id', 'fuel_type'], keep='last')
            
            # Map the previous_price from latest_changed onto latest_full
            # (If the price never changed since the first record, previous_price will be NaN)
            for idx, row in latest_full.iterrows():
                s_id = row['station_id']
                f_type = row['fuel_type']
                
                # Find the matching row in latest_changed
                match = latest_changed[(latest_changed['station_id'] == s_id) & (latest_changed['fuel_type'] == f_type)]
                if not match.empty:
                    # If the latest full row IS the same as the latest changed row, use its previous_price
                    # If it's NOT (meaning prices stayed the same for a while), the previous price to the current
                    # is whatever the last change's *current* price was? Wait.
                    # No, if the latest full row has price 2.00, and latest_changed has price 2.00 (and prev 1.95),
                    # we want previous_price=1.95.
                    latest_full.at[idx, 'previous_price'] = match.iloc[-1]['previous_price']
                else:
                    latest_full.at[idx, 'previous_price'] = None
                    
            return latest_full
        except Exception as e:
            logger.error(f"Error fetching latest prices: {e}")
            return pd.DataFrame(columns=[c.name for c in FuelPrice.__table__.columns])
        finally:
            session.close()

    def toggle_favorite(self, station_id: str):
        session = self.Session()
        try:
            station = session.query(Station).filter(Station.id == station_id).first()
            if station:
                station.is_favorite = 1 if not station.is_favorite else 0
                session.commit()
                logger.info(f"Toggled favorite for station {station_id}: {station.is_favorite}")
                return True
            return False
        except Exception as e:
            session.rollback()
            logger.error(f"Error toggling favorite for {station_id}: {e}")
            return False
        finally:
            session.close()

    def get_favorite_stations(self):
        session = self.Session()
        try:
            return session.query(Station).filter(Station.is_favorite == 1).all()
        except Exception as e:
            logger.error(f"Error fetching favorite stations: {e}")
            return []
        finally:
            session.close()

    def delete_station(self, station_id: str):
        session = self.Session()
        try:
            logger.info(f"Deleting station and data for: {station_id}")
            # Delete prices first (referential integrity)
            session.query(FuelPrice).filter(FuelPrice.station_id == station_id).delete()
            # Delete station
            session.query(Station).filter(Station.id == station_id).delete()
            session.commit()
            logger.info(f"Successfully deleted station {station_id}")
            return True
        except Exception as e:
            session.rollback()
            logger.error(f"Error deleting station {station_id}: {e}")
            return False
        finally:
            session.close()

    # --- Refuel Logbook Methods ---

    def add_refuel_entry(self, fuel_type: str, liters: float, price_per_liter: float, total_cost: float, 
                         station_id: str = None, station_name_fallback: str = None, 
                         odometer: int = None, notes: str = None):
        from schemas import RefuelLogSchema
        try:
            # Validate
            data = RefuelLogSchema(
                station_id=station_id,
                station_name_fallback=station_name_fallback,
                fuel_type=fuel_type,
                liters=liters,
                price_per_liter=price_per_liter,
                total_cost=total_cost,
                odometer=odometer,
                notes=notes
            )
            session = self.Session()
            try:
                new_entry = RefuelLog(
                    station_id=data.station_id,
                    station_name_fallback=data.station_name_fallback,
                    timestamp=data.timestamp,
                    fuel_type=data.fuel_type,
                    liters=data.liters,
                    price_per_liter=data.price_per_liter,
                    total_cost=data.total_cost,
                    odometer=data.odometer,
                    notes=data.notes
                )
                session.add(new_entry)
                session.commit()
                logger.info("Successfully saved refuel log entry.")
                return True
            except Exception as e:
                session.rollback()
                logger.error(f"Error saving refuel log: {e}")
                return False
            finally:
                session.close()
        except Exception as e:
            logger.error(f"Validation failed for refuel entry: {e}")
            return False

    def get_refuel_logs(self):
        import pandas as pd
        session = self.Session()
        try:
            results = session.query(RefuelLog, Station.name, Station.brand).outerjoin(
                Station, RefuelLog.station_id == Station.id
            ).order_by(RefuelLog.timestamp.desc()).all()
            
            data = []
            for log, s_name, s_brand in results:
                d = {col: getattr(log, col) for col in RefuelLog.__table__.columns.keys()}
                # Construct a display name: either the linked station name or the fallback
                if log.station_id and s_name:
                    d['station_display'] = f"{s_brand + ' ' if s_brand else ''}{s_name}"
                elif log.station_name_fallback:
                    d['station_display'] = log.station_name_fallback
                else:
                    d['station_display'] = "Unbekannt"
                    
                data.append(d)
                
            return pd.DataFrame(data)
        except Exception as e:
            logger.error(f"Error fetching refuel logs: {e}")
            return pd.DataFrame() # Empty DataFrame on error
        finally:
            session.close()
            
    def delete_refuel_entry(self, entry_id: int):
        session = self.Session()
        try:
            session.query(RefuelLog).filter(RefuelLog.id == entry_id).delete()
            session.commit()
            return True
        except Exception as e:
            session.rollback()
            logger.error(f"Error deleting refuel entry {entry_id}: {e}")
            return False
        finally:
            session.close()
