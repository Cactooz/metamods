#!/usr/bin/env python3
"""Generate the four screen-ink overlay textures.

    python3 mods/metacraft-rivals/tools/ink_overlays.py

Writes `src/main/resources/assets/metacraft-rivals/textures/post/ink_1.png` … `ink_4.png`.

These are PLACEHOLDERS, and they are checked in as ordinary resources so an artist can paint over
them. The format they have to keep is in `textures/post/README.md`, and in short:

    320 x 180 RGBA.  ALPHA is coverage and is 0 or 255, never anything between — the shader draws a
    hard pixel-art edge and a soft alpha would read as a blurred one.  RGB is a greyscale SHADING map,
    not a colour: dark is shadow, mid is the base, light is a highlight.  The shader maps that
    luminance to four tones of the team's own colour, so an overlay is the same drawing in either
    team's ink.

State 1 is a little ink around the edges and state 4 is nearly covered with the centre still clear;
ink always creeps in from the sides, because that is where a faceful of it lands and because the
middle of the screen is where the player is aiming.

What this script draws, so that the placeholders have real depth rather than two flat tones: blobs
anchored on all four edges growing inward per state, with a wavy inner boundary; a dark one-texel
outline ring just inside every edge; a lighter band inside that, which is what reads as thickness;
the base fill; highlight specks scattered near the inner edge and at the tops of the drips; and drips
hanging off the ink with darker undersides.

Stdlib plus Pillow. Deterministic: the noise is a hash of the coordinate, so re-running it produces
the same four files byte for byte.
"""
import math
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "src/main/resources/assets/metacraft-rivals/textures/post")

WIDTH = 320
HEIGHT = 180
STATES = 4

# How far the ink reaches in from an edge at each state, as a fraction of the half-width (for the left
# and right edges) or the half-height (top and bottom). State 4 leaves a clear island in the middle.
REACH = [0.13, 0.29, 0.47, 0.78]
# The clear circle in the middle, as a fraction of the height. The shader keeps one of its own at 9%;
# this is a little wider so the texture can never be the thing that covers the reticle.
CLEAR = 0.12

# Luminances, which the shader turns into four tones of the team colour. These sit inside its bands:
# < 0.3 shadow, < 0.6 base, < 0.85 light, else highlight.
SHADOW = 0.16
BASE = 0.45
BAND = 0.72
HIGHLIGHT = 0.93


def noise(seed, i):
    """A stable value in 0..1 from an integer. sin-hash, so it needs no library and no seed state."""
    x = math.sin(i * 12.9898 + seed * 78.233) * 43758.5453
    return x - math.floor(x)


def wave(seed, t, terms=3):
    """Smooth 0..1 noise along one axis, from a few sine terms with hashed phases."""
    total = 0.0
    weight = 0.0
    for k in range(terms):
        frequency = 2.0 + 3.0 * k
        phase = noise(seed, k) * math.tau
        amplitude = 1.0 / (k + 1)
        total += amplitude * math.sin(t * frequency * math.tau + phase)
        weight += amplitude
    return 0.5 + 0.5 * total / weight


class Drip:
    """A tongue of ink hanging off one edge's inner boundary, inward and downward."""

    def __init__(self, edge, along, width, length):
        self.edge = edge
        self.along = along
        self.width = width
        self.length = length


def drips(state):
    """A fixed set per edge, growing with the state. Same every run, so the four states line up."""
    out = []
    for edge in range(4):
        count = 3 + edge % 2
        for i in range(count):
            seed = edge * 17 + i
            along = 0.12 + 0.76 * ((i + 0.5) / count + 0.12 * (noise(seed, 1) - 0.5))
            width = 3.0 + 5.0 * noise(seed, 2)
            length = (4.0 + 16.0 * noise(seed, 3)) * (0.45 + 0.25 * state)
            out.append(Drip(edge, along, width, length))
    return out


