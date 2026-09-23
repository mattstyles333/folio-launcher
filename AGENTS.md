# Folio — notes for coding agents

Sideloadable Android 12+ (API 31) home-screen launcher. Package `com.folio.launcher`, label Folio. Idle is a print. No ads, no feed, no account.

## Layout target

Primary device is a **Galaxy S23** on Android 14+ (One UI): 6.1" 1080×2340 (19.5:9), ~425 ppi, centre punch-hole, 120 Hz AMOLED, gesture nav, rounded corners.

- Use `WindowInsets.safeDrawing` (cutout + bars), not raw percentages of a 2400px emulator.
- Clock sits just under the status/cutout band. Idle has no ringer chrome — long-press the print.
- Bing UHD fetch uses the real `DisplayMetrics` size (1080×2340 on S23), not a hardcoded 1920 height. Download the UHD original first, crop a portrait window, scale to the panel (plus a little bleed for parallax). Do not prefer Bing's 1080×1920 file.
- Portrait only. Prefer 120 Hz when the panel offers it.
- Parallax is a few millimetres and must unregister on pause.

## Build

```bash
export JAVA_HOME="${JAVA_HOME:-$(mise where java)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

JDK 17, AGP 9 (built-in Kotlin, no `kotlin-android` plugin), Gradle 9. compileSdk 37 (latest AndroidX needs it), targetSdk 35. Release is signed with the Folio release key (`keystore.properties` locally, GitHub Actions secrets in CI). Output: `app/build/outputs/apk/release/app-release.apk`. Release runs R8 (minify + resource shrink). Every JSON decode uses the compile-time serializer (`decodeFromString<T>()`); keep it that way — a reflective `serializer(KType)` lookup needs new keep rules. `profileinstaller` + `src/main/baseline-prof.txt` precompile Folio's code for sideloaded installs. Never commit `keystore.properties` or the `.keystore`.

## Architecture

| Area | Where |
|---|---|
| Home composition | `home/HomeScreen.kt` |
| Print + ringer grades | `home/WallpaperLayer.kt` (full colour / 50% sat / grey) |
| Rail + pull-up sheet | `home/Rail.kt`, `home/Sheet.kt`, `recents/ExpandingDock.kt` — finger-tracked sheet, LazyGrid of every app |
| Ringer develop | Long-press print — `GradeReveal` circular clip in `home/WallpaperLayer.kt` |
| Search | `search/SearchOverlay.kt` — Spotlight field, empty = suggestions |
| Onboarding | Role → Bing auto-fetch → one Allow walk (DND, usage, then notification access for Spotify). All skippable. The walk is `onboarding/AccessWalk.kt`, driven by `HomeViewModel` (survives process death); the activity only opens the Settings screen it's asked for. |
| Bing prints | `data/BingClient.kt` (archive, parallel, cached on disk per day), `data/PrintController.kt` (first / daily / next / previous / local photo), `data/WallpaperRepository.kt` (region-decodes only the portrait crop). Prefs store the Bing identity (`bingId`), never a list position. |
| Quotes | `assets/quotes.json` + `data/QuoteBank.kt` — one line per day, salt++ on Bing |
| Now playing / charge | `data/DeviceSignals.kt`, `home/PlaybackStrip.kt`. Charge hairline is a full oval around the clock. |

`HomeViewModel` is the only state owner; it composes `PrintController` for the print and `Ranking.fillRail` for the rail. Silent stashes the user's own DND policy in `folio_dnd` SharedPreferences before writing its alarms+media policy, and restores it when Folio's DND ends (Sound/Vibrate, or DND turned off elsewhere — checked on every ringer/DND broadcast and on resume, so it survives process death). On Android 15+ (targetSdk 35) the platform scopes an app's policy to an implicit per-app rule, so the global policy is safe there; the stash is what protects Android 12–14 (the S23 is on 14+, so on 14 it matters). Setting the ringer to normal/vibrate always exits DND (platform behaviour). Prefs are a single JSON blob in DataStore (`PrefsStore`).

## Do not add

Widget host, icon packs, news/feed, accounts, network beyond Bing wallpaper, ads.

## Touch contracts

- Swipe up on wallpaper/rail: app sheet follows the finger (layout/draw only — do not read `SheetPull.px` in composition). Three rests — closed, peek (~two rows of most-used, thumb zone), full. A swipe from idle lands on peek; a second pull or a drag past peek opens the full grid, which then scrolls. The grid is most-used first (habit score, last-used only as a tie-break), then unused A–Z. Pull down from the top of the grid to the peek; a hard fling down closes. Back steps full → peek → closed. Other home screens are hidden from the grid, recents, and the rail (search still finds them) via `HomeApps`. Usage access = last 30 days of system opens; otherwise only launches from Folio.
- Swipe an icon right (rail or drawer) to hide that package from the home screen. Settings → Hidden apps to unhide. Search still finds hidden apps. Long-press a sheet or search icon for system app info. Rail long-press is still pin.
- Swipe down (idle): Android notification shade, not search.
- Swipe left on the idle print: open the chosen AI app. Swipe right: Google Search. Icon swipe-right still hides.
- Search pill: tap. No search gesture. Long-press Search: Ask overlay for the chosen AI.
- Triple-tap the print: Ask overlay. Double-tap is still next Bing. Settings → Ask cycles Grok / ChatGPT / Gemini / Claude if installed. Folio only launches the app (with a prompt when it can); no AI network of its own.
- Double-tap print: next Bing + next quote. Bing archive is idx 0 and 7 (Bing serves nothing new past idx=7) across the local market, en-GB and en-US — roughly 15–30 unique UHD prints, picked at random, never the current or previous one. First unlock after midnight quietly fetches a new Bing if the current print is Bing (local photos stay). Caption fades after two seconds. Settings → Previous print restores the one before.
- Plug in: hairline oval around the clock. Green on Sound. At 100% the oval is closed. Same oval when unplugged and charge is 15% or below (PrintInk, not green).
- Now playing on home is prev / play / next above the rail, always visible — no album art. Skip glyphs are thin strokes in PrintInk with a hard shadow. Tap play to pause, or to start Spotify if nothing is playing; long-press opens Spotify. Prefer Spotify if several sessions are active.
- Clock tap does nothing. Clock long-press → Settings → Choose photo for a local print.
- Long-press the print or the quote: cycle Sound → Vibrate → Silent (applies the real ringer immediately, then the print develops). Vibrate buzzes. Hardware ringer still drives the look. Silent mutes calls and notifications, not media. Silent without DND access shows a hint. Long-press the clock is Settings, not the ringer.
- Quote sits above the clock: 16sp serif, PrintInk, hard drop shadow (no blur). Clock and date use the same ink and shadow so they read on the print. Clock cluster is not rasterized while idle.

## Tests

`app/src/test` — Ranking (incl. rail fill), ClockCopy, BingImage URL helpers / pick / dedupe, cover crop + sample size, Prefs migration, QuoteBank pick stability, sheet settle/peek/rubber-band, drawer habit order, charge hairline, ringer visual mapping + DND restore, access walk, AiApps install/cycle/uris. `home/HomeGesturesTest` drives the real `HomeScreen` under Robolectric for the print gestures in Touch contracts — add a case when you add or change a gesture. Run `testDebugUnitTest lintDebug` before a push; CI runs both.

## Ship

The S23 is installed from **GitHub Releases**, not from a local `adb` on this machine. Do not `gh release create` by hand. After any change meant to be felt on the phone, bump `versionCode` / `versionName` in `app/build.gradle.kts`, then:

```bash
git add -A && git commit && git push origin main
git tag vX.Y.Z
git push origin vX.Y.Z
```

The tag must match `versionName` (`v1.2.8` ↔ `1.2.8`). Actions runs tests, signs `folio-X.Y.Z.apk` with the release key, and publishes the GitHub Release plus a SHA-256. Local `assembleRelease` needs `keystore.properties` (see `keystore.properties.example`).

v1.2.7 and earlier were debug-signed. The first release-keyed APK will not overlay those installs — uninstall Folio on the phone, then install from the new Release and set Home again.
