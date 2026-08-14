from pydantic import BaseModel, Field
from datetime import datetime
from typing import Optional, List

class StationSchema(BaseModel):
    id: str = Field(..., description="Unique UUID for the station")
    name: str = Field(..., min_length=1, description="Display name of the station")
    brand: Optional[str] = None
    city: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None

class FuelPriceSchema(BaseModel):
    station_id: str
    fuel_type: str = Field(..., pattern="^(e5|e10|e5p|diesel)$")
    price: float = Field(..., gt=0)
    timestamp: datetime = Field(default_factory=datetime.now)

class DashboardState(BaseModel):
    selected_station_id: Optional[str] = None
    fuel_type: str = "e10"

class RefuelLogSchema(BaseModel):
    station_id: Optional[str] = None
    station_name_fallback: Optional[str] = None
    timestamp: datetime = Field(default_factory=datetime.now)
    # Must stay in sync with FuelPriceSchema: the logbook dialog offers the same
    # four fuel types, so "Super Plus" (e5p) has to validate here as well.
    fuel_type: str = Field(..., pattern="^(e5|e10|e5p|diesel)$")
    liters: float = Field(..., gt=0)
    price_per_liter: float = Field(..., gt=0)
    total_cost: float = Field(..., gt=0)
    odometer: Optional[int] = Field(None, gt=0)
    notes: Optional[str] = Field(None, max_length=250)
