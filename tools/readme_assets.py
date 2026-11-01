"""Generates the README's theme-aware assets into <repo>/docs/readme/.

usage: pip install fonttools pillow && python tools/readme_assets.py
Text is outlined from the app's own font (Pretendard subset, variable wght),
so badges and the wordmark render the same on every viewer.
"""
import os
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from PIL import Image, ImageDraw

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "readme")
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "rapfi_sans.ttf")
SHOTS = os.path.join(ROOT, "app", "src", "test", "screenshots")

THEMES = {
    "light": dict(primary="#65528A", tile="#ECE6F8", text="#1C1B1E", muted="#6B6774",
                  surface="#FFFFFF", border="#DEDAE6"),
    "dark": dict(primary="#CDBAFA", tile="#2F2A3B", text="#E8E3EC", muted="#A8A3B2",
                 surface="#1F1D24", border="#3B3844"),
}
MAPLE, INK, PEARL = "#DDB27C", "#171513", "#F6F1E9"

_fonts = {}


def font(wght):
    if wght not in _fonts:
        _fonts[wght] = instantiateVariableFont(TTFont(FONT), {"wght": wght})
    return _fonts[wght]


def text_path(text, size, wght, x, y, tracking=0.0):
    """SVG path data for `text` with its baseline at (x, y); returns (d, width)."""
    f = font(wght)
    gs, cmap = f.getGlyphSet(), f.getBestCmap()
    scale = size / f["head"].unitsPerEm
    pen = SVGPathPen(gs, ntos=lambda v: ("%.1f" % v).rstrip("0").rstrip("."))
    cx = x
    for ch in text:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (scale, 0, 0, -scale, cx, y)))
        cx += gs[g].width * scale + tracking * size
    return pen.getCommands(), cx - x - (tracking * size if text else 0)


def write(name, body, w, h):
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:g}" height="{h:g}" '
           f'viewBox="0 0 {w:g} {h:g}">{body}</svg>\n')
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(svg)


