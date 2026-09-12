# Metacraft Rivals

METAmods module `mods/metacraft-rivals` (mod id `metacraft-rivals`). A Splatoon-style paint
prototype for vanilla clients: Fabric + [Polymer](https://polymer.pb4.eu), Minecraft 26.3-rc-1, Java 25.
(Loader 0.19.5, Fabric API 0.160.3+26.3, Polymer 0.18.0+26.3-rc-1 — all from the root
`gradle.properties`. The module is not shipped in `dist`; it runs on a server of its own.)
Players need only the auto-served resource pack. Design: `docs/superpowers/specs/2026-09-11-metacraft-rivals-paint-prototype-design.md`
(v1), `docs/superpowers/specs/2026-09-12-metacraft-rivals-v2-design.md` (v2: feel, art, any-block
paint, ink), `docs/superpowers/specs/2026-09-12-metacraft-rivals-v3-design.md` (v3: gloss that
actually renders, real squid form, blobby bouncing shots, three more weapons) and
`docs/superpowers/specs/2026-09-12-metacraft-rivals-v4-connected-paint-design.md` (v4: two teams,
connected paint, the shader-drawn border). v6 added the two visual features described below: paint on
non-full blocks as block displays of the real paint state, and ink on the screen from damage taken.
v7 is Julle's four weapon models, the roller that replaces the sprayer, held-use fire, Splatoon 1's
own numbers on a hundred-unit tank, an ink LED nobody can see, and screen ink drawn from four textures
an artist can paint over.

**Standalone.** This module is not bundled into the `dist` jar (root `build.gradle`, `standaloneMods`):
its pack retextures sculk vein, resin clump, tripwire and redstone wire as paint, which only a
dedicated Rivals server wants.

## How it works

- Two teams, one colour each, in their ovvar chapter's ovve: **DATA** `#BD3754` and **IT**
  `#8A57BD`. A team's name is its colour id (`data`, `it`); `/rivals setup` creates both
  (it lists `PaintColor.idList()`, so a third colour would need no command change).
- A painted region renders as a continuous sheet, not a decal picked at random per cell. A
  single-face cell is `ConnectedPaintBlock`: server-side properties for its face and four in-plane
  connection bits — which of its floor/wall neighbours hold the same colour on the same face —
  recomputed from those neighbours every time one changes. The bits travel to vanilla clients as
  the low nibble of a texture's red channel, and the fragment shader turns them into a rounded,
  gently wobbling edge wherever the sheet actually ends, so there are no edge tiles to draw and the
  border is a smooth curve at any resolution (see the shader bullet below). A cell painted on more
  than one face (a corner: floor plus wall in the same air cell) falls back to `PaintBlock`, the
  plain multiface splat that carries no bits.
- Both block kinds are sent to vanilla clients as blockstates borrowed from four donors that render
  whatever the pack says and, once picked clean of the states that would misbehave (waterlogged,
  unlit, powered), have no collision, emit no light and have no client-side behaviour:

  | donor | usable states | note |
  |---|---|---|
  | sculk vein | 63 | six face booleans; waterlogged and the all-false state excluded |
  | resin clump | 63 | same |
  | tripwire | 128 | attached × disarmed × powered × n/e/s/w; thin outline shape only |
  | redstone wire, power 0 | 81 | only power 0 is silent and carries no dust tint |

  335 usable states in all; 306 are in use (153 per colour: 96 connected face×bits combinations, then
  57 corner masks). Which donor state stands for which paint state is chosen by *shape*, because the
  client draws the targeted-block outline from the borrowed state and no resource pack can change it:
  corner cells take the multiface state whose face flags are exactly their mask, floor cells take
  tripwire's flat full-square slab, wall cells take a redstone-wire state with a full-height strip up
  the face the paint is on (eight of each colour's sixteen per direction — that is every strip state
  there is), and the rare ceiling cells take tripwire's half box. Glow lichen was considered and dropped — it lights every
  state that has a face, which would make paint glow. The v1 caveat still applies, now for four
  blocks instead of one: real sculk veins, resin clumps, tripwire and redstone dust a player places
  in an arena render as paint too.
- Every ink burst — muzzle flash, impact splash, the rays off it, the charger's trail, a squid's wake
  — is made of vanilla's block-break crumbs carrying one of the paint client states, not redstone
  dust. Dust reads as a drifting grey-red haze; a crumb is a lump that arcs and falls, which is what
  thrown liquid does. The state is always a multiface one (sculk vein for DATA, resin clump for IT):
  the client takes the sprite from that state's model `particle` texture, which the pack points at the
  team's paint tile, and a redstone-wire-backed state would have been tinted dark red by vanilla's own
  colour provider instead. Crumbs are much bigger than grains, so every burst count is about half what
  the dust counts were.
- A swimming squid leaves a wake: a few crumbs of its own ink at its feet on every tick it is
  actually moving (measured between ticks, because a real player's server-side delta is zero most
  ticks), a soft swim note every sixth such tick, and a ring of specks thrown outwards on the dive. A
  squid holding still leaves nothing, so the trail reads as movement rather than as a marker saying
  where someone is hiding.
- Squid form also hides the held items from everyone else. Vanilla invisibility hides the body but not
  what it is carrying, so without this a squid reads to an enemy as a gun floating across the floor.
  Every squid tick sends the players tracking that squid — never the squid itself, which still wants
  to see its own gun — an equipment packet with empty hands and armour; the tick it stops being a
  squid sends the real one back.
- Shots hurt. A direct hit on someone from another team takes hearts off them as well as painting the
  ground under their feet, and since round 7 **every pellet lands its own damage**. Vanilla keeps a
  twenty-tick window after a hit and, for the first ten of it, applies only the *excess* over the last
  one — which is why a slosher's two pellets used to be worth one and a three-tick shooter lost two
  shots in three. `PaintDamage` stands in front of every paint hit and takes the window off before and
  after it (`LivingEntity.damageCooldownTime = 0`, which is also the branch that stops `lastHurt`
  gating anything), so a bucketful is a bucketful. Knockback is deliberately left vanilla, so two
  pellets do shove twice. Teammates take the paint and nothing else, no team at all counts as fair
  game, and a squid is an ordinary player here — squid form is cover, not armour. Kills are attributed
  to the shooter.
- Damage is Splatoon's, and it falls off. A shot is worth its full damage for the first few ticks of
  flight and then loses a slice a tick down to a floor, read at the hit rather than baked in at the
  throw: a shooter's ball is 8 up close and 4 across a courtyard. A weapon that says nothing about
  falloff — the slosher, the splat bomb — is flat.
- One colour per cell: a hit in another colour wipes the cell and starts it over as a single
  connected face in the new colour, even if the old cell held paint on more than one face.
- Four weapons, one item class (`PaintWeapon`) parameterised by a `Weapon` enum, given with
  `/rivals gun <shooter|charger|slosher|roller>` (default shooter) or all at once with
  `/rivals kit`. **Every number is Splatoon 1's**, read off Splatcraft (MIT), whose
  `data/splatcraft/weapon_settings/*.json` is that game's figures on the scale this module already
  uses: 20 hit points, a hundred-unit ink tank, twenty ticks to the second. Each default in `Weapon`
  names its source file beside it.

  | Weapon | Ink | Cadence | Damage | Shot | Splatcraft |
  |---|---|---|---|---|---|
  | shooter | 1 | 3 ticks (held) | 8, −0.34/tick from tick 3, floor 4 | one ball at 2.0 straight for 8 blocks, then 0.5 falling at 0.075; one bounce; 3×3 splat; spread 6° on the ground, 12° in the air | `splattershot.json` |
  | charger | 2 → 18 | 20 ticks | 8 → 16 over a partial charge, **32 at a full one** | hold right click to aim (the spyglass scope; a full charge is 20 ticks), left click to fire a hitscan line of 9 → 24 blocks, stopped by the first block or player in it | `splat_charger.json` |
  | slosher | 7 | 12 ticks (click) | 7, flat | 2 pellets 8° apart, lobbed 15° up at 1.1 under gravity 0.06, 5×5 splat, no bounce | `slosher.json` |
  | roller | 9 a flick, 1 per 16 ticks rolling | 15 ticks after a flick | flick 30, −3.45/tick from tick 8, floor 7; roll 25 | **hold** right click to roll a 3-wide strip where you walk, with 8% more speed and a head that runs over anyone in front once per 10 ticks — both only while you are actually moving, so a roller parked in a doorway is not a wall of damage; **tap** it to flick 3 drops in a high arc | `splat_roller.json` |
  | splat bomb | 70 | 4 s of its own | 36 at the centre → 6 at 3.25 blocks | thrown 30° up at 0.75, bounces where it lands and goes off 20 ticks later | `splat_bomb.json` |

  Standing in your own ink refills the tank in ten seconds on your feet and three as a squid, and a
  weapon that has just fired waits its own `refill_delay` first (7 for a shooter, 15 for a roller —
  Splatcraft's `ink_recovery_cooldown`).

  **Controls.** Right click fires. The shooter and the roller are *held*: a vanilla client repeats a
  held right click only every four ticks, which is not a fire rate a shooter can have, so the press
  starts using the item and `Item#onUseTick` does the work every tick until the button is let go — the
  shooter fires whenever its cooldown is up, the roller rolls. The slosher stays a click, because
  twelve ticks is slower than the client's repeat anyway. The roller's release is its other gesture:
  under six ticks it was a tap and throws the bucketful, longer and it was a roll and throws nothing.
  The charger is the exception on both counts: right click is its scope and letting go fires nothing,
  and left click is its trigger — the charge it has built if it is scoped, a snap shot at no charge if
  it is not.

  Holding an item in use costs a vanilla player their sprint and four fifths of their speed — a bow's
  behaviour, read client-side off the `minecraft:use_effects` component — so the shooter and the roller
  carry their own: the shooter keeps sprinting at 72% (about what firing costs in Splatoon) and the
  roller at 100%, since its `roll_speed` attribute is what decides how fast a roll is. The charger is
  left on vanilla's, because being pinned in place is what its damage is paid for.

  Left click throws a **splat bomb** on everything but the charger: a slow lob that bounces where it
  lands and counts twenty ticks down there rather than going off on contact, so it is a thing you can
  run away from and throwing one is a decision about where someone will be. It splashes a 7×7 patch
  and takes 36 hearts off at the centre falling to 6 at three and a quarter blocks (linear in distance
  squared, no line-of-sight test), for 70 ink and a four-second wait of its own — separate from the
  fire cooldown, so the trigger is never held up by it; all of it is tunable as `special_*`.
  Server-side, a left click arrives as up to two packets in the same tick — an attack
  on the block or entity under the crosshair, then a swing — so Fabric's `AttackBlockCallback` and
  `AttackEntityCallback` (both returning `FAIL`, so a paint weapon never breaks the arena or
  punches anyone) and a mixin on `handlePunch`, 26.3's replacement for the swing packet, all go
  through one `PaintWeapon.leftClick` that answers the first of the tick and ignores the rest. The
  client sends the punch even while an item is in use, which is exactly what lets the charger aim
  with one button and fire with the other; it does not repeat it while the button is held, so a
  left click is one special.

  Only the slosher swings the arm on use — it's a bucket, and the throw reads as one — so its
  `use` returns `SUCCESS_SERVER` (the server broadcasts the swing, including to the thrower); the
  other three return `CONSUME`, which takes the click without animating the hand, since a
  four-tick swing loop on a rapid-fire weapon looks like a stutter rather than firing.
- Every paint ball is a snowball entity hidden from clients, with a Polymer item display — a
  rounded, dyed blob model (a cube cut back to an octagon in all three planes by three 45° bands),
  not the vanilla firework-star particle — riding along on an attachment. It squashes and
  stretches as it flies: each tick the model's local up is turned onto the velocity and the blob
  is drawn out along it by its speed, losing across what it gains in length, so the volume reads
  constant. The shooter's ball keeps two bounces: on a block hit it splashes, reflects off the hit
  face at 62% of its speed, pancakes flat against that face for two ticks before easing back into
  its flying shape over three more, throws off two short-lived single-face droplets along the
  reflection (droplets never throw droplets of their own) with a wet slime step, and keeps flying
  until the impact after the last bounce spends it; slosher pellets and roller flicks do not bounce. On
  impact (or the final bounce) it splashes: the usual blob on the struck face (3×3 for the shooter, 5×5
  for the slosher), plus fourteen short rays from the impact point (six axis directions and eight
  diagonals) that paint whatever face they hit, so a floor shot next to a wall also paints the
  wall and fills in the corner. A coloured dust burst and a wet impact sound go with it; the burst
  is sized to the splat (4 grains for a single face, 10 for a 3×3, 16 for a 5×5, with ray dust only
  from radius 1 up), so a single-face droplet at point-blank range does not fill its own screen. Colour comes
  from the shooter's vanilla team, whose name is the colour id.
- Firing has a kick: the client's pitch is nudged up on the shot and eased back down two ticks
  later (scaled to the charger's charge), plus a small push, a muzzle particle burst and a
  layered sound (a low slime step added under the slosher's throw for weight). Recoil packets
  only reach real connected players; mock players (game tests) are unaffected. The muzzle burst
  goes to every viewer *but* the shooter (per-player particles, 32 blocks): in first person those
  grains hang in the middle of the camera. The shooter gets three small ones at the barrel tip
  instead, offset right and down out of the crosshair. The charger's trail starts its dust 1.5
  blocks along the shot for the same reason; the paint under the line still starts at the eyes.
- **Paint on other shapes.** Paint blocks only ever sit on a full face — the same attach rule
  vanilla's own multiface blocks use, and exactly the rule paint wants. A face that isn't full
  (stairs, slabs, fences, panes, walls, glass panes) instead gets a set of flat quads that wrap the
  block's own outline shape (not its collision box, so paint on a fence sits on top of the post, not
  floating at collision height). Each quad is a Polymer **block display carrying the paint state
  itself** — `PaintStates.connected(colour, attach, bits)`, the very client state a painted cell
  carries — so a quad on a slab is the same material as the paint block beside it, borders against it,
  and is drawn by the same shader code (below). Placing one needs no rotation: the paint state's attach
  direction already puts the model's single quad against the right side of the display's unit cube, so
  it is the box's face plane on the face axis and the box's own extent on the other two. The
  connection nibble counts both kinds of neighbour — paint blocks through
  `ConnectedPaintBlock.neighbourBits`, and neighbouring quad cells of the same colour on the same face
  — and because nothing sends a block display a neighbour update, `Painter` re-states the quads around
  every cell it paints (and the sweep does it for every cell it drops), so a newly painted neighbour
  opens their border within the tick. It works both ways round: `ConnectedPaintBlock.neighbourBits` also
  counts quad cells, so a paint block beside a slab opens its own edge towards the quads rather than
  leaving a one-sided seam. At most three quads per cell, the largest boxes on the struck side, so a wall post
  with four arms doesn't put a dozen displays in one cell. These quads aren't blocks, so nothing tells
  them to fall on their own: they are dropped, and stop being counted, once their surface is destroyed,
  replaced, buried, or merely changes shape (a stair turned under them), or once its chunk unloads — a
  chunk unload takes the paint with it, and unlike a paint block it does not come back when the chunk
  reloads. Entity lighting is one sample with no ambient occlusion, so a quad is lit a little more
  flatly than the block paint around it. (Before v6 these were item displays of a white sprite tinted
  with the team colour: the right silhouette, but a different material from the block paint and no way
  to border against it.)
- Paint on a full block face is drawn by the pack's own art and the shader (below), not a Kenney
  silhouette. Per colour there are 16 uniform 16×16 textures — the paint colour, alpha 235 (the
  gloss shader's marker: the window 233..237 is the one band in 200..254 that no vanilla block texture
  has a texel in, with 232 on `nether_portal` and 238 on `frosted_ice` the nearest values that exist), and the four connection bits packed into the low
  nibble of the red channel (`r = (base & 0xF0) | bits`) — plus six shared one-quad models, one per
  attach direction, each 0.1/16 off the face like the multiface donors. A blockstate `variants` file
  per donor block maps every one of its states to either a wrapper model (a connected cell's face
  quad with its (colour, bits) texture) or a mask model (a corner cell's quad-per-face, all on the
  all-connected texture); states paint doesn't use point at an empty model. The override replaces the
  donor's whole vanilla blockstate file, so those unused states render *nothing at all* — a sculk
  vein, resin clump, tripwire or redstone dust a player places in an arena is invisible under the
  pack, and so is powered redstone dust, wiring and all.
  The display quads need no art of their own: they show a paint state, so they resolve to the same
  wrapper model and the same bit-carrying texture a painted cell does.
- The pack also overrides `assets/minecraft/shaders/core/terrain.vsh`/`terrain.fsh` — the pair that
  actually draws chunk geometry in 26.3 — inside a guard on that alpha marker. It reads the four
  bits back out of the texel's red channel, works out the face's two in-plane axes from
  `cross(dFdx(chunkPos), dFdy(chunkPos))`, and computes the signed distance to a rounded box in that
  plane: unconnected sides are inset (with a slow time-and-position wobble) and rounded at a corner
  only where both sides meeting there are unconnected, while connected sides run out past the cell
  so the seam to the next sheet is invisible; a fragment outside that box is discarded, which is what
  draws the border with no baked edge tile at any resolution. Everything that decision and the look
  depend on — the in-plane cell coordinate, the wobble's unwrapped coordinate and the position the
  waves and highlights are sampled at — is first snapped to the centre of its 1/16-block texel, so the
  paint reads as pixel art on the vanilla grid: stepped corners, a wobble that moves in whole texels
  (time stays continuous, so texels flip rather than slide) and blocky highlights. Inside the border it keeps v3's liquid
  pass — a meniscus rim lit toward the light on the shape's outer edge, a moving three-wave normal,
  and glint/sheen/fresnel mixed toward white — now computed from that same distance field instead of
  sampling neighbour texels; every other texel keeps vanilla's shading byte for byte. (v2 keyed this
  into `block.vsh`/`block.fsh`, which chunk terrain never runs through, so the gloss never rendered;
  those overrides are gone.) Known limit: a shader pack (e.g. Iris) replaces the core shaders
  wholesale and loses the gloss.
- The pack overrides `assets/minecraft/shaders/core/item.vsh`/`item.fsh` as well, with the same gloss
  block behind the same marker guard, because that is the pair that draws the display quads: both
  display kinds render block models through `Sheets.cutoutBlockItemSheet()`, which is
  `RenderPipelines.ITEM_CUTOUT`, which is `core/item` — not terrain, not entity. Two varyings carry it.
  `viewPos` is the view-space position, for the normal (from its derivatives) and the view vector;
  `rawColor` is the vertex tint before vanilla's directional light, which the chunks never get — without
  that, display paint came out visibly darker than the paint block beside it (the lightmap and the fog
  still apply, exactly as for terrain).

  The in-face coordinate comes off the **sprite**: `fract(texCoord0 * textureSize(Sampler0, 0) / 16)`.
  terrain.fsh can use `chunkPos` because chunk geometry is stored chunk-relative, but a display's
  vertices are baked by the render PoseStack with the camera rotation already in them — there is no world
  position in the item pair to take `fract()` of. (v6 first tried to rebuild one from the `Globals`
  camera; on a village path it drew borders across the middle of cells, each quad a random blob with
  holes.) The sprite works because the paint sprites are 16×16 and the stitcher lays equal-size sprites
  out on multiples of their own size; the one setting that breaks it is anisotropic texture filtering,
  which asks the stitcher for padding around every sprite and so shifts the pattern inside the cell.
  Which way round the sprite lies is decided by the `uv` array `PaintArt` writes per face — vanilla maps
  a face's u and v to world axes differently per face, so each of the six gets the flip that lines its
  sprite up with the paint's own in-plane axes. The wobble and the waves then run on the cell's own
  coordinate with the connection bits shifting their phase, so the pattern repeats from cell to cell;
  that shows only where a cell ends, and where a cell ends there is a border anyway.

  Held items, dropped items and the inventory come through untouched: no texture in either atlas an
  item pipeline draws — blocks and items — carries an alpha anywhere in the 233..237 the guard admits at
  mip 0 (a handful average to 236 at mip 3, which is noted in the shader).
- **Ink on your screen.** Taking enemy paint in the face throws ink over the player's view: blobs in
  the enemy's colour at fixed pseudo-random places, growing and dripping as the meter fills, edges
  wobbling on `GameTime`, every edge snapped to a 4-pixel grid for the pixel-art look, and the middle
  of the screen left clear so the reticle stays readable. At full it covers about 45% of the screen.
  `InkOnScreen` keeps the meter server-side: 25 per point of damage from an enemy weapon (the victim's
  screen, in the shooter's colour), 2 a tick standing in enemy ink, 4 a tick off on the ticks nothing
  added any, clamped to 0..255, and cleared outright on nought, on death, on spectating, on leaving the
  teams and on logging out. All five numbers are constants at the top of that class.

  It is drawn by a post effect, which is where the interesting part is. A server-side mod cannot run
  client code, but 26.3's `GameRenderer.update` asks for the post effect `minecraft:end_of_frame`
  *every single frame* and drops the request silently when no pack defines it — so a pack that does
  define it gets one full-screen pass per frame, with nothing to trigger and nothing to switch on.

  It cannot set a uniform either, so the number has to be *in the frame* — and in the frame before the
  effect runs. `GameRenderer.render` calls `renderLevel()`, then `applyPostEffects()`, and only then
  `GuiRenderer.render()`, so nothing on the HUD (a title, the action bar) is on the target the effect
  samples; what is on it is the held item, drawn inside `renderLevel` by `renderItemInHand`. So the
  meter rides the weapon. Every gun model carries a **data LED**: one model pixel, six faces on a
  dedicated 16×16 texture whose alpha is 246, tinted by
  `custom_model_data` colour 0. 246 is in 245..247, one of only three three-wide alpha bands that no
  texel of any blocks- or items-atlas texture reaches at *any* mip level 0..4, so the item shader can
  recognise it: it takes the unlit vertex tint (`rawColor`, a varying our `item.vsh` adds) and writes it
  to the frame exactly, before lighting, fog and the paint gloss. `InkOnScreen` writes
  `(255, team, amount)` into that component — red at full with green under 16 is the signature, green's
  low nibble picks the ink colour, blue is the amount — and `PaintWeapon.inventoryTick` is the one place
  it reaches the stacks, in the same tick that keeps the tank dyed, so a weapon stowed with a full screen
  cannot come back out still carrying a live number. With no ink the value is `InkOnScreen.IDLE`, a dark
  grey, and the item shader **discards** it outright: an LED with nothing to say draws nothing at all.

  And the LED is not on the gun. It is a slip of a box floating free of it, placed so that each weapon's
  own `firstperson_righthand` transform lands it *at the bottom centre of the screen, inside the hotbar*
  — which the GUI draws after the post effect has read the frame, so the probe reads it out of the frame
  and the player never sees it. `tools/weapon_models.py` solves that placement per weapon by walking the
  same chain the client walks (a fixed 70° vertical FOV for the hand pass from `Camera.calculateHudFov`,
  `applyItemArmTransform`'s `(±0.56, −0.52, −0.72)`, then `ItemTransform.apply`'s
  translate·rotate·scale·translate(−½)) and inverting it; the arithmetic is checked against Julle's own
  in-game screenshot, and a game test re-walks it and asserts the box is inside the hotbar's 182×22 GUI
  pixels at GUI scale 1. Move a display transform and the script has to be run again. The two knock-on
  costs: in third person, or in the inventory, the holder's own weapon has a lit pixel floating away from
  it (nobody else's does — every other viewer is sent the idle value, which is discarded).

  The value only ever goes to the holder's own client: Polymer's per-viewer item hook hands every other
  player the idle colour, because a lit LED on someone else's gun is both a tell and a false reading — the
  probe would find it on their third-person weapon and splatter the finder's screen. The item definitions
  also turn `hand_animation_on_swap` off: a component change is a stack change, and without that the client
  replays the equip animation every time the meter (or the tank's dye) moves.

  The chain's first pass renders to a **one-by-one** target, so the hunt for the LED runs once a frame
  rather than once a pixel — and since round 7 it is a hunt through one small box rather than half the
  screen: the bottom 8% of the height and the middle 30% of the width, which is where the placement above
  puts the LED and nothing else. Stepped at 0.6% of the screen height, that is about 1 300 samples a
  frame at 1080p, and each match is confirmed by a second sample a short hop to either side, so a red
  pixel in the world is never mistaken for it and a scan column landing near the LED's edge never loses
  it. The pass hands on the amount and the team, and nothing else: there is no longer anything for the
  ink pass to paint over, because the hotbar does it.

  **The ink itself is four textures**, not a field of procedural blobs — two flat tones and a signed
  distance function had no depth and did not read as pixel art. `textures/post/ink_1.png` …
  `ink_4.png` are 320×180 RGBA overlays, one per quarter of the meter, bound to the ink pass as
  `PostChainConfig` texture inputs with `bilinear: false`. **Alpha is coverage** and is only ever 0 or
  255; **RGB is a greyscale shading map**, which the shader steps into four tones of the *team's*
  colour (under 0.30 → the colour at 55%, under 0.60 → the colour, under 0.85 → a quarter toward
  white, above → 60% toward white), so one drawing serves both teams. Sampled at texel centres with no
  filtering, so a texel is a fat block of screen pixels — the texture *is* the pixel grid. State 1 is a
  little ink around the edges, state 4 is nearly covered with the middle still clear, and ink always
  creeps in from the sides. They are ordinary resources: an artist paints over them and nothing else
  changes. The format is written out in `textures/post/README.md`, and `tools/ink_overlays.py` (Pillow)
  drew the placeholders that are checked in.

  Known limits: no weapon in view means no ink, so third
  person and an empty hand show a clean screen however full the meter is (the meter keeps running, and
  the ink comes back with the weapon); the pass runs every frame whether there is ink or not (two
  full-screen passes' worth of work); and a shader pack that replaces the post chain loses the effect,
  exactly as it loses the gloss.
- The guns' 3D models live under `assets/metacraft-rivals/models/item/`; the ink faces are dye-tinted
  to the team colour. All four are Julle's hand-built Blockbench models, delivered under
  `tools/julle/` (two snapshots, each with its masters, previews and a ready resource pack) and
  copied in as `paint_gun.json` (their shooter — the registry id is v2's and stays), `charger.json`,
  `slosher.json` and `roller.json`. The only edits on the way in are the texture ids — one shared
  128×128 pair, `julle_body` and `julle_ink`, instead of `splat:item/shooter_*` — and the ink LED
  element. Their `display` transforms are kept verbatim: they were authored against in-game
  first- and third-person screenshots (see `tools/julle/*/previews/`), and the LED's placement is
  computed from the first-person one, so changing a transform moves the LED with it.
- Ink faces take `tintindex: 0`, which our `items/*.json` feeds from `minecraft:dye` — the same
  component `PaintWeapon.withTankColor` writes every inventory tick — and the LED takes
  `tintindex: 1` off `custom_model_data` colour 0. Fixed lime, ivory and dark parts stay untinted.
- Ink: every gun holds one shared tank of **100**, which is Splatoon's own scale — every cost in the
  table above is that game's percentage with no factor in front of it — tracked in the stack's own data
  so it survives item moves, and clamped on the way out so a stack written when the tank was 40 comes
  back part-full rather than wrong. Trying to fire on a tank that can't cover the shot starts a 30-tick
  refill (sound, cooldown) that fills the tank the moment the deadline passes. Standing in your own
  colour's paint tops the tank up at Splatoon 1's rates — ten seconds on your feet, three as a squid —
  but not while the weapon is still inside its own `refill_delay` after a shot. An action-bar ammo bar
  in the team colour refreshes every 10 ticks and after every shot, rounded to ten cells
  (`INK ██████░░░░ 61/100`), and reads
  `REFILLING…` or adds `SQUID` as appropriate. A scoped charger adds its charge to the same line and
  refreshes every tick instead of every ten (`INK ████░░ 26  CHARGE ▮▮▮▯▯▯ 48%`), in bold yellow at
  100%: the spyglass zoom says you are aiming and nothing else said how long you had been at it.
- Sneaking on your own colour's paint is squid form, and it's now a real mechanic rather than a
  cosmetic buff: half size, +80% movement speed with the vanilla sneak penalty lifted (net faster
  than sprinting), a big ~2.5-block hop with a floatier fall and no fall damage from it, and a
  taller step (seven attribute modifiers, not potion effects, so they're exact and don't show up
  in the client's effect list), plus vanilla invisibility — all reapplied every tick so they fade
  within a second of leaving the paint or standing back up — and the gun refuses to fire while
  it's active. Diving into squid form from a stand gives a snappy horizontal surge and a quiet
  splash. Squid form also holds beside a wall face painted in your own colour even with no paint
  underfoot, so a climb off the floor paint doesn't drop you mid-wall: pushing into that wall
  swims you straight up it for as long as the ink goes, with a nudge over the lip at the top, and
  easing off clings in place instead of sliding back down. While the form is on, everyone else sees a
  team-coloured blob riding your feet — the paint ball's own model, turned along the way you are going
  and stretched by how fast — except when you are lying still in your own ink, which is how a squid
  hides: then it shows nothing at all, and comes back the moment you move. You never see your own. "Pushing into" is your own movement keys
  rather than the server noticing a collision — walking into a wall is clipped client-side, so the
  server never sees one, which is why the climb used to stall a block up. Standing on an enemy
  colour's paint is a trap instead: Slowness II, no jumping at all, and half a heart of damage
  every second that never brings you below one health.
- Score: bossbars show each colour's share of painted faces across all levels — paint blocks and
  surviving display quads alike — counted once a second from the cells the painter has touched (in
  memory; a restart forgets them). `/rivals score` counts only the level it is run in, and names
  that level in its reply.

## Play

```
/rivals setup            teams data and it
/team join data @s
/rivals gun              shooter, the default
/rivals gun slosher      or charger / roller
/rivals kit              one of every weapon
/rivals score
/rivals reset
```

### Tuning

Every number a shot is made of is adjustable from inside the game, per weapon, and lands on the next
click — no restart, no reload:

```
/rivals tune                            everything that is off its default
/rivals tune shooter                    one weapon's whole sheet, defaults in [brackets]
/rivals tune shooter velocity           one number
/rivals tune shooter velocity 2.4       set it; the reply says what it was
/rivals tune shooter reset              that weapon back to its defaults
/rivals tune reset                      all four back to theirs
```

Weapon ids and parameter names both tab-complete, and a name that does not exist answers with the
ones that do.

| group | parameters | who reads them |
|---|---|---|
| the shot | `velocity`, `spread`, `spread_air`, `straight_blocks`, `decayed_speed`, `gravity`, `bounces`, `restitution`, `lifetime`, `splat_radius`, `count`, `fan_yaw`, `fan_pitch` | the three that throw a ball |
| the cost | `ink`, `cooldown`, `refill_delay`, `kick` | all four |
| the damage | `damage`, `decay_start`, `decay_per_tick`, `decayed_damage` | the three that throw a ball |
| a bounce | `spatter_count`, `spatter_lifetime`, `spatter_speed`, `spatter_scatter`, `spatter_damage` | the three that throw a ball |
| the splat bomb | `special_ink`, `special_cooldown`, `special_refill_delay`, `special_radius`, `special_damage`, `special_edge_damage`, `special_blast`, `special_fuse`, `special_velocity`, `special_gravity`, `special_lifetime` | everything but the charger |
| the roll | `roll_width`, `roll_damage`, `roll_hit_cooldown`, `roll_ink_every`, `roll_speed`, `flick_tap` | the roller alone |
| the charge | `charge_min`, `charge_full`, `range_min`, `range_full`, `charge_ink_min`, `charge_ink_full`, `charge_damage_min`, `charge_damage_partial`, `charge_damage_full` | the charger alone |

`straight_blocks` and `decayed_speed` are the shot's shape — how far it flies straight and fast, and
what it drops to after that — and `decay_start` / `decay_per_tick` / `decayed_damage` are how the
damage falls off with time in the air; a `decay_per_tick` of 0 is a weapon that does not care how far
it has thrown. `refill_delay` belongs to all four, and a splat bomb waits `special_refill_delay`
instead — its own, not the weapon it was thrown from, because seventy ink of a hundred is not a
shooter's shot.

The charger's `range_*` and `charge_ink_*` run from no charge to a full one with everything between
interpolated. Its **damage does not**, and that is deliberate: `charge_damage_min` → `charge_damage_partial`
is the line a partial charge climbs (8 → 16), and `charge_damage_full` (32) is a *step* taken the moment
the charge is full. Splatoon's charger is built on exactly that discontinuity — held to the top it
splats, let go a moment early it does not — and a straight 8 → 32 would make every fraction of a charge
worth its fraction of a kill, which is a duller weapon. The charger takes no `special_*`, because its
left click is its shot rather than a bomb.

Every parameter has a range, which `/rivals tune <weapon>` prints beside it and a refusal states:
several of them are loop bounds and spawn counts, so a `splat_radius` of 500 (a million block writes
in one splat) or a `count` of 5000 is refused rather than clamped. A value out of range in the file is
clamped with a warning, and `NaN`/`Infinity` — which a hand edit can get past the json parser — is
ignored with one.

The tuning lives in `config/metacraft-rivals/weapons.json` and is written after every change. Only
what differs from the defaults is kept, so a fresh file is `{}` and a default changed in the code
still reaches everyone who never touched it. The defaults themselves are the constants in
`Weapon.java` and `PaintBall.java`; a missing or unreadable file is a warning in the log and the
defaults stand.

Or just get dressed: wearing an ovvar ovve puts you on that chapter's team within the second, creating
the teams if nobody has run `/rivals setup` yet. Matched on the item's registry id (`ovvar:data_*` →
DATA, `ovvar:it_*` → IT), so there is no dependency on ovvar and an arena without it plays exactly as
before; taking the ovve off leaves you on the team you were on.

## Build, run, test

```
./gradlew mods:metacraft-rivals:build -x mods:metacraft-lib:test  # lib unit tests fail on dev for unrelated reasons
./gradlew mods:metacraft-rivals:runServer      # needs two runs on a fresh clone, see below
./gradlew mods:metacraft-rivals:runGameTest    # server-side game tests (68 of ours, plus vanilla's always_pass: 69 in total)
```

`run/` is gitignored, and the `eula = true` in `build.gradle` applies only to the game-test run, so
on a fresh clone `runServer` takes two passes:

1. Run it once. That creates `run/` (with `eula.txt` and `server.properties`) and the server stops.
2. Set `eula=true` in `run/eula.txt` and `online-mode=false` in `run/server.properties` (a fresh
   `server.properties` has `online-mode=true`), then run it again.

Polymer's pack autohost is on by default in the dev environment, so the required pack is served
without any extra setup.

Join the dev server with a vanilla 26.3-rc-1 client (offline mode) and accept the pack.

To drive a running dev server without a client attached (`/rivals setup`, `/op <name>`, a score
check), `tools/rcon.py` sends one command over RCON and prints the reply — standard library only,
no dependency to install:

```
python3 mods/metacraft-rivals/tools/rcon.py 'rivals setup'
```

It defaults to `127.0.0.1:25576` with the password `rivals-dev` (override with `RCON_HOST`,
`RCON_PORT`, `RCON_PASSWORD`). RCON is off in a fresh `server.properties`, so add these three
lines to `run/server.properties` alongside `online-mode=false` in step 2 above, then restart:

```
enable-rcon=true
rcon.port=25576
rcon.password=rivals-dev
```

## Credits

All four weapon models — shooter, charger, slosher and roller — are Julle's, delivered in
`tools/julle/` with their Blockbench masters, their shared 128×128 `julle_body`/`julle_ink` atlas and
their display transforms, which ship verbatim. In their own words: *original fan-made geometry;
unofficial fan models inspired by Splatoon. Splatoon belongs to Nintendo; Minecraft belongs to
Mojang/Microsoft.* The paint art — block textures and the display quads' sprite alike — is generated
by the mod. Kenney's Blaster Kit supplied the weapons up to round 6 and Kenney's Splat Pack the
display quads up to round 5; neither is shipped any more.

`tools/splatcraft-rp/` is **SplatCraft RP by SculK3d**, from the "SplatCraft Map + RP" bundle, under
**LGPL-3.0** (its own `LICENSE.LGPL-3.0` and `ATTRIBUTION.md` are kept with it). It is source material,
not something the mod ships: it is where the squid item model, the splat bomb model and the ink tank
textures are being read from. Nothing in `src/main/resources` is copied out of it as things stand, and
anything that ever is inherits those terms.

## Not yet

Arena bounds and a round loop; persisting display quads and the tally across a restart; damage on
enemy paint (beyond the enemy-ink drip); a real squid model; Iris-compatible gloss; respawn/death
handling for the drip; weapon-switching UI.
