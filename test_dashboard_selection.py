import pandas as pd

from visualization_dashboard import TankRadarDashboard


class FakeDatabase:
    def get_latest_prices(self):
        return pd.DataFrame(
            [
                {"station_id": "station-e5", "fuel_type": "e5", "price": 1.70},
                {"station_id": "station-diesel", "fuel_type": "diesel", "price": 1.60},
            ]
        )


def test_station_ids_with_fuel_returns_string_ids_for_selected_fuel():
    dashboard = object.__new__(TankRadarDashboard)
    dashboard.db = FakeDatabase()

    assert dashboard._station_ids_with_fuel("e5") == {"station-e5"}
    assert dashboard._station_ids_with_fuel("diesel") == {"station-diesel"}
    assert dashboard._station_ids_with_fuel("e10") == set()


def test_empty_figure_hides_default_axes_and_shows_message():
    dashboard = object.__new__(TankRadarDashboard)

    figure = dashboard._empty_figure("Keine Preisdaten")

    assert figure.layout.xaxis.visible is False
    assert figure.layout.yaxis.visible is False
    assert figure.layout.annotations[0].text == "Keine Preisdaten"
