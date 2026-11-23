# Dex Hub TV

Dex Hub TV is [Nuvio Arc](https://github.com/ysosrs123/NuvioTV-Fork) (a fork of
[NuvioTV](https://github.com/NuvioMedia/NuvioTV) by Tapframe and friends) with Dex Hub's name,
look and additions. It is GPL-3.0, as Arc and NuvioTV are: its source stays open, and their
authors stay credited in the app's About and Licences pages.

It follows Arc's releases: Arc's changes are merged into this branch as they come. So that a
merge rarely conflicts, Dex Hub's changes live in their own places, and Arc's files change only
at the hook points below.

## Where Dex Hub's changes live

| Place | What |
| --- | --- |
| `app/src/full/res/` | Every Dex Hub image, string and sound. Android takes these over the same names in `app/src/main/res`, so Arc's files are not touched. |
| `app/src/full/res/mipmap-*`, `drawable-nodpi/arc_launcher_foreground*` | Launcher icons and TV banners, all of Arc's 16 colours (`dexhub/brand/make_brand.py`) |
| `app/src/full/res/drawable-nodpi/app_logo_wordmark*` | The logo inside the app (top bar, profiles, About) |
| `app/src/full/res/values*/dexhub_strings.xml` | Arc's texts with "Dex Hub TV" for "Nuvio Arc", every language (`dexhub/brand/make_strings.py`); the credits keep Arc's and Nuvio's names |
| `app/src/full/res/raw/arc_intro_sound.m4a` | The entrance's sound (`dexhub/brand/make_sound.sh`) |
| `app/src/main/java/com/nuvio/tv/ui/brand/DexHubIntroScene.kt` | The entrance: the infinity mark drawn in behind a light, DEX HUB under it, a sheen, then a soft glow while Home loads |
| `app/src/full/res/font/ui_*`, `assets/licenses/fonts/` | IBM Plex Sans Arabic, Cairo and Tajawal (SIL OFL 1.1, with their licences): fonts with Arabic letters for the interface and subtitles |
| `app/src/full/res/values*/dexhub_features.xml` | Dex Hub TV's own texts (poster sizes and shapes, the fonts' credit) |
| `app/src/main/java/com/nuvio/tv/ui/components/DexHubPosterShape.kt` | The posters' shape (2:3, 3:4, square), kept on the device |
| `app/src/main/java/com/nuvio/tv/dexhub/dexworld/` | DexWorld's ready sections (blocks.dexworld.cc), from Flix: shelves of sections with the site's covers, a section's titles, a title's page |
| `app/src/main/java/com/nuvio/tv/dexhub/livetv/` | Live TV, from Flix: M3U playlists (Flix's parser, from Plozz, GPL-3.0) and Xtream accounts, with their channels (XMLTV now and next, favourites, a preview of the focused channel), films and series (a panel's episodes asked for when a series opens) |
| `app/src/main/java/com/nuvio/tv/dexhub/thememusic/` | Theme music on title pages, as Flix plays it: ThemerrDB's theme songs through the trailers' YouTube reader (Layout > Title page) |
| `app/src/main/java/com/nuvio/tv/dexhub/sources/` | The sources page in Flix's design: quality tabs, a sort, a card per add-on (Layout > Sources) |
| `app/src/main/java/com/nuvio/tv/dexhub/welcome/` | The first-run question (what the viewer likes), which orders the side menu and DexWorld |
| `app/src/main/java/com/nuvio/tv/dexhub/collections/` | A collection's posters fanned and drifting behind its page's heading, as Flix draws it |
| `app/src/main/java/com/nuvio/tv/dexhub/ui/DexHubCard.kt` | Every Dex Hub page's card, with Arc's own focus, glow and glass (its v2 design system) |
| `app/src/main/java/com/nuvio/tv/dexhub/subtitles/` | Subtitle translation, from Dex Hub for Kodi (subsync/subtrans.py, transflow.py): the player's subtitles > Translate, by Google Translate or the viewer's AI service |
| `app/src/main/java/com/nuvio/tv/dexhub/ai/` | The viewer's own AI service (ai_search.py's list: Groq, Gemini, OpenAI, Claude, Grok, OpenRouter, DeepSeek, Mistral), its key and model on this device (Playback > Subtitles > Translate subtitles with) |
| `app/src/main/java/com/nuvio/tv/dexhub/sync/` | Dex Hub's clocks for AutoSync when the video has no subtitle to time by, from Dex Hub for Kodi (engref.py, whisperref.py, dexpick.py): DexWorld's shared sync (syncshare.py: a trusted fix or the release's timeline; the video's own timing shared back), an English subtitle of the same release (OpenSubtitles' public add-on), else the film's speech by Whisper with the viewer's Groq or OpenAI key |
| `app/src/main/java/com/nuvio/tv/dexhub/settings/` | Settings > Dex Hub, first in the rail: every Dex Hub feature in one place (AI translation, subtitle and app fonts, Whisper, DexWorld sync, theme music, interests, Flix sources, Usenet) |
| `app/src/main/java/com/nuvio/tv/dexhub/ui/DexHubFields.kt` | The text field and buttons of Dex Hub's setup dialogs |
| `app/src/main/java/com/nuvio/tv/dexhub/usenet/` | Usenet through the built-in engine, from Dex Hub for Kodi (usenet_source.py, usenet_engine.py): the viewer's providers and indexers (Layout > Sources > Usenet), the search, a Stremio add-on on 127.0.0.1:17465 and the engine |
| `app/src/main/cpp/dexhub_usenet_jni.c` | The bridge to the engine library's three C calls |
| `dexhub/` | The tools that make the above, and Dex Hub's mark |
| `.github/workflows/dexhub-build.yml` | The APK build and release |

## Hook points in Arc's files

| File | Change |
| --- | --- |
| `app/build.gradle.kts` | `applicationId = "com.dexhub.tv"`; `GITHUB_OWNER` / `GITHUB_REPO` = `6ahd` / `DexHub-TV` (the app's update check) |
| `app/src/testFull/.../ForkUpdateRepositoryTest.kt` | reads the owner and repository from `BuildConfig` instead of Arc's names |
| `ui/brand/ArcIgnitionView.kt`, `ui/brand/ArcIntroPrep.kt` | `DexHubIntroScene` where they had `ArcIgnitionScene` |
| `domain/model/AppFont.kt`, `SubtitleFont.kt`, `ui/theme/Type.kt`, `ui/screens/player/SubtitleFonts.kt` | the three Arabic fonts as choices for the interface and subtitles |
| `data/local/ThemeDataStore.kt` | until a font is chosen, an Arabic interface uses IBM Plex Sans Arabic |
| `ui/components/PosterCardDefaults.kt`, `ui/screens/home/ModernHomeContent.kt` | posters take their height from the chosen shape |
| `ui/screens/settings/LayoutCardSettings.kt` | Huge and Cinema widths, Soft and Round corners, the Poster shape row |
| `ui/screens/detail/MetaDetailsScreen.kt` | the theme music (one call) |
| `ui/screens/stream/StreamScreen.kt` | the pick's playing as one `playPicked`, and Flix's sources or Arc's list |
| `ui/screens/settings/LayoutDetailSettings.kt` | the Theme music and Flix sources switches; the Usenet row |
| `NuvioApplication.kt` | starts the Usenet add-on; moves a full-screen Home trailer into the hero's window once |
| `data/local/LayoutPreferenceDataStore.kt` | the Home trailer plays in the hero's window by default (`FEATHERED_WINDOW`), and `dexHubTrailerInHeroOnce` |
| `ui/screens/settings/PlaybackSubtitleSettings.kt` | the "Translate subtitles with", "Sync by the film's speech" and "Shared sync with DexWorld" rows |
| `ui/screens/settings/SettingsCatalog.kt`, `SettingsScreen.kt`, `SettingsSearch.kt` | the Dex Hub category (`SettingsCategory.DEXHUB`) |
| `ui/screens/player/SubtitleSelectionOverlay.kt` | also "Translate subtitles with", the AI service chosen from the player |
| `ui/screens/player/autosync/AutomaticSubtitleSync.kt` | with no reference of the video's own, Dex Hub's references are asked; with one, its timing is shared (one block) |
| `ui/screens/player/PlayerRuntimeControllerAutomaticSubtitleSync.kt` | tells Dex Hub's references what is playing (one call) |
| `app/src/main/cpp/CMakeLists.txt` | the `dexhub_usenet` bridge library |
| `ui/navigation/NuvioNavHost.kt` (Home), `MainActivity.kt` (menu entries) | the first-run question over Home; the menu in the viewer's order |
| `ui/screens/collection/FolderDetailScreen.kt` | the posters' strip at the top of a collection |
| `ui/screens/player/PlayerUiState.kt`, `PlayerRuntimeControllerPlaybackEvents.kt`, `SubtitleSelectionOverlay.kt` | the Translate event, its handling and its row in the subtitles panel |
| `ui/screens/player/PlayerRuntimeControllerSubtitleTiming.kt` | a subtitle on this device (`file://`, a translation) is read from its file |
| `MainActivity.kt` (menu entries, `appRootRoutes`), `ui/navigation/NuvioNavHost.kt` | the Live TV and DexWorld pages and their menu entries |
| `domain/model/AppTheme.kt`, `ThemeAccess.kt`, `ui/theme/ThemeColors.kt`, `ui/theme/Theme.kt`, `ui/brand/ArcPalette.kt`, `ui/screens/settings/ThemeSettings*.kt`, `MainActivity.kt` | the Dex Hub theme (lavender on navy), first in the list and the default (`DefaultAppTheme`) |

## After merging an Arc release

1. Resolve any conflict at the hook points above.
2. `python3 dexhub/brand/make_strings.py`: Arc's new or changed texts that name "Nuvio Arc".
3. If Arc changed its launcher images: `python3 dexhub/brand/make_brand.py LeagueSpartan-ExtraBold.ttf`
   (the font is in skin.dexhub's `fonts/`).

## Building

`.github/workflows/dexhub-build.yml` builds the arm64-v8a and armeabi-v7a APKs on every push to
`main` or a `claude/` branch, and on demand from the Actions tab; "publish" puts them on a
GitHub release, which the app's update check offers. Its secrets (all optional) are listed at
the top of the workflow: without a keystore the APKs are signed with a throwaway key, and
without `local.properties` the app has no TMDb, Trakt or other keys of its own.

## Usenet

Dex Hub TV has the same Usenet engine as Dex Hub for Kodi: NuvioTV's engine (NuvioMedia/NuvioTV
pull request 4426, GPL-3.0) built as an Android library, `libnuviousenet.so`. The build takes it
from Dex Hub's own release of script.dexhub.usenet (`dexhub-build.yml`, "The Usenet engine"),
with its licence, source note and the licences of the libraries in it (assets
`usenet-engine/`); nothing of it is kept in this repository. A build without it has no engine
and shows no Usenet.

How it works (`app/src/main/java/com/nuvio/tv/dexhub/usenet/`):

1. Layout > Sources > Usenet: the viewer's providers (news server, login, connections, a
   backup) and indexers (Newznab, or a Prowlarr for all of its Usenet indexers), kept on this
   device only. Once one of each is there, the app installs its own add-on,
   `http://127.0.0.1:17465/manifest.json`.
2. A title's sources ask that add-on, which searches every indexer at once (by IMDb or TVDB id,
   then by name; 25 seconds; the same search kept for half an hour) and lists the releases.
3. Picking one asks `/play/<token>`: the release opens in the engine (`POST /sessions`) and the
   player is sent on to the engine's stream. Arc's longer wait for a local server
   (`SlowStartServer`) covers the opening. Nothing in a stream's link names an indexer's key.

Usenet also still arrives the ways Arc has it: an AIOStreams add-on with its own engine, and
TorBox's Usenet downloads in its cloud library.
