#!/usr/bin/env python3
"""Convert a low-poly OBJ (Kenney Blaster Kit) into a Minecraft item element model.

Surface-voxelises the mesh, colours every voxel from the OBJ's colormap texture, greedy-merges
equal-colour voxels into boxes, writes each colour as one texel of a palette PNG, and emits the
element JSON. The accent family (orange in blaster-b) becomes a white, dye-tinted tank.

    python3 mods/metacraft-rivals/tools/obj2mc.py \
        mods/metacraft-rivals/tools/kenney/blaster-b.obj \
        mods/metacraft-rivals/src/main/resources/assets/metacraft-rivals/models/item/paint_gun.json \
        mods/metacraft-rivals/src/main/resources/assets/metacraft-rivals/textures/item/paint_gun_palette.png \
        [--res 40] [--length 14] [--flip] [--max-elements 400]

Frame of the output: Y up, the mesh's longest axis along Z, muzzle toward -Z (use --flip if the
in-game screenshot shows it backwards), centred on (8, 8, 8). Standard library plus Pillow.
"""
import argparse
import json
import math
import os
import sys
from collections import defaultdict

from PIL import Image

ACCENT = [(255, 126, 68), (207, 83, 79)]  # blaster-b's orange body and its shade: the tank
ACCENT_TOLERANCE = 40

_INLINE = {}  # placeholder token -> the one-line JSON it stands for
_TOKENS = {}  # that one-line JSON -> its token, so equal values share a token


def one_line(value):
	"""Stand for a value that json.dump should print on one line instead of one number a line.

	A few hundred boxes with six faces each is a quarter of a megabyte when every list is
	exploded; corners and faces are short and belong on a single line, as in a hand-written model.
	"""
	literal = json.dumps(value)
	if literal not in _TOKENS:
		token = f"@@inline{len(_TOKENS)}@@"
		_TOKENS[literal] = token
		_INLINE[token] = literal
	return _TOKENS[literal]


def write_model(model, path):
	text = json.dumps(model, indent="\t")
	for token, literal in _INLINE.items():
		text = text.replace(json.dumps(token), literal)
	with open(path, "w") as f:
		f.write(text + "\n")


def load_obj(path):
	verts, uvs, tris = [], [], []
	for line in open(path):
		parts = line.split()
		if not parts:
			continue
		if parts[0] == "v":
			verts.append(tuple(float(p) for p in parts[1:4]))
		elif parts[0] == "vt":
			uvs.append(tuple(float(p) for p in parts[1:3]))
		elif parts[0] == "f":
			corners = []
			for token in parts[1:]:
				fields = token.split("/")
				vi = int(fields[0]) - 1
				ti = int(fields[1]) - 1 if len(fields) > 1 and fields[1] else None
				corners.append((vi, ti))
			for i in range(1, len(corners) - 1):
				tris.append((corners[0], corners[i], corners[i + 1]))
	return verts, uvs, tris


def colormap_path(obj_path):
	"""The texture the .mtl names, falling back to a colormap.png beside the .obj.

	Kenney's kits name the map relative to the kit root ("Textures/colormap.png") while this
	vendored copy keeps .obj, .mtl and .png in one directory, so the bare name is tried too.
	"""
	folder = os.path.dirname(obj_path)
	mtl = obj_path[:-4] + ".mtl"
	candidates = []
	if os.path.exists(mtl):
		for line in open(mtl):
			if line.startswith("map_Kd"):
				name = line.split(None, 1)[1].strip()
				candidates.append(os.path.join(folder, name))
				candidates.append(os.path.join(folder, os.path.basename(name)))
	candidates.append(os.path.join(folder, "colormap.png"))
	for candidate in candidates:
		if os.path.exists(candidate):
			return candidate
	sys.exit(f"no colormap texture next to {obj_path} (tried {candidates})")


def near(a, b, tol):
	return all(abs(x - y) <= tol for x, y in zip(a, b))


