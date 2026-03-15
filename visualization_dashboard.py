import dash
from dash import Dash, html, dcc, dash_table, Input, Output, State, callback_context, ALL
print("Importing Plotly...")
import plotly.graph_objects as go
print("Importing Pandas...")
import pandas as pd
print("Importing local modules...")
from database import DatabaseManager
from adac_scraper import ADACScraper
from analysis_engine import AnalysisEngine
from prediction_model import FuelPredictionModel
from autostart_manager import AutostartManager
import config
from datetime import datetime
import time
import uuid
import re
import json
print("Imports complete.")

def format_fuel_price(price_val):
    if price_val is None or price_val == float('inf'):
        return "N/A"
    
    # Format to 3 decimals
    s = f"{price_val:.3f}"
    main_part = s[:-1].replace(".", ",")
    last_digit = s[-1]
    
    return html.Span([
        main_part,
        html.Sup(last_digit, style={'fontSize': '0.7em', 'marginLeft': '1px'})
    ])

class TankRadarDashboard:
    def __init__(self):
        self._app_start_ts = time.time()
        self.app = Dash(__name__, assets_folder='assets', suppress_callback_exceptions=True)
        self.db = DatabaseManager()
        self.analysis = AnalysisEngine()
        self.model = FuelPredictionModel()
        self.autostart = AutostartManager()
        self._setup_layout()
        self._setup_callbacks()

    def _setup_layout(self):
        self.app.layout = html.Div(className='app-container', children=[
            # Sidebar
            html.Div(className='sidebar', children=[
                html.Div(className='sidebar-brand', children=[
                    html.H1("TankRadar")
                ]),
                
                html.Div(className='sidebar-actions', children=[
                    html.P("Aktionen", className='input-label'),
                    html.Button("Bulk-Import", id='open-bulk-import', className='btn-primary', style={'width': '100%', 'marginBottom': '10px'}),
                    html.Button("Station +", id='open-add-station', className='btn-secondary', style={'width': '100%', 'marginBottom': '10px'}),
                    html.Button("Preis +", id='open-update-price', className='btn-secondary', style={'width': '100%'}),
                    html.Hr(style={'borderColor': 'rgba(255,255,255,0.1)', 'margin': '12px 0'}),
                    html.P("ADAC Scraper", className='input-label'),
                    dcc.Input(id='scraper-plz-input', type='text', placeholder='PLZ (z.B. 35444)', value=getattr(config, 'DEFAULT_SCRAPE_LOCATION', '35037'), style={'width': '100%', 'marginBottom': '8px', 'padding': '8px', 'borderRadius': '8px', 'border': '1px solid rgba(255,255,255,0.2)', 'background': 'rgba(255,255,255,0.05)', 'color': 'white'}),
                    html.Button("🔄 Preise abrufen", id='trigger-adac-scrape', className='btn-primary', style={'width': '100%', 'marginBottom': '5px'}),
                    html.Button("☁️ Cloud Sync", id='trigger-cloud-sync', className='btn-secondary', style={'width': '100%', 'marginTop': '5px'}),
                    html.Div(id='scraper-status', style={'fontSize': '0.8rem', 'color': 'var(--text-dim)', 'marginTop': '5px'})
                ]),

                html.Div(className='sidebar-navigation', style={'marginTop': '20px'}, children=[
                    html.P("Ansicht", className='input-label'),
                    html.Button("🔍 Radar", id='btn-nav-radar', className='btn-primary', style={'width': '100%', 'marginBottom': '10px'}),
                    html.Button("📖 Tank-Tagebuch", id='btn-nav-logbook', className='btn-secondary', style={'width': '100%'})
                ]),

                dcc.Store(id='current-view-store', data='radar'),

                html.Div(className='sidebar-settings', style={'marginTop': 'auto'}, children=[
                    html.Div(style={'marginBottom': '20px', 'padding': '15px', 'background': 'rgba(255,255,255,0.03)', 'borderRadius': '12px', 'border': '1px solid var(--border-light)'}, children=[
                        html.Label("Windows Autostart", className='input-label', style={'marginBottom': '10px', 'display': 'block'}),
                        dcc.Checklist(
                            id='autostart-toggle',
                            options=[{'label': ' Beim Systemstart öffnen', 'value': 'enabled'}],
                            value=['enabled'] if self.autostart.is_enabled() else [],
                            className='dash-checklist'
                        )
                    ]),
                    html.Label("Kraftstoffart", className='input-label'),
                    dcc.Dropdown(
                        id='fuel-type-selector',
                        options=[
                            {'label': 'Super E5', 'value': 'e5'},
                            {'label': 'Super E10', 'value': 'e10'},
                            {'label': 'Super Plus', 'value': 'e5p'},
                            {'label': 'Diesel', 'value': 'diesel'}
                        ],
                        value='e10',
                        clearable=False
                    )
                ])
            ]),

            # Main Content
            html.Div(className='main-content', children=[
                # Notification Area for feedback
                html.Div(id='notification-area', style={'marginBottom': '20px'}),
                
                # --- RADAR VIEW ---
                html.Div(id='view-radar', style={'display': 'block'}, children=[
                    # Stale Data Warning Banner
                    html.Div(id='stale-data-banner', className='stale-data-banner', style={'display': 'none'}),

                    # Top Analytics Row
                    html.Div(id='prediction-summary', className='metric-row'),

                    # Graph Card
                    html.Div(className='glass-card', children=[
                        html.Div(style={'display': 'flex', 'justifyContent': 'space-between', 'alignItems': 'center', 'marginBottom': '10px'}, children=[
                            html.H2("Preisentwicklung", style={'margin': '0', 'fontSize': '1.8rem', 'fontWeight': '700'}),
                            html.Span(id='selected-station-name', style={'color': 'var(--text-dim)', 'fontWeight': '500'})
                        ]),
                        # Next-scrape countdown bar
                        html.Div(id='scrape-countdown-bar', style={
                            'display': 'flex', 'alignItems': 'center', 'gap': '10px',
                            'padding': '8px 14px', 'marginBottom': '15px',
                            'borderRadius': '10px',
                            'background': 'rgba(255,255,255,0.04)',
                            'border': '1px solid rgba(255,255,255,0.08)',
                            'fontSize': '0.82rem', 'color': 'var(--text-dim)',
                        }, children=[
                            html.Span("⏱️", style={'fontSize': '1rem'}),
                            html.Span("Nächster Scan in "),
                            html.Span(id='scrape-countdown-value', children='--:--', style={'fontWeight': '700', 'color': 'var(--primary)', 'fontVariantNumeric': 'tabular-nums'}),
                            html.Span("|", style={'opacity': '0.3'}),
                            html.Span(id='scrape-last-time', children='Noch kein Scan', style={'opacity': '0.7'}),
                        ]),
                        dcc.Graph(
                            id='price-graph', 
                            config={
                                'displayModeBar': 'hover', 
                                'responsive': True,
                                'scrollZoom': True
                            },
                            style={'width': '100%', 'height': '500px'}
                        ),
                        
                        # Price Calculator Widget
                        html.Div(id='calculator-container', className='calculator-widget', children=[
                            html.Div(className='calculator-header', children=[
                                html.Span("🧮", style={'fontSize': '1.4rem'}),
                                html.H3("Preis-Rechner", style={'margin': '0', 'fontSize': '1.1rem'})
                            ]),
                            html.Div(className='calculator-body', children=[
                                html.Div(className='input-group', children=[
                                    html.Label("Menge (Liter)", className='input-label', style={'marginBottom': '5px'}),
                                    dcc.Input(id='calculator-liters', type='number', value=10, min=1, step=1, className='calc-input')
                                ]),
                                html.Div(className='calculator-result', children=[
                                    html.Div("Gesamtpreis", className='metric-label'),
                                    html.Div(id='calculator-total', className='calc-total', children="0.00 €")
                                ])
                            ])
                        ])
                    ]),
                    
                    # Insight Engine (Statistics Section)
                    html.Div(id='insight-engine-container', className='glass-card', style={'marginTop': '20px', 'display': 'none'}, children=[
                        html.H2("💡 Tank-Insider", style={'fontSize': '1.4rem', 'fontWeight': '700', 'marginBottom': '20px'}),
                        html.Div(className='insight-grid', children=[
                            html.Div(className='insight-card', children=[
                                html.Div("Günstigster Tag", className='insight-label'),
                                html.Div(id='best-weekday-value', className='insight-value', children="-")
                            ]),
                            html.Div(className='insight-card', children=[
                                html.Div("Beste Uhrzeit", className='insight-label'),
                                html.Div(id='best-hour-value', className='insight-value', children="-")
                            ]),
                            html.Div(className='insight-card', children=[
                                html.Div(id='city-avg-label', className='insight-label', children="Stadtdurchschnitt"),
                                html.Div(id='city-avg-comparison', className='insight-value', children="-")
                            ])
                        ])
                    ]),

                    # Station Discovery/Selection
                    html.Div(style={'marginTop': '40px'}, children=[
                        html.H2("Deine Tankstellen", style={'fontSize': '1.6rem', 'fontWeight': '700'}),
                        html.Div(id='station-grid', className='station-grid'),
                    ])
                ]),
                
                # --- LOGBOOK VIEW ---
                html.Div(id='view-logbook', style={'display': 'none'}, children=[
                    html.Div(style={'display': 'flex', 'justifyContent': 'space-between', 'alignItems': 'center', 'marginBottom': '20px'}, children=[
                        html.H1("📖 Tank-Tagebuch", style={'margin': '0', 'fontSize': '2rem'}),
                        html.Button("+ Neuer Tankvorgang", id='open-add-refuel', className='btn-primary', style={'padding': '12px 24px'})
                    ]),
                    
                    # KPI Row for Logbook
                    html.Div(id='logbook-kpi-row', className='metric-row', children=[
                        html.Div(className='metric-item', children=[
                            html.Div(id='kpi-total-spent', className='metric-value', children="0.00 €"),
                            html.Div("Ausgaben diesen Monat", className='metric-label')
                        ]),
                        html.Div(className='metric-item', children=[
                            html.Div(id='kpi-total-liters', className='metric-value', children="0 L"),
                            html.Div("Getankte Menge diesen Monat", className='metric-label')
                        ]),
                        html.Div(className='metric-item', children=[
                            html.Div(id='kpi-avg-price', className='metric-value', children="0.00 €/L"),
                            html.Div("Durchschnittspreis", className='metric-label')
                        ])
                    ]),
                    
                    # Data Table Container
                    html.Div(className='glass-card', style={'marginTop': '20px', 'overflowX': 'auto'}, children=[
                        html.Div(id='logbook-table-container')
                    ])
                ]),

                # Hidden state for selected station
                dcc.Store(id='station-selection-store', data=self._get_default_station_id()),
                dcc.Store(id='edit-station-id-store'),
                dcc.Store(id='last-scrape-ts', data=None),
                
                # Internal management components
                dcc.Interval(id='interval-component', interval=30*1000, n_intervals=0),
                dcc.Interval(id='countdown-interval', interval=1000, n_intervals=0),
                html.Div(id='bulk-dummy-output', style={'display': 'none'}),
                html.Div(id='scraper-dummy-output', style={'display': 'none'}),
                html.Div(id='dummy-output', style={'display': 'none'}),
                # For callback stability:
                html.Button(id='trigger-update-from-banner', style={'display': 'none'})
            ]),

            # Modals (Hidden by default)
            html.Div(id='add-station-modal', className='modal-overlay', style={'display': 'none'}, children=[
                html.Div(className='modal-content', children=[
                    html.H2(id='station-modal-title', children="Neue Station hinzufügen"),
                    html.Div(className='form-grid', children=[
                        html.Div(className='input-group full-width', children=[
                            html.Label("Name der Tankstelle", className='input-label'),
                            dcc.Input(id='new-station-name', type='text', placeholder='z.B. Aral Berlin-Mitte')
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Marke", className='input-label'),
                            dcc.Input(id='new-station-brand', type='text', placeholder='z.B. Aral')
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Stadt", className='input-label'),
                            dcc.Input(id='new-station-city', type='text', placeholder='z.B. Berlin')
                        ]),
                    ]),
                    html.Div(style={'marginTop': '30px', 'display': 'flex', 'gap': '10px', 'justifyContent': 'flex-end'}, children=[
                        html.Button("Abbrechen", id='close-add-station', className='btn-secondary'),
                        html.Button("Speichern", id='save-station', className='btn-primary')
                    ])
                ])
            ]),

            html.Div(id='update-price-modal', className='modal-overlay', style={'display': 'none'}, children=[
                html.Div(className='modal-content', children=[
                    html.H2("Preis aktualisieren"),
                    html.Div(className='input-group full-width', style={'marginBottom': '15px'}, children=[
                        html.Label("Tankstelle auswählen", className='input-label'),
                        dcc.Dropdown(
                            id='price-update-station-selector',
                            placeholder="Wähle eine Tankstelle...",
                            clearable=False,
                            searchable=True,
                            className='dash-dropdown'
                        )
                    ]),
                    html.Div(className='form-grid', children=[
                        html.Div(className='input-group', children=[
                            html.Label("Kraftstoffart", className='input-label'),
                            dcc.Dropdown(
                                id='update-fuel-type',
                                options=[
                                    {'label': 'Super E5', 'value': 'e5'},
                                    {'label': 'Super E10', 'value': 'e10'},
                                    {'label': 'Super Plus', 'value': 'e5p'},
                                    {'label': 'Diesel', 'value': 'diesel'}
                                ],
                                value='e10',
                                clearable=False,
                                searchable=False,
                                className='dash-dropdown'
                            )
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Preis (€/L)", className='input-label', style={'color': 'white', 'fontSize': '1.0rem'}),
                            dcc.Input(id='update-price-value', type='number', step=0.001, placeholder='1.749', style={'height': '50px', 'fontSize': '1.2rem'})
                        ]),
                    ], style={'backgroundColor': 'rgba(255,255,255,0.02)', 'padding': '20px', 'borderRadius': '16px', 'marginTop': '10px'}),
                    html.Div(style={'marginTop': '30px', 'display': 'flex', 'gap': '10px', 'justifyContent': 'flex-end'}, children=[
                        html.Button("Abbrechen", id='close-update-price', className='btn-secondary'),
                        html.Button("Aktualisieren", id='save-price', className='btn-primary')
                    ])
                ])
            ]),

            html.Div(id='bulk-import-modal', className='modal-overlay', style={'display': 'none'}, children=[
                html.Div(className='modal-content', children=[
                    html.H2("Bulk-Import"),
                    html.P("Kopiere die Liste aus deiner Tank-App hier hinein:", style={'color': '#8888a0'}),
                    dcc.Textarea(
                        id='bulk-text-input',
                        placeholder='JET\nSuper E5 · Heute, 17:18\n...\n2,04 9',
                        style={'height': '250px'}
                    ),
                    html.Div(style={'marginTop': '20px', 'display': 'flex', 'gap': '10px', 'justifyContent': 'flex-end'}, children=[
                        html.Button("Abbrechen", id='close-bulk-import', className='btn-secondary'),
                        html.Button("Importieren", id='run-bulk-import', className='btn-primary')
                    ])
                ])
            ]),

            # Add Refuel Log Modal
            html.Div(id='add-refuel-modal', className='modal-overlay', style={'display': 'none'}, children=[
                html.Div(className='modal-content', children=[
                    html.H2("Neuer Tankvorgang"),
                    html.Div(className='input-group full-width', style={'marginBottom': '15px'}, children=[
                        html.Label("Tankstelle auswählen", className='input-label'),
                        dcc.Dropdown(
                            id='refuel-station-selector',
                            placeholder="Wähle eine Tankstelle aus deinen Favoriten/Liste...",
                            clearable=True,
                            searchable=True,
                            className='dash-dropdown'
                        )
                    ]),
                    html.Div(className='input-group full-width', style={'marginBottom': '15px'}, children=[
                        html.Label("Oder Freitext Eingabe (falls nicht in Liste)", className='input-label'),
                        dcc.Input(id='refuel-station-fallback', type='text', placeholder='z.B. ARAL Autobahn A7')
                    ]),
                    html.Div(className='form-grid', children=[
                        html.Div(className='input-group', children=[
                            html.Label("Kraftstoffart", className='input-label'),
                            dcc.Dropdown(
                                id='refuel-fuel-type',
                                options=[
                                    {'label': 'Super E5', 'value': 'e5'},
                                    {'label': 'Super E10', 'value': 'e10'},
                                    {'label': 'Super Plus', 'value': 'e5p'},
                                    {'label': 'Diesel', 'value': 'diesel'}
                                ],
                                value='e10',
                                clearable=False,
                                searchable=False,
                                className='dash-dropdown'
                            )
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Menge (Liter)", className='input-label', style={'color': 'white', 'fontSize': '1.0rem'}),
                            dcc.Input(id='refuel-liters', type='number', step=0.01, placeholder='0.00', style={'height': '50px', 'fontSize': '1.2rem'})
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Preis (€/L)", className='input-label', style={'color': 'white', 'fontSize': '1.0rem'}),
                            dcc.Input(id='refuel-price-per-liter', type='number', step=0.001, placeholder='0.000', style={'height': '50px', 'fontSize': '1.2rem'})
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Gesamtkosten (€)", className='input-label', style={'color': 'white', 'fontSize': '1.0rem'}),
                            dcc.Input(id='refuel-total-cost', type='number', step=0.01, placeholder='Wird berechnet...', style={'height': '50px', 'fontSize': '1.2rem'})
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Zählerstand (optional, km)", className='input-label'),
                            dcc.Input(id='refuel-odometer', type='number', step=1)
                        ]),
                        html.Div(className='input-group', children=[
                            html.Label("Notizen", className='input-label'),
                            dcc.Input(id='refuel-notes', type='text')
                        ]),
                    ], style={'backgroundColor': 'rgba(255,255,255,0.02)', 'padding': '20px', 'borderRadius': '16px', 'marginTop': '10px'}),
                    html.Div(style={'marginTop': '30px', 'display': 'flex', 'gap': '10px', 'justifyContent': 'flex-end'}, children=[
                        html.Button("Abbrechen", id='close-add-refuel', className='btn-secondary'),
                        html.Button("Speichern", id='save-refuel', className='btn-primary')
                    ])
                ])
            ])
        ])

    def _get_station_options(self):
        stations = self.db.get_all_stations()
        if not stations:
            # Fallback to config IDs if DB is empty
            station_ids = getattr(config, 'STATION_IDS', [])
            return [{'label': f"Station {s_id[:8]}...", 'value': s_id} for s_id in station_ids]
        return [{'label': f"{s.brand if s.brand else ''} {s.name} ({s.city})", 'value': s.id} for s in stations]

    def _get_default_station_id(self):
        stations = self.db.get_all_stations()
        if stations:
            return stations[0].id
        station_ids = getattr(config, 'STATION_IDS', [])
        return station_ids[0] if station_ids else None

    def _get_station_grid_content(self, fuel_type, selected_id=None):
        stations = self.db.get_all_stations()
        if not stations:
            return [html.P("Keine Tankstellen vorhanden. Nutze den Bulk-Import!", style={'color': 'var(--text-dim)'})]

        # Get latest price for ALL stations to find the cheapest
        latest_df = self.db.get_latest_prices()
        fuel_latest = latest_df[latest_df['fuel_type'] == fuel_type]
        
        import logging
        dash_logger = logging.getLogger("TankRadar.Dashboard")
        
        cheapest_station_id = None
        if not fuel_latest.empty:
            cheapest_row = fuel_latest.loc[fuel_latest['price'].idxmin()]
            cheapest_station_id = cheapest_row['station_id']
            dash_logger.info(f"Cheapest station for {fuel_type}: {cheapest_station_id} at {cheapest_row['price']} Euro")
        else:
            dash_logger.info(f"No prices found for {fuel_type} to identify cheapest station.")

        cards = []
        for s in stations:
            station_price_row = fuel_latest[fuel_latest['station_id'] == s.id]
            
            latest_price = "N/A"
            price_val = float('inf')
            timestamp_str = None
            if not station_price_row.empty:
                # Use the very latest matching row
                price_val = station_price_row.iloc[-1]['price']
                timestamp_str = station_price_row.iloc[-1]['timestamp']
                latest_price = format_fuel_price(price_val)

            is_selected = s.id == selected_id
            is_favorite = getattr(s, 'is_favorite', 0) == 1
            # Exact match for the cheapest ID
            is_cheapest = str(s.id) == str(cheapest_station_id) and price_val != float('inf')
            
            card_content = [
                html.Div(style={'position': 'absolute', 'top': '15px', 'right': '15px', 'display': 'flex', 'gap': '10px', 'zIndex': '10'}, children=[
                    html.Span("⭐" if is_favorite else "☆", id={'type': 'toggle-favorite', 'index': s.id}, n_clicks=0, 
                              style={'cursor': 'pointer', 'fontSize': '1.2rem', 'color': 'var(--primary)' if is_favorite else 'white', 'opacity': '0.9'}),
                    html.Span("✏️", id={'type': 'edit-station', 'index': s.id}, n_clicks=0, style={'cursor': 'pointer', 'fontSize': '1.2rem', 'opacity': '0.6', 'transition': 'opacity 0.2s'}),
                    html.Span("🗑️", id={'type': 'delete-station', 'index': s.id}, n_clicks=0, style={'cursor': 'pointer', 'fontSize': '1.2rem', 'opacity': '0.6', 'transition': 'opacity 0.2s', 'color': 'var(--secondary)'})
                ]),
                
                html.Div(s.brand or "Freie Tankstelle", style={'fontSize': '0.8rem', 'textTransform': 'uppercase', 'color': 'var(--text-dim)'}),
                html.Div(s.name, style={'fontWeight': 'bold', 'fontSize': '1.1rem', 'margin': '5px 0'}),
                html.Div(s.city, style={'fontSize': '0.85rem', 'color': 'var(--text-dim)'}),
            ]
            
            # --- Price Trend Indicator ---
            if not station_price_row.empty:
                prev_price = station_price_row.iloc[-1].get('previous_price', None)
                if pd.notna(prev_price) and price_val != prev_price:
                    diff = price_val - prev_price
                    if diff > 0:
                        trend_el = html.Span("↑", style={'color': 'var(--secondary)', 'fontSize': '1.5rem', 'marginLeft': '8px', 'fontWeight': 'bold'})
                        tooltip = f"+{diff:.3f} €"
                    else:
                        trend_el = html.Span("↓", style={'color': 'var(--success)', 'fontSize': '1.5rem', 'marginLeft': '8px', 'fontWeight': 'bold'})
                        tooltip = f"{diff:.3f} €"
                        
                    price_div = html.Div([
                        latest_price, 
                        html.Span(" €", style={'fontSize': '0.8rem', 'marginLeft': '2px', 'opacity': '0.6'}),
                        html.Span(trend_el, title=tooltip)
                    ], className='price', style={'display': 'flex', 'alignItems': 'center'})
                else:
                    price_div = html.Div([latest_price, html.Span(" €", style={'fontSize': '0.8rem', 'marginLeft': '2px', 'opacity': '0.6'})], className='price')
            else:
                price_div = html.Div([latest_price], className='price')
                
            card_content.append(price_div)
            
            # --- Timestamp Display ---
            if timestamp_str:
                try:
                    ts_dt = pd.to_datetime(timestamp_str)
                    time_display = ts_dt.strftime("%H:%M")
                    card_content.append(html.Div(f"Stand: {time_display} Uhr", style={
                        'fontSize': '0.7rem', 
                        'color': 'var(--text-dim)', 
                        'marginTop': '-10px',
                        'marginBottom': '10px',
                        'opacity': '0.7'
                    }))
                except:
                    pass
            
            if is_cheapest:
                # Use standard class but ensure it's at the very top (index 0)
                card_content.insert(0, html.Div("Bester Preis", className='best-price-badge'))

            cards.append(html.Div(
                id={'type': 'station-card', 'index': s.id},
                n_clicks=0,
                className=f'station-card {"selected" if is_selected else ""} {"cheapest" if is_cheapest else ""} {"favorite" if is_favorite else ""}',
                children=card_content
            ))
        
        # Sort cards: Favorites first
        cards.sort(key=lambda x: "favorite" in x.className, reverse=True)
        return cards

    def _setup_callbacks(self):
        # Callback to render the station grid
        @self.app.callback(
            Output('station-grid', 'children'),
            [Input('interval-component', 'n_intervals'),
             Input('station-selection-store', 'data'),
             Input('fuel-type-selector', 'value')]
        )
        def render_station_grid(_, selected_id, fuel_type):
            return self._get_station_grid_content(fuel_type, selected_id)

        # Callback to handle station selection from cards
        @self.app.callback(
            Output('station-selection-store', 'data'),
            Input({'type': 'station-card', 'index': ALL}, 'n_clicks'),
            prevent_initial_call=True
        )
        def select_station(n_clicks):
            ctx = callback_context
            if not ctx.triggered:
                return self._get_default_station_id()
            
            # Extract the ID from the triggered element
            triggered_id = ctx.triggered[0]['prop_id'].split('.')[0]
            import json
            triggered_dict = json.loads(triggered_id)
            return triggered_dict['index']

        # Callback for Management Actions (Delete/Edit)
        @self.app.callback(
            [Output('notification-area', 'children', allow_duplicate=True),
             Output('station-grid', 'children', allow_duplicate=True),
             Output('add-station-modal', 'style', allow_duplicate=True),
             Output('edit-station-id-store', 'data', allow_duplicate=True),
             Output('new-station-name', 'value', allow_duplicate=True),
             Output('new-station-brand', 'value', allow_duplicate=True),
             Output('new-station-city', 'value', allow_duplicate=True)],
            [Input({'type': 'delete-station', 'index': ALL}, 'n_clicks'),
             Input({'type': 'edit-station', 'index': ALL}, 'n_clicks')],
            [State('fuel-type-selector', 'value')],
            prevent_initial_call=True
        )
        def handle_management(n_delete, n_edit, fuel_type):
            ctx = callback_context
            if not ctx.triggered:
                return dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update
            
            # Extract trigger info
            trigger = ctx.triggered[0]
            if not trigger['value']: # No clicks actually happened (triggered by grid refresh)
                return dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update

            triggered_id = trigger['prop_id'].split('.')[0]
            try:
                id_dict = json.loads(triggered_id)
                action_type = id_dict['type']
                station_id = id_dict['index']
            except:
                return dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update
            
            if action_type == 'delete-station' and trigger['value'] > 0:
                if self.db.delete_station(station_id):
                    msg = "Station erfolgreich gelöscht."
                    color = "var(--secondary)"
                    notification = html.Div(msg, style={
                        'padding': '15px 25px', 
                        'borderRadius': '16px', 
                        'background': 'rgba(0,0,0,0.4)', 
                        'border': f'1px solid {color}',
                        'color': color,
                        'fontWeight': '600',
                        'animation': 'fadeIn 0.5s ease-out'
                    })
                    return notification, self._get_station_grid_content(fuel_type), dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update
            
            elif action_type == 'edit-station' and trigger['value'] > 0:
                stations = self.db.get_all_stations()
                station = next((s for s in stations if s.id == station_id), None)
                if station:
                    return dash.no_update, dash.no_update, {'display': 'flex'}, station_id, station.name, station.brand, station.city
            
            elif action_type == 'toggle-favorite' and trigger['value'] > 0:
                if self.db.toggle_favorite(station_id):
                    return dash.no_update, self._get_station_grid_content(fuel_type), dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update

            return dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update

        # Callback to update modal title
        @self.app.callback(
            Output('station-modal-title', 'children'),
            Input('edit-station-id-store', 'data')
        )
        def update_modal_title(edit_sid):
            return "Station bearbeiten" if edit_sid else "Neue Station hinzufügen"

        # Callback to update the dashboard content
        @self.app.callback(
            [Output('price-graph', 'figure'),
             Output('prediction-summary', 'children'),
             Output('selected-station-name', 'children')],
            [Input('station-selection-store', 'data'),
             Input('fuel-type-selector', 'value'),
             Input('interval-component', 'n_intervals'),
             Input('save-price', 'n_clicks')]
        )
        def update_dashboard(station_id, fuel_type, _, _n2):
            if not station_id:
                return go.Figure(), [], ""

            try:
                stations = self.db.get_all_stations()
                station = next((s for s in stations if s.id == station_id), None)
                s_display_name = f"{station.brand or ''} {station.name}" if station else f"Station {station_id}"

                df = self.db.get_historical_data(station_id, days=14) # Get more data for average
                
                if df.empty or fuel_type not in df['fuel_type'].unique():
                    fig = go.Figure()
                    fig.update_layout(template="plotly_dark", paper_bgcolor='rgba(0,0,0,0)', plot_bgcolor='rgba(0,0,0,0)')
                    return fig, [
                        html.Div(className='metric-item', children=[
                            html.P("Keine Daten für diese Auswahl.", className='metric-label')
                        ])
                    ], s_display_name

                fuel_df = df[df['fuel_type'] == fuel_type].sort_values('timestamp')
                current_price = fuel_df.iloc[-1]['price']
                avg_price = round(fuel_df['price'].mean(), 3)
                
                prediction = self.model.predict_next_24h(fuel_df)
                
                fig = go.Figure()
                
                # Historical Line
                fig.add_trace(go.Scatter(
                    x=fuel_df['timestamp'],
                    y=fuel_df['price'],
                    mode='lines+markers',
                    name='Preisverlauf',
                    line={'color': '#00f2ff', 'width': 4},
                    marker={'size': 10, 'color': '#00f2ff', 'line': {'width': 2, 'color': '#05050a'}},
                    hovertemplate="<b>Datum:</b> %{x}<br><b>Preis:</b> %{y:.3f} €<extra></extra>"
                ))
                
                # Average Line
                fig.add_hline(
                    y=avg_price, 
                    line_dash="dot", 
                    line_color="rgba(255,255,255,0.3)",
                    annotation_text=f"Ø {avg_price:.3f}", 
                    annotation_position="top left"
                )
                
                metrics = [
                    html.Div(className='metric-item', children=[
                        html.Div([format_fuel_price(current_price), html.Small(" €", style={'fontSize': '1rem', 'marginLeft': '4px'})], className='metric-value'),
                        html.Div("Aktueller Preis", className='metric-label')
                    ]),
                    html.Div(className='metric-item', children=[
                        html.Div([format_fuel_price(avg_price), html.Small(" €", style={'fontSize': '1rem', 'marginLeft': '4px'})], className='metric-value', style={'opacity': '0.7'}),
                        html.Div(f"Ø Preis ({len(fuel_df)} Datenp.)", className='metric-label')
                    ])
                ]
                
                if prediction:
                    forecast_df = prediction['forecast']
                    
                    # Sanity check: Filter out extreme outliers in prediction for the plot
                    forecast_df = forecast_df[forecast_df['yhat'] < 3.0].copy()
                    
                    if not forecast_df.empty:
                        fig.add_trace(go.Scatter(
                            x=forecast_df['ds'],
                            y=forecast_df['yhat'],
                            mode='lines',
                            name='Vorhersage',
                            line={'color': '#ff2d95', 'width': 3, 'dash': 'dash'},
                            hovertemplate="<b>Datum:</b> %{x}<br><b>Erwartet:</b> %{y:.3f} €<extra></extra>"
                        ))
                    
                    if prediction['best_price'] < 3.0:
                        fig.add_trace(go.Scatter(
                            x=[prediction['best_time']],
                            y=[prediction['best_price']],
                            mode='markers+text',
                            name='Beste Zeit',
                            text=[f"{prediction['best_price']:.3f} €"],
                            textposition="top center",
                            textfont={'color': '#ffffff', 'size': 16, 'family': 'Arial'},
                            marker={'color': '#00ffaa', 'size': 20, 'symbol': 'star', 'line': {'width': 3, 'color': '#05050a'}},
                            hovertemplate="<b>Spar-Tipp:</b> Heute %{x|%H:%M}<br><b>Preis:</b> %{y:.3f} €<extra></extra>"
                        ))
                        
                        metrics.extend([
                            html.Div(className='metric-item', children=[
                                html.Div([format_fuel_price(prediction['best_price']), html.Small(" €", style={'fontSize': '1rem', 'marginLeft': '4px'})], className='metric-value', style={'color': '#00ffaa'}),
                                html.Div(f"Tiefstwert (heute {prediction['best_time'].strftime('%H:%M')})", className='metric-label')
                            ]),
                            html.Div(className='metric-item', children=[
                                html.Div(f"{max(0.0, current_price - prediction['best_price']):.3f} €", className='metric-value', style={'color': '#ffd700'}),
                                html.Div("Mögliche Ersparnis", className='metric-label')
                            ])
                        ])

                # Calculate dynamic Y-axis range based on both history and prediction
                all_prices = fuel_df['price'].tolist()
                if prediction:
                    # Only include reasonable predictions in the scale to avoid squashing the graph
                    pred_prices = [p for p in prediction['forecast']['yhat'].tolist() if 0.5 < p < 3.0]
                    all_prices.extend(pred_prices)
                
                y_min = 0.0
                y_max = max(all_prices) * 1.05 if all_prices else 2.5

                # DEBUG: Print prediction presence to terminal
                print(f"DEBUG: Station {station_id} | Fuel {fuel_type} | Prediction Found: {prediction is not None}")
                if prediction:
                    print(f"DEBUG: Best price predicted: {prediction['best_price']} at {prediction['best_time']}")

                fig.update_layout(
                    margin=dict(l=65, r=30, t=55, b=50), 
                    xaxis_title=None,
                    yaxis_title="Preis (€/L)",
                    template="plotly_dark",
                    paper_bgcolor='rgba(0,0,0,0)', 
                    plot_bgcolor='rgba(0,0,0,0)',
                    font={'family': 'Arial, sans-serif', 'color': '#ffffff', 'size': 14},
                    legend={
                        'orientation': "h", 
                        'yanchor': "bottom", 
                        'y': 1.02,          # war 1.1 → wurde abgeschnitten
                        'xanchor': "right", 
                        'x': 1
                    },
                    hovermode="closest",
                    hoverlabel=dict(
                        bgcolor="#1a1a2e",
                        font=dict(size=16, family="Arial", color="#ffffff"),
                        bordercolor="#00f2ff"
                    ),
                    xaxis={
                        "rangeslider": {"visible": True},
                        "showgrid": True,
                        "gridcolor": "rgba(255,255,255,0.15)",
                        "linecolor": "white",
                        "linewidth": 2,
                        "tickfont": {"color": "white", "size": 13}, 
                        "showticklabels": True,
                        "mirror": True
                        # automargin entfernt — kämpft gegen feste margins
                    },
                    yaxis={
                        "showgrid": True,
                        "gridcolor": "rgba(255,255,255,0.15)",
                        "linecolor": "white",
                        "linewidth": 2,
                        "tickfont": {"color": "white", "size": 15, "weight": "bold"}, 
                        "tickformat": ".3f",
                        "showticklabels": True,
                        "range": [y_min, y_max],
                        "mirror": True
                        # automargin entfernt
                    },
                    height=500,      # Von 450 auf 500 erhöht, da Range Slider Platz braucht
                    # autosize=True  ← ENTFERNT: kämpft gegen explizite height
                )
                
                return fig, metrics, s_display_name
            except Exception as e:
                import logging
                dash_logger = logging.getLogger("TankRadar.Dashboard")
                dash_logger.error(f"Error in update_dashboard: {e}")
                import traceback
                dash_logger.error(traceback.format_exc())
                return go.Figure(), [html.Div(f"Fehler: {e}", style={'color': 'var(--secondary)'})], "Fehler"

        # Insight Engine Callback
        @self.app.callback(
            [Output('insight-engine-container', 'style'),
             Output('best-weekday-value', 'children'),
             Output('best-hour-value', 'children'),
             Output('city-avg-label', 'children'),
             Output('city-avg-comparison', 'children')],
            [Input('station-selection-store', 'data'),
             Input('fuel-type-selector', 'value'),
             Input('interval-component', 'n_intervals')]
        )
        def update_insights(station_id, fuel_type, _):
            if not station_id:
                return {'display': 'none'}, "-", "-", "Stadtdurchschnitt", "-"
            
            try:
                weekday_info = self.analysis.get_cheapest_weekday(station_id, fuel_type)
                hour_info = self.analysis.get_best_time_of_day(station_id, fuel_type)
                city_info = self.analysis.get_city_comparison(station_id, fuel_type)
                
                weekday_val = f"{weekday_info['day']} ({weekday_info['price']:.3f}€)" if weekday_info else "N/A"
                hour_val = f"{hour_info['hour']:02d}:00 Uhr ({hour_info['price']:.3f}€)" if hour_info else "N/A"
                
                city_label = f"Schnitt in {city_info['city']}" if city_info else "Stadtdurchschnitt"
                if city_info:
                    diff_color = 'var(--success)' if city_info['is_cheaper'] else 'var(--secondary)'
                    diff_prefix = "Günstiger" if city_info['is_cheaper'] else "Teurer"
                    city_val = html.Span([
                        f"{city_info['city_avg']:.3f}€ (",
                        html.B(f"{abs(city_info['difference']):.3f}€ {diff_prefix}", style={'color': diff_color}),
                        ")"
                    ])
                else:
                    city_val = "N/A"
                    
                visible = {'display': 'block', 'marginTop': '20px'}
                return visible, weekday_val, hour_val, city_label, city_val
            except Exception as e:
                import logging
                logger = logging.getLogger("TankRadar.Dashboard")
                logger.error(f"Error in update_insights: {e}")
                return {'display': 'none'}, "-", "-", "Stadtdurchschnitt", "-"

        # Price Calculator Callback
        @self.app.callback(
            Output('calculator-total', 'children'),
            [Input('calculator-liters', 'value'),
             Input('station-selection-store', 'data'),
             Input('fuel-type-selector', 'value'),
             Input('save-price', 'n_clicks')]
        )
        def calculate_total(liters, station_id, fuel_type, _n):
            if not liters or not station_id:
                return "0.00 €"
            
            try:
                latest_df = self.db.get_latest_prices()
                station_price = latest_df[(latest_df['station_id'] == station_id) & (latest_df['fuel_type'] == fuel_type)]
                
                if not station_price.empty:
                    current_price = station_price.iloc[-1]['price']
                    total = float(liters) * current_price
                    return f"{total:.2f} €"
                return "N/A"
            except Exception as e:
                return "Err"

        # --- View Switching Callbacks ---
        @self.app.callback(
            [Output('view-radar', 'style'),
             Output('view-logbook', 'style'),
             Output('current-view-store', 'data')],
            [Input('btn-nav-radar', 'n_clicks'),
             Input('btn-nav-logbook', 'n_clicks')],
            prevent_initial_call=False
        )
        def switch_view(btn_radar, btn_logbook):
            ctx = callback_context
            if not ctx.triggered:
                return {'display': 'block'}, {'display': 'none'}, 'radar'
                
            button_id = ctx.triggered[0]['prop_id'].split('.')[0]
            
            if button_id == 'btn-nav-logbook':
                return {'display': 'none'}, {'display': 'block'}, 'logbook'
            else:
                return {'display': 'block'}, {'display': 'none'}, 'radar'

        # --- Add Refuel Modal Callbacks ---
        @self.app.callback(
            Output('add-refuel-modal', 'style'),
            [Input('open-add-refuel', 'n_clicks'),
             Input('close-add-refuel', 'n_clicks'),
             Input('save-refuel', 'n_clicks')],
            State('add-refuel-modal', 'style')
        )
        def toggle_add_refuel_modal(open_clicks, close_clicks, save_clicks, current_style):
            ctx = callback_context
            if not ctx.triggered:
                return dash.no_update
                
            trigger_id = ctx.triggered[0]['prop_id'].split('.')[0]
            
            if trigger_id == 'open-add-refuel':
                return {'display': 'flex'}
            elif trigger_id in ['close-add-refuel', 'save-refuel']:
                return {'display': 'none'}
            
            return current_style

        # Generate total cost dynamically based on liters and price per liter input
        @self.app.callback(
            Output('refuel-total-cost', 'value'),
            [Input('refuel-liters', 'value'),
             Input('refuel-price-per-liter', 'value')]
        )
        def calculate_refuel_total(liters, price):
            if liters and price:
                return round(liters * price, 2)
            return dash.no_update

        # Save Refuel Entry
        @self.app.callback(
            [Output('notification-area', 'children', allow_duplicate=True),
             Output('refuel-station-selector', 'value'),
             Output('refuel-station-fallback', 'value'),
             Output('refuel-liters', 'value'),
             Output('refuel-price-per-liter', 'value'),
             Output('refuel-total-cost', 'value', allow_duplicate=True),
             Output('refuel-odometer', 'value'),
             Output('refuel-notes', 'value')],
            Input('save-refuel', 'n_clicks'),
            [State('refuel-station-selector', 'value'),
             State('refuel-station-fallback', 'value'),
             State('refuel-fuel-type', 'value'),
             State('refuel-liters', 'value'),
             State('refuel-price-per-liter', 'value'),
             State('refuel-total-cost', 'value'),
             State('refuel-odometer', 'value'),
             State('refuel-notes', 'value')],
            prevent_initial_call=True
        )
        def save_refuel_record(n_clicks, station_id, fallback_name, fuel_type, liters, price, total, odometer, notes):
            if not n_clicks:
                raise dash.exceptions.PreventUpdate

            if not station_id and not fallback_name:
                return self._create_notification("Bitte wähle eine Tankstelle aus oder gib einen Namen ein.", "error"), dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update
                
            if not liters or not price or not total:
                 return self._create_notification("Bitte fülle alle Pflichtfelder (Liter, Preis, Gesamt) aus.", "error"), dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update

            success = self.db.add_refuel_entry(
                fuel_type=fuel_type,
                liters=liters,
                price_per_liter=price,
                total_cost=total,
                station_id=station_id,
                station_name_fallback=fallback_name,
                odometer=odometer,
                notes=notes
            )

            if success:
                return self._create_notification("Tankvorgang erfolgreich gespeichert!", "success"), None, "", None, None, None, None, ""
            else:
                return self._create_notification("Fehler beim Speichern des Tankvorgangs.", "error"), dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update

        # --- Refuel Logbook Table and KPIs ---
        @self.app.callback(
            [Output('logbook-table-container', 'children'),
             Output('kpi-total-spent', 'children'),
             Output('kpi-total-liters', 'children'),
             Output('kpi-avg-price', 'children'),
             Output('refuel-station-selector', 'options')],
            [Input('current-view-store', 'data'),
             Input('save-refuel', 'n_clicks')]
        )
        def update_logbook_view(view_state, save_clicks):
            stations = self.db.get_all_stations()
            station_options = []
            for s in stations:
                brand_str = f"{s.brand} " if s.brand else ""
                name = f"{brand_str}{s.name}"
                station_options.append({'label': name, 'value': s.id})

            if view_state != 'logbook':
                return dash.no_update, dash.no_update, dash.no_update, dash.no_update, station_options

            df_logs = self.db.get_refuel_logs()
            
            if df_logs.empty:
                table = html.Div("Noch keine Tankvorgänge gespeichert.", style={'padding': '20px', 'textAlign': 'center', 'color': 'var(--text-dim)'})
                return table, "0.00 €", "0 L", "0.00 €/L", station_options

            current_month = datetime.now().month
            current_year = datetime.now().year
            
            df_logs['timestamp'] = pd.to_datetime(df_logs['timestamp'])
            this_month_logs = df_logs[(df_logs['timestamp'].dt.month == current_month) & (df_logs['timestamp'].dt.year == current_year)]
            
            if not this_month_logs.empty:
                total_spent = this_month_logs['total_cost'].sum()
                total_liters = this_month_logs['liters'].sum()
                avg_price = total_spent / total_liters if total_liters > 0 else 0
            else:
                total_spent = 0
                total_liters = 0
                avg_price = 0

            kpi_spent_str = f"{total_spent:.2f} €"
            kpi_liters_str = f"{total_liters:.1f} L"
            kpi_avg_price_str = f"{avg_price:.3f} €/L"

            df_display = df_logs.copy()
            df_display['Datum'] = df_display['timestamp'].dt.strftime('%d.%m.%Y %H:%M')
            df_display = df_display.rename(columns={
                'station_display': 'Tankstelle',
                'fuel_type': 'Sorte',
                'liters': 'Liter',
                'price_per_liter': 'Preis/L (€)',
                'total_cost': 'Gesamt (€)',
                'odometer': 'km-Stand'
            })
            
            cols_to_show = ['Datum', 'Tankstelle', 'Sorte', 'Liter', 'Preis/L (€)', 'Gesamt (€)', 'km-Stand']
            available_cols = [c for c in cols_to_show if c in df_display.columns]
            
            table = dash_table.DataTable(
                data=df_display.to_dict('records'),
                columns=[{'name': i, 'id': i} for i in available_cols],
                style_table={'overflowX': 'auto', 'minWidth': '100%'},
                style_header={
                    'backgroundColor': 'rgba(255,255,255,0.05)',
                    'color': 'white',
                    'fontWeight': 'bold',
                    'borderBottom': '1px solid rgba(255,255,255,0.2)',
                    'textAlign': 'left',
                    'padding': '12px'
                },
                style_cell={
                    'backgroundColor': 'transparent',
                    'color': '#d0d0e0',
                    'borderBottom': '1px solid rgba(255,255,255,0.05)',
                    'textAlign': 'left',
                    'padding': '12px'
                },
                style_data_conditional=[
                    {
                        'if': {'row_index': 'odd'},
                        'backgroundColor': 'rgba(255,255,255,0.02)'
                    }
                ],
                page_size=10
            )

            return table, kpi_spent_str, kpi_liters_str, kpi_avg_price_str, station_options

        # Stale Data Reminder Callback
        @self.app.callback(
            [Output('stale-data-banner', 'children'),
             Output('stale-data-banner', 'style'),
             Output('open-update-price', 'className')],
            [Input('interval-component', 'n_intervals'),
             Input('save-price', 'n_clicks'),
             Input('bulk-dummy-output', 'children')]
        )
        def check_data_freshness(_, _n2, _n3):
            latest_df = self.db.get_latest_prices()
            if latest_df.empty:
                return None, "btn-secondary"
            
            latest_update = pd.to_datetime(latest_df['timestamp']).max()
            now = datetime.now()
            diff_hours = (now - latest_update).total_seconds() / 3600
            
            if diff_hours > 2:
                content = [
                    html.Span("⚠️", style={'fontSize': '1.4rem'}),
                    html.Div([
                        html.B("Daten veraltet! "),
                        f"Die letzte Aktualisierung war vor {int(diff_hours)} Stunden ({latest_update.strftime('%H:%M')})."
                    ]),
                    html.Button("Jetzt aktualisieren", id={'type': 'trigger-update-btn', 'index': 'banner'}, className='btn-primary', 
                                style={'padding': '8px 20px', 'fontSize': '0.85rem', 'marginLeft': '20px'})
                ]
                return content, {'display': 'flex'}, "btn-secondary pulse-primary"
            
            return None, {'display': 'none'}, "btn-secondary"

        @self.app.callback(
            Output('update-price-modal', 'style', allow_duplicate=True),
            [Input('trigger-update-from-banner', 'n_clicks'),
             Input({'type': 'trigger-update-btn', 'index': ALL}, 'n_clicks')],
            prevent_initial_call=True
        )
        def open_price_modal_from_banner(n1, n2):
            if n1 or any(n2):
                return {'display': 'flex'}
            return dash.no_update

        @self.app.callback(
            [Output('bulk-import-modal', 'style'),
             Output('add-station-modal', 'style', allow_duplicate=True),
             Output('update-price-modal', 'style'),
             Output('edit-station-id-store', 'data', allow_duplicate=True),
             Output('price-update-station-selector', 'options'),
             Output('price-update-station-selector', 'value', allow_duplicate=True)],
            [Input('open-bulk-import', 'n_clicks'),
             Input('close-bulk-import', 'n_clicks'),
             Input('run-bulk-import', 'n_clicks'),
             Input('open-add-station', 'n_clicks'),
             Input('close-add-station', 'n_clicks'),
             Input('save-station', 'n_clicks'),
             Input('open-update-price', 'n_clicks'),
             Input('close-update-price', 'n_clicks'),
             Input('save-price', 'n_clicks')],
            [State('station-selection-store', 'data')],
            prevent_initial_call=True
        )
        def toggle_modals(n1, n2, n3, n4, n5, n6, n7, n8, n9, current_sid):
            ctx = callback_context
            if not ctx.triggered:
                return dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update
                
            button_id = ctx.triggered[0]['prop_id'].split('.')[0]
            
            styles = {'bulk': {'display': 'none'}, 'add': {'display': 'none'}, 'price': {'display': 'none'}}
            edit_reset = dash.no_update
            station_opts = dash.no_update
            station_val = dash.no_update
            
            if button_id == 'open-bulk-import': styles['bulk'] = {'display': 'flex'}
            elif button_id == 'open-add-station': 
                styles['add'] = {'display': 'flex'}
                edit_reset = None 
            elif button_id == 'open-update-price': 
                styles['price'] = {'display': 'flex'}
                stations = self.db.get_all_stations()
                station_opts = [{'label': f"{s.brand or ''} {s.name}", 'value': s.id} for s in stations]
                # Default to current selection, or first station if none selected
                if current_sid and any(s.id == current_sid for s in stations):
                    station_val = current_sid
                elif stations:
                    station_val = stations[0].id
            
            return styles['bulk'], styles['add'], styles['price'], edit_reset, station_opts, station_val

        # Bulk Import Processing
        @self.app.callback(
            [Output('bulk-dummy-output', 'children'),
             Output('bulk-text-input', 'value')],
            Input('run-bulk-import', 'n_clicks'),
            State('bulk-text-input', 'value'),
            prevent_initial_call=True
        )
        def handle_bulk_import(n_clicks, text):
            if not n_clicks or not text:
                return "", text
                
            import logging
            dash_logger = logging.getLogger("TankRadar.Dashboard")
            dash_logger.info("Starting Resilient Bulk Import process")
            
            lines = [l.strip() for l in text.split('\n') if l.strip()]
            import_count = 0
            
            last_station_id = None
            last_potential_name = None
            current_city = ""
            
            # Common patterns
            price_pattern = re.compile(r'(\d)[.,](\d{2})\s?(\d)?')
            city_pattern = re.compile(r'\d{5}\s+([a-zA-ZäöüÄÖÜß\s-]+)')
            
            for i, line in enumerate(lines):
                # 1. Detect Price
                price_match = price_pattern.search(line)
                if price_match:
                    p_str = f"{price_match.group(1)}.{price_match.group(2)}{price_match.group(3) or ''}"
                    try:
                        price_val = float(p_str)
                        
                        # 2. Detect Fuel Type
                        fuel_type = 'e10' # Base default
                        up_line = line.upper()
                        if any(x in up_line for x in ['SUPER E5', ' E5', 'PREMIUM', 'SUPERE5']): fuel_type = 'e5'
                        elif 'DIESEL' in up_line: fuel_type = 'diesel'
                        elif 'E10' in up_line: fuel_type = 'e10'
                        
                        # 3. Identify Station
                        if not last_station_id:
                            name_candidate = last_potential_name if last_potential_name else line.split(price_match.group(0))[0].strip()
                            if (not name_candidate or len(name_candidate) < 3) and i > 0:
                                name_candidate = lines[i-1]
                            
                            if name_candidate and len(name_candidate) >= 3:
                                stations = self.db.get_all_stations()
                                station = next((s for s in stations if s.name.lower() in name_candidate.lower() or name_candidate.lower() in s.name.lower()), None)
                                
                                if not station:
                                    s_id = str(uuid.uuid4())
                                    dash_logger.info(f"Creating new station from import: {name_candidate}")
                                    self.db.add_station(s_id, name_candidate, city=current_city)
                                    last_station_id = s_id
                                else:
                                    last_station_id = station.id
                        
                        if last_station_id:
                            dash_logger.info(f"Adding price: {last_station_id} | {fuel_type} | {price_val}")
                            self.db.add_price(last_station_id, fuel_type, price_val)
                            import_count += 1
                        
                        continue
                    except Exception as e:
                        dash_logger.warning(f"Failed to parse price in line '{line}': {e}")

                city_match = city_pattern.search(line)
                if city_match:
                    current_city = city_match.group(1).strip()
                    dash_logger.info(f"Detected city context: {current_city}")

                up_line = line.upper()
                if len(line) > 3 and not ('E5' in up_line or 'E10' in up_line or 'DIESEL' in up_line or 'EURO' in up_line or 'LITER' in up_line or 'PREIS' in up_line):
                    if i + 1 < len(lines):
                        next_up = lines[i+1].upper()
                        if ('E5' in next_up or 'E10' in next_up or 'DIESEL' in next_up) or price_pattern.search(lines[i+1]):
                            last_station_id = None
                            last_potential_name = line
                            dash_logger.info(f"Potential new station block detected: {line}")
                        else:
                            last_potential_name = line
                    else:
                        last_potential_name = line
            
            dash_logger.info(f"Bulk Import finished. Processed {import_count} entries.")
            return f"Importiert: {import_count} Einträge", ""

        # Persistence Callbacks
        @self.app.callback(
            [Output('notification-area', 'children', allow_duplicate=True),
             Output('station-grid', 'children', allow_duplicate=True),
             Output('new-station-name', 'value'),
             Output('update-price-value', 'value'),
             Output('price-update-station-selector', 'value', allow_duplicate=True),
             Output('add-station-modal', 'style', allow_duplicate=True),
             Output('update-price-modal', 'style', allow_duplicate=True),
             Output('edit-station-id-store', 'data', allow_duplicate=True)],
            [Input('save-station', 'n_clicks'),
             Input('save-price', 'n_clicks')],
            [State('new-station-name', 'value'),
             State('new-station-brand', 'value'),
             State('new-station-city', 'value'),
             State('station-selection-store', 'data'),
             State('update-fuel-type', 'value'),
             State('update-price-value', 'value'),
             State('edit-station-id-store', 'data'),
             State('price-update-station-selector', 'value')],
            prevent_initial_call=True
        )
        def handle_persistence(n_save_station, n_save_price, s_name, s_brand, s_city, selected_sid, f_type, price, edit_sid, price_modal_sid):
            ctx = callback_context
            if not ctx.triggered:
                return dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update, dash.no_update
            
            button_id = ctx.triggered[0]['prop_id'].split('.')[0]
            msg = ""
            color = "var(--success)"
            
            if button_id == 'save-station' and s_name:
                # If edit_sid is present, it's an update, otherwise new
                s_id_to_save = edit_sid if edit_sid else str(uuid.uuid4())
                self.db.add_station(s_id_to_save, s_name, brand=s_brand, city=s_city)
                msg = f"Tankstelle '{s_name}' {'aktualisiert' if edit_sid else 'hinzugefügt'}."
            
            elif button_id == 'save-price' and (price_modal_sid or selected_sid) and price:
                try:
                    target_sid = price_modal_sid if price_modal_sid else selected_sid
                    self.db.add_price(target_sid, f_type, float(price))
                    msg = f"Preis für {f_type.upper()} erfolgreich aktualisiert."
                except Exception as e:
                    msg = f"Fehler beim Speichern: {e}"
                    color = "var(--secondary)"
                
            notification = html.Div(msg, style={
                'padding': '15px 25px', 
                'borderRadius': '16px', 
                'background': 'rgba(0,0,0,0.4)', 
                'border': f'1px solid {color}',
                'color': color,
                'fontWeight': '600',
                'animation': 'fadeIn 0.5s ease-out'
            }) if msg else ""
            
            # Clear edit store if we saved a station
            edit_store_reset = None if button_id == 'save-station' else dash.no_update
            
            return notification, self._get_station_grid_content(f_type), "", "", None, dash.no_update, {'display': 'none'}, edit_store_reset

        # Callback for ADAC Scraper
        @self.app.callback(
            [Output('scraper-status', 'children'),
             Output('scraper-dummy-output', 'children'),
             Output('last-scrape-ts', 'data')],
            Input('trigger-adac-scrape', 'n_clicks'),
            State('scraper-plz-input', 'value'),
            prevent_initial_call=True
        )
        def run_adac_scrape(n_clicks, plz):
            if not n_clicks or not plz:
                return "", "", dash.no_update
            
            try:
                scraper = ADACScraper(self.db)
                results = scraper.scrape_all_fuel_types(plz=plz.strip())
                
                total = sum(len(v) for v in results.values())
                parts = [f"{len(v)}× {k}" for k, v in results.items() if v]
                detail = ", ".join(parts) if parts else "keine Treffer"
                
                msg = f"✅ {total} Stationen importiert ({detail})"
                return msg, "", time.time()
            except Exception as e:
                return f"❌ Fehler: {e}", "", dash.no_update

        # Countdown timer callback
        @self.app.callback(
            [Output('scrape-countdown-value', 'children'),
             Output('scrape-last-time', 'children')],
            Input('countdown-interval', 'n_intervals'),
            State('last-scrape-ts', 'data'),
        )
        def update_countdown(_, last_ts):
            interval_sec = getattr(config, 'SCRAPE_INTERVAL_MINUTES', 15) * 60
            
            if not last_ts:
                # No manual scrape yet — use app start time as fallback
                if not hasattr(self, '_app_start_ts'):
                    self._app_start_ts = time.time()
                last_ts = self._app_start_ts
            
            elapsed = time.time() - last_ts
            remaining = max(0.0, float(interval_sec) - elapsed)
            mins = int(remaining // 60)
            secs = int(remaining % 60)
            
            last_time_str = datetime.fromtimestamp(last_ts).strftime('%H:%M:%S')
            
            return f"{mins:02d}:{secs:02d}", f"Letzter Scan: {last_time_str}"

        # Autostart toggle callback
        @self.app.callback(
            Output('notification-area', 'children', allow_duplicate=True),
            Input('autostart-toggle', 'value'),
            prevent_initial_call=True
        )
        def toggle_autostart(checked_values):
            enable = 'enabled' in (checked_values or [])
            success = self.autostart.set_autostart(enable)
            if success:
                status = "aktiviert" if enable else "deaktiviert"
                return self._create_notification(f"Windows Autostart wurde {status}.", 'success')
            else:
                return self._create_notification("Fehler beim Ändern des Autostarts.", 'error')

        # Cloud Sync Callback
        @self.app.callback(
            [Output('notification-area', 'children', allow_duplicate=True),
             Output('station-grid', 'children', allow_duplicate=True)],
            Input('trigger-cloud-sync', 'n_clicks'),
            [State('fuel-type-selector', 'value'),
             State('station-selection-store', 'data')],
            prevent_initial_call=True
        )
        def run_cloud_sync(n_clicks, fuel_type, selected_id):
            if not n_clicks:
                return dash.no_update, dash.no_update
            
            url = getattr(config, 'GITHUB_CSV_URL', '')
            if not url:
                return self._create_notification("Cloud-URL nicht konfiguriert! Siehe config.py.", 'warning'), dash.no_update
            
            try:
                import requests
                import io
                import csv
                
                response = requests.get(url, timeout=15)
                response.raise_for_status()
                
                csv_data = io.StringIO(response.text)
                reader = csv.DictReader(csv_data)
                
                count = 0
                for row in reader:
                    # Sync price to DB
                    s_id = row['station_id']
                    f_type = row['fuel_type']
                    p_val = float(row['price'])
                    ts = row['timestamp']
                    
                    # We need to make sure the station exists
                    # If not, the sync will just skip or we could try to create it
                    # For now, we assume user shares the same stations
                    if self.db.add_price(s_id, f_type, p_val, timestamp=ts):
                        count += 1
                
                grid = self._get_station_grid_content(fuel_type, selected_id)
                return self._create_notification(f"Cloud Sync erfolgreich! {count} Einträge importiert.", 'success'), grid
            except Exception as e:
                return self._create_notification(f"Sync fehlgeschlagen: {str(e)}", 'error'), dash.no_update

    def _create_notification(self, message, n_type='info'):
        colors = {
            'success': 'var(--success)',
            'error': 'var(--secondary)',
            'info': 'var(--primary)',
            'warning': '#ffd700'
        }
        color = colors.get(n_type, 'var(--primary)')
        
        return html.Div(message, style={
            'padding': '15px 25px', 
            'borderRadius': '16px', 
            'background': 'rgba(0,0,0,0.4)', 
            'border': f'1px solid {color}',
            'color': color,
            'fontWeight': '600',
            'animation': 'fadeIn 0.5s ease-out',
            'marginBottom': '10px'
        })

    def run(self, debug=False, port=8050):
        self.app.run(debug=debug, port=port)

if __name__ == "__main__":
    db = TankRadarDashboard()
    db.run(debug=True)
