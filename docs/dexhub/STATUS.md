# Dex Hub TV: where the port stands

Goal: this app looks and behaves like Dex Hub on Kodi (plugin.video.dexhub + skin.dexhub 3.28.229),
everything except the skin's animations. The skin's measured layout is in `skin-spec.md`
(sizes in px and dp = px / 2). The reference is Kodi 22 with skin.dexhub on the Mac
(`/Applications/Kodi.app`, its add-on in `~/Library/Application Support/Kodi/addons/plugin.video.dexhub`).

## Done
- Identity: name, package `com.dexhub.androidtv`, icons, banners, Arabic first, updates from 6ahd/DexHub-TV.
- Dex Hub palette as the default theme; white focus (the skin's `F2FFFFFF`).
- Left-to-right layout in Arabic like the skin; each paragraph keeps its own text direction.
- Top bar from the skin (`ui/v2/navigation/V2TopNavigation.kt`): glass pill, nav icons from the skin,
  Home / Movies / Series / Add-ons | Discover / My Library, Edit / Search / Menu / Power, bell + clock.
- Movies and Series tabs: Home filtered by type (`ui/screens/home/DexHomeTypeFilter.kt`).
- Hero scrims, facts line, two-line plot; poster 88x132 dp, wide Continue Watching card 240x135 dp.
- Hero state A (`ui/screens/home/DexSpotlight.kt`): Play / More info / Trailer, page dots with the source
  row's name, 10 s carousel over the first catalog row's 8 titles, rows peek until Down; Up from the
  first row returns to it. Trailer plays full screen behind the hero.
- Focused poster widens to a 16:9 backdrop card at once (was 3 s), clearlogo plus "genre • year".
- DexWorld kits on Home (`core/dexworld/DexWorldCatalogProvider.kt`): Arabic, TV networks, streamers,
  Top 10 on streamers, studios, read from `blocks.dexworld.cc` like the add-on; tiles use its covers.

- Hero "Ends at HH:MM" pill on films; row titles start at x 96 in Arabic too.
- Rows from what was watched (`core/dexpicks/DexPicksCatalogProvider.kt`): Because you watched X • Films / Series
  (TMDB recommendations, no key) and Recommended for you by AI • Films / Series (the profile's AI key, last 25
  titles, the add-on's PICKS prompt; kept a day or until the history changes).
- Scout / Ask (AI keys, Ask, Mixes) on by default; its tab is named Discover like the add-on's.

- Spotlight promo like Flix (FlixHeroTrailer): after 2.5 s on a title its trailer plays muted inside the backdrop,
  once, with the text and rows kept; the carousel waits for it. Trailer button turns it into the full trailer.
- Branding: intro lettering DEX HUB, "Arc" is "Dex Hub" in every string, no Support Nuvio on sign-in.

## Missing, by what shows most (from the add-on's code, `homeui/` and `skinui/rows.py`)
1. Collection tile focus: hero turns into a poster mosaic with the brand logo and "Live from TMDB · N titles".
2. Continue Watching like the add-on: wide cards with clearlogo and "N min left"; optional split into
   Continue Watching • Movies / • Series; Next episode row (off by default).
3. Hero extras: ratings line (IMDb, TMDB %, RT), "Ends at HH:MM" pill, source pill (e.g. "Plex • ARPX"),
   progress bar with "N min left"; auto trailer after 3 s.
4. Built-in rows: Recently watched • Movies/Series (watched mark), Because you watched X (TMDB
   recommendations of the last title), Recommended for you by AI • Films/Series (needs an AI key).
5. Row shapes (18, `homeui/layout.py`): poster, poster_large, tall, landscape, landscape_info, square, circle,
   wide, feature, ranked, tall_ranked, wide_ranked, collection_spotlight, collection_compact,
   collection_ranking, collage, mosaic, stack. Per-row menu: move, top, bottom, show/hide, shape, follow-row.
6. DexWorld per page: Series gets networks, Top 10, reality, anime (+ Gulf, Levantine, Turkish, Egyptian
   in Arabic); Movies gets studios, genres, documentaries (+ Arabic movies). Bundled Dex collections:
   Genre Universe, Moods & Themes, K-Wave, Collector's Shelf (`data/dex_collections.json`).
7. Discover page: Trending, Picked for you, Most watched this week (ranked), Most anticipated (tall),
   Top rated, Short on time, Feel good, Edge of your seat, Mind benders, Out lately, Coming soon.
8. Library page: Continue, New episodes this week, Watching, Plan to watch, Favorites, Trakt/Simkl/MDBList lists.
9. Sign-in page: Arabic error text.
10. Servers page like the add-on's "My Servers" (`routes/servers_tab.py`): counts (servers, libraries,
    to continue), chips All + each server, Continue watching per server, recently added, libraries.
11. Collection / kit page (`homeui/collection_page.py`): kit name or network logo, "N catalogs", chips
    All / Movies / Series (client-side filter), one section per member list, up to 20 titles each, shapes
    rotating (feature, tall, posters, wide), lists named "Top N" drawn ranked; poster wall behind.
12. DexWorld page (`homeui/dexworld_page.py`): chips from the API's `filters` with counts (Arabic, World,
    Asia, Anime, Streamers, Famous lists, Collections), "DexWorld bundles" cards, up to 28 sections,
    "See all · N titles"; menu "Add to a bar section…".