# --- glyphs on a 24-unit grid, stroked --------------------------------------
GLYPHS = {
    "phone": '<rect x="6" y="2.5" width="12" height="19" rx="2.5"/><path d="M10.5 18.5h3"/>',
    "sparkles": '<path d="M11 3.5l1.7 5.3 5.3 1.7-5.3 1.7L11 17.5l-1.7-5.3L4 10.5l5.3-1.7z"/>'
                '<path d="M18.5 15v5M16 17.5h5"/>',
    "chip": '<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="9.5" y="9.5" width="5" height="5" rx=".8"/>'
            '<path d="M9.5 2.5v3.5M14.5 2.5v3.5M9.5 18v3.5M14.5 18v3.5M2.5 9.5H6M2.5 14.5H6M18 9.5h3.5M18 14.5h3.5"/>',
    "download": '<path d="M12 3.5v11.5M7 10.5l5 5 5-5M4.5 20.5h15"/>',
    "code": '<path d="M8 7.5L3.5 12 8 16.5M16 7.5l4.5 4.5-4.5 4.5M13.5 4.5l-3 15"/>',
    "question": '<circle cx="12" cy="12" r="9"/><path d="M9.6 9.4a2.5 2.5 0 1 1 3.6 2.3c-.7.3-1.2.9-1.2 1.7v.4"/>'
                '<circle cx="12" cy="17" r=".4" fill="currentColor"/>',
    "heart": '<path d="M12 20s-7.5-4.3-7.5-10A4.2 4.2 0 0 1 12 7.3 4.2 4.2 0 0 1 19.5 10c0 5.7-7.5 10-7.5 10z"/>',
    "doc": '<path d="M6.5 2.5h7.5l4 4v15h-11.5z"/><path d="M14 2.5v4h4M9.5 12h5.5M9.5 15.5h5.5"/>',
    "graph": '<path d="M3.5 20.5h17"/><path d="M4 16l4.5-5.5 4 3 4.5-7 3.5 3"/>',
    "clock": '<circle cx="12" cy="13.5" r="7.5"/><path d="M12 9.5v4l2.7 2.2M9.5 2.5h5"/>',
    "grid": '<path d="M3.5 8h17M3.5 16h17M8 3.5v17M16 3.5v17"/><path d="M10.4 10.4l3.2 3.2M13.6 10.4l-3.2 3.2"/>',
    "history": '<path d="M3.8 12a8.2 8.2 0 1 0 2.4-5.8L3.8 8.5"/><path d="M3.8 3.8v4.7h4.7M12 8v4.2l3 2"/>',
    "branch": '<circle cx="6.5" cy="5" r="2"/><circle cx="6.5" cy="19" r="2"/><circle cx="17.5" cy="8" r="2"/>'
              '<path d="M6.5 7v10M17.5 10c0 3.8-6.5 3.2-10 6.5"/>',
    "book": '<path d="M12 6.5C10 5 7 4.5 3.5 5v13.5c3.5-.5 6.5 0 8.5 1.5 2-1.5 5-2 8.5-1.5V5C17 4.5 14 5 12 6.5z"/>'
            '<path d="M12 6.5V20"/>',
    "shield": '<path d="M12 3l7 2.8V11c0 4.6-3 8.3-7 10-4-1.7-7-5.4-7-10V5.8z"/><path d="M9 12l2.2 2.2L15.2 10"/>',
    "refresh": '<path d="M19.5 9A8 8 0 0 0 5 7.5M4.5 15A8 8 0 0 0 19 16.5"/><path d="M19.5 3.5V9H14M4.5 20.5V15H10"/>',
    "globe": '<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="3.8" ry="9"/><path d="M3 12h18"/>',
    "translate": '<path d="M3.5 5.5h9M8 3.5v2M5.5 5.5c.5 3.5 3 6 6 7M10.5 5.5c-.5 3.5-3 6-6 7"/>'
                 '<path d="M12.5 20.5l4-9 4 9M14 17h5"/>',
    "server": '<rect x="3.5" y="4" width="17" height="7" rx="2"/><rect x="3.5" y="13" width="17" height="7" rx="2"/>'
              '<path d="M7 7.5h.01M7 16.5h.01"/>',
}


def glyph(name, color, x, y, box, stroke=1.9):
    s = box / 24
    return (f'<g transform="translate({x:g} {y:g}) scale({s:g})" fill="none" stroke="{color}" color="{color}" '
            f'stroke-width="{stroke:g}" stroke-linecap="round" stroke-linejoin="round">{GLYPHS[name]}</g>')


def icon_tile(name, t, size, radius, box):
    pad = (size - box) / 2
    return (f'<rect width="{size}" height="{size}" rx="{radius}" fill="{t["tile"]}"/>'
            + glyph(name, t["primary"], pad, pad, box))


# --- logo lockup ------------------------------------------------------------
ICON = (
    f'<rect width="72" height="72" rx="17" fill="{MAPLE}"/>'
    '<g transform="translate(-18 -18)">'
    f'<path fill="{PEARL}" d="M56.42,38.15 A16.56,16.56 0 1,1 56.42,69.85 A18.18,18.18 0 0,0 56.42,38.15 z"/>'
    f'<path fill="{INK}" d="M32.58,54 a14.94,14.94 0 1,0 29.88,0 a14.94,14.94 0 1,0 -29.88,0 z"/>'
    '</g>'
)


def lockup(theme):
    t = THEMES[theme]
    h, icon = 112, 112
    word, ww = text_path("Zenyth", 84, 760, icon + 30, 86, tracking=-0.02)
    w = icon + 30 + ww + 4
    body = (f'<g transform="scale({icon / 72:g})">{ICON}</g>'
            f'<path fill="{t["text"]}" d="{word}"/>')
    write(f"logo/zenyth-lockup-{theme}.svg", body, round(w), h)


