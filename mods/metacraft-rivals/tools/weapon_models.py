#!/usr/bin/env python3
"""Build the four weapon models from Julle's delivery, and solve the ink LED's place on each.

    python3 mods/metacraft-rivals/tools/weapon_models.py [--report]

Reads `tools/julle/*/resourcepack/assets/splat/models/item/*.json`, rewrites their texture ids to our
shared pair, keeps everything else — geometry, UVs, tint indices, `gui_light` and above all the
`display` transforms — verbatim, and appends one element: the ink meter's data LED.

The LED is the interesting half. It is not on the gun. It is a free-floating slip of a box placed so
that the weapon's *own* first-person transform lands it at the bottom centre of the screen, inside the
hotbar — which the GUI draws after the post effect has already read the frame, so the probe finds the
LED in the frame and the player never sees it. Its model coordinates therefore depend on the display
transform, and if Julle ever moves one this script has to be run again.

The chain below is 26.3-rc-1's, each step read off the client with `javap`:

  Camera.calculateHudFov              the hand pass is a 70 deg VERTICAL fov, whatever the fov slider says
  Projection.setupPerspective         -> Matrix4f.setPerspective(fovy, aspect, 0.05, far)
  FirstPersonHandsAndItemsRenderer
      .submitHandsWithItems           two view-bob rotations, both zero when the view is still
      .applyItemArmTransform          translate(+-0.56, -0.52 + equip*-0.6, -0.72); equip is 0 once settled
  ItemTransform.apply                 translate(t) ; rotate(rotationXYZ(r)) ; scale(s) ; translate(-0.5)
                                      with t = display.translation/16, and vertices at element/16

Checked against Julle's own in-game screenshot, tools/julle/splat_roller_models/previews/
minecraft_roller_firstperson.png: this arithmetic puts the roller's green grip heel at x 1162..1225,
y 907..980 of that 1600x1000 frame, which is where it is. `--report` prints the same table for the LED.

Stdlib only.
"""
import collections
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)
JULLE = os.path.join(HERE, "julle")
OUT = os.path.join(MODULE, "src/main/resources/assets/metacraft-rivals/models/item")

SOURCES = {
    "paint_gun": "splat_shooter_charger_slosher_models/resourcepack/assets/splat/models/item/shooter.json",
    "charger": "splat_shooter_charger_slosher_models/resourcepack/assets/splat/models/item/charger.json",
    "slosher": "splat_shooter_charger_slosher_models/resourcepack/assets/splat/models/item/slosher.json",
    "roller": "splat_roller_models/resourcepack/assets/splat/models/item/roller.json",
}

TEXTURES = {"splat:item/shooter_body": "metacraft-rivals:item/julle_body",
            "splat:item/shooter_ink": "metacraft-rivals:item/julle_ink"}

CREDIT = ("Original fan-made geometry by Julle (tools/julle/), Splatoon inspired; "
          "ink faces take tint index 0, the data LED tint index 1")

FOV_Y = 70.0                 # Camera.calculateHudFov
ARM = (0.56, -0.52, -0.72)   # applyItemArmTransform, right hand, equip progress 0

# Where the LED has to land. Both are resolution-independent: x dead centre is ndc_x = 0 at any aspect,
# and the vertical share only ever divides by the fixed 70 deg. 9/1080 of the height puts the whole box
# inside a 22-GUI-pixel hotbar even at GUI scale 1 — which is smaller than vanilla's automatic scale
# picks at any window size worth playing at — and leaves a couple of pixels of headroom under its top
# edge, which is what absorbs the view bob: the hand pass is rotated by a tenth of the turn rate, so a
# fast flick moves the LED a pixel or two. The cost is that the bottom of the box is clipped by the
# bottom of the screen, which costs nothing: it is under the hotbar either way, and what is left is
# still three times the probe's sampling step.
TARGET_Y_SHARE = 9.0 / 1080.0
# How far in front of the eye. Nearer and the box comes out too tall for the hotbar; further and the
# model coordinates run past the [-16, 32] a model element may use.
DEPTH = 1.5
# Half the box, in model pixels: square across, a thin slice deep. Depth is what the perspective divide
# magnifies, and this far below the middle of the screen a whole pixel of it smeared the LED half as
# tall again. The centre is snapped to a 64th so every corner is an exact double and the box is exactly
# one model pixel across, which is what the game test measures.
HALF = (0.5, 0.5, 0.125)
SNAP = 64.0


def mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def rotation(rx, ry, rz):
    """JOML's Quaternionf.rotationXYZ as a matrix: x, then y, then z."""
    ax, ay, az = math.radians(rx), math.radians(ry), math.radians(rz)
    cx, sx, cy, sy, cz, sz = (math.cos(ax), math.sin(ax), math.cos(ay),
                              math.sin(ay), math.cos(az), math.sin(az))
    return mul(mul([[1, 0, 0], [0, cx, -sx], [0, sx, cx]],
                   [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]),
               [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])


def inverse(m):
    a, b, c = m[0]
    d, e, f = m[1]
    g, h, i = m[2]
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    return [[(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
            [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
            [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det]]


def camera_point(model_px, display):
    """A model-pixel coordinate in camera space, for the right hand with the equip animation done."""
    t = [c / 16.0 for c in display["translation"]]
    s = display["scale"]
    v = apply(rotation(*display["rotation"]),
              [(model_px[i] / 16.0 - 0.5) * s[i] for i in range(3)])
    return [ARM[i] + t[i] + v[i] for i in range(3)]


def screen(v, width, height):
    """Pixels, x from the left and y from the BOTTOM. None behind the eye."""
    if v[2] >= -1.0e-6:
        return None
    tan = math.tan(math.radians(FOV_Y) / 2.0)
    return (((v[0] / (width / height * tan)) / -v[2] + 1.0) * 0.5 * width,
            ((v[1] / tan) / -v[2] + 1.0) * 0.5 * height)


def led_centre(display):
    """The model-pixel centre that lands on the target, snapped to a 64th."""
    tan = math.tan(math.radians(FOV_Y) / 2.0)
    ndc_y = 2.0 * TARGET_Y_SHARE - 1.0
    target = (0.0, ndc_y * tan * DEPTH, -DEPTH)
    t = [c / 16.0 for c in display["translation"]]
    s = display["scale"]
    u = apply(inverse(rotation(*display["rotation"])),
              [target[i] - ARM[i] - t[i] for i in range(3)])
    centre = [round(16.0 * (u[i] / s[i] + 0.5) * SNAP) / SNAP for i in range(3)]
    for i in range(3):
        # Parenthesised: without the brackets this is `(not lower) and upper`, which passes a box that
        # has run off the low end and fails one that has not.
        if not (-16.0 <= centre[i] - HALF[i] and centre[i] + HALF[i] <= 32.0):
            raise SystemExit("LED corner %r is outside the [-16, 32] a model element may use; "
                             "try a smaller DEPTH" % (centre,))
    return centre


def led_element(centre):
    faces = collections.OrderedDict()
    for side in ("north", "south", "east", "west", "up", "down"):
        faces[side] = collections.OrderedDict(
            [("uv", [0, 0, 16, 16]), ("texture", "#led"), ("tintindex", 1)])
    return collections.OrderedDict([
        ("name", "data_led"),
        ("from", [centre[i] - HALF[i] for i in range(3)]),
        ("to", [centre[i] + HALF[i] for i in range(3)]),
        ("faces", faces),
    ])


def convert(name, path, report):
    with open(path) as handle:
        source = json.load(handle, object_pairs_hook=collections.OrderedDict)
    textures = collections.OrderedDict(
        (key, TEXTURES.get(value, value)) for key, value in source["textures"].items())
    textures["led"] = "metacraft-rivals:item/data_led"
    display = source["display"]
    centre = led_centre(display["firstperson_righthand"])
    model = collections.OrderedDict([
        ("credit", CREDIT),
        ("texture_size", source["texture_size"]),
        ("textures", textures),
        ("gui_light", source["gui_light"]),
        ("display", display),
        ("elements", list(source["elements"]) + [led_element(centre)]),
    ])
    with open(os.path.join(OUT, name + ".json"), "w") as handle:
        json.dump(model, handle, indent="\t")
        handle.write("\n")
    if report:
        led = model["elements"][-1]
        for width, height in ((1920.0, 1080.0), (2560.0, 1440.0), (1280.0, 720.0)):
            box = [screen(camera_point([(led["from"] if corner & 1 << axis else led["to"])[axis]
                                        for axis in range(3)], display["firstperson_righthand"]),
                          width, height) for corner in range(8)]
            print("%-10s %dx%d  x %.1f..%.1f (centre %.0f)  %.1f..%.1f px above the bottom"
                  % (name, width, height, min(p[0] for p in box), max(p[0] for p in box), width / 2,
                     min(p[1] for p in box), max(p[1] for p in box)))
    print("%-10s %d elements, LED centre %r" % (name, len(model["elements"]), centre))


report = "--report" in sys.argv
for name, relative in SOURCES.items():
    convert(name, os.path.join(JULLE, relative), report)
