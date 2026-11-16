# skin.dexhub (v3.28.228) — visual spec for a Jetpack Compose / Android TV re-implementation

Source: `skin.dexhub/xml/*` + companion `plugin.video.dexhub` (which sets the `Window(Home)` properties).
Canvas 1920x1080 px. **dp = px / 2** (1920 px = 960 dp). Notation: `px (dp)`. Colours are Kodi `AARRGGBB`
(alpha first). In Compose: `Color(0xAARRGGBB)`.

## 0. What "default" means here

| Setting (where) | Default | Effect |
|---|---|---|
| Colour theme (`theme_preset`=7, `skin_theme.DEFAULT_PRESET`) | **Dex Infinity** | colours/Dex Infinity.xml == colors/defaults.xml |
| Home layout `homeui_layout_mode` | **`cinema`** | full-screen spotlight; rows peek at bottom (§2.1) |
| Nav position `homeui_nav_position` | **`top`** | top glass bar (side rail is the alternative, §7) |
| `homeui_nav_icons_only` / `homeui_nav_names` | true / false | icon-only tabs; name shows only on focused/selected tab |
| `homeui_nav_autohide` | true | top bar hides while a row (not spotlight) has focus |
| `homeui_background_style` | `fullscreen` | backdrop fills 1920x1080 |
| `homeui_title_style` | `logo` | clearlogo instead of title text when available |
| `homeui_focus_motion` | `zoom` | focused card scales to 102–103 % |
| `homeui_card_style` | Overlay | info drawn on the card, no labels under poster cards |
| `homeui_row_dim` | `light` | unfocused rows at 85 % opacity |
| `homeui_poster_scale` | 100 | all `DhScale_px_N` constants == N px |
| `homeui_trailers` / delay / mode / sound / vol | on / 3 s / `card` / on / 30 % | §2.7 |
| `homeui_immersive` / delay | on / 16 s | UI fades away during a trailer |
| `homeui_carousel` / interval | on / 10 s | spotlight auto-advances |
| `homeui_kenburns` | on | backdrop slow zoom |
| `homeui_spot_count` | 8 | spotlight items |
| `homeui_clean_look` | on | "Ends at" pill, no type-badge on title page |
| `homeui_rating_inline` | on | |
| Skin strings written on fresh install (`skin_defaults.py`) | `dh.titletabs=on`, `dh.pagetone=artwork`, `dh.posterlogo=on`, `dh.glow=off`, `dh.bar.sysinfo=on` | |
| `dh.kodifocus` (focus colour) | unset ⇒ **white** focus | NOT accent: focus fill `F2FFFFFF`, text on focus `FF272727` |
| `dh.newlook` | unset ⇒ new look on (`dh6_new`) | |
| `DexHub.TitleActions` | unset ⇒ **Icons** design on title page | |
| Default focus on Home open | control 4010 (spotlight buttons) | |

## 1. Top bar / navigation (default: top, icons-only)

### 1.1 Container
- Whole bar group at (0,0). **Glass bar** image: x 48 (24), y 22 (11), **1824 x 76 (912 x 38)**, texture `glass_bar.png`
  (9-slice border 38 ⇒ fully rounded pill, radius 38 px (19 dp)). Hidden only if `dh.noglass`.
  - Measured texture: fill is a vertical gradient, top ≈ `rgba(127,128,131,0.32)` → middle `rgba(106,107,111,0.29)` →
    bottom `rgba(69,70,75,0.24)`; a ~2 px light rim: top edge `rgba(240,240,240,0.80)`, bottom edge `rgba(168,169,171,0.41)`,
    sides `rgba(217,217,218,0.61)`. Compose: `Brush.verticalGradient(0x52FFFFFF-ish greys)` + 1 dp border `0x99FFFFFF`
    (stronger at top). It is a frosted pill; no blur in Kodi.
- Page header scrim behind it (always on Home): `grad_top.png` 1920 x 250 (960 x 125) tinted **surface `FF0B0E1C`**;
  alpha stops (top→bottom): 0 % .85, 10 % .80, 20 % .67, 30 % .53, 40 % .40, 50 % .35, 60 % .31, 70 % .23, 80 % .12, 90 % .04, 100 % 0.
- Bar animation: when focus is NOT on the bar/spotlight buttons (4010)/profile/right icons: slide (0,-30) + fade to 60 %, 230 ms
  cubic-out. With autohide (default) and a row focused (`dhs.fr>0`): slide (0,-150) + fade to 0, 260 ms / 200 ms.
  During trailer "stage": fade 0 + slide (0,-40) 290 ms; "immersive": fade 0, 320 ms.

### 1.2 Left: profile / brand button (id 9005)
- Button x 70 (35), y 34 (17), **140 x 52 (70 x 26)**; focus texture `capsule52.png` (full pill, r 26 px) in focus colour `F2FFFFFF`.
- With a profile: avatar 40x40 (20) circle at (82,40); when focused a 44x44 ring (focus-on colour) behind it. Name label at
  x 136, w 156, h 52, font13 (Inter Regular 30 px / 15 sp), **bold**, white; on focus `FF272727`.
