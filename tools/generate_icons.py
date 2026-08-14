"""Generate the TankRadar app icons.

Renders the brand's radar mark (concentric rings on the dark app background) into
the PNG sizes that the PWA manifest and the Android launcher need. Uses only the
standard library so the icons can be regenerated without extra dependencies:

    python tools/generate_icons.py

Regenerate whenever the brand colors in assets/style.css change.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = REPO_ROOT / "assets" / "icons"

BACKGROUND = (7, 17, 31)
RING_OUTER = (34, 231, 123)
RING_INNER = (69, 163, 255)
CENTER = (34, 231, 123)

# Ring geometry as a fraction of the icon's half-width, outermost first.
# (outer_radius, inner_radius, color); inner_radius 0 means a filled disc.
RINGS = [
    (0.78, 0.68, RING_OUTER),
    (0.54, 0.45, RING_INNER),
    (0.22, 0.0, CENTER),
]

# Fraction of the half-width kept as transparent padding, so the same artwork
# survives Android's adaptive-icon mask without clipping the outer ring.
SAFE_AREA = 0.92


def _mix(background: tuple[int, int, int], foreground: tuple[int, int, int], alpha: float) -> tuple[int, int, int]:
    return (
        round(background[0] + (foreground[0] - background[0]) * alpha),
        round(background[1] + (foreground[1] - background[1]) * alpha),
        round(background[2] + (foreground[2] - background[2]) * alpha),
    )


def _coverage(distance: float, edge: float, feather: float) -> float:
    """Antialiased step: 1 well inside `edge`, 0 well outside it."""
    if feather <= 0:
        return 1.0 if distance <= edge else 0.0
    return max(0.0, min(1.0, (edge - distance) / feather + 0.5))


def render_icon(size: int, *, transparent_background: bool = False) -> bytes:
    """Render one square RGBA icon and return the encoded PNG bytes."""
    half = size / 2
    feather = 1.2
    rows = bytearray()

    for y in range(size):
        rows.append(0)  # PNG filter type 0 (None) for this scanline
        for x in range(size):
            distance = ((x + 0.5 - half) ** 2 + (y + 0.5 - half) ** 2) ** 0.5

            disc = _coverage(distance, half * SAFE_AREA, feather)
            if transparent_background:
                color = BACKGROUND
                alpha = disc
            else:
                color = BACKGROUND
                alpha = 1.0

            for outer, inner, ring_color in RINGS:
                inside = _coverage(distance, half * outer, feather)
                if inner:
                    inside *= 1.0 - _coverage(distance, half * inner, feather)
                if inside > 0:
                    color = _mix(color, ring_color, inside)
                    alpha = max(alpha, inside if transparent_background else 1.0)

            rows.extend((color[0], color[1], color[2], round(alpha * 255)))

    return _encode_png(size, size, bytes(rows))


def _chunk(tag: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + tag
        + payload
        + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
    )


def _encode_png(width: int, height: int, raw: bytes) -> bytes:
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", header)
        + _chunk(b"IDAT", zlib.compress(raw, 9))
        + _chunk(b"IEND", b"")
    )


def main() -> None:
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    # The opaque icons are full-bleed with the artwork inside the maskable safe
    # zone, so one file can serve both the "any" and "maskable" purposes. The
    # transparent 432px render is Android's adaptive-icon foreground layer.
    targets = [
        ("icon-192.png", 192, False),
        ("icon-512.png", 512, False),
        ("icon-foreground-432.png", 432, True),
    ]
    for filename, size, transparent in targets:
        path = ICON_DIR / filename
        path.write_bytes(render_icon(size, transparent_background=transparent))
        print(f"wrote {path.relative_to(REPO_ROOT)} ({size}x{size})")


if __name__ == "__main__":
    main()
