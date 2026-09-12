# Ovvar

Student overalls (ovvar) with sewn-on patches for METAcraft — server-side, Fabric + Polymer,
Minecraft 26.2. Players need nothing but the auto-served resource pack.

## What it adds

One ovve per chapter — Data (cerise), IT (purple), the older silicon-blue IT — and the Media
frack. An ovve is a single item worn in the legs slot with pockets: it is a bundle, filled and
emptied with the usual bundle clicks — also while worn, by clicking items onto the legs slot. It
holds twice a bundle through METAcraft's own bundle mod (`metacraft-bundles`, a hard dependency in
METAmods; here `libs/metacraft-bundles-1.0.0.jar` is compiled against and the dev server runs
without it, with vanilla-sized pockets). Right-click is the bundle's (hold to empty); sneak +
right-click rolls the top up or down; neither equips it, so drag it in or shift-click. While the
top is up and the chest slot is free the
mod keeps a companion "top" there so the sleeves render; it is not a real item and deletes itself
anywhere else. Real chest armour goes on over it as usual (right-click it, or swap it into the
slot) and hides the top until it comes off again. Leather-grade defence, no durability. The
look is an equipment asset cut from the skin overlays on metacraft.se/style.

Patches are items (`ovvar:patch_<id>`) and go on any 4×4-texel cell of the ovve (`Spot.java`: every
face you see of the body, sleeves and legs — not the inner faces — keeping off the collar, the
belt, the hands and the cuffs: 32 cells, plus the seat for the 8×4 chapter patch). Sewing: put the ovve on
an armour stand, hold a patch, look at the stand — the patch shows washed out on the cell you aim
at (a ghosted sprite, see below), the action bar
names it, right-click sews it on; sneak to aim at the far face of the part you look at
(the back of the body, the back of an arm). The aim follows the stand's pose. Shears on a sewn
patch unpick it. No cap on the number of patches. While the ovve is on a stand its patches are flat item displays laid on their cells
(`StandDisplays`, Polymer virtual entities following the stand's pose; the armour draws none of
them there), so a sewing session needs no resource pack at all — the pack matters once the ovve
is taken off and worn. A big patch lies flat on its cell's face, the overhang sticking out past
the corner (`PatchPieces` can instead cut it at the corners and lay each piece on the face it
hangs over, so it bends round the box on the stand too; that is off for now,
`BEND_ROUND_CORNERS`, until the pieces line up with the armour on posed stands); datagen makes
one item model per piece — a single zero-thickness quad, so the displays are sprites, not slabs
— and a ghosted twin of each (mixed
60 % to white), which is what the patch being aimed at is shown as, on top of everything, until
it is sewn. That is the whole preview. The companion top and the virtual cuffs carry the same
on-stand flag, so nothing draws the patches twice.

With the stitching minigame on (`config/ovvar.json`: `sewing_minigame`, `stitches`; default on,
6 stitches for a cell-sized patch — a longer outline, a bigger patch or an intricate edge, takes
proportionally more, up to 16) the right-click opens a dialog instead: the patch lies on the ovve's cloth and the
seam goes around its edge, following the shape of the art (a heart is sewn around its lobes). The
holes come in pairs — one on the cloth just outside the edge where the thread comes out, one on
the patch just inside where it goes in — so each pair is a stitch over the edge, and the thread
runs under the cloth to the next pair (`Seam.Style.WHIP`; `ZIGZAG` draws every run on top like a
machine seam). The needle sits on the next hole, coming in over the edge; click where it is to
pull it through. Each pull sounds at the stand, the last one sews the patch (`SewingGame`);
Escape or the "Cut the thread" band abandons it, and the patch only leaves your hand when the
seam is done. The dialog's clicks come back as custom click actions (`CustomClickMixin`).