- No profile: Dex Hub wordmark `brand_wordmark.png` 122 x 36 (61 x 18) at (84,42), left-aligned keep-aspect
  (mono version tinted `FF272727` when focused). With a name but no avatar: infinity mark 48x48 at (80,36).
- Click: profile switcher.

### 1.3 Tabs (grouplist id 9000)
- x 214 (107), y 34 (17), w 900, h **52 (26)**, horizontal, **item gap 3 px**.
- **Default order** (skin string `dh.ord` unset): Home · Movies · Series · Servers · [Custom 1–3, only if defined] ·
  [Sport, only if `DexHub.SportTab`] · Add-ons · **divider** · Discover · Live TV · My Library · DexWorld · YouTube.
  Each (except Home) can be hidden by the add-on (`dhs.bar.off.<key>`); Servers also hides if `show_servers=false`,
  Live TV if IPTV/live is off.
- Icons are **PNG line icons, not Font Awesome** (`media/dexhub/nav/*.png`, 56x56 source, ~3 px stroke, Lucide/Feather style;
  selected tab uses a filled `_on` variant when one exists):

  | Tab | icon (selected) | label (en) |
  |---|---|---|
  | Home | house outline (`home.png` / `home_on.png` filled) | Home |
  | Movies | clapperboard (`movie`/`movie_on`) | Movies |
  | Series | TV with antenna (`series`/`series_on`) | Series |
  | Servers | server (`servers`/`servers_on`) | Servers |
  | Add-ons | puzzle/box (`addons`/`addons_on`) | Add-ons |
  | Discover | compass (`discover`) | Discover |
  | Live TV | broadcast waves (`live`/`live_on`) | Live TV |
  | My Library | stacked folder w/ play (`library`/`library_on`) | My Library |
  | DexWorld | DexWorld mark (`dexworld`) | DexWorld |
  | YouTube | YouTube (`youtube`) | YouTube |
  | Custom c1–c3 | user-chosen picture | user name |
- **Divider**: 12 x 52 slot, a 2 x 24 (1 x 12) line at (5,14), colour `40FFFFFF`.
- Each tab = a 28 x 28 (14 x 14) icon image at (18,12) inside the pill + a button with `width min 64 (32) auto`, h 52 (26).
  - **Unselected, unfocused**: no fill; icon tint `B3FFFFFF` (70 % white); no text.
  - **Selected tab (current page), bar not focused**: pill fill `2EFFFFFF` (18 % white, capsule r 26); icon `FFFFFFFF`
    (filled variant); the tab's **name is shown** inside the pill to the right of the icon (font `dh_nav` = Inter Regular
    28 px / 14 sp, white; text inset 18 px (9 dp) from right edge) ⇒ the selected tab is an expanding icon+label pill.
  - **Focused**: pill fill `F2FFFFFF` (95 % white). The focus texture (`nav/f_<tab>.png`, 9-slice border 56/0/26/0) already
    contains the icon drawn dark (~`#262626`) at the left, so the focused pill = white pill, dark icon, dark label
    (`FF272727`), label shown (only the focused tab shows its name while the bar has focus).
  - Focusing a tab also opens it (`homeui_nav_focus_open` true) — no click needed.
- Alternative (`homeui_nav_names=true`): text-only pills, font dh_nav 28 px, min width 72 (36), text inset 22 px (11 dp),
  selected fill `2EFFFFFF`, focus `F2FFFFFF` + `FF272727` text.

### 1.4 Right side
- **Icon buttons** (list id 9001): x 1256 (628), y 28 (14), item 76 x 70 (38 x 35); each item: focus capsule 68 x 52 (34 x 26)
  at (0,6), icon 28 x 28 (14) at (20,18). Icon tint `B3FFFFFF`; focused: capsule `F2FFFFFF`, icon `FF272727`.
  Default visible items in order: **Edit Home** (`nav/edit.png`, pencil), **Search** (`nav/search.png`, magnifier),
  **Menu** (`nav/menu.png`, 3-line hamburger), **Power** (`nav/power.png`). (Add-ons and "back to previous skin" items exist
  but are hidden by default.) ⇒ visible x positions 1256, 1332, 1408, 1484.
  The list slides left (220 ms cubic-out) by 171–235 px when the "island" widens (party/alerts/now-playing).