# --- pill badges ------------------------------------------------------------
BADGES = {
    "release": ("download", "DOWNLOAD", "Latest release"),
    "obtainium": ("refresh", "AUTO-UPDATE WITH", "Obtainium"),
    "web": ("globe", "ALSO IN THE BROWSER", "Zenyth Web"),
    "readme-ko": ("translate", "README", "한국어"),
    "readme-en": ("translate", "README", "English"),
}


def badge(key, theme):
    t = THEMES[theme]
    icon, small, big = BADGES[key]
    h, left = 56, 50
    sd, sw = text_path(small, 10.5, 640, left, 23, tracking=0.08)
    bd, bw = text_path(big, 17.5, 720, left, 42.5)
    w = left + max(sw, bw) + 22
    body = (f'<rect x=".75" y=".75" width="{w - 1.5:g}" height="{h - 1.5:g}" rx="{(h - 1.5) / 2:g}" '
            f'fill="{t["surface"]}" stroke="{t["border"]}" stroke-width="1.5"/>'
            + glyph(icon, t["primary"], 17, 16, 24, 2.1)
            + f'<path fill="{t["muted"]}" d="{sd}"/><path fill="{t["text"]}" d="{bd}"/>')
    write(f"badges/{key}-{theme}.svg", body, round(w), h)


# --- section & feature icons -----------------------------------------------
SECTIONS = {
    "screenshots": "phone", "features": "sparkles", "download": "download", "engine": "chip",
    "development": "code", "faq": "question", "credits": "heart", "licence": "doc",
}
FEATURES = {
    "engine": "chip", "analysis": "graph", "play": "clock", "rules": "grid",
    "review": "history", "openings": "branch", "joseki": "book", "offline": "shield",
}


# --- showcase ---------------------------------------------------------------
SCREENS = ["setup_welcome", "screen_live_analysis", "screen_board_sheet", "settings_root"]


def showcase(theme):
    pw, ph = 822, 1542               # the board goldens' size; the others are fitted into it
    frames = []
    for name in SCREENS:
        im = Image.open(os.path.join(SHOTS, f"{name}_{theme}_100.png")).convert("RGB")
        bg = im.getpixel((2, 2))
        s = min(pw / im.width, ph / im.height)
        im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
        canvas = Image.new("RGB", (pw, ph), bg)
        canvas.paste(im, ((pw - im.width) // 2, 0))
        frames.append(canvas)
    scale = 0.5
    fw, fh = int(pw * scale), int(ph * scale)
    gap, bezel, radius = 40, 10, 44
    W = len(frames) * (fw + 2 * bezel) + (len(frames) - 1) * gap
    H = fh + 2 * bezel
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bezel_color = (32, 31, 36, 255) if theme == "light" else (58, 56, 64, 255)
    for i, fr in enumerate(frames):
        x = i * (fw + 2 * bezel + gap)
        shell = Image.new("RGBA", (fw + 2 * bezel, H), (0, 0, 0, 0))
        ImageDraw.Draw(shell).rounded_rectangle((0, 0, shell.width - 1, H - 1), radius + bezel, fill=bezel_color)
        scr = fr.resize((fw, fh), Image.LANCZOS).convert("RGBA")
        mask = Image.new("L", (fw, fh), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, fw - 1, fh - 1), radius, fill=255)
        shell.paste(scr, (bezel, bezel), mask)
        out.alpha_composite(shell, (x, 0))
    out.save(os.path.join(OUT, f"showcase-{theme}.png"), optimize=True)


def main():
    for theme, t in THEMES.items():
        lockup(theme)
        for key in BADGES:
            badge(key, theme)
        for key, g in SECTIONS.items():
            write(f"sections/{key}-{theme}.svg", icon_tile(g, t, 32, 9, 20), 32, 32)
        for key, g in FEATURES.items():
            write(f"features/{key}-{theme}.svg", icon_tile(g, t, 56, 16, 30), 56, 56)
        showcase(theme)
    write("logo/zenyth-icon.svg", ICON, 72, 72)


if __name__ == "__main__":
    main()
