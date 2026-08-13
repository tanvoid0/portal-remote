#!/usr/bin/env python3
"""Generate the README mockups in docs/assets.

    python docs/assets/generate.py              # write every screen
    python docs/assets/generate.py --only android-media android-share
    python docs/assets/generate.py --list       # names it knows
    python docs/assets/generate.py --check      # exit 1 if a file is stale (CI / pre-commit)
    python docs/assets/generate.py --png        # also rasterise to docs/assets/png/

Each screen is one function returning SVG body markup, registered in SCREENS at the
bottom. To add a screen: write the function, add it to SCREENS, run the script, then
reference docs/assets/<name>.svg from the README. Files whose content is unchanged are
left alone, so `git status` only shows what actually moved.

Colours, type and geometry come from docs/design-system.md; labels come from the real
strings in the Compose screens and Tray/MainForm.cs. These are mockups of the UI, not
captures of a running build — keep them honest when the UI changes.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent

# --- tokens (docs/design-system.md §3, dark theme) --------------------------------

ACCENT = "#60A5FA"
BG = "#080C18"
SURFACE = "#101725"
RAISED = "#18202F"
MUTED = "#1F2839"
BORDER = "#2A3446"
# §3's `border-strong`: the boundary of a *control*, at the 3:1 WCAG 1.4.11 asks for.
# Was a one-off "border, but visible on black" value before §3 had a token for it.
BORDER_STRONG = "#64748B"
SECONDARY_CONTAINER = "#24406E"
PRIMARY_CONTAINER = "#14264A"
TXT = "#EEF2F8"
TXT2 = "#9AA7BD"
TXT3 = "#8492A8"
SUCCESS = "#4ADE80"
DANGER = "#F87171"
WARNING = "#FBBF24"

FONT = "Segoe UI, Roboto, Helvetica, Arial, sans-serif"
FONT_DESKTOP = "Segoe UI, Segoe UI Variable, Roboto, Helvetica, Arial, sans-serif"
MONO = "Cascadia Mono, Consolas, monospace"

PHONE_W, PHONE_H = 380, 800

# --- primitives -------------------------------------------------------------------


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, s, size=13, fill=TXT, weight=None, anchor=None, family=None, opacity=None):
    a = f' font-weight="{weight}"' if weight else ""
    a += f' text-anchor="{anchor}"' if anchor else ""
    a += f' font-family="{family}"' if family else ""
    a += f' fill-opacity="{opacity}"' if opacity else ""
    return f'<text x="{x}" y="{y}" fill="{fill}" font-size="{size}"{a}>{esc(s)}</text>'


def rect(x, y, w, h, r=0, fill="none", stroke=None, opacity=None, stroke_opacity=None):
    a = f' rx="{r}"' if r else ""
    a += f' stroke="{stroke}"' if stroke else ""
    a += f' fill-opacity="{opacity}"' if opacity is not None else ""
    a += f' stroke-opacity="{stroke_opacity}"' if stroke_opacity is not None else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}"{a}/>'


def svg(body: str, w: int, h: int, font: str = FONT) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" '
        f'width="{w}" height="{h}" font-family="{font}">\n{body}\n</svg>\n'
    )


def text_width(s: str, size: float) -> float:
    """Rough advance width — enough to size a chip or a pill, not to typeset."""
    return len(s) * size * 0.53


# --- shared chrome ----------------------------------------------------------------


def phone_frame() -> str:
    return (
        # Token-derived, not a literal: this used to string-replace the hex value of
        # BORDER to add the width, which silently became a no-op the first time the
        # token changed — the frame just lost its outline.
        rect(2, 2, 376, 796, 42, "#05070C", stroke=BORDER).replace(
            f'stroke="{BORDER}"', f'stroke="{BORDER}" stroke-width="2"'
        )
        + "\n  "
        + rect(12, 12, 356, 776, 33, BG)
    )


def title_row(name: str, dot=SUCCESS, note: str | None = None) -> str:
    out = [
        f'<circle cx="38" cy="46" r="5" fill="{dot}"/>',
        text(54, 51, name, 15, TXT, "600"),
        f'<g transform="translate(334,46)" stroke="{TXT2}" stroke-width="1.6" fill="none">'
        '<circle r="3.4"/>'
        '<path d="M0 -8v3M0 8v-3M-8 0h3M8 0h-3M-5.7 -5.7l2.1 2.1M5.7 5.7l-2.1-2.1'
        'M-5.7 5.7l2.1-2.1M5.7 -5.7l-2.1 2.1"/></g>',
    ]
    if note:
        out.append(text(190, 51, note, 12, TXT2, anchor="middle"))
    return "\n  ".join(out)


def mode_tabs(selected: str) -> str:
    """ControlScreen's PrimaryTabRow — trackpad / keyboard / media / TV remote."""
    tabs = [("Trackpad", 66, 34, 64), ("Keyboard", 150, 118, 64), ("Media", 228, 200, 56), ("Remote", 304, 276, 56)]
    out = []
    for label, cx, ux, uw in tabs:
        on = label == selected
        out.append(text(cx, 94, label, 13, ACCENT if on else TXT2, "600" if on else None, "middle"))
        if on:
            underline = rect(ux, 104, uw, 3, 1.5, ACCENT)
    out.append(rect(24, 106, 332, 1, fill=BORDER))
    out.append(underline)
    return "\n  ".join(out)


