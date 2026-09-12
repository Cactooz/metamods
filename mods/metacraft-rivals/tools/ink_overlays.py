#!/usr/bin/env python3
"""Generate the four screen-ink overlay textures.

    python3 mods/metacraft-rivals/tools/ink_overlays.py

Writes `src/main/resources/assets/metacraft-rivals/textures/effect/ink_1.png` … `ink_4.png`, and a
contact sheet at `/tmp/ink_sheet_new.png` (the four states 2x2 over mid grey, upscaled 2x NEAREST) so
the drawing can be looked at without starting the game.

That directory is forced: a post chain's texture input names a bare location, and the client resolves
it as `textures/effect/<path>.png`, so `end_of_frame.json` asks for `metacraft-rivals:ink_1`.

These are PLACEHOLDERS, and they are checked in as ordinary resources so an artist can paint over
them. The format they have to keep is in `textures/effect/README.md`, and in short:

    320 x 180 RGBA.  ALPHA is coverage and is 0 or 255, never anything between — the shader draws a
    hard pixel-art edge and a soft alpha would read as a blurred one.  RGB is a greyscale SHADING map,
    not a colour: dark is shadow, mid is the base, light is a highlight.  The shader maps that
    luminance to four tones of the team's own colour, so an overlay is the same drawing in either
    team's ink.

What this script draws: **blobs**, not a frame of ink. Splatoon's screen ink is the reference — fat,
round, glossy paint thrown at the glass — so each splat here is a union of overlapping discs (a big
body plus two to four smaller lobes on its rim) with one to three drips hanging off its lowest edge,
and the union is a metaball threshold on the sum of `r²/d²` falloffs, so the lobes fuse into the body
with a fillet instead of showing their own outlines. Every body is centred on or beyond the border
with its lobes reaching inward, because ink arrives from outside the frame; the middle of the screen
keeps a clear island, because that is where the player is aiming.

The four states are **cumulative**: one list of seventeen splats, each tagged with the state it first
appears in (five in state 1, four more in each of 2, 3 and 4), and every splat already on screen grows
by {@code GROWTH} per later state, so ink both arrives and spreads. State N therefore contains every
texel of state N-1, which the script asserts texel for texel.

Shading is the four bands the shader steps on and nothing else: a one-texel dark outline along every
alpha edge, a base rim inside it, a broad lighter band that is the splat's own silhouette inset and
pushed up-left, a highlight crescent between two further insets (so it lies along the top-left of the
drop and runs down the left of its drips), a specular dot on every drip bead that is still on the
outside of the paint, and shadow under every downward-facing edge, which is what puts a drip's
underside in its own shade. No speckle: scattered light texels read as grit, not as gloss. The sheen
follows the splat and not each disc — a pale circle inside every lobe drew a raft of bubbles.

Stdlib plus Pillow, no anti-aliasing (the 320x180 grid is the pixel art), and deterministic: every
random-looking number is a hash of a fixed seed, so re-running produces the same files byte for byte.
"""
import math
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "src/main/resources/assets/metacraft-rivals/textures/effect")
SHEET = "/tmp/ink_sheet_new.png"

WIDTH = 320
HEIGHT = 180
STATES = 4

# How much every splat already on screen grows per later state. Ink spreads as well as arrives, and it
# is what keeps state 4 from looking like state 1 with more blobs beside it.
GROWTH = 1.24
# The clear island in the middle, as a fraction of the height, per state. It only ever shrinks, which is
# what lets a later state contain every texel of an earlier one.
CLEAR = [0.50, 0.45, 0.40, 0.35]
# Roughly how much of the screen each state covers, as a fraction. Asserted with a wide tolerance: the
# drawing is what matters and the numbers only catch a state that has stopped building up.
WANT = [0.15, 0.32, 0.50, 0.70]
TOLERANCE = 0.06

# The sheen: how far the silhouette is shrunk for the lighter band, and how far up-left it is pushed,
# as a fraction of the splat's body radius. The highlight is the sliver between two further insets one
# push apart.
SHEEN_INSET = 0.72
SHEEN_PUSH = 0.13
CREST_INSET = 0.56
CREST_PUSH = 0.26

