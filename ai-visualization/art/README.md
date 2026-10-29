# Theme art generators

Python + Pillow scripts that draw the pixel art of the Phantom AI Visualization themes. The plugin does not run them; it bundles their output.

- `pix.py`: colors, sprite canvas, 2:1 isometric box rasterizer, outlines.
- `sprites.py`: every sprite at native resolution, packed into sheets with an atlas each (frames with x/y/w/h and an anchor): theme A's `sprites.png`/`sprites.json` (182 frames) and `sprites-preview.png` in `OUT`, theme B's `sprites.png`/`sprites.json` (170 frames) in `OUT/b/`, theme C's (169 frames) in `OUT/c/`. Their people come from `people_hd.py`.
- `people_hd.py`: the office people of A, B and C, drawn at twice the scene's pixel density (`DETAIL`; `Theme.personDetail` in the plugin). Ten looks (`PEOPLE_HD`: hair style, skin, top, glasses, beard, lanyard...), each seated at the desk seen from behind typing (`person<v>_working_<0..3>`, four frames, `_lw` mirrored for desks along v), asleep on folded arms (`person<v>_idle[_lw]`), standing front 3/4 with an open hand raised (`person<v>_waiting_<0|1>`) and with a steaming mug, eyes closed in a smile (`person<v>_done_<0|1>`). Every size and anchor is even, so a frame covers whole scene pixels. A part is a mask shaded in two or three tones lit from the upper left, with a darker line of its own color where it meets a part behind it and a warm dark outline outside the silhouette. Run it on its own (`"$VENV/bin/python" people_hd.py [lineup.png]`) for a lineup of every look and pose at x4 (default `/tmp/viz-chars-lineup.png`) and the people at a desk of A (`/tmp/viz-chars-desk.png`).
- `scenes.py`: composes the mockups (`A-warm-wood.png`, `B-modern-bright.png`, `C-night.png`, native 480x300 at x3) from the sprites.
- `theme_d.py`: theme D on its own (imports `pix.py` and `sprites.py`, changes nothing in them): its sheet (89 frames) in `OUT/d/` and a mockup `OUT/D-restroom.png` (`all` as argument also writes `D-restroom-<phase>.png` for phases 1 to 3). Run it like `sprites.py`: `PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_d.py`.

## Regenerate

Run from the repository root. `VENV` and `OUT` are outside the repository; `OUT` must exist.

```
export VENV=/tmp/phantom-ai-viz-gen/.venv OUT=/tmp/phantom-ai-viz-out
mkdir -p "$OUT" && python3 -m venv "$VENV" && "$VENV/bin/pip" install Pillow
cd ai-visualization/art && PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" sprites.py
```

Keep the venv outside the repository (never `.venv` here); nothing under `art/` but the scripts and this file is committed. Output is deterministic: the same scripts give a byte-identical `sprites.png`.

Environment variables:

- `PHANTOM_AIVIZ_OUT`: the output directory, which must exist. Default `/tmp/phantom-ai-viz`.
- `PHANTOM_AIVIZ_FONT`: a `.ttc` or `.ttf` with Korean glyphs, used by the preview and the mockups. Default is the macOS path `/System/Library/Fonts/AppleSDGothicNeo.ttc`; `scenes.py` also picks a face by index in that collection, so another font may need that index changed.

## Put the output into the plugin

Copy each theme's two files, unchanged, to its resource folder:

```
cp "$OUT/sprites.png" "$OUT/sprites.json" \
   ../src/main/resources/dev/phantom/ai/visualization/themes/a/
cp "$OUT/b/sprites.png" "$OUT/b/sprites.json" \
   ../src/main/resources/dev/phantom/ai/visualization/themes/b/
cp "$OUT/c/sprites.png" "$OUT/c/sprites.json" \
   ../src/main/resources/dev/phantom/ai/visualization/themes/c/
cp "$OUT/d/sprites.png" "$OUT/d/sprites.json" \
   ../src/main/resources/dev/phantom/ai/visualization/themes/d/
```

The sheets in the plugin must stay byte-identical to what `sprites.py` (and `theme_d.py` for `d/`) writes; check with `cmp "$OUT/sprites.png" ../src/main/resources/dev/phantom/ai/visualization/themes/a/sprites.png` (and the same for `b/`, `c/` and `d/`).