def glyph(kind: str, cx: float, cy: float, color: str) -> str:
    """16dp nav icons, drawn rather than pulled from Material Symbols."""
    stroke = f'stroke="{color}" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round"'
    g = {
        "control": f'<circle r="3.2" fill="{color}"/><circle r="7.2" fill="none" stroke="{color}" '
                   'stroke-width="1.7" stroke-opacity="0.55"/>',
        "browser": f'<g {stroke}><circle r="7"/><ellipse rx="3" ry="7"/><path d="M-7 0h14"/></g>',
        "screen": f'<g {stroke}><rect x="-8" y="-6.5" width="16" height="11.5" rx="2"/><path d="M-3.5 8.5h7"/></g>',
        "share": f'<g {stroke}><path d="M-4.5 7.5V-7M-7.5 -4l3-3 3 3"/><path d="M4.5 -7.5V7M1.5 4l3 3 3-3"/></g>',
        "files": f'<g {stroke}><path d="M-8 5.5v-11h5l2 2.2h9v8.8z"/></g>',
        "assistant": f'<path d="M0 -8l1.9 5.9L8 0 1.9 2.1 0 8-1.9 2.1-8 0-1.9-2.1Z" fill="{color}"/>',
    }[kind]
    return f'<g transform="translate({cx},{cy})">{g}</g>'


NAV_TABS = [
    ("control", "Control"),
    ("browser", "Browser"),
    ("screen", "Screen"),
    ("share", "Share"),
    ("files", "Files"),
    ("assistant", "Assistant"),
]


def nav_bar(selected: str, y: float = 702, frosted: bool = False) -> str:
    """The floating capsule: only the selected tab says its name (design-system §7)."""
    x, w, h, pad = 30, 320, 60 if not frosted else 56, 10
    label = dict(NAV_TABS)[selected]
    pill_w = 46 + text_width(label, 12.5)
    cy = y + h / 2
    out = [rect(x, y, w, h, h / 2, RAISED, stroke=BORDER, opacity=0.85 if frosted else None)]

    pill_x = x + pad
    others = [t for t in NAV_TABS if t[0] != selected]
    idx = [k for k, _ in NAV_TABS].index(selected)
    lead, trail = others[:idx], others[idx:]

    slot = (w - 2 * pad - pill_w) / max(len(others), 1)
    cursor = x + pad
    for kind, _ in lead:
        out.append(glyph(kind, cursor + slot / 2, cy, TXT2))
        cursor += slot
    pill_x = cursor
    out.append(rect(pill_x, y + (h - 40) / 2, pill_w, 40, 20, ACCENT, opacity=0.16))
    out.append(glyph(selected, pill_x + 22, cy, ACCENT))
    out.append(text(pill_x + 40, cy + 5, label, 12.5, ACCENT, "600"))
    cursor += pill_w
    for kind, _ in trail:
        out.append(glyph(kind, cursor + slot / 2, cy, TXT2))
        cursor += slot
    return "\n  ".join(out)


def chip_row(x, y, items, size=12.5, h=32, on_black=False):
    """FilterChip row; items = [(label, selected), ...]. Returns (markup, end_x)."""
    out, cursor = [], x
    for label, on in items:
        w = 32 + text_width(label, size)
        if on:
            out.append(rect(cursor, y, w, h, h / 2, ACCENT, opacity=0.18, stroke=ACCENT, stroke_opacity=0.5))
        else:
            out.append(rect(cursor, y, w, h, h / 2, "none", stroke=BORDER_STRONG if on_black else BORDER))
        out.append(text(cursor + w / 2, y + h / 2 + 4.5, label, size, ACCENT if on else TXT2,
                        "600" if on else None, "middle"))
        cursor += w + 8
    return "\n  ".join(out), cursor


