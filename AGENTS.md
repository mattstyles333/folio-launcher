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
| Search brain | `data/SearchBrain.kt` (Jev questions → one `SearchRoute`, confidence-gated), `data/OpenRouter.kt` (Jev `typesafe/jev-1.13` on `/api/v1/systemone`, `deepseek/deepseek-v4.1-flash` on chat completions, reasoning off). `HomeViewModel.think` runs it per keystroke; `searchHint` is its own StateFlow. |
| Onboarding | Role → Bing auto-fetch → one Allow walk (DND, usage, then notification access for Spotify). All skippable. The walk is `onboarding/AccessWalk.kt`, driven by `HomeViewModel` (survives process death); the activity only opens the Settings screen it's asked for. |
| Bing prints | `data/BingClient.kt` (archive, parallel, cached on disk per day), `data/PrintController.kt` (first / daily / next / previous / local photo), `data/WallpaperRepository.kt` (region-decodes only the portrait crop). Prefs store the Bing identity (`bingId`), never a list position. |
| Quotes | `assets/quotes.json` + `data/QuoteBank.kt` — one line per day, salt++ on Bing. With a key, `data/QuoteFit.kt`: Jev picks from a 60-quote shortlist (headed by the plain daily pick) the one that suits the new Bing print's title + credit line; the bank index is stored as `quoteFit` in the same prefs write as `bingId` (and swapped with `quotePrevFit` on Previous print), so print and quote change together. Local photos keep the daily pick. |
| Now playing / charge | `data/DeviceSignals.kt`, `home/PlaybackStrip.kt`. Charge hairline is a full oval around the clock. |
| Ask providers | `data/AskProviders.kt`: Hermes (only when Hermes Live is installed **and** signed in) first, then the installed `AiKind` apps (`data/AiApps.kt`, unchanged). `Prefs.aiPackage` empty = never picked = Hermes when ready; an explicit pick (an app package or `com.folio.hermeslive`) is respected. Without Hermes the list, `resolve` and `cycle` equal `AiApps`' (tested exhaustively). |
| Hermes connection | `hermes/HermesLive.kt` (contract names: permission `com.folio.permission.HERMES`, provider `content://com.folio.hermeslive.connection` `call("connection")` → `signed_in, base_url, access_token, expires_at_ms`; `action.CALL` + `extra.PROMPT`), `hermes/LiveTokenProvider.kt` (`AccessTokenProvider` that re-asks Hermes Live per request; refuses a token for a server other than the gateway's; after a 401, a different token or stop). Availability is re-checked on resume (throttled 10 s), on package changes, and when Settings or the Hermes overlay opens; off the main thread. The token lives only in memory, never in `HomeUiState`, Prefs or logs. |
| Diagnostics | `:hermes` `telemetry/` (shared with Hermes Live; see its AGENTS.md › Diagnostics), installed first in `FolioApp.onCreate` as service `folio`. Endpoint = release `BuildConfig.DIAGNOSTICS_URL` (from the release CI variable `FOLIO_DIAGNOSTICS_URL`; `""` in debug, tests and local builds) unless Settings › Diagnostics (`Prefs.diagnostics`, on by default) is off; `FolioApp.diagnosticsEndpoint`. Events: `home.start` (start_ms), `hermes.ask` (op + type/code), `bing.fetch_failed` (what + http_status), `jev.error` (call + http_status/type), crashes and previous exits (ANR, native). Names and codes only — never typed text, answers, prompts, keys or URLs. Works with or without Hermes Live. |
| Hermes Ask | `hermes/HermesAsk.kt` (gateway + one `HermesConversation`, stored id in `Prefs.hermesSessionId`, session title "Folio Ask"; connected only while the overlay is visible **and** the activity is started), `hermes/HermesAskState.kt` (pure reducer over `TurnUpdate`), UI in `search/HermesAskPane.kt` inside `AskOverlay`. Uses the `:hermes` library (submodule `hermes-live/hermes`). |

`HomeViewModel` is the only state owner; it composes `PrintController` for the print and `Ranking.fillRail` for the rail. Silent stashes the user's own DND policy in `folio_dnd` SharedPreferences before writing its alarms+media policy, and restores it when Folio's DND ends (Sound/Vibrate, or DND turned off elsewhere — checked on every ringer/DND broadcast and on resume, so it survives process death). On Android 15+ (targetSdk 35) the platform scopes an app's policy to an implicit per-app rule, so the global policy is safe there; the stash is what protects Android 12–14 (the S23 is on 14+, so on 14 it matters). Setting the ringer to normal/vibrate always exits DND (platform behaviour). Prefs are a single JSON blob in DataStore (`PrefsStore`).

## Do not add

Widget host, icon packs, news/feed, accounts, ads, network beyond Bing wallpaper, OpenRouter (Jev + DeepSeek, only with the user's own key), the user's own Hermes server — only the base URL Hermes Live hands over, with Hermes Live's token, only while the Hermes overlay is open — and the diagnostics endpoint baked in by release CI (`FOLIO_DIAGNOSTICS_URL`; crash reports and errors only, no content, no keys; off via Settings › Diagnostics). Never write that URL in code, tests, docs or commits. Never build an API key or Hermes credential into the APK or CI: the repo and its Releases are public. The OpenRouter key is pasted in Settings → Jev and lives only in `Prefs`; Folio never stores a Hermes password or token. Cleartext is allowed only for `*.ts.net` (`res/xml/network_security_config.xml`).

## Touch contracts

- Swipe up on wallpaper/rail: app sheet follows the finger (layout/draw only — do not read `SheetPull.px` in composition). Three rests — closed, peek (~two rows of most-used, thumb zone), full. A swipe from idle lands on peek; a second pull or a drag past peek opens the full grid, which then scrolls. The grid is most-used first (habit score, last-used only as a tie-break), then unused A–Z. Pull down from the top of the grid to the peek; a hard fling down closes. Back steps full → peek → closed. Other home screens are hidden from the grid, recents, and the rail (search still finds them) via `HomeApps`. Usage access = last 30 days of system opens; otherwise only launches from Folio.
- Swipe an icon right (rail or drawer) to hide that package from the home screen. Settings → Hidden apps to unhide. Search still finds hidden apps. Long-press a sheet or search icon for system app info. Rail long-press is still pin.
- Swipe down (idle): Android notification shade, not search.
- Swipe left on the idle print: with Hermes chosen, start a Hermes Live voice call (`action.CALL`, explicit package); otherwise open the chosen AI app. Swipe right: Google Search. Icon swipe-right still hides.
- Search pill: tap. No search gesture. Long-press Search: Ask overlay for the chosen AI (Hermes: the Hermes overlay below).
- Search with a key (Settings → Jev: copy an OpenRouter key, tap; hold to remove): once typing is 3+ characters and isn't an app's name (prefix or word prefix) — or has a space — Folio waits 280 ms, then asks Jev for an intent (open app / web / answer / ask / music if Spotify is installed) and which installed app (≤254, most-used first, plus `none`). Below 0.5 confidence nothing changes. A picked app floats to the top; other routes add one row above the apps; an answer is one serif line from DeepSeek (SKIP → web). Enter follows Jev's route, else the first app, else the web. No key: local search as before, plus "Search the web" when nothing matches. What leaves the phone: the typed text and app labels + package names.
- Triple-tap the print: Ask overlay. Double-tap is still next Bing. Settings → Ask cycles Hermes (when Hermes Live is signed in) / Grok / ChatGPT / Gemini / Claude if installed. For an app the Ask overlay only launches it (with a prompt when it can).
- Hermes overlay (Hermes chosen): "Ask Hermes" field; Send streams Hermes's reply into the overlay (serif, PrintInk, hard shadow), tool progress as a quiet line ("Hermes is working: web search"), Stop interrupts, errors in plain words. Approvals show Approve once / Deny, clarify questions show their choices (or a text answer) and Skip; nothing is ever answered automatically. Mic opens Hermes Live (with the typed text as its first request). Chips: Talk to Hermes (call), Catch me up, the quote, Where were we. "New" forgets the stored session. Closing the overlay or leaving Folio disconnects; a turn still running finishes in the Hermes session.
- Settings → Hermes: status (not installed → install Hermes Live, tap opens the release page; signed out → "Open Hermes Live to sign in"; ready → "Hermes answers Ask; swipe left to talk"). Tap opens Hermes Live.
- Search with Jev: an `Ask` or `Answer` route, with Hermes chosen, opens the Hermes overlay and sends the typed text; otherwise unchanged.
- Double-tap print: next Bing + next quote. Bing archive is idx 0 and 7 (Bing serves nothing new past idx=7) across the local market, en-GB and en-US — roughly 15–30 unique UHD prints, picked at random, never the current or previous one. First unlock after midnight quietly fetches a new Bing if the current print is Bing (local photos stay). Caption fades after two seconds. Settings → Previous print restores the one before.
- Plug in: hairline oval around the clock. Green on Sound. At 100% the oval is closed. Same oval when unplugged and charge is 15% or below (PrintInk, not green).
- Now playing on home is prev / play / next above the rail, always visible — no album art. Skip glyphs are thin strokes in PrintInk with a hard shadow. Tap play to pause, or to start Spotify if nothing is playing; long-press opens Spotify. Prefer Spotify if several sessions are active.
- Clock tap does nothing. Clock long-press → Settings → Choose photo for a local print.
- Long-press the print or the quote: cycle Sound → Vibrate → Silent (applies the real ringer immediately, then the print develops). Vibrate buzzes. Hardware ringer still drives the look. Silent mutes calls and notifications, not media. Silent without DND access shows a hint. Long-press the clock is Settings, not the ringer.
- Quote sits above the clock: 16sp serif, PrintInk, hard drop shadow (no blur). Clock and date use the same ink and shadow so they read on the print. Clock cluster is not rasterized while idle.

## Tests

`app/src/test` — Ranking (incl. rail fill), ClockCopy, BingImage URL helpers / pick / dedupe, cover crop + sample size, Prefs migration, QuoteBank pick stability, sheet settle/peek/rubber-band, drawer habit order, charge hairline, ringer visual mapping + DND restore, access walk, AiApps install/cycle/uris, SearchBrain gating/requests/routes, OpenRouter parsing. `search/SearchOverlayTest` covers what Enter does with and without a Jev hint. `OpenRouterLiveTest` makes real calls only when `OPENROUTER_API_KEY` is set (results in `app/build/openrouter-live.txt`); CI never sets it. `home/HomeGesturesTest` drives the real `HomeScreen` under Robolectric for the print gestures in Touch contracts — add a case when you add or change a gesture (it covers swipe left, triple-tap, long-press Search and the search Ask route with and without Hermes). Hermes: `data/AskProvidersTest` (without Hermes = `AiApps`, exhaustively; Hermes first/default; explicit choice kept), `hermes/HermesLiveTest` (not installed = off, no answer = signed out, bundle, explicit call intent, permission), `hermes/LiveTokenProviderTest`, `hermes/HermesAskReducerTest`, `hermes/HermesAskTest` (off state: no gateway is ever built), `hermes/HermesAskWireTest` (against a scripted dashboard on a `*.ts.net` name resolved to loopback: bearer only to Live's base URL, streaming, approval only on tap, detach closes the socket, a refused token stops), `search/HermesAskOverlayTest` (send, mic, chips, Stop/New, approval and clarify only by tap). Diagnostics: `data/DiagnosticsTest` (on by default, off without a built-in URL, off when switched off; debug `BuildConfig.DIAGNOSTICS_URL` is empty), `settings/SettingsDiagnosticsTest`; the telemetry library's own tests live in hermes-live. Run `testDebugUnitTest lintDebug` before a push; CI runs both.

## Hermes Live

Hermes Live (`com.folio.hermeslive`) is the companion app that signs in to the user's Hermes and runs voice calls. It lives in its own repo, [mattstyles333/hermes-live](https://github.com/mattstyles333/hermes-live), with its own Releases. Folio gets Hermes only through it: uninstall it or sign out there and Folio behaves exactly as without Hermes.

- `:hermes` (the Hermes client library) comes from that repo as the git submodule `hermes-live/`; `settings.gradle.kts` points `:hermes` at `hermes-live/hermes`. Clone with `--recurse-submodules` (or `git submodule update --init`). CI checks it out.
- Don't edit `hermes-live/` here. Change `:hermes` in hermes-live, then bump the pin: `git -C hermes-live pull origin main && git add hermes-live`.
- `:hermes` builds with Folio's version catalog, so its aliases (`okhttp`, `okhttp-mockwebserver`, `kotlinx-coroutines-core`, `kotlinx-coroutines-test`, plugin `android-library`) must stay in `gradle/libs.versions.toml`.
- Both apps must be signed with the Folio release key: they share the signature permission `com.folio.permission.HERMES`.
- Folio's release workflow also builds Hermes Live from the submodule pin (`cd hermes-live && ./gradlew :live:assembleRelease`) and attaches `hermes-live-A.B.C.apk` to the Folio Release, checking both share a signer. To ship a Hermes Live change: bump the pin, bump Folio's version, tag.

## Ship

The S23 is installed from **GitHub Releases**, not from a local `adb` on this machine. Do not `gh release create` by hand. After any change meant to be felt on the phone, bump `versionCode` / `versionName` in `app/build.gradle.kts`, then:

```bash
git add -A && git commit && git push origin main
git tag vX.Y.Z
git push origin vX.Y.Z
```

The tag must match `versionName` (`v1.2.8` ↔ `1.2.8`). Actions builds (no tests in CI/CD, user 2026-10-14: run them locally before merging), signs `folio-X.Y.Z.apk` with the release key, and publishes the GitHub Release plus a SHA-256. Local `assembleRelease` needs `keystore.properties` (see `keystore.properties.example`).

v1.2.7 and earlier were debug-signed. The first release-keyed APK will not overlay those installs — uninstall Folio on the phone, then install from the new Release and set Home again.