# How far apart the discs down a drip's run may be, in the narrowest of their radii. Below 1 they
# overlap outright and the run is a solid tapering tongue rather than a string of beads.
DRIP_STEP = 0.5

# Luminances, which the shader turns into four tones of the team colour. These sit inside its bands:
# < 0.3 shadow, < 0.6 base, < 0.85 light, else highlight.
SHADOW = 0.16
BASE = 0.45
BAND = 0.72
HIGHLIGHT = 0.93

# The splats: (first state, x as a fraction of the width, y as a fraction of the height, body radius as
# a fraction of the height, the inward direction in degrees, seed). Screen coordinates, so y grows
# downward and the inward angle of a splat on the top edge is +90. Bodies sit on or past the border and
# the lobes are thrown inward from there; corners come first, because a faceful of ink lands wide.
ANCHORS = [
	(1, 0.00, 0.00, 0.149, 45, 11),
	(1, 1.00, 0.00, 0.143, 135, 12),
	(1, 0.00, 1.00, 0.143, -45, 13),
	(1, 1.00, 1.00, 0.149, -135, 14),
	(1, 0.54, -0.05, 0.099, 90, 15),
	(2, -0.03, 0.44, 0.118, 0, 21),
	(2, 1.03, 0.60, 0.118, 180, 22),
	(2, 0.30, 1.05, 0.112, -90, 23),
	(2, 0.78, -0.06, 0.112, 90, 24),
	(3, 0.20, -0.05, 0.118, 90, 31),
	(3, 1.03, 0.20, 0.124, 180, 32),
	(3, 0.64, 1.06, 0.124, -90, 33),
	(3, -0.03, 0.80, 0.118, 0, 34),
	(4, 0.88, 1.05, 0.130, -90, 41),
	(4, -0.03, 0.14, 0.124, 0, 42),
	(4, 1.04, 0.40, 0.124, 180, 43),
	(4, 0.42, -0.05, 0.124, 90, 44),
]


def noise(seed, i):
	"""A stable value in 0..1 from two integers. sin-hash, so it needs no library and no seed state."""
	x = math.sin(i * 12.9898 + seed * 78.233) * 43758.5453
	return x - math.floor(x)


def span(seed, i, low, high):
	"""A stable value in low..high."""
	return low + (high - low) * noise(seed, i)


class Disc:
	"""A round lobe of paint: a body, a lobe on its rim, a step down a drip, or a drip's bead.

	{@code big} discs get a highlight crescent and beads get a specular dot; everything a splat is made
	of is one of these, because a round brush is the only brush thrown paint has."""

	def __init__(self, x, y, r, big=False, bead=False, sheen=True):
		self.x = x
		self.y = y
		self.r = r
		self.big = big
		self.bead = bead
		self.sheen = sheen

	def near(self, px, py):
		"""The squared distance from (px, py) to the shape's skeleton — its centre."""
		dx = px - self.x
		dy = py - self.y
		return dx * dx + dy * dy

	def box(self, reach):
		return self.x - reach, self.y - reach, self.x + reach, self.y + reach

	def shift(self, dx, dy, scale):
		return Disc(self.x + dx, self.y + dy, self.r * scale)


class Splat:
	"""One thrown blob: its parts fuse with each other, and nothing else.

	Fusing only within a splat is deliberate. A metaball field summed over the whole sheet would let
	sixteen distant blobs swell each other into one soft mass; summed per splat, each one keeps its own
	fat round silhouette and two that happen to touch simply overlap.
	"""

	def __init__(self, parts, cx, cy, r):
		self.parts = parts
		self.cx = cx
		self.cy = cy
		self.r = r

	def inset(self, scale, push):
		"""A smaller copy of the whole silhouette, pushed up-left: the splat's own sheen and highlight.

		Insetting the splat rather than each disc is what makes a splat read as one drop of paint. A
		lighter disc drawn inside every lobe drew a raft of bubbles instead — the eye picks out each
		pale circle and stops seeing the blob.
		"""
		out = []
		for part in self.parts:
			out.append(Disc(self.cx + (part.x - self.cx) * scale - push,
					self.cy + (part.y - self.cy) * scale - push, part.r * scale))
		return Splat(out, self.cx - push, self.cy - push, self.r * scale)

	def inside(self, px, py):
		# The metaball threshold: r²/d² is exactly 1 on a lone part's own rim, so a part never shrinks,
		# and where two overlap the sum crosses 1 outside both — which is the fillet between them.
		total = 0.0
		for part in self.parts:
			d2 = part.near(px, py)
			total += part.r * part.r / max(d2, 0.35)
			if total >= 1.0:
				return True
		return total >= 1.0

	def box(self):
		x0 = y0 = 1e9
		x1 = y1 = -1e9
		for part in self.parts:
			# A part can be pulled outward by the others, but never by more than sqrt(n) of its radius.
			reach = part.r * math.sqrt(len(self.parts)) + 2.0
			bx0, by0, bx1, by1 = part.box(reach)
			x0 = min(x0, bx0)
			y0 = min(y0, by0)
			x1 = max(x1, bx1)
			y1 = max(y1, by1)
		return x0, y0, x1, y1