def brand_mark(x, y, scale, fg=ACCENT, bg=BG) -> str:
    """A display with a phone-shaped opening (design-system §11), 24-unit grid."""
    return (
        f'<g transform="translate({x},{y}) scale({scale}) translate(-12,-12)">'
        f'<rect x="1.5" y="3" width="21" height="15" rx="3" fill="{fg}"/>'
        f'<rect x="9.5" y="6" width="5" height="9" rx="1.5" fill="{bg}"/>'
        f'<rect x="10.5" y="17.5" width="3" height="3" fill="{fg}"/>'
        f'<rect x="7" y="20" width="10" height="2.5" rx="1.25" fill="{fg}"/></g>'
    )


def monitor_glyph(cx, cy, color) -> str:
    return (
        f'<g transform="translate({cx},{cy})">'
        f'<rect x="-14" y="-11" width="28" height="19" rx="3" fill="none" stroke="{color}" stroke-width="1.8"/>'
        f'<path d="M-5 12h10" stroke="{color}" stroke-width="1.8" stroke-linecap="round"/></g>'
    )


def qr(x, y, size, payload: str, modules: int = 25) -> str:
    """A QR-shaped block: real finder/timing/alignment structure, hashed payload fill."""
    n = modules
    dark = [[False] * n for _ in range(n)]

    def finder(r, c):
        for dr in range(7):
            for dc in range(7):
                dark[r + dr][c + dc] = dr in (0, 6) or dc in (0, 6) or (2 <= dr <= 4 and 2 <= dc <= 4)

    finder(0, 0)
    finder(0, n - 7)
    finder(n - 7, 0)
    for i in range(8, n - 8):
        dark[6][i] = dark[i][6] = i % 2 == 0
    for dr in range(5):
        for dc in range(5):
            dark[n - 9 + dr][n - 9 + dc] = dr in (0, 4) or dc in (0, 4) or (dr == 2 and dc == 2)

    reserved = {(6, i) for i in range(n)} | {(i, 6) for i in range(n)}
    for br, bc in ((0, 0), (0, n - 7), (n - 7, 0)):
        reserved |= {(br + dr, bc + dc) for dr in range(-1, 8) for dc in range(-1, 8)}
    reserved |= {(n - 9 + dr, n - 9 + dc) for dr in range(-1, 6) for dc in range(-1, 6)}

    seed = hashlib.sha256(payload.encode()).digest() * 40
    k = 0
    for r in range(n):
        for c in range(n):
            if (r, c) in reserved:
                continue
            dark[r][c] = bool(seed[k % len(seed)] & (1 << (k % 8)) and seed[(k * 7 + 3) % len(seed)] & 1)
            k += 1

    m = size / (n + 4)  # 2-module quiet zone each side
    out = [rect(x, y, size, size, 6, "#FFFFFF")]
    for r in range(n):
        for c in range(n):
            if dark[r][c]:
                out.append(f'<rect x="{x + (c + 2) * m:.1f}" y="{y + (r + 2) * m:.1f}" '
                           f'width="{m:.1f}" height="{m:.1f}" fill="#18181B"/>')
    return "\n    ".join(out)


# --- screens ----------------------------------------------------------------------

PC_NAME = "Living-Room PC"
PC_ADDR = "192.168.1.24:8080"


def android_pair() -> tuple[str, int, int]:
    """PairScreen: remembered PC, LAN discovery, QR/typed fallbacks."""
    b = [phone_frame()]
    b += [
        brand_mark(190, 132, 2.6),
        text(190, 216, "Portal Remote", 24, TXT, "600", "middle"),
        text(190, 244, "Pick the PC you want to drive", 13.5, TXT2, anchor="middle"),
        text(26, 290, "LAST USED", 11.5, TXT2),
        rect(24, 302, 332, 86, 14, SURFACE, stroke=ACCENT, stroke_opacity=0.45),
        monitor_glyph(60, 345, ACCENT),
        text(94, 336, PC_NAME, 15.5, TXT, "600"),
        text(94, 358, f"{PC_ADDR} · paired", 12.5, TXT2),
        rect(266, 326, 76, 36, 18, ACCENT),
        text(304, 349, "Connect", 13, BG, "600", "middle"),
        text(26, 424, "PCS ON THIS NETWORK", 11.5, TXT2),
        f'<circle cx="330" cy="420" r="8" fill="none" stroke="{ACCENT}" stroke-width="2" stroke-dasharray="30 12"/>',
    ]
    for i, (name, addr) in enumerate([("STUDIO-DESK", "192.168.1.31:8080"), ("HTPC-LOUNGE", "192.168.1.55:8080")]):
        y = 436 + i * 84
        b += [
            rect(24, y, 332, 72, 14, SURFACE, stroke=BORDER),
            monitor_glyph(60, y + 36, TXT2),
            text(94, y + 32, name, 14.5),
            text(94, y + 52, f"{addr} · v1.4.0", 12, TXT2),
        ]
    b += [
        text(190, 628, "Tapping a PC asks it to allow this phone", 12, TXT3, anchor="middle"),
        text(118, 690, "Scan QR code", 14, ACCENT, "600", "middle"),
        text(262, 690, "Type address", 14, ACCENT, "600", "middle"),
        text(190, 730, "Same Wi-Fi as the PC · nothing leaves the network", 11.5, TXT3, anchor="middle"),
    ]
    return "  " + "\n  ".join(b), PHONE_W, PHONE_H


