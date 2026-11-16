#!/usr/bin/env python3
"""Regenerates DebridOne's brand images.

The DebridOne mark (composeApp/src/debridoneMain/brand/icon.svg) is drawn here with Pillow so the
script has no native dependencies. Outputs:

  composeApp/src/debridoneMain/composeResources/drawable/   overlay for Nuvio's in-app brand images
  composeApp/src/debridoneMain/resources/icons/             window/taskbar icon (.png, .ico)

The macOS bundle icon (debridone-app-icon.icns) is checked in as-is; rebuild it on a Mac with
`iconutil` if the mark changes.

Usage: python3 scripts/debridone/generate-brand-assets.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OVERLAY = ROOT / "composeApp/src/debridoneMain/composeResources/drawable"
ICONS = ROOT / "composeApp/src/debridoneMain/resources/icons"

GRADIENT_FROM = (0xA7, 0x8B, 0xFA)
GRADIENT_TO = (0x6D, 0x28, 0xD9)
COLOURWAYS = ["original", "arctic_blue", "emerald", "rose_gold", "copper", "graphite"]
WORDMARKS = ["", "_original", "_arctic_blue", "_emerald", "_rose_gold", "_copper", "_graphite", "_gold"]
FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/inter/InterDisplay-SemiBold.otf",
    "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf",
    "/System/Library/Fonts/SFNS.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def mark(size: int, rounded: bool = True) -> Image.Image:
    """The DebridOne mark: a white 'D' with a centre dot on a violet gradient tile."""
    scale = 4
    px = size * scale
    unit = px / 512.0

    gradient = Image.new("RGB", (px, px))
    draw = ImageDraw.Draw(gradient)
    for i in range(2 * px):
        t = i / (2 * px - 1)
        colour = tuple(round(a + (b - a) * t) for a, b in zip(GRADIENT_FROM, GRADIENT_TO))
        draw.line([(i, 0), (0, i)], fill=colour, width=2)

    mask = Image.new("L", (px, px), 0)
    radius = round(112 * unit) if rounded else 0
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, px - 1, px - 1], radius=radius, fill=255)
    tile = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    tile.paste(gradient, (0, 0), mask)

    def point(x: float, y: float):
        return ((85 + x * 5.33) * unit, (85 + y * 5.33) * unit)

    import math

    outline = [point(19, 16), point(32, 16)]
    for step in range(0, 181, 3):
        angle = math.radians(-90 + step)
        outline.append(point(32 + 16 * math.cos(angle), 32 + 16 * math.sin(angle)))
    outline += [point(19, 48), point(19, 16), point(32, 16)]

    glyph = ImageDraw.Draw(tile)
    stroke = round(6 * 5.33 * unit)
    glyph.line(outline, fill=(255, 255, 255, 255), width=stroke)
    # Round joins: a disc at every vertex hides the seams between line segments.
    half = stroke / 2
    for x, y in outline:
        glyph.ellipse([x - half, y - half, x + half, y + half], fill=(255, 255, 255, 255))
    cx, cy = point(31, 32)
    r = 4 * 5.33 * unit
    glyph.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255))
    return tile.resize((size, size), Image.LANCZOS)


def font(size: int) -> ImageFont.FreeTypeFont:
    for candidate in FONT_CANDIDATES:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    raise SystemExit("No suitable font found; install Inter or edit FONT_CANDIDATES.")


def wordmark(width: int = 1085, height: int = 344) -> Image.Image:
    """The DebridOne mark followed by 'DebridOne' in white, sized like Nuvio's wordmark images."""
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    tile = round(height * 0.62)
    gap = round(tile * 0.32)
    size = 200
    while size > 40:
        face = font(size)
        left, top, right, bottom = draw.textbbox((0, 0), "DebridOne", font=face)
        if tile + gap + (right - left) <= width * 0.96 and bottom - top <= height * 0.6:
            break
        size -= 4
    text_width = right - left
    x = 0
    image.alpha_composite(mark(tile), (x, (height - tile) // 2))
    draw.text((x + tile + gap - left, (height - (bottom - top)) / 2 - top), "DebridOne", font=face, fill=(255, 255, 255, 255))
    return image


def main() -> None:
    OVERLAY.mkdir(parents=True, exist_ok=True)
    ICONS.mkdir(parents=True, exist_ok=True)

    icon_256 = mark(256)
    opaque = Image.new("RGB", (256, 256), (0x0D, 0x0D, 0x0D))
    opaque.paste(icon_256, (0, 0), icon_256)
    for name in COLOURWAYS:
        opaque.save(OVERLAY / f"app_icon_{name}.png", optimize=True)
        icon_256.save(OVERLAY / f"app_icon_{name}_transparent.png", optimize=True)

    mark_image = wordmark()
    for suffix in WORDMARKS:
        mark_image.save(OVERLAY / f"app_logo_wordmark{suffix}.png", optimize=True)

    mark(512).save(ICONS / "debridone-app-icon.png", optimize=True)
    mark(256).save(ICONS / "debridone-app-icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print(f"Wrote brand assets to {OVERLAY.relative_to(ROOT)} and {ICONS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