13. Library ("Your watching", `homeui/my_page.py`): hours/episodes/films watched, source chips
    (Trakt, Nuvio, Floppy, servers…), Pick up where you left, weekly streak card, Recommended, History
    with day labels, Coming up week strip with a link to the calendar.
14. Calendar (`homeui/calendar_page.py`, `calendar_feed.py`): Week / List / Month, All / Series / Movies,
    7-day strip with up to 3 dots, Open title / Play / bell; data TMDB + TVmaze + Trakt, 30 days back to 120 ahead.
15. Live TV (`homeui/live_*.py`, `guide.py`): sources M3U (+ XMLTV), Xtream Codes (fallback servers),
    Stremio live catalogs, DexWorld IPTV; guide with category panel (Favourites, Recently watched first),
    preview and a 2-hour timeline; Home rows Watched lately / Favourite channels / categories; live OSD.
16. YouTube (`homeui/youtube_page.py`, `youtube_feed.py`): InnerTube, no key; side rail (Search, Home,
    Shorts, Music, Subscriptions, Library, Live, Gaming, Sports, News, Trending, Arabic pages);
    rows Continue watching, Recommended, topics; sign-in through a TV token gives the account's shelves.
17. Sport (`homeui/sport_page.py`): ESPN scoreboards, Today / My games / Tables / Follow, matched to
    the user's IPTV channels.
18. Smaller: AI search, bell notices, new-episode reminders, screensaver, credits panel / autoplay next,
    skip intro (TheIntroDB), Parents Guide, phone setup by QR.

## Notes
- Reference screenshots of Kodi + skin.dexhub (Home, Movies, Series, Servers, Library, Calendar, Live TV,
  DexWorld, YouTube): `/Volumes/Backup/DexHub-Env/screens-app/kodi-ref-20261116/` (kept off the repo:
  they show the owner's accounts).
- skin.dexhub's InterArabic font breaks Android's Arabic shaping; keep Inter.
- Package `com.dexhub.androidtv`: the Kodi Dex Hub APK is `com.dexhub.tv`, so both install on one device.
- `.github/workflows/dexhub-compare.yml` renders Kodi + an emulator side by side into branch `ci-screens`
  (its Kodi shots are still Estuary, not skin.dexhub).
- Build locally: `source /Volumes/Backup/DexHub-Env/android/env.sh` then
  `./gradlew :app:assembleFullDebug -Pandroid.injected.build.abi=armeabi-v7a`; the APK lands in
  `app/build/intermediates/apk/full/debug/` and needs `adb install -t`. A local build is signed with a
  different key than CI's, so switching between them means uninstalling first.