def android_control() -> tuple[str, int, int]:
    """Control tab, trackpad mode: pad, scroll rail, gesture echo, click buttons."""
    b = [phone_frame(), title_row(PC_NAME), mode_tabs("Trackpad")]
    b += [
        # §5's face-and-edge: the pad is a control, so `surface-muted` under a
        # `border-strong` outline rather than a card fill under a hairline.
        rect(24, 124, 332, 470, 16, MUTED, stroke=BORDER_STRONG),
        # scroll rail — drawn, not interactive; the pad's own handler owns it
        rect(312, 124, 44, 470, fill=ACCENT, opacity=0.05),
        f'<path d="M312 124v470" stroke="{BORDER}"/>',
        f'<g transform="translate(334,359)" stroke="{TXT2}" stroke-opacity="0.75" stroke-width="1.6" fill="none" '
        'stroke-linecap="round" stroke-linejoin="round">'
        '<path d="M-5 -14l5-5 5 5"/><path d="M-5 14l5 5 5-5"/>'
        '<path d="M0 -6v12" stroke-opacity="0.35"/></g>',
        # gesture echo: a resolved two-finger back swipe
        f'<circle cx="168" cy="300" r="46" fill="{ACCENT}" fill-opacity="0.10"/>',
        f'<g transform="translate(168,292)" stroke="{TXT}" stroke-width="2.2" fill="none" '
        'stroke-linecap="round" stroke-linejoin="round">'
        '<path d="M8 -10l-10 10 10 10"/><path d="M-8 0h16" stroke-opacity="0.55"/></g>',
        text(168, 336, "Back", 14, TXT, "600", "middle"),
        text(168, 430, "Tap to click · drag to move", 12.5, TXT2, anchor="middle"),
        text(168, 452, "Two fingers to scroll or go back", 12.5, TXT2, anchor="middle"),
        text(168, 474, "Three fingers for desktops and task view", 12.5, TXT2, anchor="middle"),
        text(168, 510, "Right edge scrolls · hold to drag", 11.5, TXT3, anchor="middle"),
        # The click buttons are `secondaryContainer`, not a card: a filled control
        # needs no outline, the fill is the contrast (§5).
        rect(24, 610, 196, 56, 12, SECONDARY_CONTAINER),
        text(122, 644, "Left click", 14, anchor="middle"),
        rect(230, 610, 126, 56, 12, SECONDARY_CONTAINER),
        text(293, 644, "Right", 14, anchor="middle"),
        nav_bar("control"),
    ]
    return "  " + "\n  ".join(b), PHONE_W, PHONE_H


