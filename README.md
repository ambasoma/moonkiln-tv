# Moon Kiln TV

A custom Android TV home launcher for Amba's OnePlus TV Y Series (Android TV 11, MediaTek MT5621, 32-bit, 1 GB RAM, 1280x720), PS5-first, in Moon Kiln's own look. It replaces the Google TV launcher. The Mac mini side, a tiny JSON endpoint for the "Moon Kiln today" card, lives in `mini/`.

Linear project: "Moon Kiln TV launcher" (P-MOO-25). No em-dashes anywhere in this repo.

## Layout

- `app/`: the launcher, Kotlin plus Jetpack Compose for TV (`androidx.tv`), minSdk 30, targetSdk 30, `armeabi-v7a` only.
- `mini/`: `today_server.py` (the endpoint), its launchd plist and `install.sh`.

## Build

Everything runs from the command line with the Gradle wrapper and the JDK bundled in Android Studio 2026.1. No system `java` or `gradle` is needed.

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
```

`local.properties` (not in git) needs `sdk.dir=/Users/<you>/Library/Android/sdk` (platform android-36, build-tools 36.0.0). The release build is minified with R8 and signed with the debug key: this is a sideloaded launcher, not a Play upload.

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

## HDMI 1 and the PS5

The TV's inputs come from `com.mediatek.tvinput`. HDMI 1 is the passthrough input `com.mediatek.tvinput/.hdmi.HDMIInputService/HW5` (HDMI 2 is `HW6`). Switching is one intent, which Live TV (`com.mediatek.wwtv.tvcenter`) handles; it is the only app that renders HDMI on this set, so it stays installed and is only hidden from the apps row:

```
am start -a android.intent.action.VIEW -d "content://android.media.tv/passthrough/com.mediatek.tvinput%2F.hdmi.HDMIInputService%2FHW5"
```

The TV sends CEC `<Set Stream Path>` to port 1 on that switch and the PS5 answers `<Active Source>`. The PS5 (CEC logical address 4, physical 0x1000) also wakes on it when it is in rest mode, as the CEC history showed on 31 Oct 2026 (power status 01 standby, then 00 after the switch). Power state for a normal app: `TvInputManager.getInputState(inputId)` on `HW5` and on the CEC device input `HDMI100004` (0 connected, 1 connected standby, 2 disconnected). `HdmiControlManager` itself needs the signature permission `android.permission.HDMI_CEC`, which a sideloaded app cannot hold, and `cmd hdmi_control` has no shell implementation on this build.

## The Moon Kiln today endpoint (Mac mini)

`mini/today_server.py` serves `GET /today` (orders today, revenue today, pieces awaiting Delhivery pickup) and `GET /health` on port 9470, LAN only (home network, Tailscale range and loopback; anything else gets 403) and behind a bearer token. It reads the warehouse file `~/Library/Application Support/mk-ig-metrics/ig.duckdb` read-only for the milliseconds a query takes, so the nightly dbt writer is never blocked; if dbt holds the file, the last good answer is served with `"stale": true`. Because the warehouse is rebuilt once a day at 09:30 IST, today's order count and revenue are topped up live from the Shopify Admin API when the warehouse's Shopify token file exists; the warehouse number is the fallback. Revenue is `net_sales` (after discounts and refunds, before shipping) and comes formatted as `revenue_today_inr`, "Rs. 1,23,456" with Indian grouping. Answers are cached for two minutes.

```
cd mini && ./install.sh
```

The installer copies the server to `~/Library/Application Support/mk-tv-today/` (launchd children cannot run from the iCloud-synced Documents folder), creates the token at `~/Library/Application Support/mk-airbyte/secrets/mk-tv-today-token` if missing, and loads the agent `com.moonkiln.tv-today` (log `~/Library/Logs/mk-tv-today.log`). It runs on the warehouse venv's Python, which has the matching DuckDB. Re-run it after editing the server. The launcher reads the same token from `mk.todayToken` in `local.properties` at build time.

```
curl -H "Authorization: Bearer <token>" http://192.168.0.14:9470/today
```

## Restoring the Google launcher

The Google packages are removed for user 0 only, which keeps their APKs on the system partition. To bring them back:

```
for p in com.google.android.tvlauncher com.google.android.tvrecommendations com.google.android.youtube.tv.recommendations com.google.android.backdrop com.google.android.leanbacklauncher.partnercustomizer; do
  adb -s 192.168.0.11:5555 shell cmd package install-existing $p
done
```

Then pick the launcher again in Settings or set it with `cmd package set-home-activity com.google.android.tvlauncher/.MainActivity`.
