# Dex Hub TV: where the port stands

Goal: this app looks and behaves like Dex Hub on Kodi (plugin.video.dexhub + skin.dexhub 3.28.228).
The skin's measured layout is in `skin-spec.md` (sizes in px and dp = px / 2).

## Done
- Identity: name, package `com.dexhub.tv`, icons, banners, Arabic first, updates from 6ahd/DexHub-TV.
- Dex Hub palette as the default theme; white focus (the skin's `F2FFFFFF`).
- Left-to-right layout in Arabic like the skin; each paragraph keeps its own text direction.
- Top bar from the skin (`ui/v2/navigation/V2TopNavigation.kt`): glass pill, nav icons from the skin,
  Home / Movies / Series / Add-ons | Discover / My Library, Edit / Search / Menu / Power, bell + clock.
- Movies and Series tabs: Home filtered by type (`ui/screens/home/DexHomeTypeFilter.kt`).
- Hero scrims, facts line, two-line plot; poster 88x132 dp, wide Continue Watching card 240x135 dp.

## Next (in order)
1. Hero state A (skin §2.1): big spotlight with Play / More info / Trailer, page dots, 10 s carousel,
   rows peeking below; rows move up when focused.
2. Sign-in page rebranded to Dex Hub (no Arc / "Support Nuvio", Arabic error text).
3. Row shapes: ranked, then tall (skin §3.3).
4. Servers and Live TV tabs.

## Notes
- skin.dexhub's InterArabic font breaks Android's Arabic shaping; keep Inter.
- `.github/workflows/dexhub-compare.yml` renders Kodi + an emulator side by side into branch `ci-screens`.
- Build locally: `./gradlew :app:assembleFullDebug` (add `-PdexEmulatorAbi=true` for an x86_64 APK).