def android_screen() -> tuple[str, int, int]:
    """Screen tab: the mirror, letterboxed on flat black, with its chip row."""
    desk = [
        rect(12, 180, 356, 149, fill="#12203A"),
        rect(34, 196, 228, 112, 5, SURFACE, stroke="#2C3448"),
        rect(34, 196, 228, 16, 5, RAISED),
        '<circle cx="44" cy="204" r="2.6" fill="#F87171"/>'
        '<circle cx="53" cy="204" r="2.6" fill="#FBBF24"/>'
        '<circle cx="62" cy="204" r="2.6" fill="#4ADE80"/>',
        '<g fill="#8FA3C8" fill-opacity="0.75">'
        '<rect x="44" y="222" width="86" height="4" rx="2"/>'
        '<rect x="44" y="234" width="140" height="4" rx="2" fill="#60A5FA"/>'
        '<rect x="52" y="246" width="112" height="4" rx="2"/>'
        '<rect x="52" y="258" width="150" height="4" rx="2"/>'
        '<rect x="44" y="270" width="68" height="4" rx="2"/>'
        '<rect x="44" y="282" width="120" height="4" rx="2" fill-opacity="0.4"/></g>',
        rect(272, 212, 80, 70, 5, "#1B2438", stroke="#2C3448"),
        '<rect x="280" y="224" width="64" height="4" rx="2" fill="#8FA3C8" fill-opacity="0.6"/>'
        '<rect x="280" y="236" width="46" height="4" rx="2" fill="#8FA3C8" fill-opacity="0.4"/>',
        '<path d="M300 250l0 22 5.5-5.5 3.5 7.5 3-1.5-3.5-7.5 7.5-0.5z" fill="#FFFFFF"/>',
        rect(12, 310, 356, 19, fill="#0B1220", opacity=0.9),
        '<g fill="#8FA3C8" fill-opacity="0.8">'
        '<rect x="168" y="316" width="8" height="8" rx="1.5"/>'
        '<rect x="182" y="316" width="8" height="8" rx="1.5"/>'
        '<rect x="196" y="316" width="8" height="8" rx="1.5"/></g>',
        text(352, 324, "21:07", 8, "#8FA3C8", anchor="end"),
    ]
    monitors, _ = chip_row(30, 558, [("1", True), ("2", False), ("All", False)], on_black=True)
    quality, _ = chip_row(30, 622, [("Smooth", True), ("Sharp", False), ("Max", False), ("Full screen", False)],
                          on_black=True)
    b = [phone_frame(), title_row(PC_NAME)]
    b += [
        # flat black behind everything: it's the letterbox around a picture, not a card
        rect(12, 72, 356, 716, fill="#000000"),
        '<g transform="translate(0,176)">' + "\n    ".join(desk) + "</g>",
        # overlay controls at 35% black, per the mirror's full-screen chrome
        rect(26, 86, 96, 26, 13, "#000000", opacity=0.35),
        text(74, 103, "14.5 fps", 12, anchor="middle"),
        rect(286, 86, 66, 26, 13, "#000000", opacity=0.35),
        text(319, 103, "Type", 12, anchor="middle"),
        text(30, 548, "MONITOR", 11.5, TXT2),
        monitors,
        text(30, 612, "QUALITY", 11.5, TXT2),
        quality,
        nav_bar("screen"),
    ]
    return "  " + "\n  ".join(b), PHONE_W, PHONE_H


def android_media() -> tuple[str, int, int]:
    """Control tab, media mode: now playing, transport, volume, cast targets."""
    cast, _ = chip_row(24, 462, [("This PC", True), ("Living Room TV", False), ("Roku · ECP", False)], h=34)
    b = [
        '<defs><linearGradient id="art" x1="0" y1="0" x2="1" y2="1">'
        '<stop offset="0" stop-color="#3B82F6"/><stop offset="0.55" stop-color="#7C3AED"/>'
        '<stop offset="1" stop-color="#F87171"/></linearGradient></defs>',
        phone_frame(),
        title_row(PC_NAME),
        mode_tabs("Media"),
        rect(24, 124, 332, 150, 14, SURFACE, stroke=BORDER),
        rect(38, 138, 122, 122, 10, "url(#art)"),
        f'<circle cx="99" cy="199" r="22" fill="{BG}" fill-opacity="0.35"/>',
        f'<circle cx="99" cy="199" r="6" fill="{BG}" fill-opacity="0.6"/>',
        text(176, 164, "Midnight City", 16, TXT, "600"),
        text(176, 186, "M83 — Hurry Up, We're Dreaming", 13, TXT2),
        text(176, 208, "Spotify", 11.5, TXT3),
        rect(176, 224, 164, 4, 2, BORDER),
        rect(176, 224, 96, 4, 2, ACCENT),
        f'<circle cx="272" cy="226" r="6" fill="{ACCENT}"/>',
        text(176, 250, "2:31", 11, TXT3),
        text(340, 250, "4:03", 11, TXT3, anchor="end"),
        # transport: prev · back 10s · play/pause · forward 30s · next
        f'<g fill="{TXT}"><path transform="translate(84,326)" d="M6 -12L-6 0 6 12z"/>'
        '<rect transform="translate(84,326)" x="-9" y="-12" width="3" height="24" rx="1.5"/></g>',
        f'<path transform="translate(148,326)" d="M0 -12L-14 0 0 12z" fill="{TXT2}"/>',
        f'<circle cx="190" cy="326" r="30" fill="{ACCENT}"/>',
        f'<g transform="translate(190,326)" fill="{BG}">'
        '<rect x="-8" y="-11" width="6" height="22" rx="2"/>'
        '<rect x="2" y="-11" width="6" height="22" rx="2"/></g>',
        f'<path transform="translate(232,326)" d="M0 -12L14 0 0 12z" fill="{TXT2}"/>',
        f'<g fill="{TXT}"><path transform="translate(296,326)" d="M-6 -12L6 0-6 12z"/>'
        '<rect transform="translate(296,326)" x="6" y="-12" width="3" height="24" rx="1.5"/></g>',
        f'<g transform="translate(40,398)" fill="{TXT2}"><path d="M0 -5h4l6-6v22l-6-6H0z"/>'
        f'<path d="M14 -5a8 8 0 0 1 0 16" fill="none" stroke="{TXT2}" stroke-width="1.6"/></g>',
        rect(80, 401, 240, 4, 2, BORDER),
        rect(80, 401, 150, 4, 2, ACCENT),
        f'<circle cx="230" cy="403" r="7" fill="{ACCENT}"/>',
        text(340, 408, "62", 12, TXT2, anchor="end"),
        text(24, 452, "CAST TO", 11.5, TXT2),
        cast,
        rect(24, 512, 332, 52, 12, SURFACE, stroke=BORDER),
        text(40, 543, "Paste a video link", 13, TXT3),
        rect(268, 522, 78, 32, 16, ACCENT),
        text(307, 543, "Cast link", 12.5, BG, "600", "middle"),
        text(24, 586, "Cast a file from this phone", 12, TXT2),
        nav_bar("control"),
    ]
    return "  " + "\n  ".join(b), PHONE_W, PHONE_H


