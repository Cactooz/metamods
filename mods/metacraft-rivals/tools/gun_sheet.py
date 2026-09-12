#!/usr/bin/env python3
"""Render orthographic views of the paint gun model as an SVG design sheet.

Reads a vanilla/Blockbench "Java Block/Item" model JSON (the elements list) and draws
front (XY), side (ZY) and top (XZ) projections, one rectangle per box, coloured by the
palette pixel each element's faces sample. Colours are read straight from the palette PNG
referenced by the model's `textures["0"]` entry, so the sheet stays correct however many
columns wide that palette is. Run it after editing the model so the sheet in docs/ never
drifts from the JSON:

    python3 mods/metacraft-rivals/tools/gun_sheet.py \
        mods/metacraft-rivals/src/main/resources/assets/metacraft-rivals/models/item/paint_gun.json \
        docs/superpowers/specs/2026-09-11-paint-gun-design-sheet.svg

Requires Pillow (PIL) to read the palette PNG.
"""
import json
import sys
from pathlib import Path

from PIL import Image

SCALE = 12  # px per model unit
PAD = 24


def palette_path(model_path, texture_ref):
    """Resolve a `namespace:item/name` texture reference to its PNG on disk.

    `<resources>` is the model file's `assets/..` ancestor, i.e. the directory that
    contains the `assets/<namespace>/...` tree.
    """
    namespace, _, name = texture_ref.partition(":")
    resources = Path(model_path).resolve()
    for parent in resources.parents:
        if parent.name == "assets":
            resources = parent.parent
            break
    else:
        raise ValueError(f"could not find an 'assets' ancestor of {model_path}")
    return resources / "assets" / namespace / "textures" / f"{name}.png"


def load_palette(model_path, textures):
    image = Image.open(palette_path(model_path, textures["0"])).convert("RGB")
    return image


def palette_colour(palette, element):
    """The RGB colour an element samples: its first face's uv x, scaled to the palette width."""
    face = next(iter(element["faces"].values()))
    column = int(face["uv"][0] * palette.width / 16)
    column = max(0, min(palette.width - 1, column))
    return palette.getpixel((column, 0))


def view(elements, palette, axes, title, ox, oy, flip_x=False):
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
        colour = "#{:02X}{:02X}{:02X}".format(*palette_colour(palette, e))
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
        model = json.load(f)
    elements = model["elements"]
    palette = load_palette(model_path, model["textures"])
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
    # v2 (obj2mc) frame: Y is up, the barrel runs along -Z, so -Z is the muzzle and +Z the back.
    parts += view(elements, palette, (0, 1), "front (X right, Y up; looking down the barrel)", PAD, PAD * 2)
    parts += view(elements, palette, (2, 1), "side (Z right = back of gun, Y up; muzzle at -Z)", PAD + col, PAD * 2)
    parts += view(elements, palette, (0, 2), "top (X right, Z down = back of gun)", PAD + 2 * col, PAD * 2)
    parts.append("</svg>")
    with open(svg_path, "w") as f:
        f.write("\n".join(parts))
    print(f"wrote {svg_path} ({len(elements)} elements)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
