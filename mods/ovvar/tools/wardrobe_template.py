#!/usr/bin/env python3
"""Draws the greyscale wardrobe screen template, once, checked in as the source of truth.

metacraft.ovvar.pack.WardrobeArt tints this at runtime: output = round(luminance/255 * chapterColour)
per channel, except pixels at or above WHITE_THRESHOLD (the stitching) which are kept pure white.
So every tone in this file other than the stitching should sit comfortably below that threshold.

Geometry follows the vanilla chest grid: slot (row, col) occupies pixels
x = 8 + 18*col .. 8 + 18*col + 16, y = 18 + 18*row .. 18 + 18*row + 16 (the 18px pitch, 16px cell,
1px gap on each side that the vanilla slot frame itself draws). Rows 0..5 are the six rows of the
GENERIC_9x6 container; row 0 is the tab row, rows 1-4 the patch/preview body, row 5 the action row.

Output: 176x126 -- exactly 18 (the header margin) + 18*6 (six 18px rows), the GENERIC_9x6
container's own slot area and nothing past it, so no opaque cloth or stitching paints over the
player's inventory below it. 8-bit greyscale + alpha (mode "LA").
"""
import random

from PIL import Image

WIDTH, HEIGHT = 176, 126   # 18 + 18*6: the header margin plus exactly six 18px rows, no further
random.seed(20260912)  # deterministic noise: re-running this script must not change the checked-in file

CLOTH = 132        # base cloth tone
CLOTH_NOISE = 6     # +/- per-pixel luminance noise for the woven-cloth feel
PATCH_PANEL = 200   # the cream canvas pocket the patches sit on
PREVIEW_PANEL = 118 # slightly darker cloth behind the preview, so the silhouette reads
SILHOUETTE = 96     # the mannequin silhouette, drawn in mid grey per the spec
STITCH = 255        # overlock stitching: pure white, never tinted (see WHITE_THRESHOLD in WardrobeArt)

SLOT = 18
ORIGIN_X, ORIGIN_Y = 8, 18


def slot_rect(row, col, cols=1, rows=1):
	x0 = ORIGIN_X + SLOT * col
	y0 = ORIGIN_Y + SLOT * row
	x1 = x0 + SLOT * cols
	y1 = y0 + SLOT * rows
	return x0, y0, x1, y1


def fill(img, box, value):
	x0, y0, x1, y1 = box
	for y in range(max(0, y0), min(HEIGHT, y1)):
		for x in range(max(0, x0), min(WIDTH, x1)):
			img.putpixel((x, y), (value, 255))


def stitch_rect(img, box, inset=1):
	"""A 1px dashed-looking overlock outline (every other pixel) around the given rectangle."""
	x0, y0, x1, y1 = box
	x0 += inset
	y0 += inset
	x1 -= inset
	y1 -= inset
	for x in range(x0, x1):
		if (x - x0) % 2 == 0:
			img.putpixel((x, y0), (STITCH, 255))
			img.putpixel((x, y1 - 1), (STITCH, 255))
	for y in range(y0, y1):
		if (y - y0) % 2 == 0:
			img.putpixel((x0, y), (STITCH, 255))
			img.putpixel((x1 - 1, y), (STITCH, 255))


def draw_silhouette(img, box):
	"""A very simple standing-ovve silhouette (head, torso+sleeves, legs) centred in the preview panel."""
	x0, y0, x1, y1 = box
	cx = (x0 + x1) // 2
	top = y0 + 4
	# head
	fill(img, (cx - 3, top, cx + 3, top + 6), SILHOUETTE)
	# torso + sleeves (the ovve's top)
	fill(img, (cx - 9, top + 6, cx + 9, top + 24), SILHOUETTE)
	# legs (the ovve's bottom), split with a 1px gap between them
	fill(img, (cx - 7, top + 24, cx - 1, top + 40), SILHOUETTE)
	fill(img, (cx + 1, top + 24, cx + 7, top + 40), SILHOUETTE)


def main():
	img = Image.new("LA", (WIDTH, HEIGHT), (CLOTH, 255))

	# base cloth, everywhere, with subtle luminance noise
	for y in range(HEIGHT):
		for x in range(WIDTH):
			n = random.randint(-CLOTH_NOISE, CLOTH_NOISE)
			v = max(0, min(254, CLOTH + n))  # stays under STITCH so noise is never mistaken for stitching
			img.putpixel((x, y), (v, 255))

	# tab row (row 0): a plain stitched strip; individual tab/toggle buttons draw their own icons
	tab_row = slot_rect(0, 0, cols=9, rows=1)
	stitch_rect(img, tab_row)

	# patch panel: rows 1-4, cols 0-4 -- the cream canvas pocket
	patch_panel = slot_rect(1, 0, cols=5, rows=4)
	fill(img, patch_panel, PATCH_PANEL)
	stitch_rect(img, patch_panel)

	# preview panel: rows 1-4, cols 5-8
	preview_panel = slot_rect(1, 5, cols=4, rows=4)
	fill(img, preview_panel, PREVIEW_PANEL)
	stitch_rect(img, preview_panel)
	draw_silhouette(img, preview_panel)

	# action row (row 5)
	action_row = slot_rect(5, 0, cols=9, rows=1)
	stitch_rect(img, action_row)

	# outer border, the whole background
	stitch_rect(img, (0, 0, WIDTH, HEIGHT), inset=0)

	img.save("wardrobe_template.png")


if __name__ == "__main__":
	main()
