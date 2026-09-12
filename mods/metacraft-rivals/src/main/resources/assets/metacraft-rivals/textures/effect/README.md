# Screen ink — the four overlays

`ink_1.png` … `ink_4.png` are the ink that lands on a player's screen. They are ordinary textures:
paint over them and the game uses what you painted. Nothing in the shader needs changing.

They live in `textures/effect/` and nowhere else, and they keep these names. A post effect's texture
input is written as a bare location — `end_of_frame.json` says `metacraft-rivals:ink_1` — and the
client turns that into `textures/effect/ink_1.png` by itself. A file anywhere else, or a location that
spells the directory out, is simply not found, and the screen fills with the magenta-and-black
missing-texture checker instead.

## The format

| | |
|---|---|
| Size | **320 × 180**, RGBA, exactly. All four. |
| Alpha | **Coverage**, and only ever **0 or 255** — never a value between. |
| RGB | A **greyscale shading map**, not a colour. |

**Alpha is where the ink is.** The shader draws a hard, blocky edge on purpose, so a soft or
anti-aliased alpha does not make a soft edge — it makes a ragged one. Keep it binary.

**RGB is light, not paint.** The shader never shows your grey: it reads the luminance and picks one of
four tones of the *team's* colour, so the same drawing works for both teams. The four bands are:

| luminance | tone |
|---|---|
| under 0.30 | the team colour at 55% — shadow |
| 0.30 – 0.60 | the team colour — the base |
| 0.60 – 0.85 | the team colour mixed a quarter toward white — light |
| over 0.85 | the team colour mixed 60% toward white — highlight |

So paint in four values and you get exactly four tones. Suggested: **40** for shadow, **115** for the
base, **185** for light, **235** for highlight. Anything in between still works; it just lands in one
of the four bands.

## What the four states mean

They are the meter in quarters — 1 is a graze, 4 is a faceful.

| file | when | how much |
|---|---|---|
| `ink_1.png` | amount 1–63 | a little ink around the edges |
| `ink_2.png` | 64–127 | creeping in |
| `ink_3.png` | 128–191 | most of the way |
| `ink_4.png` | 192–255 | nearly covered, **centre still clear** |

Two rules the drawings have to keep:

- **Ink comes in from the edges.** It is thrown at the player's face; it does not appear in the middle
  of the glass. Every state should read as ink creeping inward from the sides, top and bottom.
- **Leave the middle clear.** The player is aiming through it. The shader keeps a circle of 9% of the
  screen height clear whatever the texture says, but the drawing should not be relying on that — the
  placeholders keep a wider island of their own.

## Pixel size

The overlay is stretched across the whole screen with no filtering, so one texel is a fat block of
screen pixels — about 6 × 6 at 1080p. That is the grid to draw on; there is no finer detail to be had.
On a window that is not 16:9 the texels come out as rectangles rather than squares, which is the price
of ink that reaches every edge on every screen.

## The placeholders

`tools/ink_overlays.py` (Python, Pillow) drew the ones checked in here, and can redraw them:

```
python3 mods/metacraft-rivals/tools/ink_overlays.py
```

It is a stand-in, not a pipeline — an artist's files are meant to replace these outright, and the
script does not have to be kept working once they do.
