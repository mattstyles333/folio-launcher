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

JDK 17, compile/target SDK 35. Release is signed with the Folio release key (`keystore.properties` locally, GitHub Actions secrets in CI). Output: `app/build/outputs/apk/release/app-release.apk`. Do not minify without re-checking kotlinx.serialization keep rules. Never commit `keystore.properties` or the `.keystore`.

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
| Now playing / charge | `data/DeviceSignals.kt`, `home/PlaybackStrip.kt`. Charge hairline is a full oval around the clock. |

`HomeViewModel` is the only state owner. Prefs are a single JSON blob in DataStore (`PrefsStore`).

## Do not add

Widget host, icon packs, news/feed, accounts, network beyond Bing wallpaper, ads.

## Touch contracts

- Swipe up on wallpaper/rail: app sheet follows the finger (layout/draw only — do not read `SheetPull.px` in composition). Fling settles with spring. Once open, the grid scrolls; pull down from the top to close. Other home screens are hidden from the grid, recents, and the rail (search still finds them) via `HomeApps`. Recent/most-used first. Usage access = last 30 days of system opens; otherwise only launches from Folio.
- Swipe an icon right (rail or drawer) to hide that package from the home screen. Settings → Hidden apps to unhide. Search still finds hidden apps.
- Swipe down (idle): Android notification shade, not search.
- Swipe left on the idle print: open the chosen AI app. Swipe right: Google Search. Icon swipe-right still hides.
- Search pill: tap. No search gesture. Long-press Search: Ask overlay for the chosen AI.
- Triple-tap the print: Ask overlay. Double-tap is still next Bing. Settings → Ask cycles Grok / ChatGPT / Gemini / Claude if installed. Folio only launches the app (with a prompt when it can); no AI network of its own.
- Double-tap print: next Bing + next quote.
- Now playing on home is prev / play / next above the rail, always visible — no album art. Skip glyphs are thin strokes in PrintInk with a hard shadow. Tap play to pause, or to start Spotify if nothing is playing; long-press opens Spotify. Prefer Spotify if several sessions are active.
- Clock tap does nothing. Clock long-press → Settings → Choose photo for a local print.
- Plug in: hairline oval around the clock. Green on Sound. At 100% the oval is closed.
- Long-press print: cycle Sound → Vibrate → Silent (applies the real ringer immediately, then the print develops). Vibrate buzzes. Hardware ringer still drives the look. Silent without DND access shows a hint.
- Quote sits above the clock: 16sp serif, PrintInk, hard drop shadow (no blur). Clock and date use the same ink and shadow so they read on the print. Clock cluster is not rasterized while idle.

## Tests

`app/src/test` — Ranking, ClockCopy, BingImage URL helpers, QuoteBank pick stability, sheet open/rubber-band, AiApps install/cycle/uris. Run `testDebugUnitTest` before a push.

## Ship

The S23 is installed from **GitHub Releases**, not from a local `adb` on this machine. Do not `gh release create` by hand. After any change meant to be felt on the phone, bump `versionCode` / `versionName` in `app/build.gradle.kts`, then:

```bash
git add -A && git commit && git push origin main
git tag vX.Y.Z
git push origin vX.Y.Z
```

The tag must match `versionName` (`v1.2.8` ↔ `1.2.8`). Actions runs tests, signs `folio-X.Y.Z.apk` with the release key, and publishes the GitHub Release plus a SHA-256. Local `assembleRelease` needs `keystore.properties` (see `keystore.properties.example`).

v1.2.7 and earlier were debug-signed. The first release-keyed APK will not overlay those installs — uninstall Folio on the phone, then install from the new Release and set Home again.