- **Island / clock pill** (button 9510): group at x 1608 (804), y 28 (14). Default state (no alerts, nothing playing):
  - pill 244 x 52 (122 x 26) at (0,6): fill `C70E1014` (78 % #0E1014) + 1-px ring `24FFFFFF` (`pill_ring.png`).
  - bell icon 28 x 28 at (20,18), tint `8CFFFFFF` (55 %; `F2FFFFFF` if unread alerts, with a red/white count badge 32 x 20
    capsule, font `dh_badge` Inter Bold 19 px, text `FF111318`).
  - vertical divider 2 x 22 at (70,21) `33FFFFFF`.
  - **clock** `System.Time`, font `dh_clock` (Inter Regular 32 px / 16 sp, rendered **bold**), white, centred in 162 px at x 74.
  - Focused: pill `F2FFFFFF`, icon and clock `FF272727`, divider `33000000`, zoom 92→100 % 160 ms. Click opens the alerts panel.
  - Variants: now-playing (np icon, title + time remaining, 3-px accent progress), notifications (tinted circle: sport
    `FF22A55A`, silo `FF7C5CE0`, new-ep/soon `FFE0961F`, resume `FF3B82F6`, else `FFE5484D`), watch-party (accent pulsing ring).
    Extended pills are 412–476 px wide, 56 tall, fill `F5050608`, labels `dh_small_b` (Bold 22) + `dh_tiny` (19, `A6FFFFFF`).
- No weather/CPU text by default.

## 2. Hero / spotlight (layout `cinema`)

### 2.1 States and vertical layout
The spotlight is fed by hidden list 4000 (8 items, `homeui_spot_count`). Three states (all y in px; dp = /2):

| Element | **A. Spotlight focused** (default on open; focus on buttons 4010) | **B. A row focused** (`dhs.fr ≥ 1`) |
|---|---|---|
| Hero group offset | +40 (roomy) **+260** | +40 |
| Clearlogo box (bottom-left aligned) | x 96, y 440–590 (660 x 150) | x 96, y 180–330 |
| Facts line (pills + meta) | y 604, h 36 | y 344 |
| Ratings / studio line | y 652, h 40 | y 392 |
| Episode name (if episode) | y 700 | y 440 |
| Plot (2 lines) | y 702 (+40 under an ep name, +50 if badge row) | y 442 |
| Action buttons | y 836, x 84 (only in A) | hidden |
| Page dots | y 900, x 96 (only in A) | hidden |
| First row title | **y 940** (rows group 650 + 290) – only titles peek | **y 650** |

- Top bar visible in A, hidden in B. When top bar is focused and fr=0: hero +80 and rows +80 (rows at 730).
- Transitions: hero/rows slides 240–320 ms `cubic-out`; the hero text group enters with fade 0→100 + slide x −30→0, 260 ms.
- Row-focused hero (B) shows the **focused card's** info and backdrop, same geometry; title fallback fonts there are
  font60 (60 px) short / font45 multi-line.

### 2.2 Backdrop treatment (z-order bottom → top)
1. Video layer 1920 x 1080 (trailer / playing video behind Home).
2. Backdrop: focused item `fanart`, **1920 x 1080 fill, top-aligned crop** (`aspectratio scale aligny=top`), 300 ms crossfade.
   Ken Burns (`dhs_drift`: idle ≥ 6 s, no trailer): zoom 100→105 % over **40 000 ms**, sine in-out, centre (960,540).
   (Non-fullscreen style: 1300 x 731 at x 620 masked by `hero_mask.png`, i.e. right-aligned 16:9 with soft left/bottom edges.)
3. `grad_top.png` 1920 x 250, surface colour (stops in §1.1).
4. `full_left.png` **1260 x 1080 (630 x 540)**, surface colour `FF0B0E1C`, horizontal alpha (left→right):
   0 % .86, 10 % .84, 20 % .77, 30 % .67, 40 % .56, 50 % .43, 60 % .30, 70 % .18, 80 % .09, 90 % .02, 100 % 0.
5. `full_bottom.png` at y 440, **1920 x 640 (960 x 320)**, surface colour, vertical alpha (top→bottom):
   0 % 0, 10 % .05, 20 % .14, 30 % .26, 40 % .40, 50 % .53, 60 % .66, 70 % .78, 80 % .87, 90 % .94, 100 % .96.
6. Hero text shade `hero-text-shade.png`: x 0, y 120 (+hero offset), 1220 x 420 (610 x 210), black radial-ish blob,
   max alpha ≈ .37 at ~10 % from left / vertical middle, fading to 0 at all edges.
7. While a trailer plays full-screen: `shade_380_1350.png` 1920 x 1080 in surface colour (left & bottom vignette: left
   edge α 1.0 → 0 by ~70 % width in the top half; bottom 30 % ≈ α .9–1.0), fading to 45 % in "stage", 30 % in immersive;
   left/bottom scrims fade to 35 % / 45 %.
- No explicit vignette other than the above; no accent glow on Home (`dh.glow=off`).

### 2.3 Title / logo
- Clearlogo 660 x 150 (330 x 75), keep aspect, **aligned left + bottom** in its box, 250 ms fade.
- Fallback text (no logo): short title `dh_hero` = Inter **Bold 78 px (39 sp)**, white, shadow `66000000`, in 900 x 100 at y 190
  (+offsets); long titles `dh_title` Bold 56 px (28 sp) wrapped in 900 x 132 at y 160.
- Spotlight source label (e.g. "Trending"): font12 bold `B3FFFFFF`, at dots y −14, placed right of the dots (only state A).

### 2.4 Facts line (horizontal, item gap 12 px (6 dp), x 96, h 36)
Order:
1. **Type/episode badge** pill (if `dhs.badge`: "Movie" / "Series" / "S1:E7" / "Collection"…): h 32 (16), auto width,
   padding 16 px (8 dp) each side, fill `33FFFFFF` (20 % white) full capsule, font10 (Inter 23 px / 11.5 sp) **bold** white.
2. **Seasons** pill ("3 Seasons"): same, fill `24FFFFFF` (14 %), regular weight.
3. **Show status** pill, h 30, padding 14, UPPERCASE bold font10, fill by status: Returning `8C16A34A`, Ended `594B5563`,
   Canceled `8CDC2626`, In production `8CD97706`, Planned/Pilot `8C2563EB`.
4. **Meta text** (not a pill): font12 (Inter 25 px / 12.5 sp) `F2FFFFFF`, shadow `66000000`, max 760 px. Content built by the
   add-on: `1 Oct 2025   •   2h 14m   •   Action / Thriller` (date or year, runtime, ≤2 genres; separator is
   3 spaces + U+2022 + 3 spaces).
5. **Certificate** pill (MPAA e.g. "PG-13"): h 32, outline only — 1–2 px ring `99FFFFFF` (`pill_ring.png`), bold font10 white.

### 2.5 Ratings / studio line (x 96, y facts+48, h 40, item gap 12; inner rating group gap 16 px (8 dp))
- Optional **studio logo** first (white logo, 40 tall, width bucket 48…272 px) followed by a 1 x 22 divider `30FFFFFF`.
- Ratings (each logo then value, value font12 **bold** `F2FFFFFF`, value overlaps logo by −8 px):
  IMDb logo 58 x 30 · TMDb % ring image 46 x 38 · RT critic icon 31 x 31 · RT audience popcorn (fresh 23 x 32 / rotten 49 x 31) ·
  Metacritic logo 30 x 30 + score box 38 x 30 (colour from add-on, text white or `FF1B1B1B`) · Trakt 30 x 30 ·
  Letterboxd 48 x 18 · MDBList 30 x 30 · MAL 46 x 26.
  Fallback when no rating set: gold star `FFF5C518` 26 x 26 + rating text.
- "Watched" mark (Trakt check 26 x 26 + label) and **"Ends at 22:41" pill** (h 32, fill `24FFFFFF`, font10) close the line.

### 2.6 Plot, buttons, dots
- **Plot**: x 96, 900 x 64 (450 x 32) ⇒ **2 lines**, font12 (25 px / 12.5 sp), colour `D9FFFFFF` (85 %), shadow `A0000000`, wraps.
- **Episode name** (episodes only): `dh_nav_b` Inter Bold 28 px (14 sp) white, 900 x 36.
- **Action buttons** (list 4010): x 84, horizontal, item pitch 290 (gap 20) – the list is scaled **84 %** about its top-left, so
  rendered size per button ≈ **227 x 57 px (113 x 29 dp)**, gap ≈ 17 px; design size 270 x 68, capsule r 34.
  Buttons in order (FA6 Solid glyph in `dh_icon_s` = fa-solid-900 24 px, at x 24 w 40; label `dh_label_b` Inter Bold 28 px at x 70):
  1. **Play** — `U+F04B` (play) — label "Play" (or "Resume" with progress; add-on may override)
  2. **More info** — `U+F05A` (circle-info)
  3. **Trailer** — `U+F03D` (video)
  - Rest: fill `B3161B22` (70 % #161B22) + ring `2EFFFFFF` (glass); text/icon white.
  - Focused: fill `F2FFFFFF`, text/icon `FF272727`. (With `dh.noglass`: rest fill `40FFFFFF`.)
- **Page dots**: one per spotlight item, pitch 22 px (11 dp): inactive 12 x 8 (6 x 4) capsule `66FFFFFF`; active **34 x 8 (17 x 4)**
  capsule `FFFFFFFF`; items after the active one shift +22. No animation (instant).
- **Carousel**: auto-advances every **10 s** while fr=0, idle ≥ 4 s and < 600 s, and no trailer active.

### 2.7 Trailer behaviour
- After **3 s** on a spotlight item (or a big card), a 720p trailer starts, sound on at 30 % volume.
- Spotlight (fr=0): trailer plays **full-screen behind the UI** (backdrop crossfades out 270 ms); after 1.5 s visible + 3 s idle
  → "stage": top bar fades out, hero text slides down 100 px, scrims fade (§2.2-7); hero info fades (trailer-clean) 290 ms.
  After **16 s** idle → "immersive": everything fades (390 ms) except a small logo that slides to (120, ~820); any key restores.
- In rows, mode `card`: the trailer plays **inside the focused card** for wide/tall/feature/landscape_info/wide_ranked cards
  (the card's art is hidden, video shows through the card mask).

## 3. Rows

### 3.1 Row block geometry (all shapes)
- Rows stack from y **650** (state B) with **pitch 348 px (174 dp)** per row (title 44 + item 292 + 12). Focusing row n>1
  slides the whole stack up 348·(n−1), 200 ms cubic-out. Rows above the previous one fade out (200 ms).
- **Row title**: x 96 (48), y 0, 1100 x 44 (550 x 22), font `dh_row` = **Inter Bold 30 px (15 sp)**, white, vertically centred.
  Right-aligned subtitle/counter: x 1200–1824, font10 (23 px) `99FFFFFF`.
- **List**: x 82 (41), y 44 (22), width 1782, horizontal; **focus stays at position 0** (`focusposition 0`, `movement 1`):
  the focused card is the first visible card and the row scrolls under it, **300 ms sine in-out**.
- Card art usually starts 14 px inside the item ⇒ first card left edge at x 96 aligned with the title.
- Unfocused rows: 85 % opacity (`light`; `strong` = 60 %).
- Loading skeleton: `tile_bg` (r ≈ 16 px) cards in `dh_card FF161B30`, pulsing opacity 100↔45 % (900 ms sine).
- Cards are drawn as: surface `dh_card FF161B30` placeholder → art masked by rounded-rect mask → 1-px-ish inner edge
  `2EFFFFFF` (`dh2_edge_*`, ≈2 px at 18 % white) → overlays.
- Common **focus treatment (default `zoom`)**: scale **102 %** (posters, 140 ms cubic-out) / **103 %** (landscape/wide);
  soft drop shadow `glow.png` 9-slice (border 60) extending **26 px (13 dp)** beyond the card, colour **`8C000000`** (55 % black);
  **focus ring**: `dh2_ring.png` drawn 4 px outside the card on all sides, **~4 px (2 dp) stroke**, colour **`F2FFFFFF`**
  (white 95 %; accent only if focus colour = accent), outer radius ≈ 24 px (12 dp). Landscape cards also get `card_sheen.png`
  (white, α .27 at top fading to 0 by ~25 % height). No border on unfocused cards beyond the 18 % inner edge.
- Other focus motions: `tvos` 114 % (109 % wide) + lift −8 px + extra shadow `4D000000`; `lift` −16 px; `pop` 112 % back-tween
  210 ms; `glow`. "Calm motion" disables them.
- **Watched badge**: `watched_trakt.png` (round check) 32 x 32 (16) at top-right inset 10 (poster) or 26 x 26 inset 8 — only
  when PlayCount > 0 and no progress. Dimmed items (`dhs.dim`) at 60 %.
- **Progress bar** (`ListItem.PercentPlayed>0`): rounded `bar.png` (r 4/2 px), track `66FFFFFF`, fill accent `FF9B6BFF`
  on poster/landscape cards, **white `FFFFFFFF`** on wide/big/tall/feature cards.
- "More" / "See all" tile: card fill `FF1E2440`, arrow 60 x 60 `E6FFFFFF` centred, label font12 bold white.

### 3.2 Corner radii (from mask textures)
| Mask | radius in the drawn card |
|---|---|
| poster (`dh2_mask_poster`, 2x tex r 34) | **17 px (8.5 dp)** at 176 x 264; scales with card |
| landscape (`dh2_mask_landscape`, 640x360 tex r 27) | ≈ 20 px at 480 x 270, 13.5 px at 320 x 180 (≈ 4.2 % of width) |
| square (`dh2_mask_square`, 352 tex r 24) | ≈ 6.8 % of side |
| tile_bg (9-slice border 16) | ≈ 16 px (8 dp) – collection banners, skeletons |
| focus ring | outer ≈ 24 px (12 dp), 4 px stroke |
| circle | full circle |

### 3.3 Shapes (sizes at 100 %; "item" = list slot incl. spacing; card = drawn art)
Default auto shapes: **catalog rows = `poster`**; **Continue Watching = `wide`**; Dex genre/mood/shelf collections = `wide`.

1. **poster** (default) — item **198 x 292 (99 x 146)**, card **176 x 264 (88 x 132)** at (0,14) ⇒ **gap 22 px (11 dp)**.
   No title under card (Overlay style). Missing poster: card fill `FF161B30` + title textbox font12 bold `99FFFFFF` centred
   (or initials font14 `66FFFFFF`). Folder/library/channel tiles: bottom shade `tile_shade` (172 px) + label font12 bold
   white centred at y 214. Progress 152 x 6 at (12,250), accent fill. Focus: 102 %, ring 184 x 272 at (−4,−4), shadow 228 x 316.
2. **wide** ("Apple-TV big banner", Continue Watching) — item **504 x 292 (252 x 146)**, card **480 x 270 (240 x 135)**, gap 24.
   Art = landscape/fanart. Bottom shade from y 110 (160 tall). Clearlogo 280 x 88 at (22,110) (or title font30_title
   Inter Bold 30 white at (22,130)). If progress: **white** bar 64 x 6 at (22,232) + meta text (`wide_meta`, e.g.
   "S2 E4 · 23m left") font12 `D9FFFFFF` at (98,220); else meta at (22,220). Focus 103 %, ring 488 x 278.
3. **landscape** — item **342 x 208 (171 x 104)**, card **320 x 180 (160 x 90)**, gap 22. Logo 240 x 60 at (16,48) for works;
   folder labels font12 bold + subtitle font10 `CCFFFFFF`. Progress 296 x 6 at (12,166) accent.
4. **ranked** (Top 10) — item **310 x 292**; outlined rank numeral image 230 x 180 at (0,98) (hollow numerals, ~8 px light-grey
   `#F0F0F0` stroke, transparent fill, Netflix style), poster 176 x 264 at (112,14) overlapping the numeral's right side.
5. **wide_ranked** — item **636 x 292**; numeral 250 x 200 at (0,84); wide card 480 x 270 at (132,14).
6. **tall** ("cards that open up") — unfocused item 206 x 292, poster **190 x 276** at (0,8) with clearlogo 166 x 66 at (12,194)
   over a bottom shade. **Focused card expands to 491 x 276 landscape** (item 507): grows from 39 % width (zoom 39→100 %
   horizontally, 220 ms cubic) + fade 70→100 (180 ms); logo 270 x 71 at (22,151), meta font12 `D9FFFFFF` at (22,234).
7. **tall_ranked** — like tall with numeral 240 x 202 at (0,82) and card at x 112 (items 318 / focused 619).
8. **circle** (networks, actors) — item 240 x 292; circle **200 x 200** at (20,14); logo 128 x 128 centred over a `A6000000`
   disc when a logo exists; label font13 (Inter 30 px) `B3FFFFFF` (focused white) centred under at y 236. Focus = 208 px
   circle in ring colour behind (4 px halo).
9. **poster_large** — item 240 x 420; poster **216 x 324** at (0,14); title font13 white at y 346, meta font10 `CCFFFFFF` at
   y 384 (labels under the card). Progress 195 x 5 white.
10. **feature** (huge cinematic banner) — item 664 x 420; card **640 x 360**; shade from y 172; logo 320 x 84 at (28,220);
    meta font12 `F2FFFFFF` at (28,320); progress 240 x 5 accent at (28,310).
11. **landscape_info** — item 376 x 292; card **352 x 198**; title font13 white at y 220, meta font10 `CCFFFFFF` at y 258.
12. **collage / mosaic / stack** (collection wall, one banner) — item 1120 x 292; banner **1100 x 276** `tile_bg`
    (`FF16161B`/`FF14161C`). Collage: logo 363 x 126 at (66,66) left, posters wall of 112-wide masked tiles from x 466 in
    3 staggered columns, fanart fallback, `grad_left` blend `FF16161B`. Mosaic: 256² + 123² square grid. Stack: up to 9
    posters 146 x 215 fanned with `CC000000` shadows. Title bar: accent bar 5 x 34 at (18,200) + title font30_title + line font12.
13. **collection_spotlight** — 1100 x 264 card `dh_card`: fanart 469 x 264 left; title font30_title at (495,12); 3 preview
    thumbs 188 x 106 with titles font12 `E6FFFFFF`.
14. **collection_compact** — 640 x 264: 3 stacked posters 128 x 192 offset 26/12 px; logo 348 x 96 at (260,66); line font12.
15. **collection_ranking** — 640 x 264: title font27 bold, three lines "1/2/3" (font27 `80FFFFFF`) + 51 x 28 thumb + title font13.
16. **shelf** (user collection row) — item 664 x 292 card 640 x 276 using the collection looks above.
- **square** is listed in the add-on but has no dedicated skin layout (renders with the poster template).
- `two_rows` layout uses ×0.8 versions of every shape (e.g. poster item 158 x 234).

## 4. Fonts (fontset "Default"; all InterArabic = Inter + IBM Plex Sans Arabic)

| Kodi name | File | px | sp (=px/2) | Used on Home for |
|---|---|---|---|---|
| dh_hero | InterArabic-Bold | 78 | 39 | spotlight title (no logo) |
| dh_title | InterArabic-Bold | 56 | 28 | long spotlight title |
| font60 / font45 | Regular (+[B]) | 60 / 45 | 30 / 22.5 | row-focus hero title fallback |
| dh_row | Bold | 30 | 15 | **row titles** |
| font30_title | Bold | 30 | 15 | titles on wide/feature/collection cards |
| dh_nav / dh_nav_b | Regular / Bold | 28 | 14 | tab labels / episode name |
| dh_label_b | Bold | 28 | 14 | spotlight button labels |
| dh_clock | Regular (+[B]) | 32 | 16 | clock |
| font14 | Regular | 33 | 16.5 | initials on artless cards |
| font13 | Regular | 30 | 15 | profile name, circle/poster_large labels |
| font12 | Regular | 25 | 12.5 | meta line, plot, ratings values, card labels |
| font10 | Regular | 23 | 11.5 | pills, subtitles |
| dh_small / dh_small_b | Reg / Bold | 22 | 11 | island text |
| dh_tiny, dh_badge | Reg / Bold | 19 | 9.5 | island second line, count badges |
| dh_icon_s / dh_icon / dh_icon_l / dh_icon_xl | fa-solid-900.ttf (Font Awesome 6 Free Solid) | 24/32/42/58 | 12/16/21/29 | glyph icons |
| font_flag / font20_title…font52_title | Bold | 18,20,25,30,32,36,40,45,52 | | misc |
Other bundled: LeagueSpartan-ExtraBold (player info tags, uppercase 24), NotoMono 26. `[B]` in labels ⇒ use SemiBold/Bold.
Font sets with 90–120 % scaling exist; default 100 %.

## 5. Colours — Dex Infinity (default) and accent usage

| Token | Hex (AARRGGBB) | Use |
|---|---|---|
| dh_accent / button_focus | **FF9B6BFF** (violet) | progress fills, collage accent bar, island now-playing bar, party ring, spinner |
| selected | FFB490FF | Kodi list selection |
| dh_accent_dim / button_alt_focus | 479B6BFF / 809B6BFF | dim fills |
| dh_secondary / blue | FF4F8BFF (electric blue) | secondary |
| dh_surface / primary_background | **FF0B0E1C** (navy) | all scrims (`full_left`, `full_bottom`, `grad_top`) |
| background | FF06080F | |
| dialog_tint | FF101426 | dialogs |
| dh_card | **FF161B30** | card placeholder |
| secondary_background | 33161B30 | |
| dh_text / white | FFF3F5FF | text token (Home mostly uses pure FFFFFFFF) |
| dh_muted / grey | FF9AA3C4 | muted text |
| dh_ok | FF34D399 | success/cached |
| red | FFE5484D | errors, alert island |
| dh_on_accent | FFFFFFFF | text on accent |
| dh_glow_a / dh_glow_b / dh_glow_focus | 5C9B6BFF / 404F8BFF / 999B6BFF | accent glows (off by default) |
| border_alpha / disabled | 60FFFFFF / 40FFFFFF | |
| text_shadow | 22000000 | |
| **Focus fill** (DhKodiFocus) | **F2FFFFFF** | every focused pill/button/ring |
| **Text on focus** (DhKodiOnFocus) | **FF272727** | |
| Focus shadow (DhGlowFocus) | 8C000000 | card drop shadow |
| White alphas used | F2 (95 %), E6 (90 %), D9 (85 %), CC (80 %), B3 (70 %), 99 (60 %), 66 (40 %), 33 (20 %), 2E (18 %), 24 (14 %) | text hierarchy / glass |
| Rating star | FFF5C518 | |
| Status pills | 8C16A34A / 594B5563 / 8CDC2626 / 8CD97706 / 8C2563EB | §2.4 |

**Accent application**: the default design is deliberately *white-focus* (Apple-TV-like). Accent appears only in progress bars on
poster/landscape cards, collection title bars, playing indicators/dots, loader spinner, party ring. Setting
`Focus colour = accent` (`dh.kodifocus=accent`) switches every focus fill and ring to `FF9B6BFF`, focus text to `FFFFFFFF`,
and the focus shadow to `999B6BFF`. The add-on can override all tokens at runtime via `dexhub.tvos.theme.*` properties
(accent, accent_dim = accent @ 0x47, glow = accent @ 0x26, surface, surface_card, text, muted, on_accent).
Other themes (same keys): Dex Crimson E04355/0D0D12/1B1B24, Midnight Ocean 38BDF8/070B14/121B2E, Royal Violet 8B5CF6/0E0A16/1D1430,
Emerald Noir 34C495/08110D/12211A, Amber Cinema F2A93B/120F0A/241C12, Slate Mono E5E7EB/0C0D0F/1A1C21, OLED Black 8C9BFF/000000/111216,
Graphite, Deep Teal, Rose Noir, Nordic Night, Nuvio Black E5484D/000000/121212, Pure Black, Black Gold, Black Aqua, 10× "Nuvio *".

## 6. Title page (DialogVideoInfo, Dex Hub details) — default "new look", tabs on, artwork tone, Icons actions

- **Page background**: solid `DhPageTone` = colour sampled from the artwork (`dhs.art.tone`; fallback surface). Backdrop
  fanart **1400 x 788 (700 x 394) at x 520, y 0**, masked by `title_mask.png` (left edge alpha 0→1 over the first ~60 % of
  width; bottom 40 % fades to 0; top-left ≈ .62). Then `grad_top` 1920 x 250 and `grad_bottom` at y 520 (1920 x 560) in the
  page tone, plus two `fold_glow` radial glows in the artwork glow colour (1560 x 1300 at (−360,−460) and 1100 x 900 at
  (1240,620)). Window fade in/out 200 ms.
- Vertical grouplist from y 26, gap 16. **Header block (542 px)**:
  - **Poster** 252 x 378 (126 x 189) at (96, 110), poster mask r ≈ 24 px, shadow tile `66000000` 6 px around; logo on poster on.
  - Text column x 424 (212):
    - **Clearlogo** 760 x 140 (380 x 70) at y 62 (fallback title font60 bold white, long titles font45).
    - Facts pills at y 208, h 36–40, gap 16: seasons (`26FFFFFF` rounded-rect r≈16), status (status colours), facts capsule
      40 tall font12 `F2FFFFFF` (`year • runtime • genres • cert`).
    - Ratings line y 264 (same rating logos/values as hero, gap 16) + studio mark after a 1 x 22 divider.
    - **Plot** textbox y 316, 1100 x 99 (3 lines), font12 `D9FFFFFF`.
    - Parental-guide pills (optional) y 430: capsule34 `1FFFFFFF` font10.
    - Watch progress (optional) y 470: 200 x 6 track `33FFFFFF` with 20-px steps `F2FFFFFF` + status text font10 `D9FFFFFF`.
  - **Actions** at x 84, y 500, h 68 (34), gap 6 (Icons design): 68 px capsules (`capsule68`, r 34) with a 48 x 48 (24) icon;
    **Play is a pill that always shows its label** ("Play"/"Resume"), the others are 68 x 68 glass circles (`40FFFFFF`)
    that expand to icon + label (max 560 px) when focused. Focus: fill `F2FFFFFF`, icon/text `FF272727`, zoom 94→100 % 200 ms.
    Order: Play (`play-f`), Sources (`layers`), Trailer (`clapperboard`), Favourite (star / filled star), Track/Watchlist
    (`bookmark`), Mark watched (`circle-check`), TMDb Helper (only if installed), Aspect/`ratio`, More (`ellipsis`).
    Alternative `pills` design: 232–280 x 62 capsules with 28 px icons + labels (Play/Sources/Trailer/Favourite/Track/More).
  - Hint capsule top-right (only while actions focused): 198 x 52 at (1626,30), `8C101418` + ring `33FFFFFF`, icons ↑ bookmark bell calendar.
- **Tabs bar** (58 px): x 96, labels font12, gap 40, 52 tall; active/focused underline **3 px** (`tab_underline.png`) white;
  inactive text `FFB7BCC8`, current tab text white with `B3FFFFFF` underline; full-width hairline 1728 x 1 `1FFFFFFF` at y 57.
  Tabs: Episodes (series) · Cast · Reviews · Artwork · More like this · Collection (movies) · Studios.
- **Sections** (each with title font14 bold white at x 96 when tabs are off; lists at x 84):
  seasons pills 204 x 52 capsule `99161B22`; episode cards 400 x 225 (r≈17) + title font12 bold `E6FFFFFF`, sub font10 `99FFFFFF`,
  watched check disc `A60B0E14`, "★ rating" top badge capsule `F2F5B428` / text `FF1A1300`; cast circles 150 x 150 + name font10
  bold + role font10 `99FFFFFF`, focus ring 162 px; reviews 704 x 266; artwork 408 x 300; related posters 198 x 292 (Home poster
  card); collection 800 x 330; studios 258 x 130. Focus treatment = Home's (102 %, white ring, `8C000000` shadow).
- Alternative `dh.titlelayout=cinematic`: full-screen fanart + `grad_left` 1500 px, info card on the right.

## 7. Side menu (alternative nav, `homeui_nav_position = side`)

- Not shown by default (top bar is default). When enabled it replaces the top bar:
  - Glass rail background x 20, y 76, **92 x 920 (46 x 460)**, `tile_bg` (r ≈ 17 px) in `DB141821` (86 % #141821) + ring `38FFFFFF`
    (`dh2_ring_r21`); when names are shown (icons-only off) and it is focused, it widens to **356 px (178 dp)**.
  - Items: vertical grouplist x 32, y 96, gap 4, each **64 x 64 (32 x 32)** capsule (r 32); focused = `F2FFFFFF` capsule
    (310 wide with name when names shown), icons tinted like the top bar; current section's icon in accent.
  - Order: Profile, Search, Home, Movies, Series, Servers, Sport, Add-ons, Custom 1–3, divider, Discover, Live TV, My Library,
    DexWorld, YouTube, *(spacer)*, Edit Home, Alerts, Menu, Power (utility items pinned at the bottom under a thin line).
  - **Wraps**: Up on the first entry → last, Down on the last → first (3.28.227). Left from content opens it; it can
    auto-hide (slide −150 px + fade, 240/200 ms) while rows scroll. Content shifts right 92 px.
  - A "section pill" (current section name) sits at x 32, y 76: 64-tall capsule `DB141821` (focus `F2FFFFFF`), icon in a
    42 px circle `2EFFFFFF`, label `dh_nav_b` with 70 px text offset.
- The "Menu" icon (top bar) opens Dex Hub's own menu window (add-on, not skin XML).

## 8. Other Home layouts (one line each)
- `cinematic`: same hero, rows at 650 with spotlight info lower (+80 breathing room), no full-screen spot section.
- `two_rows`: hero zoomed to 72 %, rows ×0.8 (poster 158 x 234), rows start 200 px higher, pitch ×0.86 (−300).
- `spotlight_posters`: every row forced to plain posters.
- `netflix`: hero + one row (hero +40).
- `shelf`: hero hidden, collection shelves, rows −506 px (full page).
- `legacy`: older Dex layout, row counters "3 / 20" on the right, 106 % zoom focus.
- Poster scale 50–200 % via `custom_scale/scale_N.xml` (all `DhScale_px_*` × N/100).