def splat(anchor, state):
	"""One splat as it stands in {@code state}, or None if it has not been thrown yet."""
	first, fx, fy, fr, angle, seed = anchor
	if state < first:
		return None
	grow = GROWTH ** (state - first)
	cx = fx * WIDTH
	cy = fy * HEIGHT
	r = fr * HEIGHT * grow
	inward = math.radians(angle)
	parts = [Disc(cx, cy, r, big=True)]
	lobes = 2 + int(3 * noise(seed, 1))
	for i in range(lobes):
		# On the body's rim and biased inward, so a splat reads as paint spreading in off the border
		# rather than as a flower.
		away = inward + math.radians(span(seed, 10 + i, -78.0, 78.0))
		at = r * span(seed, 20 + i, 0.72, 1.06)
		lobe = r * span(seed, 30 + i, 0.40, 0.70)
		parts.append(Disc(cx + math.cos(away) * at, cy + math.sin(away) * at, lobe,
				big=lobe > 0.52 * r, sheen=lobe > 0.52 * r))
	# The drips hang off the lowest paint there is, so they leave the silhouette downward and never out
	# of its middle.
	bodies = list(parts)
	bottom = max(part.y + part.r for part in bodies)
	for i in range(1 + int(3 * noise(seed, 2))):
		host = bodies[int(noise(seed, 40 + i) * len(bodies))]
		width = r * span(seed, 50 + i, 0.15, 0.23)
		length = r * span(seed, 60 + i, 0.45, 1.05)
		x = host.x + r * span(seed, 70 + i, -0.35, 0.35)
		top = min(bottom - width, host.y + host.r - width)
		# Paint sags sideways as it runs, and it thins as it goes: a run of discs from fat at the lip to
		# narrow at the neck, then the bead it is all running into. A capsule of one width and a ball on
		# the end drew a lollipop.
		sag = r * span(seed, 80 + i, -0.18, 0.18)
		steps = max(3, int(length / (DRIP_STEP * width)) + 1)
		for k in range(steps):
			t = k / float(steps - 1)
			parts.append(Disc(x + sag * t, top + length * t, width * (1.25 - 0.55 * t), sheen=False))
		parts.append(Disc(x + sag, top + length, width * span(seed, 90 + i, 1.2, 1.5), bead=True, sheen=False))
	return Splat(parts, cx, cy, r)


def scene(state):
	"""Every splat on screen in {@code state}, largest first."""
	out = []
	for anchor in ANCHORS:
		one = splat(anchor, state)
		if one is not None:
			out.append(one)
	return out


def coverage(state, blobs):
	"""The alpha mask: True where there is ink. Rasterised per splat inside its own box."""
	mask = [[False] * WIDTH for _ in range(HEIGHT)]
	for blob in blobs:
		x0, y0, x1, y1 = blob.box()
		for y in range(max(0, int(math.floor(y0))), min(HEIGHT, int(math.ceil(y1)) + 1)):
			row = mask[y]
			for x in range(max(0, int(math.floor(x0))), min(WIDTH, int(math.ceil(x1)) + 1)):
				if not row[x] and blob.inside(x + 0.5, y + 0.5):
					row[x] = True
	# The island the player aims through. Carved after the blobs, and only ever smaller in a later
	# state, so carving it cannot break the rule that state N contains state N-1.
	clear = CLEAR[state - 1] * HEIGHT
	for y in range(HEIGHT):
		dy = y + 0.5 - HEIGHT / 2.0
		if abs(dy) > clear:
			continue
		row = mask[y]
		for x in range(WIDTH):
			dx = x + 0.5 - WIDTH / 2.0
			if dx * dx + dy * dy < clear * clear:
				row[x] = False
	return mask


