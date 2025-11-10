# Weather-Station-APP

**Updated:** 2025-10-02  
**Status:** Stable — documentation-only update (no functional changes)

## Overview

Weather-Station-APP is a native Android application built with **Kotlin** and **Jetpack Compose (Material 3)** to visualize and monitor a weather/fire-risk system.  
It consumes a REST API exposed at `BuildConfig.API_BASE_URL` (ending with `/api/`) and renders both summary widgets and detailed time-series charts.  
The app includes a lightweight **alarm system** that periodically evaluates server-side metrics and triggers **system notifications** under configurable thresholds.

> This update focuses on **English technical documentation**: every Spanish comment or UI text found was translated into clear technical English.  
> **No behavior or logic was modified.**

## Key Features

- **Two main tabs:** _Summary_ and _Details_.
- **Metrics supported:**
  - Temperature & Humidity → measurement: `sensor_data`, `sensor_id=1`
  - Wind Speed → measurement: `wind_data`, `sensor_id=2`
  - Fire-risk index (**F_index**) → measurement: `f_index`, field: `F_index`, `sensor_id=calcF`
- **Latest summary endpoint** with fallback to time ranges.
- **Compose-driven UI** with fullscreen summary view.
- **Vico charts** for historical data, with device time zone formatting.
- **Alarms & Notifications:**
  - User-defined alarms with operator and threshold.
  - Evaluation every **10 s**, aligned (mm:05, mm:15, …).
  - **Hysteresis ±0.1** and **rounding to one decimal** (HALF_UP).
  - Uses the **database timestamp of the same metric** for notification content.
  - Auto-disables an alarm after firing to avoid duplicates.
  - Foreground service + boot persistence.

## Architecture

- **Language/Runtime:** Kotlin (JVM 17 with desugaring), Compose, Coroutines.
- **Networking:** Retrofit + OkHttp + Moshi.
- **Persistence:** Proto/JSON via DataStore (Moshi adapter).
- **Foreground Work:** ForegroundService with custom aligned ticker.
- **Notifications:** Notification channels and receivers.
- **Time:** `java.time` across the app; data from API is UTC; UI formats in **device time zone** (default `America/Bogota`).

### Notable Modules

- `net/RetrofitProvider.kt` — Retrofit/OkHttp client configured from `BuildConfig.API_BASE_URL`.
- `net/ApiService.kt` — `latest_summary`, `range` endpoints.
- `ui/SummaryScreen.kt` — latest summary gauges, fullscreen view.
- `ui/details/*` — charts, formatting, and detail fetching.
- `ui/notifications/*` — alarms UI, DataStore, evaluator, foreground service, receivers.
- `util/*` — time formatting, aligned ticker, helpers.

## Build & Run

**Requirements**
- Android Studio Ladybug / Hedgehog+
- AGP 8.13+, Kotlin 2.2.x
- Android SDK 24–36

**Steps**
1. Open the project in Android Studio.
2. Ensure `local.properties` and SDK paths are set.
3. Build & run the `app` module on an emulator or device (Android 7.0+).

## Configuration

- **API base URL:** set via `BuildConfig.API_BASE_URL` (e.g., `https://api.weatherstation.site/api/`).
- **CORS/Tunnel:** The app assumes server-side CORS is enabled and the Cloudflare tunnel configured (as per project conventions).
- **Time zones:** The backend must return timestamps in **UTC**. The app formats timestamps using the **device time zone** (commonly `America/Bogota`).

## Permissions

- `INTERNET` — API calls.
- `POST_NOTIFICATIONS` (Android 13+) — runtime permission requested by the UI.
- `FOREGROUND_SERVICE` (and variants like `_DATA_SYNC`) — for periodic evaluation.
- `RECEIVE_BOOT_COMPLETED` — to resume evaluation after reboot if alarms are enabled.

## Alarms & Notification Flow

1. User creates an alarm (metric, operator, threshold).
2. Foreground service runs every **10 seconds** (aligned).
3. Evaluator fetches **latest_summary** for the target metric.
4. Value is **rounded to 1 decimal (HALF_UP)**; hysteresis **±0.1** is applied.
5. If condition matches and timestamp changed since last fire, a **system notification** is posted.
6. The alarm auto-disables to prevent duplicates. Re-enable to evaluate again.

> **Important:** Notifications display the **database timestamp of the same metric**, formatted in the device time zone.

## Testing

- Use Logcat to confirm network calls to `/api/latest_summary?window=5m`.
- Verify notification channels exist before first post.
- On Android 13+, ensure `POST_NOTIFICATIONS` permission is granted.
- Simulate reboot to validate `BootCompletedReceiver` path.

## Contributing

- Keep code comments in **English**.
- Avoid logic changes in documentation-only updates.
- Follow Kotlin style guidelines and prefer KDoc for public APIs.

## License

This project is provided **as-is** for educational and research purposes.  
Include your preferred license (MIT/Apache-2.0) as needed.

