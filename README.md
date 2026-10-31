# Moon Kiln TV

A custom Android TV home launcher for Amba's OnePlus TV Y Series (Android TV 11, MediaTek MT5621, 32-bit, 1 GB RAM, 1280x720), PS5-first, in Moon Kiln's own look. It replaces the Google TV launcher. The Mac mini side, a tiny JSON endpoint for the "Moon Kiln today" card, lives in `mini/`.

Linear project: "Moon Kiln TV launcher" (P-MOO-25). No em-dashes anywhere in this repo.

## Layout

- `app/`: the launcher, Kotlin plus Jetpack Compose (foundation only, no material), minSdk 30, targetSdk 30, `armeabi-v7a` only. The design is the canvas "Moon Kiln TV Launcher" (Linear MOO-637); every pixel number in the code comes from it.
  - `ui/`: the kiln palette, the two bundled variable fonts (Grandstander and Hanken Grotesk, OFL, in `licences/`), the one easing, and `FocusTile` (focus pop, ring, press).
  - `home/`: the home screen (top bar, greeting, PS5 tile, today card, apps row, hint strip), the installed apps query, the Inputs page.
  - `analytics/`: the "What's going on" page with its three Canvas charts.
  - `ambient/`: ambient mode, used inside the launcher and as the system screensaver (`AmbientDream`). The twelve photos are 600 px hero shots in `assets/photos/`.
  - `toast/`: the new order toast, an overlay window of plain views so it shows over the PS5 picture.
  - `data/`: the mini client, the today and dashboard repositories (last good answer kept, stale badge when the mini is away), a tiny image loader.
  - `tv/`: TvInputManager helpers (PS5 power from the HDMI signal state, the passthrough switch) and the network pill.
- `mini/`: `today_server.py` (the endpoint), its launchd plist and `install.sh`.

The composition locks density to one pixel per dp (`KilnRoot`), so the canvas's 1280x720 pixel sizes apply as written on the 213 dpi panel. Only transform and opacity animate; durations honour the TV's animator scale (zero means no motion).

## Build

Everything runs from the command line with the Gradle wrapper and the JDK bundled in Android Studio 2026.1. No system `java` or `gradle` is needed.

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
```

`local.properties` (not in git) needs `sdk.dir=/Users/<you>/Library/Android/sdk` (platform android-36, build-tools 36.0.0), plus `mk.todayToken=<the mini's bearer token>` and optionally `mk.miniBase=http://192.168.0.14:9470`; both become BuildConfig fields. The release build is minified with R8 and signed with the debug key: this is a sideloaded launcher, not a Play upload. Always put the release build on the TV: the debug build is 24 MB and janks on this chip.

## Install on the TV

```
adb connect 192.168.0.11:5555
adb -s 192.168.0.11:5555 install -r app/build/outputs/apk/release/app-release.apk
```

Every `adb shell` on this set prints two junk lines ("Init wrapper sys mutex successful", "open mma dev failed"); filter them. Never use `exec-out screencap`: capture with `screencap -p /sdcard/x.png` then `adb pull`. Screenshots are blank while an HDMI input or Live TV is on screen, so press `KEYCODE_HOME` first.

Memory check (the budget is under 40 MB resident for the launcher process):

```
adb -s 192.168.0.11:5555 shell dumpsys meminfo com.moonkiln.tv | grep "TOTAL PSS"
```

Measured on 31 Oct 2026 with the v1 release build (`dumpsys meminfo`, PSS):

| State | Launcher PSS | TV free RAM |
|---|---|---|
| Google launcher as home, before any removal | 32.1 MB (ours, idle) | 193 MB |
| Fresh home screen after boot, nine packages removed | 37.5 MB | 278 MB |
| After the numbers page has been used | about 50 MB | |
| Behind the PS5 picture (the TV drops the GPU caches) | 47 to 51 MB | 290 MB |

The 40 MB line holds for the home screen itself; the numbers page costs about 10 MB more (chart text and GPU caches) and that stays until the launcher leaves the screen. Both figures include about 14 MB of shared code pages and 11 MB of shared system pages, so the private cost is roughly half of what the total says.

Motion, from `dumpsys gfxinfo` after each pass (this chip renders at 60 Hz, one frame is 16.7 ms):

| Pass | Median frame | 90th percentile |
|---|---|---|
| Focus moves on the home row, warm | 11 ms | 28 ms |
| Focus moves right after a cold start | 29 ms | 48 ms |
| Page change into the numbers page | 10 ms, one 700 ms stall on the first build of the page | |
| Numbers page: count-up, expand, crosshair | 30 ms | 77 ms |
| Ambient, continuous | 11 to 13 ms | 13 to 34 ms |