def depths(mask):
	"""How many texels inside the ink each covered texel is, by a breadth-first walk from the edge."""
	far = WIDTH + HEIGHT
	out = [[0 if not mask[y][x] else far for x in range(WIDTH)] for y in range(HEIGHT)]
	frontier = []
	for y in range(HEIGHT):
		for x in range(WIDTH):
			if not mask[y][x]:
				continue
			# The texture's own border counts as the inside, so ink running off the screen has no rim:
			# an outline along the border would read as a frame drawn round the whole view.
			for ax, ay in ((1, 0), (-1, 0), (0, 1), (0, -1)):
				nx, ny = x + ax, y + ay
				if 0 <= nx < WIDTH and 0 <= ny < HEIGHT and not mask[ny][nx]:
					out[y][x] = 1
					frontier.append((x, y))
					break
	head = 0
	while head < len(frontier):
		x, y = frontier[head]
		head += 1
		for ax, ay in ((1, 0), (-1, 0), (0, 1), (0, -1)):
			nx, ny = x + ax, y + ay
			if not (0 <= nx < WIDTH and 0 <= ny < HEIGHT) or not mask[ny][nx]:
				continue
			if out[ny][nx] > out[y][x] + 1:
				out[ny][nx] = out[y][x] + 1
				frontier.append((nx, ny))
	return out


def stamp(into, mask, shape, value, minimum_depth, depth):
	"""Paint {@code value} where {@code shape} covers ink that is at least {@code minimum_depth} in."""
	x0, y0, x1, y1 = shape.box(shape.r + 1.0)
	for y in range(max(0, int(math.floor(y0))), min(HEIGHT, int(math.ceil(y1)) + 1)):
		for x in range(max(0, int(math.floor(x0))), min(WIDTH, int(math.ceil(x1)) + 1)):
			if not mask[y][x] or depth[y][x] < minimum_depth:
				continue
			if shape.near(x + 0.5, y + 0.5) < shape.r * shape.r:
				into[y][x] = value


def shade(mask, depth, blobs):
	"""The greyscale map: the four bands the shader steps on, and nothing between them."""
	lum = [[BASE if mask[y][x] else None for x in range(WIDTH)] for y in range(HEIGHT)]
	for blob in blobs:
		# One sheen and one crescent per splat, both following its own outline: the broad lighter band
		# is the silhouette inset and pushed up-left, and the highlight is the lune between two further
		# insets, which lies along the top-left of the drop and runs down the left of its drips.
		sheen = blob.inset(SHEEN_INSET, blob.r * SHEEN_PUSH)
		lit = blob.inset(CREST_INSET, blob.r * CREST_PUSH)
		dark = blob.inset(CREST_INSET, blob.r * CREST_PUSH * 0.35)
		x0, y0, x1, y1 = blob.box()
		for y in range(max(0, int(math.floor(y0))), min(HEIGHT, int(math.ceil(y1)) + 1)):
			for x in range(max(0, int(math.floor(x0))), min(WIDTH, int(math.ceil(x1)) + 1)):
				# Off the rim, so the base band stays a wall of thickness all the way round.
				if not mask[y][x] or depth[y][x] < 4:
					continue
				px, py = x + 0.5, y + 0.5
				if not sheen.inside(px, py):
					continue
				lum[y][x] = BAND
				if depth[y][x] >= 5 and lit.inside(px, py) and not dark.inside(px, py):
					lum[y][x] = HIGHLIGHT
	for blob in blobs:
		for part in blob.parts:
			if not part.bead or part.r < 3.0:
				continue
			# A bead a later state has swallowed is not a bead any more: a dot on it would be a speck of
			# grit in the middle of a sheet of paint.
			at = depth[max(0, min(HEIGHT - 1, int(part.y)))][max(0, min(WIDTH - 1, int(part.x)))]
			if at > 2.0 * part.r + 2:
				continue
			# One specular dot on every drip bead, which is what makes a drip read as wet rather than
			# as a tail. Never smaller than a texel: this is a 320x180 grid.
			dot = part.shift(-part.r * 0.30, -part.r * 0.30, 0.0)
			dot.r = max(1.0, part.r * 0.32)
			stamp(lum, mask, dot, HIGHLIGHT, 2, depth)
	for y in range(HEIGHT):
		for x in range(WIDTH):
			if lum[y][x] is None:
				continue
			# Every downward-facing edge is in the paint's own shadow, which is what puts a drip's
			# underside and the bottom of every lobe in shade.
			if depth[y][x] >= 2 and not mask[min(HEIGHT - 1, y + 2)][x] and mask[max(0, y - 2)][x]:
				lum[y][x] = SHADOW
			# The outline last, so it survives every band above: one dark texel along every alpha edge.
			if depth[y][x] == 1:
				lum[y][x] = SHADOW
	image = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
	pixels = image.load()
	for y in range(HEIGHT):
		for x in range(WIDTH):
			if lum[y][x] is None:
				continue
			value = max(0, min(255, int(round(lum[y][x] * 255))))
			pixels[x, y] = (value, value, value, 255)
	return image


