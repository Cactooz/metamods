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
connected paint, the shader-drawn border); the gun's design sheet is next to the v1 spec. v6 added the
two visual features described below: paint on non-full blocks as block displays of the real paint
state, and ink on the screen from damage taken.

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
  ground under their feet: 3 from a shooter's ball, 1 from a sprayer droplet, 4 from a slosher ball, ½
  from a bounce droplet, and 4 + 6 × charge from the charger's line, which makes a full charge the
  hardest hit in the game. The multi-projectile weapons do not add up: vanilla's post-hit
  invulnerability window means a fistful of droplets arriving together is worth one of them, so the
  sprayer and the slosher pay for their spread in coverage rather than in damage. Teammates take the paint and nothing else, no
  team at all counts as fair game, and a squid is an ordinary player here — squid form is cover, not
  armour. Kills are attributed to the shooter.
- One colour per cell: a hit in another colour wipes the cell and starts it over as a single
  connected face in the new colour, even if the old cell held paint on more than one face.
- Four weapons, one item class (`PaintWeapon`) parameterised by a `Weapon` enum, given with
  `/rivals gun <shooter|sprayer|charger|slosher>` (default shooter) or all at once with
  `/rivals kit`:

  | Weapon | Ink/shot | Cooldown | Shot |
  |---|---|---|---|
  | shooter | 1 | 4 ticks | one ball, two bounces, 3×3 splat |
  | sprayer | 1/click | 4 ticks | 3 short-lived droplets in a cone, single-face splat + rays, no bounce |
  | charger | 4 + 8 × charge | 20 ticks | hold right click to aim (the spyglass scope; the charge builds for up to 20 ticks), left click to fire a hitscan line, stopped by the first block or player in it |
  | slosher | 15 | 14 ticks | 4 balls in a fan, gravity-heavy lob, 5×5 splat, no bounce |

  **Controls.** Right click fires (hold for the shooter and sprayer). Left click throws a splat
  bomb. The charger is the exception on both counts:
  right click is its scope and letting go fires nothing, and left click is its trigger — the charge
  it has built if it is scoped, a snap shot at no charge if it is not. The splat bomb is a slow,
  fat, no-bounce lob that splashes a 7×7 patch where it lands and takes 6 hearts off anyone from
  another team within two blocks of it, for 25 ink and a four-second wait of its own (separate from
  the fire cooldown, so the trigger is never held up by it; all seven numbers are tunable as
  `special_*`). Server-side, a left click arrives as up to two packets in the same tick — an attack
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
  until the impact after the last bounce spends it; sprayer and slosher shots don't bounce. On
  impact (or the final bounce) it splashes: the usual blob on the struck face (3×3 for the shooter and sprayer, 5×5
  for the slosher), plus fourteen short rays from the impact point (six axis directions and eight
  diagonals) that paint whatever face they hit, so a floor shot next to a wall also paints the
  wall and fills in the corner. A coloured dust burst and a wet impact sound go with it; the burst
  is sized to the splat (4 grains for a single face, 10 for a 3×3, 16 for a 5×5, with ray dust only
  from radius 1 up), so a sprayer at point-blank range doesn't fill its own screen. Colour comes
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
  ink pass to paint over, because the hotbar does it. Known limits: no weapon in view means no ink, so third
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
- Ink: every gun holds 40 shots in one shared tank size, tracked in the stack's own data so it
  survives item moves. A shot costs the weapon's own ink (see the table above — 1 for the
  shooter/sprayer, 4 plus up to 8 more for the charger's charge, 15 for the slosher) and a splat
  bomb costs 25, most of a tank but not all of it; trying to
  fire on a tank that can't cover the shot starts a 30-tick refill (sound, cooldown) that fills
  the tank the moment the deadline passes. Standing in your own colour's paint tops the tank up
  over time, faster in squid form. An action-bar ammo bar in the team colour refreshes every 10
  ticks and after every shot, rounded to ten cells (`INK ██████░░░░ 24/40`), and reads
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
/rivals gun slosher      or sprayer / charger
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
ones that do. The three ball weapons take `velocity`, `spread`, `gravity`, `bounces`, `restitution`,
`lifetime`, `splat_radius`, `ink`, `cooldown`, `kick`, `damage`, `count` (balls per shot), `fan_yaw`
and `fan_pitch` (the fan those balls go out in), and `spatter_count` / `spatter_lifetime` /
`spatter_speed` / `spatter_scatter` / `spatter_damage` (what a bounce throws off), plus the splat
bomb's own `special_ink`, `special_cooldown`, `special_radius`, `special_damage`, `special_velocity`,
`special_gravity` and `special_lifetime`. The charger, which
throws no ball, takes `ink`, `cooldown` and `kick` plus its own `charge_min`, `charge_full`,
`range_min`, `range_full`, `charge_ink_min`, `charge_ink_full`, `charge_damage_min` and
`charge_damage_full` — the `*_min` at no charge, the `*_full` at a full one, with everything between
interpolated. It takes no `special_*`: its left click is its shot, not a bomb.

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

## Not yet

Arena bounds and a round loop; persisting display quads and the tally across a restart; damage on
enemy paint (beyond the enemy-ink drip); a real squid model; Iris-compatible gloss; respawn/death
handling for the drip; weapon-switching UI.
