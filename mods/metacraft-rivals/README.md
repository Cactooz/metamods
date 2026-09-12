# Metacraft Rivals

METAmods module `mods/metacraft-rivals` (mod id `metacraft-rivals`). A Splatoon-style paint
prototype for vanilla clients: Fabric + [Polymer](https://polymer.pb4.eu), Minecraft 26.2, Java 25.
Players need only the auto-served resource pack. Design: `docs/superpowers/specs/2026-09-11-metacraft-rivals-paint-prototype-design.md`
(v1), `docs/superpowers/specs/2026-09-12-metacraft-rivals-v2-design.md` (v2: feel, art, any-block
paint, ink) and `docs/superpowers/specs/2026-09-12-metacraft-rivals-v3-design.md` (v3: gloss that
actually renders, real squid form, blobby bouncing shots, three more weapons); the gun's design
sheet is next to the v1 spec.

**Standalone.** This module is not bundled into the `dist` jar (root `build.gradle`, `standaloneMods`):
its pack retextures sculk vein, resin clump and glow lichen as paint, which only a dedicated
Rivals server wants.

## How it works

- Paint is a server-side multiface block per colour (`metacraft-rivals:paint_<colour>`), sent to
  clients as the colour's donor: magenta → sculk vein, lime → resin clump, cyan → glow lichen
  (vanilla's lit one; what a client shows for our unlit paint block is unverified). Donors accept
  paint even when waterlogged — the check is on the block, not the fluid state it carries.
- One colour per cell: a hit in another colour recolours the cell and keeps its faces.
- Four weapons, one item class (`PaintWeapon`) parameterised by a `Weapon` enum, given with
  `/rivals gun <shooter|sprayer|charger|slosher>` (default shooter) or all at once with
  `/rivals kit`:

  | Weapon | Ink/shot | Cooldown | Shot |
  |---|---|---|---|
  | shooter | 1 | 4 ticks | one ball, one bounce, 3×3 splat |
  | sprayer | 1/click | 4 ticks | 3 short-lived droplets in a cone, single-face splat + rays, no bounce |
  | charger | 4 + 8 × charge | 20 ticks | hold to charge (up to 20 ticks), release for a hitscan line, stopped by the first block or player in it |
  | slosher | 15 | 14 ticks | 4 balls in a fan, gravity-heavy lob, 5×5 splat, no bounce |

  Only the slosher swings the arm on use — it's a bucket, and the throw reads as one — so its
  `use` returns `SUCCESS_SERVER` (the server broadcasts the swing, including to the thrower); the
  other three return `CONSUME`, which takes the click without animating the hand, since a
  four-tick swing loop on a rapid-fire weapon looks like a stutter rather than firing.
- Every paint ball is a snowball entity hidden from clients, with a Polymer item display — a
  rounded, dyed blob model, not the vanilla firework-star particle — riding along on an
  attachment and squashing/stretching as it flies. The shooter's ball keeps one bounce: on a
  block hit it splashes, reflects off the hit face at 45% of its speed, and keeps flying until
  the second impact spends it; sprayer and slosher shots don't bounce. On impact (or the final
  bounce) it splashes: the usual blob on the struck face (3×3 for the shooter and sprayer, 5×5
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
- Paint blocks only ever sit on a full face — vanilla's own attach rule for a multiface block, and
  exactly the rule paint wants. A face that isn't full (stairs, slabs, fences, panes, walls, glass
  panes) instead gets a set of flat splat-quad item displays that wrap the block's own outline
  shape (not its collision box, so paint on a fence sits on top of the post, not floating at
  collision height). At most three quads per cell, the largest boxes on the struck side, so a
  wall post with four arms doesn't put a dozen item displays in one cell. These quads aren't
  blocks, so nothing tells them to fall on their own: they are dropped, and stop being counted,
  once their surface is destroyed, replaced, buried, or merely changes shape (a stair turned
  under them), or once its chunk unloads — a chunk unload takes the paint with it, and unlike a
  paint block it does not come back when the chunk reloads.
- Splat art comes from Kenney's Splat Pack (CC0): eight silhouettes, each rendered per colour at
  four rotations (32 variants per colour), with paint texels marked at a specific alpha the gloss
  shader looks for. The resource pack's blockstate overrides for `sculk_vein`, `resin_clump` and
  `glow_lichen` pick one of the 32 variants per block position at random, so adjacent painted
  cells stop visibly tiling.
- The pack also overrides `assets/minecraft/shaders/core/terrain.vsh`/`terrain.fsh` — the pair
  that actually draws chunk geometry in 26.2 — to add a subtle specular/fresnel gloss on paint
  texels only; every other texel keeps vanilla's shading byte for byte. (v2 keyed this into
  `block.vsh`/`block.fsh`, which chunk terrain never runs through, so the gloss never rendered;
  those overrides are gone.) Known limit: a shader pack (e.g. Iris) replaces the core shaders
  wholesale and loses the gloss.