## Mockups

From `ai-visualization/art`, `PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" scenes.py [A|B|C ...]` writes the sheet and the mockups for the given themes (all three without arguments).

## Sheets per theme

- **A** (`build_sheet()`, `OUT/`): theme A's frames unsuffixed (`floor_wood_0`, `wall_rw`, `desk_rw`, ...), the people of `people_hd.py` and bubbles (`bubble_<state>`), plus some B variants under a `_b` suffix kept from before B had a sheet of its own. `ThemeA` uses only the unsuffixed frames. Change its people in `people_hd.py` (the sheets of A, B and C change with them, D, E and F do not); `person()` in `sprites.py` is the old native-size person, still used by the `scenes.py` mockups.
- **B** (`build_sheet_b()`, `OUT/b/`): self-contained. B's art under a `_b` suffix (`wall_rw_b`, `wall_lw_b`, `wall_corner_b`, `desk_rw_b`, `desk_lw_b`, `chair_seat_rw_b`, `chair_back_rw_b`, `chair_lw_b`, `rug_0_b`..`rug_2_b`, `nameplate_b`, `glass_rw_b`, `glass_lw_b`, `plant_big_b`, `bookshelf_lw_b`, `coffee_lw_b`), its floor as `floor_tile_0`/`floor_tile_1`, and the decor that looks the same in every theme (`whiteboard_*`, `clock_rw`, `poster_rw`, `lamp`), the people and the bubbles re-exported under A's names. The `B` dict in `sprites.py` holds B's colors.

