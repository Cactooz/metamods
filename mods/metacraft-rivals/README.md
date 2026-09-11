# Metacraft Rivals

METAmods module `mods/metacraft-rivals` (mod id `metacraft-rivals`). A Splatoon-style paint
prototype for vanilla clients: Fabric + [Polymer](https://polymer.pb4.eu), Minecraft 26.2, Java 25.
Players need only the auto-served resource pack. Design: `docs/superpowers/specs/2026-09-11-metacraft-rivals-paint-prototype-design.md`;
the gun's design sheet is next to it.

**Standalone.** This module is not bundled into the `dist` jar (root `build.gradle`, `standaloneMods`):
its pack retextures sculk vein, resin clump and glow lichen as paint, which only a dedicated
Rivals server wants.

## How it works

- Paint is a server-side multiface block per colour (`metacraft-rivals:paint_<colour>`), sent to
  clients as the colour's donor: magenta → sculk vein, lime → resin clump, cyan → glow lichen (this
  one glows). The pack replaces the donors' textures with a splat generated at pack build.
- One colour per cell: a hit in another colour recolours the cell and keeps its faces.
- The paint gun throws a snowball-based paint ball shown as a tinted firework star; on impact it
  paints a 3×3 blob (corners at random) on the struck face. Colour comes from the shooter's
  vanilla team, whose name is the colour id.
- The gun's 3D model is `assets/metacraft-rivals/models/item/paint_gun.json`; its tank is
  dye-tinted to the team colour. Re-render the design sheet after editing it:
  `python3 mods/metacraft-rivals/tools/gun_sheet.py <model.json> <out.svg>`.
- Score: bossbars show each colour's share of painted faces in the overworld, counted once a
  second from the cells the painter has touched (in memory; a restart forgets them).

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
./gradlew mods:metacraft-rivals:runServer      # run/ has eula, offline mode and the pack autohost enabled
./gradlew mods:metacraft-rivals:runGameTest    # server-side game tests
```

Join the dev server with a vanilla 26.2 client (offline mode) and accept the pack.

## Not yet

Arena bounds and a round loop; blobs wrapping onto walls; speed on own paint and damage on enemy
paint; a persistent tally; real splat art.
