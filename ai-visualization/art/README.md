# Theme art generators

Python + Pillow scripts that draw the pixel art of the Phantom AI Visualization themes. The plugin does not run them; it bundles their output.

- `pix.py`: colors, sprite canvas, 2:1 isometric box rasterizer, outlines.
- `sprites.py`: every sprite at native resolution, packed into sheets with an atlas each (frames with x/y/w/h and an anchor): theme A's `sprites.png`/`sprites.json` (54 frames) and `sprites-preview.png` in `OUT`, theme B's `sprites.png`/`sprites.json` (42 frames) in `OUT/b/`, theme C's (41 frames) in `OUT/c/`.
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

- **A** (`build_sheet()`, `OUT/`): theme A's frames unsuffixed (`floor_wood_0`, `wall_rw`, `desk_rw`, ...), the people (`person<n>_<state>`) and bubbles (`bubble_<state>`), plus some B variants under a `_b` suffix kept from before B had a sheet of its own. `ThemeA` uses only the unsuffixed frames. Leave this function's frame list alone: the bundled `themes/a/sprites.png` must not change.
- **B** (`build_sheet_b()`, `OUT/b/`): self-contained. B's art under a `_b` suffix (`wall_rw_b`, `wall_lw_b`, `wall_corner_b`, `desk_rw_b`, `desk_lw_b`, `chair_seat_rw_b`, `chair_back_rw_b`, `chair_lw_b`, `rug_0_b`..`rug_2_b`, `nameplate_b`, `glass_rw_b`, `glass_lw_b`, `plant_big_b`, `bookshelf_lw_b`, `coffee_lw_b`), its floor as `floor_tile_0`/`floor_tile_1`, and the decor that looks the same in every theme (`whiteboard_*`, `clock_rw`, `poster_rw`, `lamp`), the people and the bubbles re-exported under A's names. The `B` dict in `sprites.py` holds B's colors.

- **C** (`build_sheet_c()`, `OUT/c/`): self-contained, theme A at night. Drawn from the `Cn` dict (A with a night sky), so the windows show stars, the moon and a lit skyline. `night()` multiplies every pixel not marked emissive (`E()` in `pix.py`: screens, the lamp's glow, the night windows) by `NIGHT_AMBIENT`; the name plate boards are marked emissive first, so they stay bright (backlit). A's scenery comes under a `_c` suffix (`floor_wood_<n>_c`, `wall_*_c`, `desk_*_c`, `chair_*_c`, `rug_<n>_c`, `nameplate_c`, the decor, `lamp_c`), the people as `person<n>_<state>_c`, and the bubbles unchanged under A's names, since they are never dimmed. The light pools are not in the sheet: `ThemeC.lighting` names the frames that cast light, and the renderer adds the pools at runtime. Its `ambient` must equal `NIGHT_AMBIENT`.

- **D** (`build_sheet_d()` in `theme_d.py`, `OUT/d/`): self-contained, Restroom Cleaning (화장실 청소). Drawn from the `D` dict: a 4-unit mosaic of white tiles with teal ones (`floor_tile_<n>_d`), walls tiled teal below and white above with mirrors in the window segments (`mirror_px()`; `ThemeD` puts them at right segments 1 and 3, left 3, over the sink counter), a toilet with its seat and lid up plus a pedestal sink as the desk (`desk_*_d`, same 20x10 footprint; the toilet is at along 11..19, so it shows on the open side of the janitor in both orientations), a mop bucket as the chair (`chair_rw_d`/`chair_lw_d`, set beside the spot, no back frame), rubber drain mats as rugs (`rug_<n>_d`), A's sign board geometry as a yellow cleaning sign with hazard-striped caps (`nameplate_d`, so `NameplateSlice(12, 12, 12)` holds), and A's decor spots refilled (`urinals_*_d`, `dryer_rw_d`, `towel_rw_d`, `supply_lw_d`, `vanity_lw_d`, `wetsign_d`, `bin_d`, `cart_d`, B's `plant_big` as `plant_big_d`). The people are six janitors (`PEOPLE_D`), front-facing in every state, one frame per animation phase: `person<n>_<state>_<phase>_d`, four phases for working (the mop swings left, centre, right, centre with suds and a wet streak), two for the others (the plunger waves, the sparkles twinkle, a snot bubble swells); every phase of a look and state is cropped to one box with one anchor. All janitors stand with the boots on the anchor, so the working and idle ones, which the layout lifts by `SEAT_Z`, stand at the toilet and their session tag stays off the mop (`DROP` in the script). The bubbles are A's, under A's names.

## Add another theme

1. Add its palette dict (as `B` is) and any new draw code in `sprites.py`, and a `build_sheet_<x>()` like `build_sheet_b()` that packs its frames with `pack(frames, f'{OUT}/<x>')`; call it from `__main__`.
2. Run `sprites.py`, copy both files into `src/main/resources/dev/phantom/ai/visualization/themes/<x>/`, and check that the other themes' folders are still byte-identical to the output.
3. Add an `object Theme<X> : Theme` under `dev.phantom.ai.visualization.theme` (id, displayName, the two resource paths, `Palette`, frame names, window segments, decor, `nameplateSlice`, person/bubble names, optional `partition()` frames and `lighting`, as `ThemeB` and `ThemeC` do) and add it to the list in `Themes.all`. `Theme.frameNames()` is checked against the atlas on load and by the tests, so a missing frame fails loudly. Renderer, layout and model do not change.
