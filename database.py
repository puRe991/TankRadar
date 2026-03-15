import logging
import os
from sqlalchemy import create_engine, Column, Integer, Float, String, DateTime, Index
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from datetime import datetime
import config

# Setup professional logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s',
    handlers=[
        logging.FileHandler("logs/database.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("TankRadar.Database")

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
    def __init__(self):
        try:
            from sqlalchemy import event
            # Use connect_args to increase timeout and allow multi-thread access
            self.engine = create_engine(
                config.DATABASE_URL, 
                connect_args={"check_same_thread": False, "timeout": 30}
            )
            
            # Enable WAL mode for better concurrency
            @event.listens_for(self.engine, "connect")
            def set_sqlite_pragma(dbapi_connection, connection_record):
                cursor = dbapi_connection.cursor()
                cursor.execute("PRAGMA journal_mode=WAL")
                cursor.execute("PRAGMA synchronous=NORMAL")
                cursor.close()

            Base.metadata.create_all(self.engine, checkfirst=True)
            self._migrate_schema()
            self.Session = sessionmaker(bind=self.engine)
            logger.info(f"Database initialized at {config.DATABASE_URL}")
        except Exception as e:
            logger.error(f"Failed to initialize database: {e}")
            raise

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

    def add_price(self, station_id: str, fuel_type: str, price: float):
        from schemas import FuelPriceSchema
        try:
            # Validate with Pydantic
            data = FuelPriceSchema(station_id=station_id, fuel_type=fuel_type, price=price)
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
            except Exception as e:
                session.rollback()
                logger.error(f"Error adding price for {data.station_id}: {e}")
            finally:
                session.close()
        except Exception as e:
            logger.error(f"Validation failed for price: {e}")

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
