# Metacraft Rivals

METAmods module `mods/metacraft-rivals` (mod id `metacraft-rivals`). A Splatoon-style paint
prototype for vanilla clients: Fabric + [Polymer](https://polymer.pb4.eu), Minecraft 26.2, Java 25.
Players need only the auto-served resource pack. Design: `docs/superpowers/specs/2026-09-11-metacraft-rivals-paint-prototype-design.md`
(v1) and `docs/superpowers/specs/2026-09-12-metacraft-rivals-v2-design.md` (v2: feel, art, any-block
paint, ink); the gun's design sheet is next to the v1 spec.

**Standalone.** This module is not bundled into the `dist` jar (root `build.gradle`, `standaloneMods`):
its pack retextures sculk vein, resin clump and glow lichen as paint, which only a dedicated
Rivals server wants.

## How it works

- Paint is a server-side multiface block per colour (`metacraft-rivals:paint_<colour>`), sent to
  clients as the colour's donor: magenta → sculk vein, lime → resin clump, cyan → glow lichen
  (vanilla's lit one; what a client shows for our unlit paint block is unverified). Donors accept
  paint even when waterlogged — the check is on the block, not the fluid state it carries.
- One colour per cell: a hit in another colour recolours the cell and keeps its faces.
- The paint gun throws a snowball-based paint ball shown as a tinted firework star; on impact it
  splashes: the usual 3×3 blob on the struck face (corners at random), plus fourteen short rays
  from the impact point (six axis directions and eight diagonals) that paint whatever face they
  hit, so a floor shot next to a wall also paints the wall and fills in the corner. A coloured
  dust burst and a wet impact sound go with it. Colour comes from the shooter's vanilla team,
  whose name is the colour id.
- Firing has a kick: the client's pitch is nudged up on the shot and eased back down two ticks
  later, plus a small push, a muzzle particle burst and a layered sound. Recoil packets only
  reach real connected players; mock players (game tests) are unaffected.
- Paint blocks only ever sit on a full face — vanilla's own attach rule for a multiface block, and
  exactly the rule paint wants. A face that isn't full (stairs, slabs, fences, panes, walls, glass
  panes) instead gets a set of flat splat-quad item displays that wrap the block's own outline
  shape (not its collision box, so paint on a fence sits on top of the post, not floating at
  collision height). These quads aren't blocks, so nothing tells them to fall on their own: they
  are dropped, and stop being counted, once their surface is destroyed, replaced, buried, or its
  chunk unloads.
- Splat art comes from Kenney's Splat Pack (CC0): eight silhouettes, each rendered per colour at
  four rotations (32 variants per colour), with paint texels marked at a specific alpha the gloss
  shader looks for. The resource pack's blockstate overrides for `sculk_vein`, `resin_clump` and
  `glow_lichen` pick one of the 32 variants per block position at random, so adjacent painted
  cells stop visibly tiling.
- The pack also overrides `block.vsh`/`block.fsh` to add a subtle specular/fresnel gloss on paint
  texels only; every other texel keeps vanilla's shading byte for byte. Known limit: a shader pack
  (e.g. Iris) replaces the core shaders wholesale and loses the gloss.
- The gun's 3D model is `assets/metacraft-rivals/models/item/paint_gun.json`; its tank is
  dye-tinted to the team colour. It's Kenney's `blaster-b` (CC0, Blaster Kit) converted into a
  vanilla element model by `tools/obj2mc.py` (stdlib + Pillow): voxelise the mesh, greedy-merge
  same-colour voxels into boxes, quantise colours to a palette texture. Rerun it after touching
  the source mesh; pass `--flip` if the muzzle ends up pointing backwards. Re-render the design
  sheet after editing the model — it reads its colours straight from the palette PNG the model
  references, so it never drifts from the JSON:
  `python3 mods/metacraft-rivals/tools/gun_sheet.py <model.json> <out.svg>`.
- Ink: the gun holds 40 shots, tracked in the stack's own data so it survives item moves. Each
  shot costs 1; trying to fire at 0 starts a 30-tick refill (sound, cooldown) that fills the tank
  the moment the deadline passes. Standing in your own colour's paint tops the tank up over time,
  faster in squid form. An action-bar ammo bar in the team colour refreshes every 10 ticks and
  after every shot, rounded to ten cells (`INK ██████░░░░ 24/40`), and reads `REFILLING…` or adds
  `SQUID` as appropriate.
- Sneaking on your own colour's paint is squid form: vanilla invisibility plus Speed II, reapplied
  every tick so it fades within a second of leaving the paint or standing back up, and the gun
  refuses to fire while it's active. Standing on an enemy colour's paint applies Slowness instead.
- Score: bossbars show each colour's share of painted faces across all levels — paint blocks and
  surviving display quads alike — counted once a second from the cells the painter has touched (in
  memory; a restart forgets them). `/rivals score` counts only the level it is run in, and names
  that level in its reply.

## Play

```
/rivals setup            teams magenta, lime, cyan
/team join magenta @s
/rivals gun
/rivals score
/rivals reset
```

## Build, run, test

```
./gradlew mods:metacraft-rivals:build -x mods:metacraft-lib:test  # lib unit tests fail on dev for unrelated reasons
./gradlew mods:metacraft-rivals:runServer      # needs two runs on a fresh clone, see below
./gradlew mods:metacraft-rivals:runGameTest    # server-side game tests (22 of ours, plus vanilla's always_pass)
```

`run/` is gitignored, and the `eula = true` in `build.gradle` applies only to the game-test run, so
on a fresh clone `runServer` takes two passes:

1. Run it once. That creates `run/` (with `eula.txt` and `server.properties`) and the server stops.
2. Set `eula=true` in `run/eula.txt` and `online-mode=false` in `run/server.properties` (a fresh
   `server.properties` has `online-mode=true`), then run it again.

Polymer's pack autohost is on by default in the dev environment, so the required pack is served
without any extra setup.

Join the dev server with a vanilla 26.2 client (offline mode) and accept the pack.

## Credits

Splat sprites and the blaster model are from Kenney (kenney.nl), CC0.

## Not yet

Arena bounds and a round loop; persisting display quads and the tally across a restart; damage on
enemy paint; a real squid model; Iris-compatible gloss.