Lessons that shaped the code: a full-screen alpha fade allocates an offscreen buffer that Skia keeps cached (11 MB on the numbers page), so the page change draws a flat scrim over the content instead; a gradient scrim costs a 36 ms frame on this GPU; the polaroid crossfade applies alpha per element rather than to its layer; a graphics layer around the whole screen is re-recorded on every focus change, so the 12 px rise is an offset; the chart text measurer needs a cache larger than the default eight. The TV ships with the animator scale at 0.5 and Compose honours it, so `Motion.ms` divides by the scale to keep the canvas timings.

Grants the launcher needs, once per install of a new signing key (adb, no dialogs on this set):

```
adb -s 192.168.0.11:5555 shell appops set com.moonkiln.tv SYSTEM_ALERT_WINDOW allow   # the order toast over the PS5 picture
adb -s 192.168.0.11:5555 shell pm grant com.moonkiln.tv android.permission.ACCESS_FINE_LOCATION   # the SSID on the Wi-Fi pill, needs location on too
adb -s 192.168.0.11:5555 shell settings put secure screensaver_components com.moonkiln.tv/.ambient.AmbientDream
adb -s 192.168.0.11:5555 shell settings put secure screensaver_enabled 1
```

### Making it the home screen

```
adb -s 192.168.0.11:5555 shell cmd package set-home-activity com.moonkiln.tv/.MainActivity
```

The preference is recorded, but on this build HOME keeps resolving to the Google launcher until that package is gone (the system prefers its own home over a sideloaded one), so the real switch is `pm uninstall --user 0 com.google.android.tvlauncher`, after which HOME resolves to ours and the Home key lands on it. Done on 31 Oct 2026, then verified: PS5 tile switches HDMI 1 with CEC on, reboot lands on the launcher.

Two things the canvas assumes that this build cannot do: a long press on Home sends `ALL_APPS` pinned to the system Settings package (`pkg=com.android.tv.settings`), so it never reaches the launcher and the hint strip says "Ambient for the screensaver" instead; and `com.google.android.youtube.tv.recommendations` was never installed for user 0, so the removal list is eight packages in practice.

### The PS5 bounce (CEC)

While the PS5 is on, the TV's own HDMI input service pulls Live TV back on top within two to nine seconds of any launcher appearing, ours or Google's. Mechanism, from logcat on 31 Oct 2026: when Live TV loses its surface the MediaTek service runs `TIMER_INTERNAL_SELECT` and calls `deviceSelect(ADDR_INTERNAL)`; the PS5 answers with its own Active Source; the service's `HdmiInputChangeListener.requestInputChange` starts Live TV on `HDMI100004`. It does not happen when the PS5 is in rest mode. For screen work with the PS5 on, turn HDMI control off for the session and back on afterwards (`settings put global hdmi_control_enabled 0`, then `1`); never leave it off, it is what lets the PS5 tile wake the PS5.

## HDMI 1 and the PS5

The TV's inputs come from `com.mediatek.tvinput`. HDMI 1 is the passthrough input `com.mediatek.tvinput/.hdmi.HDMIInputService/HW5` (HDMI 2 is `HW6`). Switching is one intent, which Live TV (`com.mediatek.wwtv.tvcenter`) handles; it is the only app that renders HDMI on this set, so it stays installed and is only hidden from the apps row:

```
am start -a android.intent.action.VIEW -d "content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW5"
```

The TV sends CEC `<Set Stream Path>` to port 1 on that switch and the PS5 answers `<Active Source>`. The PS5 (CEC logical address 4, physical 0x1000) also wakes on it when it is in rest mode, as the CEC history showed on 31 Oct 2026 (power status 01 standby, then 00 after the switch). Power state for a normal app: `TvInputManager.getInputState(inputId)` on `HW5` and on the CEC device input `HDMI100004` (0 connected, 1 connected standby, 2 disconnected). `HdmiControlManager` itself needs the signature permission `android.permission.HDMI_CEC`, which a sideloaded app cannot hold, and `cmd hdmi_control` has no shell implementation on this build.

## The mini endpoint (Mac mini)

`mini/today_server.py` serves on port 9470, LAN only (home network, Tailscale range and loopback; anything else gets 403) and behind a bearer token (`/health` is open):

- `GET /today`: orders and revenue today, pieces and shipments awaiting Delhivery pickup, open orders still to fulfil split into shop and custom (custom = tagged `mk-custom` or a product type starting with "Custom"), and the latest sale (title, amount, city, time, a 160 px JPEG of the product). Warehouse marts plus a live Shopify Admin API read for what the nightly rebuild cannot know; cached one minute.
- `GET /dashboard?days=7|30|90`: the "What's going on" page, computed server side: seven tiles with the change against the previous period (followers as an absolute change), sessions by channel group per day (weekly buckets for 90), the social lines (Instagram plus Facebook, Pinterest and YouTube followers carried back before their first snapshot; reach and engagement as 7-day averages, YouTube views and reactions included), Google web search impressions and clicks with orders from search (last touch model), and the top four posts of the last 90 days. Cached ten minutes.
- `GET /thumbs/<media id>.jpg`: a post thumbnail from the warehouse thumbs folder.