def android_share() -> tuple[str, int, int]:
    """Share tab: the two-device thread, clipboard suggestion, composer."""
    views, _ = chip_row(24, 72, [("Chat", True), ("Library", False)])
    b = [phone_frame(), title_row(PC_NAME), views]
    b += [
        text(190, 134, "Today", 11, TXT3, anchor="middle"),
        # incoming (PC, left) — squared corner points at its own side
        f'<path d="M24 152h216a12 12 0 0 1 12 12v40a12 12 0 0 1-12 12H24z" fill="{MUTED}"/>',
        text(40, 178, "github.com/tanvoid0/portal-remote", 13),
        text(40, 198, "From the PC · copied to clipboard", 11, TXT2),
        # outgoing (this phone, right)
        f'<path d="M356 232H160a12 12 0 0 0-12 12v56a12 12 0 0 0 12 12h196z" fill="{PRIMARY_CONTAINER}"/>',
        rect(164, 244, 44, 44, 8, ACCENT, opacity=0.35, stroke=ACCENT, stroke_opacity=0.4),
        f'<path d="M170 280l10-12 8 9 5-5 9 8z" fill="{ACCENT}"/><circle cx="177" cy="256" r="4" fill="{ACCENT}"/>',
        text(218, 262, "screenshot.png", 13),
        text(218, 281, "1.2 MB · saved to Inbox", 11, TXT2),
        text(344, 300, "21:04", 10, TXT3, anchor="end"),
        f'<path d="M24 328h200a12 12 0 0 1 12 12v52a12 12 0 0 1-12 12H24z" fill="{MUTED}"/>',
        f'<g transform="translate(52,364)" fill="none" stroke="{TXT2}" stroke-width="1.6" stroke-linejoin="round">'
        '<path d="M-9 -12h12l6 6v18h-18z"/><path d="M3 -12v6h6"/></g>',
        text(76, 360, "invoice-aug.pdf", 13),
        text(76, 380, "312 KB · tap to open", 11, TXT2),
        # queued, not failed — it re-sends on the next reconnect
        f'<path d="M356 424H190a12 12 0 0 0-12 12v44a12 12 0 0 0 12 12h166z" fill="{PRIMARY_CONTAINER}"/>',
        text(194, 452, "Notes for the demo", 13),
        text(194, 472, "Waiting for your PC · tap to retry", 11, TXT2),
        rect(24, 576, 332, 52, 12, SURFACE, stroke=BORDER).replace("/>", ' stroke-dasharray="4 4"/>'),
        f'<g transform="translate(48,602)" fill="none" stroke="{ACCENT}" stroke-width="1.6" stroke-linejoin="round">'
        '<rect x="-7" y="-9" width="14" height="18" rx="2"/><path d="M-3 -9v-3h6v3"/></g>',
        text(70, 598, "Send what's on the clipboard", 12.5),
        text(70, 616, "https://youtu.be/dQw4w9WgXcQ", 11, TXT3),
        # composer sits on an opaque surface — a field behind glass can't be hit reliably
        rect(12, 640, 356, 148, fill=SURFACE),
        f'<path d="M12 640h356" stroke="{BORDER}"/>',
        rect(24, 656, 332, 48, 24, RAISED, stroke=BORDER),
        f'<g transform="translate(50,680)" stroke="{TXT2}" stroke-width="1.6" fill="none" stroke-linecap="round">'
        '<path d="M4 -6l-8 8a3.5 3.5 0 0 0 5 5l8-8a5.5 5.5 0 0 0-8-8l-8 8"/></g>',
        text(76, 685, "Send to your PC", 13, TXT3),
        f'<circle cx="330" cy="680" r="18" fill="{ACCENT}"/>',
        f'<path transform="translate(330,680)" d="M-6 -6l12 6-12 6 2-6z" fill="{BG}"/>',
        nav_bar("share", y=718, frosted=True),
    ]
    return "  " + "\n  ".join(b), PHONE_W, PHONE_H