Every button in that dialog is a sprite. The picture is a 7×7 grid of 20 px buttons whose
labels are glyphs of a bitmap font the pack carries (`SewingFont`,
`assets/ovvar/font/sewing.json`): an opaque cloth tile a pixel larger than the button on every
side hides the vanilla button and meets its neighbours across the grid gaps, and the last cell's
label — buttons draw in grid order, so it comes out on top — also draws the patch, the thread
(a row of dot glyphs, so any angle works), the stitch marks and the needle over the whole picture
with negative-advance spaces; every such glyph has one codepoint per vertical position, and its
texture is padded below so no ascent exceeds its height (the client drops the whole font
otherwise). The cell under the needle carries the click. Datagen builds the glyph textures from
`art/ovvar/sewing/`: `cloth.png` (22×22, recoloured in every chapter's colour), `needle.png`
(26×9, pointing right; mirrored and turned for the other directions), `thread.png` (3×3),
`stitch_in.png`, `stitch_out.png` and `hole.png` (5×5), `band.png` (154×22, the text is stamped
on), plus each patch's art scaled up (4×4 at 20×, the seat patch at 12×). It also
traces each patch's outline from its opaque texels into `ovvar/outlines.json` (`Outline`), which
`Seam` spreads the holes along at runtime. Replace the PNGs and `runDatagen`; the
`sewingLabelsFitTheirButtons` game test checks every label of every seam still measures what the
client centres without scrolling. Mockups of the design are in `docs/mockups/sewing/`.

## Wardrobes: one look per player, on every server

An ovve belongs to a player (`ovvar:owner`, set the first time a player's inventory ticks it, or
by `/ovvar give`). What they have sewn on it, and the patches they own but have not sewn (their
*stash*), make up their *wardrobe*: one versioned row in a store shared by all the servers
(`Wardrobe`, `Wardrobes`). Sew on one server, log into another and wear the same ovve; a
recrafted ovve just shows the design again. Only the look and the patches travel: pockets,
enchantments, the top being up or down stay with the item.

A patch lives in exactly one place: as an item in the world, in a stash, or on a design. The
`ovvar:patches` component on an owned ovve is a copy for drawing, refreshed every tick and never a
source. Every change is a compare-and-set naming the version it saw; a write that lost the race
fails, the cache refetches, and the player is told to try again. A sew takes the patch (from the
hand or the stash) as the click lands and it comes back if the store says no; an unpicked patch is
handed out (to the hand or the stash) only after the store has let go of it. So two ovves of one
owner, or two servers, cannot hand the same patch out twice. Ovves without an owner (`/ovvar
stands`, showcase) keep their patches on the item as before.

### The stash

`/ovvar stash` is a chest menu of the patches you own (`StashGui`). On a survival server:

- **left-click a patch** to take one out as an ordinary item: sew it on any armour stand wearing
  your ovve, the way it always worked, or trade it. The chest button puts every patch item you
  carry back in;
- **right-click a patch** for a private sewing session instead (`stash_click` swaps the two):
  an armour stand named after you appears two blocks ahead in a walking pose wearing your ovve;
  hotbar slot 9 gets the patch (as many as the stash holds) and slot 8 a pair of shears, both fake
  and pinned there (`ovvar:session`: no dropping, no moving, the hotbar selection is held to those
  two). Aim and right-click to sew straight from the stash, shears to unpick back into it. Walk
  away, idle, die, or `/ovvar stash done` and the stand goes and your two slots come back
  (`StashSession`). Nobody else can touch your stand.

On a **minigame server** (`stash.minigame_server`) the menu is view-only, no stand takes a patch,
and any patch item that lands in an inventory is banked into the stash at once, so nothing is lost
to a locked or wiped inventory. Sewing also needs a game mode in `sew_game_modes` (adventure is
not one) and a score of 0 in the `ingame` objective. A patch earned (`/ovvar patch give`, or banked)
plays the totem-of-undying flourish with the patch's art and explains the stash in chat.

`config/ovvar.json` → `designs` (the store):

    backend                  file (default) | jdbc
    file_directory           file backend: an absolute directory, "" = <world>/ovvar/wardrobes
    jdbc.url                 jdbc:mariadb://host:3306/db  or  jdbc:postgresql://host/db  (drivers bundled)
    jdbc.user, jdbc.password, jdbc.password_env   the password from the file, or from the named environment variable
    jdbc.table               created if missing: owner CHAR(36) PRIMARY KEY, version, data (JSON), updated_at
    jdbc.driver_class        force a driver class; "" lets the URL pick
    jdbc.connect_timeout_seconds, jdbc.query_timeout_seconds
    bind_on_pickup           an unowned ovve becomes the first holder's (default true)
    sew_when_unreachable     store down: sew anyway and queue the write (default false: refuse, keep the patch)
    unpick_when_unreachable  store down: hand the patch back anyway and queue the write (default false; the dupe direction)
    retry_seconds            how often failed loads and queued writes are retried (default 15)
    log_queries              log every load and store

`config/ovvar.json` → `stash` (this server's rules):

    minigame_server          true: view-only stash, no sewing or unpicking anywhere, patch items banked (default false)
    sew_game_modes           game modes that may sew and take patches out (default survival, creative)
    ingame_objective         scoreboard objective; a non-zero score means "in a game", no sewing (default "ingame", "" = off)
    bank_on_pickup           minigame (default: only on a minigame server) | always | never
    bank_in_creative         bank creative players' patch items too (default false)
    unpick_to_stash          unpicking on an ordinary stand sends the patch to the stash instead of the hand (default false)
    withdraw                 right-click in the stash takes a patch out as an item (default true; never on a minigame server)
    stash_click              withdraw (default: left-click takes the patch out as an item, right-click opens a session) | session (the reverse)
    any_stand                sew and unpick on any armour stand wearing an owned ovve, not only a session stand (default true)
    session_reach            blocks a player may walk from their session stand (default 8)
    session_seconds          idle time before a session ends (default 300)
    explain_in_chat          the stash explanation when a patch is earned (default true)

The file backend is fine for one server or a shared mount; a network of servers wants `jdbc`
(MariaDB/MySQL and PostgreSQL drivers ship in the jar).

## Debug commands (gamemasters)

    /ovvar give [player] <chapter> [patches]   their stored design; with patches (all / cell.patch / ids) those replace it
    /ovvar patches <patches>                   re-sew the ovve in your main hand (all / none / cell.patch, bare ids); owned: replaces the design
    /ovvar showcase <chapter>                  armour stands: top down, top up, each patch, every cell filled
    /ovvar stands <chapter>                    three posed stands in a plain ovve, for testing the sewing aim
    /ovvar minigame [on [stitches]|off]        the stitching minigame setting; saved to config/ovvar.json
    /ovvar aimlog on|off                       log every stand click and aim change with its numbers (server log)
    /ovvar stitch <cell.patch>                 open the stitching dialog on the nearest ovve stand, no aiming needed
    /ovvar reload                              (any player) the latest resource pack, now
    /ovvar patch give <targets> <patch> [n]    a patch into the stash of every selected player, with the flourish
    /ovvar stash                               (any player) the stash menu; stash done ends a session; stash deposit banks held patches
    /ovvar store status                        the wardrobe store: backend, cache, queued writes, this server's role, sessions
    /ovvar store show [player]                 a player's wardrobe (version, designs per chapter, stash)
    /ovvar store reload [player]               drop and refetch a player's wardrobe
    /ovvar store reconnect                     re-read the config and reopen the store

## Building

    ./gradlew runDatagen   # turns src/main/resources/art into src/main/generated (assets)
    ./gradlew build        # build/libs/ovvar-<version>.jar (Polymer bundled; Fabric API separate)

`build` refuses to run without the generated assets, and the mod refuses to start without them.

## Testing

`Start Server.command` runs an offline dev server on localhost with the pack auto-hosted;
`Start Vanilla Client.command` launches a plain vanilla client that joins it. Give yourself an
ovve with `/ovvar give data all` or from the Ovvar creative tab. `Run Tests.command` runs the
game tests (`OvvarGameTests`): every cell aimed at on stands at rest, posed and turned, and the
sneak far-face rule, checked against `StandAim.cell`, the independent cell → point mapping; and
the stitching minigame played through with the clicks its dialog sends (stale clicks ignored,
sewn on the last pull, nothing sewn after cutting the thread).

## Adding a chapter

Drop the chapter's 64×64 skin overlay (the same file the website uses; pure green = "erase the
skin here") into `src/main/resources/art/ovvar/`, add a line to `Chapter.java` naming it and,
optionally, its rolled-down (nercabbad) overlay and a tint colour, then `runDatagen`. The
armour layers, equipment definitions, icons and names are derived from that. A chapter can
instead name a ready-made 64×32 leggings texture for its rolled-down state (`nercabbadArmour`);
it is shifted to the chapter's colour and used as it is. The `*_polymiter` chapters do this with
PolymITer's hand-drawn ovve (`art/ovvar/polymiter/nercabbad.png`), there to compare the two
styles in game — `/ovvar give data_polymiter down` next to `/ovvar give data down`.

## Adding a patch

One line in `Patches.java` (id, name; `true` for a seat patch) and a PNG at
`src/main/resources/art/ovvar/patches/<id>.png` — 8×8 for a cell-sized patch, 16×8 for a seat
patch, or any even size up to 16×16 declared in the catalogue line: such a patch is centred on
its cell and hangs over the neighbours, later-sewn on top, all the way round the part — past a
limb's outer face lies its back face, the strip being a loop (garment and patch textures are the
armour layout at twice the skin's resolution, `Spot.DETAIL`). A big patch rides in the dye
colour like any other (the shader bends it round the corners from its own cell's face, as the
pack will). Then `runDatagen`. The first 22
designs in the catalogue can ride in the dye colour (instant, previewable); later ones only go
through the pack; the preview library is the head rows of the texture (52 cells) and datagen
fails loudly when that runs out.

## How the look works

The client draws an equipment asset as a stack of 64×32 layer textures over the armour model:
the chapter's base, one static texture per sewn placement (`textures/entity/equipment/<layer>/patch/<cell>/<patch>.png`,
all generated by datagen), and a dyeable preview layer. Which layers to stack is the one thing
that is per combination, so each combination of placements on a half is its own tiny equipment
JSON. Datagen writes only the empty ones; `Combos` remembers every combination ever sewn in
`<world>/ovvar/combos.json`, adds their JSONs when Polymer builds the pack, and when a new one
appears (beyond what the dye colour shows, see below) rebuilds the pack. Each build is a generation and each player is on the generation they
last loaded; who gets pushed a new pack, and when, is in "Reloads only when asked for" below;
once a client reports a pack loaded (a mixin on the resource-pack response) the equipment of
every ovve it can see is sent again. Rebuilds are batched: a combination the dye colour can
still show waits a minute and a half for company; one it cannot is built within two seconds.

The dye colour carries patches without any pack change. Up to three placements per half ride in it
— six on the legs when the wearer's feet slot carries the second channel (below), four on the top
when one of them is on the chest or the back (the trim, below): a dyeable layer
is only drawn when the item has a dye colour, and that colour reaches the shader as the vertex
colour — the only per-item data an armour shader ever gets — so it carries the *rank* of the set
of up to three (cell, design) placements among all such sets (packed as three base-255 digits so
no byte is 0; 20 cells × 22 designs, C(440,3) ≈ 14M states under 255³). The preview texture holds the art of the first 22
designs — any size, in a block of library cells — plus cell and design tables; the pack's entity core shader
(`assets/minecraft/shaders/core/entity.fsh` + `assets/ovvar/shaders/include/ovvar.glsl`) unranks
the set and draws the art on the cells, lit white so the data colour never tints it. Designs
past the first 22 in the catalogue only go through the pack. The tooltip's "Dyed" line is
hidden.

The top has a fourth instant slot: the armour trim. The client draws a trim as one more layer
whose texture is picked by the trim pattern, and the item's trim is per-item data like the dye
colour, so datagen bakes every (body cell, design) pair as its own pattern
(`textures/trims/entity/humanoid/<cell>_<design>.png`, the art anchored at the cell and bent round
the corners the way the shader does; `trim_pattern/*.json`, one material `ovvar:patch` whose
palette maps the patch's colours to themselves) and `OvveTop.dress` sets the newest placement
that does not fit in the dye colour as the trim, if it is on one of the eight chest and back
cells (`Trims.fits`; vanilla draws the trim, so the squeeze to square pixels is done texel by
texel by datagen, which costs about a column in sixteen on the body's faces but two in eight on
a sleeve — hence body only). Trims are material-tinted, so the palette is the identity and the
art comes out as it is; the tooltip's trim line is hidden with the dye line. Everything else is sampled
exactly as vanilla. The overlay's body (16,16), right arm (40,16) and right leg (0,16) boxes are
at the same coordinates in the armour layout, so datagen only copies boxes (with the skin's
second layer painted on, and the left limbs from the skin's own left art).

## The boots pass

The client draws the feet slot with the whole leg boxes of the outer model (vanilla boot
textures are just transparent above the ankle), with its own equipment asset and dye colour: 24
more bits. So an ovve wearer's feet slot carries our legs preview layer too (`OvveFeet`):
vanilla boots of a known material — chainmail, copper, iron, gold, diamond, netherite — are
marked with the wearer and shown to clients as "their layers plus ours", trim and glint kept
(`equipment/feet/<material>.json`); an empty slot is shown to *other* players as virtual cuffs
that exist only in their equipment packets, so the wearer's inventory stays empty there and
boots go on as usual (their own client renders their body from that inventory, so they see up
to three fewer of their own newest leg patches until the pack catches up). The legs then take
six instant patches instead of three. Leather boots use the dye colour for their own colour and
other mods' boots have layers we don't know: over those the channel is off and the legs fall
back to three. The boots pass is
inflated 1.0 where the leggings are 0.5, so the shader draws it on the leggings' pixel grid
(squeezed in x and y) and the two layers' pixels line up.

## Reloads only when asked for

A pushed pack is a loading screen, so nobody gets one they did not cause. On an armour stand
nothing needs the pack (the patches are display entities). The pack is pushed to a player in
exactly two cases: an ovve came into their inventory — off a stand, `/ovvar give`, `/ovvar
patches` — with more patches on a half than their pack plus the dye channels (and the trim) can show, in which
case the pack is built at once and sent to them the moment it is ready (`Looks.claimIfNeeded`
from `OvveItem.inventoryTick`); or they ran `/ovvar reload` (any player), which sends the
current pack, after a build if one is pending. Everyone else keeps the pack they have and sees
what it holds plus the newest patches in the dye channels; a half with more new patches than
that shows the older state to them until they reload or rejoin (a joining player gets the
current pack). Every combination is still built in the background within 90 s so the pack is
complete for whoever joins next.

## Square pixels

The armour model draws a texel wider than it is tall: the box is inflated (1 on the chest
layer, 0.5 on the leggings layer) but its texture is not, so a face n texels wide covers
n + 2·inflate units while 12 rows cover 12 + 2·inflate — a sleeve texel is 1.5 × 1.167 units,
a chest texel 1.25 × 1.167. Pixel art hates that, so the shader draws everything of ours on the
box sides with square pixels, which leaves 2·inflate units of slack per face. The garment and
the preview (many cells, one per face) centre each face's texels on the face, so the cells sit
on the fabric's grid, and the slack is a margin at every corner: the garment stretches its
edge column across it, the preview shows nothing there. A sewn patch's own texture holds one
patch on one face, so it is drawn continuous round the box from that face instead — its
texels centred, the neighbours' continuing past its edges at the same pixel — and all its
slack lands in the middle of the opposite face, which the patch never reaches: a big patch
hanging over a corner bends round it unbroken, and no corner ever shows a stretched, doubled
or cut column (`ovvar_centred`, `ovvar_anchored` and `ovvar_uv` in `ovvar.glsl`; the face is
in the kind texel's B). Each texture carries which layer it is for (the layer texel, two left
of the marker: R = 2·inflate).

## Asymmetric sleeves and legs

The armour model draws the left arm and leg as mirror images of the right ones from the same
texture strips, so vanilla can't show different art per side. The same shader detects mirrored
fragments from the handedness of the texture mapping: on a base texture it samples the limb boxes
one strip up, where datagen puts the mirrored left-side art; a placement texture is marked with
its side and hidden on the other limb; the preview slots carry the side in their cell. Clients whose core shaders are replaced (Iris, OptiFine) see the plain
mirrored overalls without patches — nothing breaks. Shaderpack users run `OvvarShaderPatcher.jar`
(built from `tools/shaderpatcher`, Java 11+, shipped inside the resource pack at
`assets/ovvar/shaderpatcher/` and worth linking from the website): double-clicked, it writes a
`+ovvar` copy of every pack in `.minecraft/shaderpacks` with `ovvar.glsl` spliced into the pack's
own entity program (the texture-coordinate and vertex-colour varyings are shadowed, so the pack's
code needs no changes). Patched cleanly: BSL, Bliss, Complementary Reimagined and Unbound,
MakeUp Ultra Fast, Solas, Photon, Super Duper Vanilla.