def voxelise(verts, uvs, tris, tex, res):
	xs, ys, zs = zip(*verts)
	mins = (min(xs), min(ys), min(zs))
	extent = (max(xs) - mins[0], max(ys) - mins[1], max(zs) - mins[2])
	longest = max(extent)
	size = longest / res
	grid = {}
	for (a, b, c) in tris:
		pa, pb, pc = verts[a[0]], verts[b[0]], verts[c[0]]
		uva = uvs[a[1]] if a[1] is not None else (0, 0)
		uvb = uvs[b[1]] if b[1] is not None else (0, 0)
		uvc = uvs[c[1]] if c[1] is not None else (0, 0)
		edge = max(math.dist(pa, pb), math.dist(pb, pc), math.dist(pa, pc))
		steps = max(2, int(edge / (size * 0.5)) + 1)
		for i in range(steps + 1):
			for j in range(steps + 1 - i):
				u, v = i / steps, j / steps
				w = 1 - u - v
				p = tuple(pa[k] * w + pb[k] * u + pc[k] * v for k in range(3))
				tu = uva[0] * w + uvb[0] * u + uvc[0] * v
				tv = uva[1] * w + uvb[1] * u + uvc[1] * v
				colour = tex.getpixel((int(tu * (tex.width - 1)) % tex.width, int((1 - tv) * (tex.height - 1)) % tex.height))[:3]
				key = tuple(min(res - 1, max(0, int((p[k] - mins[k]) / size))) for k in range(3))
				grid.setdefault(key, colour)
	return grid, mins, size


def quantise(grid, keep):
	"""Snap voxel colours to the most common ones so the palette stays small (<= keep)."""
	counts = defaultdict(int)
	for colour in grid.values():
		counts[colour] += 1
	palette = [c for c, _ in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:keep]]
	snapped = {c: min(palette, key=lambda p: sum((x - y) ** 2 for x, y in zip(p, c))) for c in counts}
	return {k: snapped[c] for k, c in grid.items()}, palette