It reads `~/Library/Application Support/mk-ig-metrics/ig.duckdb` and `raw_youtube.duckdb` read-only for the milliseconds a query takes, so the nightly dbt writer is never blocked; if dbt holds a file, the last good answer is served with `"stale": true`. Money is `net_sales`, formatted with Indian grouping.

```
cd mini && zsh ./install.sh
```

The installer copies the server to `~/Library/Application Support/mk-tv-today/` (launchd children cannot run from the iCloud-synced Documents folder), creates the token at `~/Library/Application Support/mk-airbyte/secrets/mk-tv-today-token` if missing, and loads the agent `com.moonkiln.tv-today` (log `~/Library/Logs/mk-tv-today.log`). It runs on the warehouse venv's Python, which has the matching DuckDB. Re-run it after editing the server. The launcher reads the same token from `mk.todayToken` in `local.properties` at build time.

```
curl -H "Authorization: Bearer <token>" http://192.168.0.14:9470/today
curl -H "Authorization: Bearer <token>" "http://192.168.0.14:9470/dashboard?days=30"
```

## Claude on the TV (v2)

The TV never runs Claude. A Claude Code session lives on the mini in `claude-on-tv/` (its own CLAUDE.md carries the TV quirks, the device rule and what it may and may not do), started by `claude-on-tv/start.sh` inside tmux as `cc-claude-on-tv` with Remote Control on, so Amba's phone attaches to the same session. The launcher's Claude tile (and the remote's mic keys) open the Claude screen built to Claude.dc.html.

The bridge is part of the mini server (same port, same token):

- The session's hooks (`claude-on-tv/.claude/settings.json` running `bridge_hook.py`) post one event per prompt (`prompt`), per tool call (`tool_start` and `tool_done`, with one-line markers such as "tv · switched to HDMI 1" or "mini · today's numbers") and per answer (`text`, the whole message at Stop; the TV reveals it word by word at 55 ms a word). Session start and end post `status`.
- `GET /bridge/events?since=N&wait=25` is what the TV long-polls while the Claude screen is open; it also carries the status (live, working, listening, offline) and whether the tmux session exists.
- `POST /bridge/prompt` types a prompt into the tmux session with `tmux send-keys` and presses Enter, so a prompt from the TV is no different from one typed at the keyboard. It answers 503 when the session is not running.
- `announce` is accepted as an event type and shown on the Claude screen for now; the home announcement (chime, spoken message, overlay over the PS5 picture) is being designed and will ride the same event.

The remote's microphone needs Bluetooth pairing before voice can work (the remote is infrared only until then); the mic keys and the "Hold mic" pill show the listening state and then point to the phone.

### The Home bounce guard

While the PS5 is on, a Home press is undone by the TV within about two seconds (see "The PS5 bounce (CEC)"). The launcher logs the whole sequence under the tag `MkBounce` and reacts according to a global setting that can be changed over adb without a rebuild:

```
settings put global mk_bounce_strategy reassert        # default: come back once when stopped within 4.5 s of Home with no user action
settings put global mk_bounce_strategy reassert:3      # up to three times
settings put global mk_bounce_strategy route:<input id>  # on Home, route the TV to that input first so CEC sends a Routing Change, then come back
settings put global mk_bounce_strategy off
```

Every strategy stays quiet when the launcher handed the screen over itself (the PS5 tile, an app, Settings).

## Removing the apps Amba does not need, and restoring them

Removed for user 0 only (`pm uninstall --user 0 <package>`), which keeps the APKs on the system partition: `com.google.android.tvlauncher`, `com.google.android.tvrecommendations`, `com.google.android.youtube.tv.recommendations`, `com.google.android.backdrop`, `com.google.android.leanbacklauncher.partnercustomizer`, `com.google.android.videos`, `com.google.android.youtube.tvmusic`, `com.google.android.play.games`, `com.Smartdevice.emanual`.

Kept: `com.mediatek.wwtv.tvcenter` and `com.mediatek.tvinput` (the only HDMI path), `com.android.tv.settings`, `com.google.android.gms`, `com.android.vending` (to reinstall anything later), `com.google.android.apps.mediashell` (Cast), `com.google.android.katniss` (speech for the remote mic, v2), `com.android.bluetooth`, `com.airoha.ble.ota` (remote firmware), and the system packages.

To bring anything back:

```
adb -s 192.168.0.11:5555 shell cmd package install-existing <package>
```

To go back to the Google launcher: reinstall `com.google.android.tvlauncher` and the four packages beside it, then `cmd package set-home-activity com.google.android.tvlauncher/.MainActivity`.