- The guns' 3D models live under `assets/metacraft-rivals/models/item/`; each tank is dye-tinted
  to the team colour. The shooter (`paint_gun.json`) is Kenney's `blaster-b`, the sprayer is
  `blaster-o`, and the charger is `blaster-p` (all CC0, Blaster Kit), each converted into a
  vanilla element model by `tools/obj2mc.py` (stdlib + Pillow): voxelise the mesh, greedy-merge
  same-colour voxels into boxes, quantise colours to a palette texture. The slosher is a
  hand-built bucket model instead — body, rim and handle — using the same tinted-tank approach.
  Rerun the converter after touching a source mesh; pass `--flip` if the muzzle ends up pointing
  backwards. The model's in-hand `display` transforms (first and third person) were checked
  against in-game screenshots on 2026-09-12; the converter's neutral values are the ones that
  look right. Re-render the design sheet after editing a model — it reads its colours straight
  from the palette PNG the model references, so it never drifts from the JSON:
  `python3 mods/metacraft-rivals/tools/gun_sheet.py <model.json> <out.svg>`.
- Ink: every gun holds 40 shots in one shared tank size, tracked in the stack's own data so it
  survives item moves. A shot costs the weapon's own ink (see the table above — 1 for the
  shooter/sprayer, 4 plus up to 8 more for the charger's charge, 15 for the slosher); trying to
  fire on a tank that can't cover the shot starts a 30-tick refill (sound, cooldown) that fills
  the tank the moment the deadline passes. Standing in your own colour's paint tops the tank up
  over time, faster in squid form. An action-bar ammo bar in the team colour refreshes every 10
  ticks and after every shot, rounded to ten cells (`INK ██████░░░░ 24/40`), and reads
  `REFILLING…` or adds `SQUID` as appropriate.
- Sneaking on your own colour's paint is squid form, and it's now a real mechanic rather than a
  cosmetic buff: half size, +80% movement speed, a higher jump and a taller step (four attribute
  modifiers, not potion effects, so they're exact and don't show up in the client's effect list),
  plus vanilla invisibility — all reapplied every tick so they fade within a second of leaving the
  paint or standing back up — and the gun refuses to fire while it's active. Pressed against a
  wall face painted in your own colour while a squid, you swim straight up it. Standing on an
  enemy colour's paint is a trap instead: Slowness II, no jumping at all, and half a heart of
  damage every second that never brings you below one health.
- Score: bossbars show each colour's share of painted faces across all levels — paint blocks and
  surviving display quads alike — counted once a second from the cells the painter has touched (in
  memory; a restart forgets them). `/rivals score` counts only the level it is run in, and names
  that level in its reply.

## Play

```
/rivals setup            teams magenta, lime, cyan
/team join magenta @s
/rivals gun              shooter, the default
/rivals gun slosher      or sprayer / charger
/rivals kit              one of every weapon
/rivals score
/rivals reset
```

## Build, run, test

```
./gradlew mods:metacraft-rivals:build -x mods:metacraft-lib:test  # lib unit tests fail on dev for unrelated reasons
./gradlew mods:metacraft-rivals:runServer      # needs two runs on a fresh clone, see below
./gradlew mods:metacraft-rivals:runGameTest    # server-side game tests (36 of ours, plus vanilla's always_pass: 37 in total)
```

`run/` is gitignored, and the `eula = true` in `build.gradle` applies only to the game-test run, so
on a fresh clone `runServer` takes two passes:

1. Run it once. That creates `run/` (with `eula.txt` and `server.properties`) and the server stops.
2. Set `eula=true` in `run/eula.txt` and `online-mode=false` in `run/server.properties` (a fresh
   `server.properties` has `online-mode=true`), then run it again.

Polymer's pack autohost is on by default in the dev environment, so the required pack is served
without any extra setup.

Join the dev server with a vanilla 26.2 client (offline mode) and accept the pack.

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

Splat sprites and the shooter, sprayer and charger models (`blaster-b`, `blaster-o`, `blaster-p`
of Kenney's Blaster Kit) are from Kenney (kenney.nl), CC0. The slosher's bucket is hand-built.

## Not yet

Arena bounds and a round loop; persisting display quads and the tally across a restart; damage on
enemy paint (beyond the enemy-ink drip); a real squid model; Iris-compatible gloss; respawn/death
handling for the drip; weapon-switching UI.