def desktop_window() -> tuple[str, int, int]:
    """Tray/MainForm.cs: pairing on the left, this PC on the right, tray balloon."""
    w, h = 1000, 780
    b = [
        '<defs><linearGradient id="wall" x1="0" y1="0" x2="1" y2="1">'
        '<stop offset="0" stop-color="#101A2E"/><stop offset="0.5" stop-color="#0B1220"/>'
        '<stop offset="1" stop-color="#1A1030"/></linearGradient></defs>',
        rect(0, 0, w, h, fill="url(#wall)"),
        # The window paints itself `bg`; the QR and status cards on it are `surface`
        # (§12). It used to be `surface` too, which in light mode is a white card on
        # a white window.
        rect(52, 34, 896, 580, 12, BG, stroke=BORDER),
        rect(52, 34, 896, 44, 12, SURFACE),
        rect(52, 66, 896, 12, fill=SURFACE),
        brand_mark(82, 56, 1.1, ACCENT, SURFACE),
        text(104, 61, "Portal Remote", 13.5, TXT, "600"),
        f'<g stroke="{TXT2}" stroke-width="1.3" fill="none"><path d="M872 56h12"/>'
        '<rect x="900" y="50" width="12" height="12" rx="1.5"/><path d="M928 50l12 12M940 50l-12 12"/></g>',
        # left: pairing
        text(88, 124, "Scan to pair a phone", 17, TXT, "600"),
        text(88, 148, "Open Portal Remote on the phone and point it here.", 12.5, TXT2),
        rect(88, 168, 300, 300, 12, SURFACE, stroke=BORDER),
        qr(100, 180, 276, f"http://{PC_ADDR}/pair"),
        text(88, 500, f"http://{PC_ADDR}", 14, TXT, family=MONO),
        text(88, 524, "Phone and PC must be on the same Wi-Fi, and this app", 11.5, TXT2),
        text(88, 542, "allowed through the firewall on Private networks.", 11.5, TXT2),
        rect(88, 558, 140, 34, 8, ACCENT),
        text(158, 580, "Copy address", 12.5, BG, "600", "middle"),
        # right: this PC
        text(492, 124, "This PC", 17, TXT, "600"),
        rect(492, 146, 420, 84, 12, SURFACE, stroke=BORDER),
        f'<circle cx="518" cy="180" r="6" fill="{SUCCESS}"/>',
        text(536, 178, "Phone connected", 14.5, TXT, "600"),
        text(536, 200, "192.168.1.77 · control socket open", 12, TXT2),
        text(492, 270, "PORT", 11.5, TXT2),
        text(492, 296, "8080", 14, TXT, family=MONO),
        text(492, 316, "Takes effect the next time the server starts.", 11, TXT3),
        rect(822, 274, 90, 34, 8, MUTED, stroke=BORDER_STRONG),
        text(867, 296, "Change", 12.5, anchor="middle"),
        f'<path d="M492 344h420" stroke="{BORDER}"/>',
        text(492, 378, "SHARED FOLDER", 11.5, TXT2),
        text(492, 404, r"C:\Users\tanvo\Portal", 13, TXT, family=MONO),
        text(492, 424, r"Anything the phone sends lands in Inbox\.", 11, TXT3),
        rect(724, 382, 86, 34, 8, MUTED, stroke=BORDER_STRONG),
        text(767, 404, "Change", 12.5, anchor="middle"),
        rect(822, 382, 90, 34, 8, MUTED, stroke=BORDER_STRONG),
        text(867, 404, "Open", 12.5, anchor="middle"),
        f'<path d="M492 452h420" stroke="{BORDER}"/>',
        rect(492, 476, 18, 18, 4, ACCENT),
        f'<path d="M496 485l3.5 3.5 6-6.5" stroke="{BG}" stroke-width="2" fill="none" '
        'stroke-linecap="round" stroke-linejoin="round"/>',
        text(522, 490, "Start Portal Remote when I sign in", 13),
        text(492, 540, "Rotating the token unpairs every phone; scan the new code afterwards.", 12, TXT2),
        rect(492, 550, 176, 34, 8, MUTED, stroke=BORDER_STRONG),
        text(580, 572, "Rotate pairing token", 12.5, anchor="middle"),
        # tray balloon: reveals a shared file, never opens it
        rect(640, 622, 308, 80, 10, RAISED, stroke=BORDER_STRONG),
        rect(656, 640, 26, 26, 6, ACCENT),
        brand_mark(669, 653, 0.85, BG, ACCENT),
        text(696, 646, "Shared from your phone", 12.5, TXT, "600"),
        text(696, 666, "screenshot.png — on the clipboard, and in", 11.5, TXT2),
        text(696, 683, r"Portal\Inbox. Click to show it in Explorer.", 11.5, TXT2),
        # taskbar
        rect(0, 728, w, 52, fill="#0B1018", opacity=0.92),
        '<g fill="#8FA3C8" fill-opacity="0.8">'
        '<rect x="432" y="746" width="16" height="16" rx="3"/>'
        '<rect x="462" y="746" width="16" height="16" rx="3"/>'
        '<rect x="492" y="746" width="16" height="16" rx="3"/></g>',
        rect(820, 742, 24, 24, 6, ACCENT),
        brand_mark(832, 754, 0.78, BG, ACCENT),
        '<g fill="#8FA3C8" fill-opacity="0.7">'
        '<rect x="856" y="748" width="14" height="12" rx="2"/>'
        '<rect x="880" y="748" width="14" height="12" rx="2"/></g>',
        text(964, 751, "21:07", 11, "#E4E4E7", anchor="end"),
        text(964, 766, "13/08/2026", 10, TXT2, anchor="end"),
    ]
    return "  " + "\n  ".join(b), w, h