def contact(images):
	"""The four states 2x2, drawn the way the shader draws them, upscaled 2x with no filtering.

	The point of the sheet is to be looked at, so it does the shader's own work: the four luminance
	bands become four tones of DATA's ink over a dark checker standing in for the world. A grey sheet
	composited over grey showed nothing — the base band and the background were the same value.
	"""
	ink = (0.7412, 0.2157, 0.3294)
	tones = []
	for mix, scale in ((0.0, 0.55), (0.0, 1.0), (0.25, 1.0), (0.6, 1.0)):
		tones.append(tuple(int(round(255 * (channel * scale + (1.0 - channel * scale) * mix))) for channel in ink))
	sheet = Image.new("RGB", (WIDTH * 2, HEIGHT * 2))
	pixels = sheet.load()
	for i, image in enumerate(images):
		source = image.load()
		ox, oy = WIDTH * (i % 2), HEIGHT * (i // 2)
		for y in range(HEIGHT):
			for x in range(WIDTH):
				grey, _, _, alpha = source[x, y]
				if alpha < 128:
					shade = 58 if (x // 8 + y // 8) % 2 == 0 else 74
					pixels[ox + x, oy + y] = (shade, shade, shade)
					continue
				lum = grey / 255.0
				pixels[ox + x, oy + y] = tones[0] if lum < 0.3 else tones[1] if lum < 0.6 \
						else tones[2] if lum < 0.85 else tones[3]
	sheet = sheet.resize((sheet.width * 2, sheet.height * 2), Image.NEAREST)
	sheet.save(SHEET)


def main():
	os.makedirs(OUT, exist_ok=True)
	images = []
	masks = []
	for state in range(1, STATES + 1):
		blobs = scene(state)
		mask = coverage(state, blobs)
		image = shade(mask, depths(mask), blobs)
		image.save(os.path.join(OUT, "ink_%d.png" % state))
		images.append(image)
		masks.append(mask)
		covered = sum(1 for row in mask for wet in row if wet) / float(WIDTH * HEIGHT)
		print("ink_%d.png  %d x %d, %d splats, %.1f%% covered (want %.0f%%)"
				% (state, WIDTH, HEIGHT, len(blobs), 100.0 * covered, 100.0 * WANT[state - 1]))
		assert abs(covered - WANT[state - 1]) <= TOLERANCE, \
			"state %d covers %.1f%%, wanted about %.0f%%" % (state, 100.0 * covered, 100.0 * WANT[state - 1])
		assert not mask[HEIGHT // 2][WIDTH // 2], "state %d has ink in the middle of the screen" % state
		if state > 1:
			for y in range(HEIGHT):
				for x in range(WIDTH):
					assert mask[y][x] or not masks[state - 2][y][x], \
						"state %d lost the texel at %d,%d that state %d had" % (state, x, y, state - 1)
	contact(images)
	print("contact sheet %s" % SHEET)


if __name__ == "__main__":
	main()
