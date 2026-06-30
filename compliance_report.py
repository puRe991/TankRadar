"""Detection and PDF evidence export for price changes after a configured cutoff."""

from datetime import time

import pandas as pd

FUEL_LABELS = {"e5": "Super E5", "e10": "Super E10", "e5p": "Super Plus", "diesel": "Diesel"}
REPORT_COLUMNS = [
    "event_id", "timestamp", "station_id", "station_name", "brand", "street",
    "house_number", "post_code", "city", "latitude", "longitude", "fuel_type",
    "previous_price", "price", "difference",
]


def detect_price_change_cases(price_rows, stations, cutoff_hour=12, days=30, now=None):
    """Return actual price changes after cutoff, enriched with station evidence data."""
    empty = pd.DataFrame(columns=REPORT_COLUMNS)
    if not price_rows:
        return empty

    now = pd.Timestamp(now or pd.Timestamp.now())
    start = now - pd.Timedelta(days, unit="D")
    prices = pd.DataFrame(price_rows)
    prices["timestamp"] = pd.to_datetime(prices["timestamp"])
    prices = prices.sort_values(["station_id", "fuel_type", "timestamp", "id"])
    prices["previous_price"] = prices.groupby(["station_id", "fuel_type"])["price"].shift(1)

    changed = prices[
        prices["previous_price"].notna()
        & prices["price"].ne(prices["previous_price"])
        & prices["timestamp"].ge(start)
        & prices["timestamp"].dt.time.gt(time(cutoff_hour, 0))
    ].copy()
    if changed.empty:
        return empty

    station_df = pd.DataFrame(stations, columns=["id", "name", "brand", "street", "house_number", "post_code", "city", "latitude", "longitude"])
    changed = changed.merge(station_df, left_on="station_id", right_on="id", how="left", suffixes=("", "_station"))
    changed["difference"] = changed["price"] - changed["previous_price"]
    changed["event_id"] = changed["id"].map(lambda value: f"TR-{int(value):08d}")
    changed["station_name"] = changed["name"].fillna("Unbekannte Tankstelle")
    for column in REPORT_COLUMNS:
        if column not in changed:
            changed[column] = None
    return changed[REPORT_COLUMNS].sort_values(by="timestamp", ascending=False).reset_index(drop=True)


def format_address(row):
    street = " ".join(filter(None, [str(row.get("street") or "").strip(), str(row.get("house_number") or "").strip()])).strip()
    city = " ".join(filter(None, [str(row.get("post_code") or "").strip(), str(row.get("city") or "").strip()])).strip()
    return ", ".join(filter(None, [street, city])) or "Keine Anschrift hinterlegt"


def _pdf_escape(value):
    return str(value).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def _simple_pdf(lines, lines_per_page=48):
    """Generate a dependency-free, printable PDF using a built-in PDF font."""
    pages = [lines[index:index + lines_per_page] for index in range(0, len(lines), lines_per_page)] or [[]]
    objects = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    page_refs = " ".join(f"{4 + index * 2} 0 R" for index in range(len(pages)))
    objects.append(f"<< /Type /Pages /Kids [{page_refs}] /Count {len(pages)} >>".encode("ascii"))
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")
    for index, page_lines in enumerate(pages):
        page_number = 4 + index * 2
        content_number = page_number + 1
        objects.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Resources << /Font << /F1 3 0 R >> >> /Contents {content_number} 0 R >>".encode("ascii"))
        commands = ["BT", "/F1 7 Tf", "30 560 Td", "9 TL"]
        for line in page_lines:
            commands.append(f"({_pdf_escape(line)}) Tj")
            commands.append("T*")
        commands.append("ET")
        stream = "\n".join(commands).encode("cp1252", errors="replace")
        objects.append(f"<< /Length {len(stream)} >>\nstream\n".encode("ascii") + stream + b"\nendstream")

    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, obj in enumerate(objects, start=1):
        offsets.append(len(output))
        output.extend(f"{number} 0 obj\n".encode("ascii") + obj + b"\nendobj\n")
    xref = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("ascii"))
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF".encode("ascii"))
    return bytes(output)


def build_complaint_pdf(cases, cutoff_hour=12, generated_at=None):
    """Build a neutral complaint/evidence PDF and return its bytes."""
    generated_at = pd.Timestamp(generated_at or pd.Timestamp.now())
    lines = [
        "TANKRADAR - DOKUMENTATION VON PREISAENDERUNGS-PRUEFFAELLEN",
        "=" * 112,
        f"Erstellt: {generated_at.strftime('%d.%m.%Y %H:%M Uhr')} | Pruefkriterium: tatsaechliche Preisaenderung nach {cutoff_hour:02d}:00 Uhr",
        "Hinweis: Diese automatisiert erkannten Prueffaelle sind kein Nachweis eines Rechtsverstosses.",
        "Das Dokument dient als sachliche Anlage fuer eine Pruefung oder Beschwerde.",
        "",
        f"Ermittelte Prueffaelle: {len(cases)}",
        "",
    ]
    for _, row in cases.iterrows():
        station = f"{row.get('brand') or ''} {row.get('station_name') or ''}".strip()
        coords = "nicht hinterlegt"
        if pd.notna(row.get("latitude")) and pd.notna(row.get("longitude")):
            coords = f"{float(row['latitude']):.6f}, {float(row['longitude']):.6f}"
        lines.extend([
            "-" * 112,
            f"Vorgang: {row['event_id']} | Zeitpunkt: {pd.Timestamp(row['timestamp']).strftime('%d.%m.%Y %H:%M:%S Uhr')}",
            f"Tankstelle: {station} | Stations-ID: {row['station_id']}",
            f"Anschrift: {format_address(row)} | Koordinaten: {coords}",
            f"Kraftstoff: {FUEL_LABELS.get(row['fuel_type'], str(row['fuel_type']).upper())}",
            f"Preis vorher: {row['previous_price']:.3f} EUR/L | Preis neu: {row['price']:.3f} EUR/L | Aenderung: {row['difference']:+.3f} EUR/L",
        ])
    if cases.empty:
        lines.append("Im gewaehlten Zeitraum wurden keine passenden Prueffaelle ermittelt.")
    return _simple_pdf(lines)