- **C** (`build_sheet_c()`, `OUT/c/`): self-contained, theme A at night. Drawn from the `Cn` dict (A with a night sky), so the windows show stars, the moon and a lit skyline. `night()` multiplies every pixel not marked emissive (`E()` in `pix.py`: screens, the lamp's glow, the night windows) by `NIGHT_AMBIENT`; the name plate boards are marked emissive first, so they stay bright (backlit). A's scenery comes under a `_c` suffix (`floor_wood_<n>_c`, `wall_*_c`, `desk_*_c`, `chair_*_c`, `rug_<n>_c`, `nameplate_c`, the decor, `lamp_c`), the people of `people_hd.py` at night under a `_c` suffix, and the bubbles unchanged under A's names, since they are never dimmed. The light pools are not in the sheet: `ThemeC.lighting` names the frames that cast light, and the renderer adds the pools at runtime. Its `ambient` must equal `NIGHT_AMBIENT`.

- **D** (`build_sheet_d()` in `theme_d.py`, `OUT/d/`): self-contained, Restroom Cleaning (화장실 청소). Drawn from the `D` dict: a 4-unit mosaic of white tiles with teal ones (`floor_tile_<n>_d`), walls tiled teal below and white above with mirrors in the window segments (`mirror_px()`; `ThemeD` puts them at right segments 1 and 3, left 3, over the sink counter), a toilet with its seat and lid up plus a pedestal sink as the desk (`desk_*_d`, same 20x10 footprint; the toilet is at along 11..19, so it shows on the open side of the janitor in both orientations), a mop bucket as the chair (`chair_rw_d`/`chair_lw_d`, set beside the spot, no back frame), rubber drain mats as rugs (`rug_<n>_d`), A's sign board geometry as a yellow cleaning sign with hazard-striped caps (`nameplate_d`, so `NameplateSlice(12, 12, 12)` holds), and A's decor spots refilled (`urinals_*_d`, `dryer_rw_d`, `towel_rw_d`, `supply_lw_d`, `vanity_lw_d`, `wetsign_d`, `bin_d`, `cart_d`, B's `plant_big` as `plant_big_d`). The people are six janitors (`PEOPLE_D`), front-facing in every state, one frame per animation phase: `person<n>_<state>_<phase>_d`, four phases for working (the mop swings left, centre, right, centre with suds and a wet streak), two for the others (the plunger waves, the sparkles twinkle, a snot bubble swells); every phase of a look and state is cropped to one box with one anchor. All janitors stand with the boots on the anchor, so the working and idle ones, which the layout lifts by `SEAT_Z`, stand at the toilet and their session tag stays off the mop (`DROP` in the script). The bubbles are A's, under A's names.
- **E** (`theme_e.py`, `OUT/e/`): self-contained, a restaurant kitchen (주방). All its code is in `theme_e.py`, which only imports `pix.py` and `sprites.py` (`pack`, `bubble`, `clock`, `foliage`, `floor_base`), so A/B/C do not change. Run it on its own: `PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_e.py [mockup]`, then copy `$OUT/e/sprites.png` and `sprites.json` to `themes/e/`. Red quarry tiles (`floor_quarry_<n>_e`), white subway-tile walls with a green band and pass windows into a dining room (`wall_*_e`), steel cooking stations as desks (`desk_rw_e`, `desk_lw_e`: burner, cutting board, sauce shelf), a wooden duckboard as the chair (`duckboard_*_e`, no back), rubber anti-fatigue mats as rugs (`mat_0_e`, `mat_1_e`), an order-ticket rail as the name plate (`nameplate_e`, same rows as A's) and kitchen decor (`fridge_lw_e`, `potshelf_lw_e`, `rice_lw_e`, `ladles_rw_e`, `orders_lw_e`, `knives_rw_e`, `extinguisher_e`, `bin_e`, `crates_e`, plus A's `clock_rw`). Six cooks, each with four real animation frames per state, `person<v>_e<phase>_<state>`; the phases of one state share size and anchor. Working is seen from behind at the station tossing a wok over flames (even looks) or chopping with a cleaver (odd looks); its frame reaches 7 px below the anchor, since the layout lifts a seated person by the seat height and the cook stands on the floor. Waiting rings a hand bell held high and calls out, done presents a plate under a cloche with steam, idle dozes on an upturned crate drawn in its frame. The bubbles are A's. `mockup` also writes `E-kitchen-<phase>.png` (a scene laid out like `scenes.py`) and `E-lineup.png` (every cook, state and phase at x4).
- **F** (`theme_f.py`, `OUT/f/`): self-contained, a construction site (공사장). Its own file imports `pix.py` and `sprites.py` and changes neither, so A, B and C stay byte-identical. Run `PHANTOM_AIVIZ_OUT="$OUT" "$VENV/bin/python" theme_f.py` from here (add `mockup` for `F-construction.png`, the scene of `scenes.py` in F's frames, and `F-crew.png`, every person frame at x6) and copy `$OUT/f/sprites.png` and `sprites.json` to `themes/f/`. Every frame has an `_f` suffix except the bubbles, which are A's under A's names: dirt floors `floor_dirt_<0..3>_f` (3 has a plank walkway), concrete walls with rebar `wall_rw_f`/`wall_lw_f`, chain-link fence over the sky with a tower crane on the window segments `wall_rw_fence_f`/`wall_lw_fence_f`, desks `bench_rw_f` (sawhorse workbench) and `brickwall_lw_f` (a low brick wall going up), seats `bucket_f`/`cinder_lw_f` (no chair backs), rugs `deck_<0..2>_f` (plywood, tarp, OSB), the striped site sign `sign_f` and the decor (site maps, signs, brick pallet, scaffold, mixer, cones, wheelbarrow, work light). People are `crew<v>_<state>_<phase>_f`, six variants with four phases each (idle has phase 0 only), all phases of a pose cropped to one size and anchor; `ThemeF.personFrame` picks them and `personOffset` adds no jitter. Working is seen from behind swinging a hammer (sparks) or tapping a brick with a trowel (dust); waiting raises a red flag; done gives a thumbs up with a ticked clipboard; idle sits turned round, arms crossed, hard hat over the face.

## Add another theme

1. Add its palette dict (as `B` is) and any new draw code in `sprites.py`, and a `build_sheet_<x>()` like `build_sheet_b()` that packs its frames with `pack(frames, f'{OUT}/<x>')`; call it from `__main__`.
2. Run `sprites.py`, copy both files into `src/main/resources/dev/phantom/ai/visualization/themes/<x>/`, and check that the other themes' folders are still byte-identical to the output.
3. Add an `object Theme<X> : Theme` under `dev.phantom.ai.visualization.theme` (id, displayName, the two resource paths, `Palette`, frame names, window segments, decor, `nameplateSlice`, person/bubble names, optional `partition()` frames and `lighting`, as `ThemeB` and `ThemeC` do) and add it to the list in `Themes.all`. `Theme.frameNames()` is checked against the atlas on load and by the tests, so a missing frame fails loudly. Renderer, layout and model do not change.