def greedy_boxes(grid, res):
	used = set()
	boxes = []
	for key in sorted(grid):
		if key in used:
			continue
		colour = grid[key]
		x, y, z = key
		# grow along x
		x1 = x
		while (x1 + 1, y, z) in grid and (x1 + 1, y, z) not in used and grid[(x1 + 1, y, z)] == colour:
			x1 += 1
		# grow along y
		y1 = y
		while all((xx, y1 + 1, z) in grid and (xx, y1 + 1, z) not in used and grid[(xx, y1 + 1, z)] == colour for xx in range(x, x1 + 1)):
			y1 += 1
		# grow along z
		z1 = z
		while all((xx, yy, z1 + 1) in grid and (xx, yy, z1 + 1) not in used and grid[(xx, yy, z1 + 1)] == colour
				for xx in range(x, x1 + 1) for yy in range(y, y1 + 1)):
			z1 += 1
		for xx in range(x, x1 + 1):
			for yy in range(y, y1 + 1):
				for zz in range(z, z1 + 1):
					used.add((xx, yy, zz))
		boxes.append(((x, y, z), (x1 + 1, y1 + 1, z1 + 1), colour))
	return boxes


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("obj")
	ap.add_argument("model_json")
	ap.add_argument("palette_png")
	ap.add_argument("--res", type=int, default=40)
	ap.add_argument("--length", type=float, default=14.0, help="model units for the longest axis")
	ap.add_argument("--colours", type=int, default=16, help="palette entries, one texel column each")
	ap.add_argument("--flip", action="store_true", help="rotate 180 degrees about Y (muzzle was pointing the wrong way)")
	ap.add_argument("--max-elements", type=int, default=400)
	ap.add_argument("--accent", help='semicolon-separated "r,g,b" triples overriding the default ACCENT family')
	ap.add_argument("--name", default="paint_gun", help="palette texture reference: metacraft-rivals:item/<name>_palette")
	args = ap.parse_args()
	if not 1 <= args.colours <= 16:
		sys.exit("--colours must be 1..16: the palette is one 16x16 texture, one texel column per colour")

	accent = ACCENT
	if args.accent:
		accent = [tuple(int(v) for v in triple.split(",")) for triple in args.accent.split(";")]

	verts, uvs, tris = load_obj(args.obj)
	tex = Image.open(colormap_path(args.obj)).convert("RGB")
	grid, mins, size = voxelise(verts, uvs, tris, tex, args.res)
	grid, palette = quantise(grid, args.colours)
	boxes = greedy_boxes(grid, args.res)
	if len(boxes) > args.max_elements:
		sys.exit(f"{len(boxes)} boxes > {args.max_elements}; rerun with a lower --res")

	unit = args.length / args.res  # model units per voxel
	xs, ys, zs = zip(*verts)
	extent = ((max(xs) - mins[0]) / size, (max(ys) - mins[1]) / size, (max(zs) - mins[2]) / size)
	centre = tuple(e * unit / 2 for e in extent)

	# Palette: the accent family turns greyscale and is tinted by the item definition's dye;
	# everything else keeps its own texel. The mesh's light/shade split survives the change:
	# colours nearest ACCENT[0] (the lit orange) go white, the rest of the family go grey.
	tinted = {c for c in palette if any(near(c, a, ACCENT_TOLERANCE) for a in accent)}
	def tint_shade(colour):
		lit = min(accent, key=lambda a: sum((x - y) ** 2 for x, y in zip(a, colour))) == accent[0]
		return (255, 255, 255) if lit else (200, 200, 200)
	order = [c for c in palette]
	pal = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
	for i, c in enumerate(order):
		shade = tint_shade(c) if c in tinted else c
		for y in range(16):
			pal.putpixel((i, y), shade + (255,))
	pal.save(args.palette_png)

	def to_model(voxel):
		x, y, z = voxel
		mx = x * unit - centre[0] + 8
		my = y * unit - centre[1] + 8
		mz = z * unit - centre[2] + 8
		if args.flip:
			mx, mz = 16 - mx, 16 - mz
		return mx, my, mz

	elements = []
	for (lo, hi, colour) in boxes:
		a = to_model(lo)
		b = to_model(hi)
		frm = [round(min(a[i], b[i]), 3) for i in range(3)]
		to = [round(max(a[i], b[i]), 3) for i in range(3)]
		col = order.index(colour)
		face = {"uv": [col, 0, col + 1, 1], "texture": "#0"}
		if colour in tinted:
			face["tintindex"] = 0
		elements.append({
			"from": one_line(frm),
			"to": one_line(to),
			"faces": {d: one_line(dict(face)) for d in ("north", "east", "south", "west", "up", "down")},
		})

	model = {
		"credit": f"Kenney Blaster Kit (CC0) {os.path.splitext(os.path.basename(args.obj))[0]}, converted by tools/obj2mc.py; tank faces tinted",
		"texture_size": one_line([16, 16]),
		"textures": {"0": f"metacraft-rivals:item/{args.name}_palette", "particle": f"metacraft-rivals:item/{args.name}_palette"},
		"elements": elements,
		"gui_light": "side",
		"display": {
			"thirdperson_righthand": one_line({"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.6, 0.6, 0.6]}),
			"thirdperson_lefthand": one_line({"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.6, 0.6, 0.6]}),
			"firstperson_righthand": one_line({"rotation": [0, 0, 0], "translation": [1, 2, 1], "scale": [0.6, 0.6, 0.6]}),
			"firstperson_lefthand": one_line({"rotation": [0, 0, 0], "translation": [1, 2, 1], "scale": [0.6, 0.6, 0.6]}),
			"gui": one_line({"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]}),
			"ground": one_line({"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]}),
			"fixed": one_line({"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]}),
		},
	}
	write_model(model, args.model_json)
	print(f"{len(elements)} elements, {len(order)} colours ({len(tinted)} tinted), voxel {unit:.3f} units")


if __name__ == "__main__":
	main()
