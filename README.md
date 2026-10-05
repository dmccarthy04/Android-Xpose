# Xpose

An Android app that shows how much a phone reveals about its owner from metadata alone.

Xpose passively logs location fixes, Bluetooth scans, app usage, screen state and network changes, then infers a behavioral profile on the device: where you live and work, when you sleep, how regular your routine is, and who likely lives with you. It never reads messages, calls, photos or files.

## What it infers

| Behavior | How |
|---|---|
| Places | Clusters GPS fixes using haversine distance and a radius that adapts to GPS accuracy |
| Home, work, venues | Labels clusters by night and weekday visit counts plus minimum dwell time, with a confidence score that decays over time |
| App usage | Pairs foreground resume/pause events into sessions, ranks apps by time used, detects habits |
| Sleep and routine | Pairs screen off/on events, merges brief overnight phone checks, uses a circular mean so times around midnight average correctly |
| Social exposure | Filters out routers and TVs with a three-signal test, estimates distance from Bluetooth signal strength, counts devices that appear at home across several nights |
| Permissions | Checks permissions live and guards every service call, so revoking access degrades a feature instead of crashing the app |

## Tech

Kotlin, Jetpack Compose (Material 3), Room (SQLite), coroutines, Google Maps Compose, foreground services, UsageStatsManager, BLE scanning. All processing happens on the device, and there is no backend.

## Build

1. Android Studio (current stable), JDK 17. Min SDK 31, target SDK 36.
2. Set your own Google Maps API key in `AndroidManifest.xml` (`com.google.android.geo.API_KEY`), restricted to the package `com.example.androidxpose`.
3. Run on a physical device and grant location, Bluetooth and usage access when prompted. The profile builds up as the app collects data over several days.
