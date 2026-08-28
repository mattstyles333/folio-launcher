# Folio — notes for coding agents

Sideloadable Android 12+ (API 31) home-screen launcher. Package `com.folio.launcher`, label Folio. Idle is a print. No ads, no feed, no account.

## Layout target

Primary device is a **Galaxy S23**: 6.1" 1080×2340 (19.5:9), ~425 ppi, centre punch-hole, 120 Hz AMOLED, gesture nav, rounded corners.

- Use `WindowInsets.safeDrawing` (cutout + bars), not raw percentages of a 2400px emulator.
- Clock sits just under the status/cutout band. Idle has no ringer chrome — long-press the print.
- Bing UHD fetch uses the real `DisplayMetrics` size (1080×2340 on S23), not a hardcoded 1920 height.
- Portrait only. Prefer 120 Hz when the panel offers it.
- Parallax is a few millimetres and must unregister on pause.

## Build

```bash
export JAVA_HOME="${JAVA_HOME:-$(mise where java)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

JDK 17, compile/target SDK 35. Release is debug-signed for sideload (`app/build/outputs/apk/release/app-release.apk`). Do not minify without re-checking kotlinx.serialization keep rules.

## Architecture

| Area | Where |
|---|---|
| Home composition | `home/HomeScreen.kt` |
| Print + ringer grades | `home/WallpaperLayer.kt` (full colour / 50% sat / grey) |
| Rail + pull-up sheet | `home/Rail.kt`, `home/Sheet.kt`, `recents/ExpandingDock.kt` — finger-tracked sheet, LazyGrid of every app |
| Ringer develop | Long-press print — `GradeReveal` circular clip in `home/WallpaperLayer.kt` |
| Search | `search/SearchOverlay.kt` — Spotlight field, empty = suggestions |
| Onboarding | Role → Bing auto-fetch → one Allow walk (DND, usage, then notification access for Spotify). All skippable. |
| Bing prints | `data/BingClient.kt`, double-tap idle |
| Quotes | `assets/quotes.json` + `data/QuoteBank.kt` — one line per day, salt++ on Bing |
| Now playing / charge | `data/DeviceSignals.kt`, `home/PlaybackStrip.kt`. Swipe-right page hosts Spotify’s own AppWidget (`SpotifyWidgetBinder`). Charge hairline is a full oval around the clock. |

`HomeViewModel` is the only state owner. Prefs are a single JSON blob in DataStore (`PrefsStore`).

## Do not add

General widget host, icon packs, news/feed, accounts, network beyond Bing wallpaper, ads. Spotify’s Now Playing AppWidget on the swipe-right page is the exception.

## Touch contracts

- Swipe up on wallpaper/rail: app sheet follows the finger (layout/draw only — do not read `SheetPull.px` in composition). Fling settles with spring. Once open, the grid scrolls; pull down from the top to close. All launchable apps except other home screens (search still finds them); recent/most-used first. Usage access = last 30 days of system opens; otherwise only launches from Folio.
- Swipe right: same Bing/print as home, Spotify’s Now Playing widget in the middle half (`NowPlayingPage` + `SpotifyWidgetBinder`). Home translates X. Swipe left or Back closes. Long-press play opens the same page. First open may ask to bind that widget. Do not start a page swipe while the app sheet is open, or a sheet swipe while the player page is open.
- Swipe down (idle): search.
- Search pill: tap.
- Double-tap print: next Bing + next quote. Clock long-press → Settings → Choose photo for a local print.
- Now playing on home is prev / play / next above the rail — no album art. Skip glyphs are thin strokes in PrintInk with a hard shadow. Tap play to pause; long-press opens the player page. The side page is the home print plus Spotify’s widget in the middle half. Prefer Spotify if several sessions are active. Idle chip until notification access is granted.
- Plug in: hairline oval around the clock. At 100% the oval is closed.
- Long-press print: cycle Sound → Vibrate → Silent (applies the real ringer immediately, then the print develops). Vibrate buzzes. Hardware ringer still drives the look. Silent without DND access shows a hint.
- Quotes sit under the date: 16sp serif, hard drop shadow (no blur). Clock cluster is not rasterized while idle.
- Clock tap: search. Clock long-press: settings.

## Tests

`app/src/test` — Ranking, ClockCopy, BingImage URL helpers, QuoteBank pick stability, sheet open/rubber-band. Run `testDebugUnitTest` before a push.

## Ship

The S23 is installed from **GitHub Releases**, not from a local `adb` on this machine. After any change meant to be felt on the phone:

```bash
./gradlew testDebugUnitTest assembleRelease
git add -A && git commit && git push origin main
gh release create vX.Y.Z --target main --title "X.Y.Z" app/build/outputs/apk/release/app-release.apk
```

Bump `versionCode` / `versionName` in `app/build.gradle.kts` for each of those. Release is debug-signed for sideload.
