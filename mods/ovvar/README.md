# Ovvar

Student overalls (ovvar) with sewn-on patches for METAcraft — server-side, Fabric + Polymer,
Minecraft 26.3. Players need nothing but the auto-served resource pack.

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
belt, the hands and the cuffs: 32 cells, plus the seat, which takes a two-cell-wide 8×4 patch
across it). The catalogue holds ITK, Nyckeln'26, METAcraft Rivals '26, IT and Data
(`Patches.java`): ITK, IT and Data are 12×12 and hang over their neighbours (IT is the PolymITer
set's 16×16 brought down to 12: the diamond rebuilt at that size, the bolt sampled from the
original); Rivals is the seat patch — Data's cerise with a creeper against IT's laser violet with
a VS — and Nyckeln'26 is Kexana's cell-sized 8×8. Sewing: put the ovve on
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
seam goes around its edge, following the shape of the art (a notched edge is sewn into its notch). The
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
on), plus each patch's art scaled up whole to fit 96 px (an 8×8 at 12×, a 12×12 at 8×, the
16×8 seat patch at 6×). It also
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

### An ovve is its owner's

Handing somebody an ovve does not hand them the wardrobe behind it, so an owned ovve is worn and
changed by its owner and by nobody else:

- **wearing.** A foreign ovve does not go in the legs slot: the armour slot refuses it (dragging,
  clicking, shift-clicking), a dispenser aimed at the player refuses it, right-clicking it says
  "That ovve belongs to \<name\>" in red, and one forced in anyway (`/item replace`, another mod,
  the rule changed while it was worn) is taken off on the wearer's next tick and put back in their
  inventory. `designs.others_ovve` chooses between that (`block`, the default), `rebind` (a given
  ovve becomes the holder's, showing *their* design) and `allow` (anyone may wear it, showing its
  owner's design — how it was before);
- **changing.** With `designs.edit_requires_owner` on (the default) only the owner sews a patch on
  an owned ovve or shears one off it, on any stand: "That ovve belongs to \<name\>; only they can
  change it". The name comes from the server's own name cache, or "someone else" for an owner it
  has never seen.

An ovve with no owner is still anybody's: they may wear it and sew on it, and it becomes theirs the
first time a player's inventory ticks it (`bind_on_pickup`) — and with `bind_on_pickup` off nothing
binds and `rebind` hands nothing over either. A gamemaster is outside all of this: `/ovvar give` and
`/ovvar patches` write a player's design straight into their wardrobe, whoever runs them. Stands and mannequins wear and show
anybody's ovve unchanged — that is what makes a sewing stand and a showcase work.

A patch lives in exactly one place: as an item in the world, in a stash, or on a design. The
`ovvar:patches` component on an owned ovve is a copy for drawing, refreshed every tick and never a
source. Every change is a compare-and-set naming the version it saw; a write that lost the race
fails, the cache refetches, and the player is told to try again. A sew takes the patch (from the
hand or the stash) as the click lands and it comes back if the store says no; an unpicked patch is
handed out (to the hand or the stash) only after the store has let go of it. So two ovves of one
owner, or two servers, cannot hand the same patch out twice. Ovves without an owner (`/ovvar
stands`, showcase) keep their patches on the item as before.

### The stash

`/ovvar stash` opens the wardrobe screen (below); `/ovvar look [player]` opens the same screen
read-only on somebody's ovve — anybody's, here or not — with their stash left out of it. On a
survival server, on your own:

- **left-click a patch** to take one out as an ordinary item: sew it on any armour stand wearing
  your ovve, the way it always worked, or trade it. The chest button puts every patch item you
  carry back in;
- **right-click a patch** for a private sewing session, only where `sessions` is on (off by
  default; `stash_click` swaps the two buttons):
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

### Wardrobe screen

`WardrobeGui` (`/ovvar stash`) is a `GENERIC_9x6` chest drawn as a full-bleed piece of stitched
cloth, tinted to the open chapter's colour, instead of the plain vanilla chest background. Slot
`row * 9 + col`:

```
row 0   [tab][tab][tab][tab][tab][tab][tab][ <- ][ -> ]  <- an ovve per owned chapter; the preview turns
row 1   [ patch  ][ patch  ][ patch  ][ patch  ][ patch  ] | [prev][prev][prev][prev]
row 2   [ patch  ][ patch  ][ patch  ][ patch  ][ patch  ] | [prev][prev][prev][prev]
row 3   [ patch  ][ patch  ][ patch  ][ patch  ][ patch  ] | [prev][prev][prev][prev]
row 4   [ patch  ][ patch  ][ patch  ][ patch  ][ patch  ] | [prev][prev][prev][prev]
row 5   [take out][put in][sew][see in 3D][finish][ · ][ · ][help][close]
```

**The screen explains itself.** Every slot says what it is and, where it cannot be used, why not:

- a tab is the chapter's *ovve*, named "Data ovve", the one on show glinting and its lore reading
  "(showing)" while the others read "Click to switch to it";
- a patch in the pocket says how many of it are in the stash, where it may be sewn, and who drew it
  ("Art by Kexana" — the credit `PatchItem` puts on the item itself);
- the preview is the **whole** ovve, top and trousers as one figure, and the two buttons at cols 7
  and 8 turn it a quarter at a time (front, their right, back, their left; per open screen, front to
  begin with). There is no piece toggle any more — the chestplate and boots that prompted "why is
  there a chestplate and boots there?" were it — because there are no longer two halves to choose
  between;
- **empty states** say what would be there, across the whole panel and in the glyph layer rather
  than as one item in the middle of it: "NO PATCHES YET / EARN THEM AT / CHAPTER EVENTS" over the
  pocket and "NOTHING SEWN YET / TAKE A PATCH TO / A SEWING STAND" over the bare garment, both baked
  pixel text (`TinyType`, `WardrobeFont.NO_PATCHES` and `NOTHING_SEWN`) drawn by the title. The slots
  underneath stay empty, so there is nothing left to hover, click or mistake for a patch;
- **row 5** is one verb per action with a line saying what it does, each wearing an icon of our own
  (`WardrobeAction`, `tools/wardrobe_icons.py`, six 16×16 sprites in `art/ovvar/`). An action this
  server refuses is the *same icon at half brightness with a small red slash*, still named for the
  verb ("Take out (not here)"), carrying the reason `StashConfig` gives (`whyNoWithdraw`,
  `whyNoSessions`, `whyNoDeposit`, `whyNoMannequin` — "Minigame server: look only", "Sewing sessions
  are off on this server", "Wear an ovve first") and with no click on it. It used to be missing
  altogether, then a pane of grey glass; a missing slot teaches nobody anything and the glass read
  as a bug. The slash is a shape and not only a colour, so it reads refused without colour too;
- the **help** item (col 7) explains the screen top to bottom in five lines — tab row, left panel,
  right panel, bottom row — then what this server allows and what is sewn on each ovve.

The right block is a **picture of the player's own ovve**, not a list of icons, and it turns.
A vanilla client cannot draw an entity inside a chest screen, so `WardrobePreview` draws a paper
doll: the humanoid model's faces, cut out of the very equipment-layer textures the client draws the
garment with, laid out flat. Front and back put each arm (4×12 skin px) beside the torso (8×12) and
the two legs (4×12) below it; a side shows the body's own side face with the sleeve beside it and the
trouser leg below, which is where that side's cells are.

```
[arm][  torso  ][arm]          [body][sleeve]
     [leg][leg]                [leg]                  front/back        a side
```

**It is built up in layers, not baked per design.** One glyph per (chapter, angle) draws the bare
garment, and one small glyph per (patch, cell) draws that patch exactly where it lands on the doll —
cropped to its own art, so the glyph is a dozen pixels square, and placed by space advances and its
own ascent. The title stacks the bare ovve and then one glyph per sewn placement, so a design is
composed at the moment the screen opens. A cell sits on exactly one face of one box and a face is
seen from exactly one of the four angles, so that is **6 × 4 = 24 bare glyphs plus one per (patch,
cell the doll can show) — 64 for today's two patches — fixed**, however much anybody sews: nothing
is regenerated and no pack is pushed when a patch goes
on. (v2 keyed the art by patch combination instead, which grew with every design, spent a glyph
budget and needed a pack build and a loading screen each time somebody sewed something.) `Combos` is
therefore back to what it was before v2, doing equipment definitions and nothing else.

The cells you cannot see from an angle simply have no glyph there. The wearer's left limbs are the
mirror images the armour model draws — and a left cell's art is pre-mirrored in its own texture to
suit — so every left limb is drawn flipped; the wearer's right is on the viewer's left from the front
and on the viewer's right from behind, which is why the two swap ends. The source textures hold 2
texels per skin px and the doll is drawn at 3 px per skin px, so each face is resampled ×1.5 (which
keeps every texel the patch art has, at the price of every other column being 2 px wide), then: 1 px
transparent gaps between the parts, the viewer's right 15 % towards black and either arm 12 % further
(a limb is a box turning away from you), a seam at the waist, and a 1 px dark outline drawn *on* the
silhouette's own outermost pixels so it costs no room. Without the gaps and the outline a front view
of arms hanging at the sides is one flat slab of cloth and reads as a texture strip; with them it
reads as a figure. A patch layer takes the same shading and keeps off the outline's own pixels, so a
patch at the edge of a sleeve cannot cut the figure's edge open. There is no head: the garment's art
has none.

Every glyph is placed by arithmetic, in the container's title, which the client draws at (8, 6)
inside the container: `WardrobeFont.move` walks the cursor with a `space` provider of ±1 … ±128 px
and back again, and a glyph's *ascent* puts its top at a given container y (a bitmap glyph's top
lands at `textY + 7 − ascent`, so anything below the header has a negative one). That is also how
the tab highlight follows the player's own tab order and how the stats readout is right-aligned.

The preview's sixteen slots therefore carry no icon at all: **on every angle**, an item wearing the
`ovvar:invisible` model (a transparent 16×16) with a name and one lore line, no click handler, over
each placement the angle on show draws — the picture shows through and all that is left of the slot
is its "\<patch\> on \<spot\>" tooltip. Turn the figure and the tooltips turn with it: a patch sewn
on the back is hoverable on the back view and its front slot is empty, because a cell is on one face
of one box and a face is seen from one of the four sides.
`./gradlew :mods:ovvar:wardrobeSheet` composites the whole screen, and all four angles side by side,
to a PNG (`WardrobeSheet`, a dev tool) so the doll can be looked at without starting a client, and
`-PsheetState=audit` prints every cell's glyph against the art it is meant to be showing. "The art",
for a patch bigger than its cell, is only the part of it that lands on the cell's own face: datagen
wraps what hangs over round the box, so those columns are drawn on the face next door and the cell's
glyph is right not to have them. `WardrobePreview.shownArt` is that window — and, for the seat, the
two legs' halves in the order the back view puts the legs — and both the audit and the
`wardrobePreviewDrawsEveryCellsOwnPatchArt` game test compare against it.

**`/ovvar look [player]`** opens the same screen read-only on somebody else's ovve: their chapters as
tabs (the ones they have a design for — their inventory is none of our business and may not be
loaded), their design on the doll, the rotation buttons, the tooltips. No stash, no take out, no put
in, no sewing, no mannequin: help and close only, and the help item is titled "A look at \<name\>'s
ovve". It works for a player who is not here, because a wardrobe is a row in a store rather than an
inventory — the name goes through the server's own profile resolver (the one `/whitelist add` uses)
and the wardrobe is fetched by UUID.

The left block (cols 0-4, rows 1-4) is the patch collection — the stash, one slot per kind, left
and right click exactly as before (take out / start a session) — and it **pages** once there are
more kinds than fit, where it used to give the last slot up to a "+N more" marker. Twenty kinds
still use all twenty slots; from the twenty-first the two ends of the bottom row become the page
arrows (the rotation arrows' own sprites, pointing the same ways), so a page holds eighteen — and
both arrow slots are reserved on every page, even one that needs only the one, so an arrow never
moves under the pointer. Kinds are sorted by name, so a kind keeps its place as the counts change;
the page is per open screen; and "page 2/3" is drawn in the tiny type in the action row's spare
middle (cols 5-6), directly under the pocket's right end — the pocket is slots edge to edge with
nothing but 1 px gutters between them, and five pixels of type has to go somewhere it does not sit
on an icon. `WardrobeGui.perPage` and `pageCount` are the whole of the arithmetic, and the game test
exercises them as arithmetic: the catalogue holds two patches, so no wardrobe this server can build
has 45 kinds to page through. Which slot of the right block a
placement's tooltip sits on is `WardrobeGui.previewSlot(angle, spot)`, and it is measured, not
tabulated: `WardrobePreview.cellRect` puts a cell-shaped mask through the very `blit` the
compositor draws a patch with — the same per-part offset, the model's mirroring and the ×1.5
resample — and gives back the pixel rectangle the cell lands in, or null when that angle does not
show the cell. The panel is exactly the 4×4 block of 18 px slot cells at rows 1-4, cols 5-8, so the
slot is the one holding that rectangle's centre, and the tooltip cannot drift from the picture the
way a hand-kept table could (the front view's slots are the ones the old table gave, and a game
test pins them). `previewSlot(spot)` is the front overload, for the callers that only ever mean the
front. Two cells of one angle can still share a slot (16 slots, and a sleeve is 12 px wide), so the
last placement drawn to a slot wins its tooltip — a design with only one cell per slot (the common
case) always shows correctly.
Row 5's "finish" (col 4) is the old `StashGui`'s "Finish sewing" button, shown only while a stash
session is running.

The container title carries the background: `WardrobeArt` tints the single greyscale template
(`art/ovvar/wardrobe_template.png`) to the chapter's colour and draws it as one `bitmap` glyph in
the `ovvar:wardrobe` font, the same negative-space trick as the sewing dialog
(`SewingFont`) and better-pets' `pet_gui` — a `space` provider moves the cursor to the corner,
the glyph draws the whole 176×126 background, another space moves the cursor back, and every other
glyph the screen needs follows the same way. The title's own **text** is the ovve's name and nothing
else: the client draws it in the vanilla font at about 6 px a character on a screen 176 px wide, and
saying `Data ovve · earned 32 · sewn 11 · stash 0` there ran off the end of it. The counts are pixel
glyphs instead (`TinyType`, a 3×5 type face drawn in `TinyType`'s own table), right-aligned in the
spare header width: as many of the three as fit beside the name, and a name long enough to crowd
them out gives up its garment word rather than the counts ("Silicon-blue IT", not "Silicon-blue IT
ovve"). The help item's lore has all three in full whatever the header had room for. 176×126 is exactly
the `GENERIC_9x6` container's own six 18px rows below its header (`18 + 18*6`), not a px more, so
the glyph never paints opaque cloth or a stitch line over the player's own inventory below it.

"See it in 3D" (col 3, once "Show on mannequin") reuses the `/ovvar showcase` mannequin builder
(`ModCommands.buildMannequin`) through `WardrobeMannequin`, which adds the guardrails a
gamemaster-only command does not need: refused outright on a minigame server; one per player (a
second click discards the first, tracked by holding the entity, not by re-finding it in the
world); gone on its own after 60 seconds, past 8 blocks, or on disconnect; and the copy of the
ovve it wears has its `BUNDLE_CONTENTS` stripped and is permanently invulnerable (and can never
die at all (`ServerLivingEntityEvents.ALLOW_DEATH`), so there is no death event left to drop
its equipment on) — the guard against a copy of somebody's real, pocket-stuffed ovve becoming a
dupe machine.

**Deviations from the design doc**, for the record:
- The doc calls for 7 backgrounds; `Chapter` has 6 values (`DATA`, `IT`, `IT_KISEL`, `MEDIA`,
  `DATA_POLYMITER`, `IT_POLYMITER`), so there are 6 — one per chapter, as the doc's own "one
  background per chapter" says.
- the doc's two piece toggles are gone altogether: the preview shows the whole garment, both halves
  at once, and the two buttons there now turn it instead. Where a half still has to be named it is
  "trousers", not "feet": the legs and waist are what a wearer sees, and the boots render channel
  `OvveFeet` adds to that half is not a garment piece of its own.
- Row 5's "take out" and "sew on stand" are reminder icons, not buttons: there is no "selected
  patch" state, so the actual gestures stay on the collection slots themselves, as they always
  were. On a minigame server they are greyed out with the reason rather than gone.
- The doc's "mode text in the middle, drawn as background text" is instead the mode's first line
  in the help book (col 7), and the reason on each refused action: a container title is one line,
  and the glyphs on it are already drawing the background, the preview and the counts.

**Template art notes** (`tools/wardrobe_template.py`, run once, checked in): 176×126 (see above —
exactly the container's own rows), greyscale + alpha; a base cloth tone (132) with ±6 per-pixel
luminance noise for a woven feel; the patch panel (cols 0-4, rows 1-4) is a lighter "cream canvas
pocket" (200); the preview panel (cols 5-8, rows 1-4) is a slightly darker cloth (118), with no
painted silhouette any more — it always carries the rendered paper doll, and two figures on top of
each other was half of the "bad overlap of textures" this fixed. A dashed white overlock-stitch
outline (255) runs around one box per cell of the tab row and the action row, both panels, and the
whole background.

**Everything drawn lies on the slot grid.** A slot's *icon* fills the 16×16 at (8 + 18·col,
18 + 18·row) and the 18×18 *cell* around it — the ring the vanilla slot frame would use, which this
background hides — starts one pixel up and to the left, at (7 + 18·col, 17 + 18·row). Every line the
template draws is on that ring and never inside the 16×16; before that the panels' and the tab row's
outlines ran a pixel inside the slots and every tab's ovve icon had a stitch line through it. The
script checks it before saving and the `wardrobeTemplateBoxesAreOnTheSlotGrid` game test checks the
checked-in PNG, every slot of the grid. `WardrobeFont.cellX`/`cellY` are the same arithmetic for
everything placed at runtime. `WardrobeArt` tints
every non-white pixel by `luminance/255 * colour` and leaves anything at or above luminance 250
pure white, so the stitching reads the same on every chapter.

`config/ovvar.json` → `designs` (the store):

    backend                  file (default) | jdbc
    file_directory           file backend: an absolute directory, "" = <world>/ovvar/wardrobes
    jdbc.url                 jdbc:mariadb://host:3306/db  or  jdbc:postgresql://host/db  (drivers bundled)
    jdbc.user, jdbc.password, jdbc.password_env   the password from the file, or from the named environment variable
    jdbc.table               created if missing: owner CHAR(36) PRIMARY KEY, version, data (JSON), updated_at
    jdbc.driver_class        force a driver class; "" lets the URL pick
    jdbc.connect_timeout_seconds, jdbc.query_timeout_seconds
    bind_on_pickup           an unowned ovve becomes the first holder's (default true)
    others_ovve              somebody else's ovve: block (default: not wearable, nothing sewn on or off it)
                             | rebind (a given ovve becomes the holder's) | allow (anyone wears it, owner's design)
    edit_requires_owner      only an owned ovve's owner may sew on it or unpick from it (default true)
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
    sessions                 the private sewing flow exists (default false: the stash only hands patches out)
    stash_click              withdraw (default: left-click takes the patch out as an item, right-click opens a session) | session (the reverse)
    any_stand                sew and unpick on any armour stand wearing an owned ovve, not only a session stand (default true)
    session_reach            blocks a player may walk from their session stand (default 8)
    session_seconds          idle time before a session ends (default 300)
    explain_in_chat          the stash explanation when a patch is earned (default true)

`config/ovvar.json` → `server` (what this server calls itself):

    name                     this server's name in the MOTD (default "METAcraft")

The MOTD is set from those two blocks when the server is up, so the server list says what a player
gets before they join: `METAcraft Survival · ovve sewing on stands, patches are items`, or
`METAcraft Minigame · ovve stash only, no sewing` where `stash.minigame_server` is on. Every key
above is optional in the file: the defaults are the ones documented here, and a key only needs
writing to change it.

Since JSON has no comments, `config/ovvar.json` and each of its `designs`, `stash`, `server` and
`designs.jdbc` blocks carry their own `_help` object (rewritten every save, so edits to it do not
stick) with a `_about` line and one entry per key, the same text as above; open the file itself if
you would rather read the help there than here. Every block is always written, even one that is
exactly its own defaults, so its `_help` is always there too.

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
game tests — `JAVA_TOOL_OPTIONS="-Dfabric-api.gametest=true" ./gradlew :mods:ovvar:runServer`,
with `-PrunDir=<dir>` when a dev server already holds `./run`'s world lock — (`OvvarGameTests`): every cell aimed at on stands at rest, posed and turned, and the
sneak far-face rule, checked against `StandAim.cell`, the independent cell → point mapping; and
the stitching minigame played through with the clicks its dialog sends (stale clicks ignored,
sewn on the last pull, nothing sewn after cutting the thread). `WardrobeTests` runs the store
(both backends, the compare-and-set cache, one patch in one place) and the ownership rules: a
foreign ovve is refused by the equip checks and evicted by the tick, a stranger's sew and unpick
change neither the store nor the ovve, the owner's own still work, `rebind` and `allow` still do
what they say, and the MOTD names this server and its mode. It also runs the wardrobe screen: that
nothing the background draws overlaps a slot's icon, the title's glyphs and spaces, the tab row and
its highlight, the empty-state notices, the four angles and that every visible cell has a glyph of
its own, that a placement is drawn by that glyph on the angle that shows it and by nothing on the
other three, the rotation buttons, the title's length and the stats readout's place, what each mode
does to the action row (and that a refused action wears the dimmed model), and that `/ovvar look` is
read-only and driven by the other player's design.

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

## Credits

Patch art: Nyckeln'26 by Kexana; IT (from the PolymITer set), ITK, METAcraft Rivals '26 and Data by
Froosty11 (the last three are placeholders until redrawn). The ovve garment art is original to this mod, cut from the chapter skin overlays on
metacraft.se/style.
