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
| `app/src/full/res/values*/dexhub_strings.xml` | Arc's texts with "Dex Hub TV" for "Nuvio Arc", every language (`dexhub/brand/make_strings.py`); the credits keep Arc's and Nuvio's names |
| `app/src/full/res/raw/arc_intro_sound.m4a` | The entrance's sound (`dexhub/brand/make_sound.sh`) |
| `app/src/main/java/com/nuvio/tv/ui/brand/DexHubIntroScene.kt` | The entrance: the infinity mark drawn in behind a light, DEX HUB under it, a sheen, then a soft glow while Home loads |
| `app/src/full/res/font/ui_*`, `assets/licenses/fonts/` | IBM Plex Sans Arabic, Cairo and Tajawal (SIL OFL 1.1, with their licences): fonts with Arabic letters for the interface and subtitles |
| `app/src/full/res/values*/dexhub_features.xml` | Dex Hub TV's own texts (poster sizes and shapes, the fonts' credit) |
| `app/src/main/java/com/nuvio/tv/ui/components/DexHubPosterShape.kt` | The posters' shape (2:3, 3:4, square), kept on the device |
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
