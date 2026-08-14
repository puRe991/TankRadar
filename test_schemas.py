import pytest

from schemas import FuelPriceSchema, RefuelLogSchema

SUPPORTED_FUEL_TYPES = ["e5", "e10", "e5p", "diesel"]


@pytest.mark.parametrize("fuel_type", SUPPORTED_FUEL_TYPES)
def test_fuel_price_schema_accepts_every_offered_fuel_type(fuel_type):
    assert FuelPriceSchema(station_id="s1", fuel_type=fuel_type, price=1.759).fuel_type == fuel_type


@pytest.mark.parametrize("fuel_type", SUPPORTED_FUEL_TYPES)
def test_refuel_log_schema_accepts_every_offered_fuel_type(fuel_type):
    """The logbook dialog offers Super Plus, so e5p has to validate here too."""
    entry = RefuelLogSchema(
        fuel_type=fuel_type,
        liters=42.0,
        price_per_liter=1.759,
        total_cost=73.88,
    )

    assert entry.fuel_type == fuel_type


def test_refuel_log_schema_still_rejects_unknown_fuel_types():
    with pytest.raises(ValueError):
        RefuelLogSchema(fuel_type="lpg", liters=1.0, price_per_liter=1.0, total_cost=1.0)