def edge_reach(state, edge, t):
    """How far the ink reaches in from {@code edge} at the position {@code t} along it, in texels."""
    half = WIDTH / 2.0 if edge in (0, 1) else HEIGHT / 2.0
    base = REACH[state - 1] * half
    # A wavy inner boundary rather than a straight band: this is what makes it read as ink rather than
    # as a vignette. Never less than a fifth of the reach, so no edge is ever bare.
    return base * (0.2 + 0.8 * wave(edge + 1, t))


def coverage(state):
    """The alpha mask: True where there is ink."""
    reaches = []
    for edge in range(4):
        span = HEIGHT if edge in (0, 1) else WIDTH
        reaches.append([edge_reach(state, edge, i / span) for i in range(span)])
    tongues = drips(state)
    clear = CLEAR * HEIGHT
    mask = [[False] * WIDTH for _ in range(HEIGHT)]
    for y in range(HEIGHT):
        for x in range(WIDTH):
            # 0 left, 1 right, 2 top, 3 bottom.
            depth = [x, WIDTH - 1 - x, y, HEIGHT - 1 - y]
            into = [reaches[0][y], reaches[1][y], reaches[2][x], reaches[3][x]]
            wet = any(depth[e] < into[e] for e in range(4))
            if not wet:
                for tongue in tongues:
                    inward = depth[tongue.edge] - into[tongue.edge]
                    if inward < 0 or inward >= tongue.length:
                        continue
                    # Tapered, with a rounded tip: a tongue of constant width reads as a tooth.
                    along_axis = HEIGHT if tongue.edge in (0, 1) else WIDTH
                    run = inward / tongue.length
                    half = tongue.width * math.sqrt(max(0.0, 1.0 - run * run))
                    along = tongue.along * along_axis
                    # Sideways drips sag as they run, because paint does.
                    if tongue.edge in (0, 1):
                        along += inward * 0.35
                    here = y if tongue.edge in (0, 1) else x
                    if abs(here - along) < half:
                        wet = True
                        break
            if wet:
                dx = (x - WIDTH / 2.0) * HEIGHT / WIDTH
                dy = y - HEIGHT / 2.0
                if dx * dx + dy * dy < clear * clear:
                    wet = False
            mask[y][x] = wet
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
            # The texture's own border counts as the outside, so ink running off the screen has no rim.
            edge = False
            for ax, ay in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + ax, y + ay
                if 0 <= nx < WIDTH and 0 <= ny < HEIGHT and not mask[ny][nx]:
                    edge = True
            if edge:
                out[y][x] = 1
                frontier.append((x, y))
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


def shade(mask, depth):
    """The greyscale map: outline, inner band, base, and highlight specks near the rim and the drips."""
    image = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    pixels = image.load()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            if not mask[y][x]:
                continue
            d = depth[y][x]
            if d <= 1:
                lum = SHADOW
            elif d <= 5:
                lum = BAND
            else:
                lum = BASE
                # A slow swell across the sheet, so a big covered area is not one flat tone.
                lum += 0.06 * (wave(9, x / WIDTH + y / (3.0 * HEIGHT), 2) - 0.5)
                # Highlight specks, sparse and only just inside the lighter band: they read as the wet
                # crest of the ink. Any denser and they read as dithering.
                if 6 <= d <= 9 and noise(3, x * 131 + y * 17) > 0.94:
                    lum = HIGHLIGHT
            # The underside of a drip is in its own shadow: the lower half of a deep, narrow tongue.
            if d >= 2 and not mask[min(HEIGHT - 1, y + 2)][x] and mask[max(0, y - 2)][x]:
                lum = min(lum, 0.24)
            value = max(0, min(255, int(round(lum * 255))))
            pixels[x, y] = (value, value, value, 255)
    return image


def main():
    os.makedirs(OUT, exist_ok=True)
    for state in range(1, STATES + 1):
        mask = coverage(state)
        image = shade(mask, depths(mask))
        path = os.path.join(OUT, "ink_%d.png" % state)
        image.save(path)
        covered = sum(1 for row in mask for wet in row if wet)
        print("ink_%d.png  %d x %d, %.1f%% covered" % (state, WIDTH, HEIGHT, 100.0 * covered / (WIDTH * HEIGHT)))


if __name__ == "__main__":
    main()