SCREENS = {
    "desktop-window": (desktop_window, FONT_DESKTOP),
    "android-pair": (android_pair, FONT),
    "android-control": (android_control, FONT),
    "android-screen": (android_screen, FONT),
    "android-media": (android_media, FONT),
    "android-share": (android_share, FONT),
}


# --- driver -----------------------------------------------------------------------


def render(name: str) -> str:
    fn, font = SCREENS[name]
    body, w, h = fn()
    return svg(body, w, h, font)


BROWSERS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "google-chrome",
    "chromium",
    "chromium-browser",
    "msedge",
]


def find_browser() -> str | None:
    """Any Chromium will do — it's only used as an SVG rasteriser."""
    for cand in [os.environ["BROWSER"]] if os.environ.get("BROWSER") else BROWSERS:
        if Path(cand).exists() or shutil.which(cand):
            return cand
    return None


def write_png(name: str, browser: str, out_dir: Path) -> Path:
    """Rasterise <name>.svg headlessly. PNGs are a convenience export, not committed
    output — nothing in the repo references them, and --check ignores them."""
    _, w, h = SCREENS[name][0]()
    out_dir.mkdir(parents=True, exist_ok=True)
    png = (out_dir / f"{name}.png").resolve()  # a relative --screenshot path is denied on Windows
    subprocess.run(
        [
            browser,
            "--headless=new",
            "--disable-gpu",
            "--hide-scrollbars",
            "--default-background-color=00000000",  # keep the phone's rounded corners transparent
            f"--window-size={w},{h}",
            f"--screenshot={png}",
            (OUT / f"{name}.svg").as_uri(),
        ],
        check=True,
        capture_output=True,
    )
    return png


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="+", metavar="NAME", help="render just these screens")
    ap.add_argument("--list", action="store_true", help="list known screen names")
    ap.add_argument("--check", action="store_true", help="don't write; exit 1 if any file is stale")
    ap.add_argument("--png", nargs="?", const="png", metavar="DIR",
                    help="also rasterise to PNG in docs/assets/DIR (default: png/) — "
                         "needs Chrome/Edge/Chromium, or set BROWSER")
    args = ap.parse_args(argv)

    if args.list:
        print("\n".join(SCREENS))
        return 0

    names = args.only or list(SCREENS)
    unknown = [n for n in names if n not in SCREENS]
    if unknown:
        print(f"unknown screen(s): {', '.join(unknown)}\nknown: {', '.join(SCREENS)}", file=sys.stderr)
        return 2

    stale = []
    for name in names:
        path = OUT / f"{name}.svg"
        new = render(name)
        old = path.read_text(encoding="utf-8") if path.exists() else None
        if old == new:
            print(f"  unchanged  {path.name}")
            continue
        stale.append(path.name)
        if args.check:
            print(f"  STALE      {path.name}")
            continue
        path.write_text(new, encoding="utf-8")
        print(f"  {'updated' if old else 'added':<10} {path.name}")

    if args.check and stale:
        print(f"\n{len(stale)} file(s) out of date — run: python {Path(__file__).name}", file=sys.stderr)
        return 1

    if args.png and not args.check:
        browser = find_browser()
        if not browser:
            print("no Chrome/Edge/Chromium found — set BROWSER=/path/to/chrome", file=sys.stderr)
            return 3
        for name in names:
            print(f"  png        {write_png(name, browser, OUT / args.png).name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
