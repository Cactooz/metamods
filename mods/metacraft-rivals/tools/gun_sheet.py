#!/usr/bin/env python3
"""Render orthographic views of the paint gun model as an SVG design sheet.

Reads a vanilla/Blockbench "Java Block/Item" model JSON (the elements list) and draws
front (XY), side (ZY) and top (XZ) projections, one rectangle per box, coloured by the
palette pixel each element's faces sample. Run it after editing the model so the sheet in
docs/ never drifts from the JSON:

    python3 mods/metacraft-rivals/tools/gun_sheet.py \
        mods/metacraft-rivals/src/main/resources/assets/metacraft-rivals/models/item/paint_gun.json \
        docs/superpowers/specs/2026-09-11-paint-gun-design-sheet.svg

Only the standard library is used.
"""
import json
import sys

# Palette pixel column -> fill colour. Mirrors paint_gun_palette.png (8x8, row 0).
PALETTE = {
    0: "#3A3A3A",  # dark grey: barrel, grip, tank cap
    1: "#1E1E1E",  # black: muzzle ring, trigger guard
    2: "#C8C8C8",  # light grey: body
    3: "#FF8A00",  # orange: nozzle tip, indicator
    4: "#F2F2F2",  # white: tank (tinted by the team colour in game)
    5: "#8A8A8A",  # mid grey: spare
}

SCALE = 12  # px per model unit
PAD = 24


def palette_column(element):
    """The palette column an element samples: uv x of its first face divided by 2."""
    face = next(iter(element["faces"].values()))
    return int(face["uv"][0] // 2)


def view(elements, axes, title, ox, oy, flip_x=False):
    """One orthographic view. axes = (horizontal axis index, vertical axis index)."""
    ax, ay = axes
    out = [f'<text x="{ox}" y="{oy - 8}" font-family="monospace" font-size="12">{title}</text>']
    # Draw far-to-near so nearer boxes overlap: sort by the remaining axis.
    depth = ({0, 1, 2} - {ax, ay}).pop()
    ordered = sorted(elements, key=lambda e: e["from"][depth])
    for e in ordered:
        x0, x1 = e["from"][ax], e["to"][ax]
        y0, y1 = e["from"][ay], e["to"][ay]
        if flip_x:
            x0, x1 = -x1, -x0
        colour = PALETTE[palette_column(e)]
        tinted = any(f.get("tintindex") == 0 for f in e["faces"].values())
        stroke = "#E83D84" if tinted else "#222"
        out.append(
            f'<rect x="{ox + (x0 + 8) * SCALE:.1f}" y="{oy + (24 - y1) * SCALE:.1f}" '
            f'width="{(x1 - x0) * SCALE:.1f}" height="{(y1 - y0) * SCALE:.1f}" '
            f'fill="{colour}" stroke="{stroke}" stroke-width="{2 if tinted else 1}"/>'
        )
    return out


def main(model_path, svg_path):
    with open(model_path) as f:
        elements = json.load(f)["elements"]
    w = 3 * (32 * SCALE + PAD) + PAD
    h = 34 * SCALE + PAD * 3
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}" font-family="monospace">',
        f'<rect width="{w}" height="{h}" fill="#FAFAFA"/>',
        f'<text x="{PAD}" y="{PAD}" font-size="14">Paint gun: orthographic views, '
        f'1 unit = 1/16 block, pink outline = tank (dye-tinted)</text>',
    ]
    col = 32 * SCALE + PAD
    # Sword frame: +Y is the muzzle, -X the tank side ("top" in hand), +X the grip, Z the width.
    parts += view(elements, (0, 1), "profile (X right: tank left, grip right; Y up = muzzle)", PAD, PAD * 2)
    parts += view(elements, (2, 1), "edge-on (Z right = width; Y up = muzzle)", PAD + col, PAD * 2)
    parts += view(elements, (0, 2), "down the barrel (X right: tank left; Z down = width)", PAD + 2 * col, PAD * 2)
    parts.append("</svg>")
    with open(svg_path, "w") as f:
        f.write("\n".join(parts))
    print(f"wrote {svg_path} ({len(elements)} elements)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
