# OBD Scanner (Android)

An Android app that reads live engine data and diagnostic trouble codes from a car
through a cheap ELM327 OBD-II adapter (Bluetooth or Wi-Fi).

## Features

- **Connect** to a paired Bluetooth ELM327 (with fallbacks for picky clone adapters),
  a Bluetooth LE adapter (found by scanning), a Wi-Fi ELM327 (default `192.168.0.10:35000`), or a **demo car** to try the app with no hardware.
- **Dashboard**: RPM and speed gauges plus your favorite sensors, each with a live trend graph.
  Switch to **All sensors** to see every one of the ~90 standard Mode 01 sensors the car supports
  (O2 sensors, wide-band λ, catalyst temperatures, EVAP, fuel rail, torque, odometer, fuel system
  status…). Tap any sensor for a big graph with min / average / max and to add it to favorites.
- **Trip computer**: live and average MPG (or L/100 km), distance and fuel used.
- **Recording**: tap *Record* on the dashboard to log live data to CSV, then *Share log*.
- **Trouble codes**: check-engine light status, stored / pending / permanent codes with
  descriptions and a *Search online* button (uses the year and make from the VIN), the
  **freeze frame** saved when the code was set, **smog-check readiness monitors**, and clearing codes.
- **Self-tests (Mode 06)**: the car's own pass/fail results for O2 sensors, catalyst, EVAP leak
  tests, EGR, misfire counts per cylinder…
- **Vehicle**: year/make, VIN, which modules answer (engine, transmission…), calibration IDs and
  CVNs (Mode 09), protocol, battery voltage, units setting (US or metric), and a raw command terminal.
- **Reconnect** to the last adapter with one tap.
- **All-system scan (experimental)**: finds every module on the main CAN bus (engine,
  transmission, ABS, airbag…) and reads its trouble codes (UDS `19 02`, falling back to GMLAN `A9`).
  Read-only. The raw exchange can be shared for troubleshooting. Modules on GM's single-wire bus
  (OBD pin 1) are out of reach for ELM327 adapters.
- **Manufacturer & custom sensors**: built-in GM extras (e.g. transmission fluid temperature —
  unverified until confirmed on a car) and your own sensors: module address, request and a formula
  (`A-40`, `(A*256+B)/4`, `SIGNED16(A,B)/100`…). They work like any other sensor: favorites,
  gauges, alerts, graphs, recording.
- **ID explorer**: scans a module for data IDs (service `22`) that answer, to find new values.
- **Customizable**: Settings screen with theme (system / light / dark), accent colors or
  Material You wallpaper colors, US or metric units, compact tiles, trend graphs, keep-screen-on.
  Pick which sensors are favorites and big gauges, reorder them, and set **alerts** (low/high
  limits) on any sensor — the tile turns red and a banner appears when one is exceeded.

## Project layout

| Module | What it is |
| --- | --- |
| `obd-core/` | Pure Kotlin/JVM library: ELM327 channel, response parsing (incl. CAN multi-frame), PID formulas, DTC decoding and descriptions, VIN, and a simulated adapter. Unit-tested, no Android dependency. |
| `app/` | Jetpack Compose UI, Bluetooth / Wi-Fi transports, `ObdViewModel`. |

## Building

Requirements: JDK 17+, Android SDK (API 35). Open the folder in Android Studio, or:

```sh
./gradlew :obd-core:test          # protocol tests (no Android SDK needed for the code under test)
./gradlew :app:assembleDebug      # APK in app/build/outputs/apk/debug/
```

Every push runs `.github/workflows/android.yml`, which runs the tests and uploads the
debug APK as a build artifact (Actions tab → latest run → *obd-scanner-debug-apk*).

## Using it in a car

1. Plug the adapter into the OBD-II port (under the dashboard, driver's side) and turn the ignition on.
2. Pair it in Android Bluetooth settings (PIN is usually `1234` or `0000`).
3. Open the app, allow Bluetooth access, tap the adapter.
   The first connection can take ~10 s while the adapter detects the car's protocol.

Bluetooth **LE** adapters (Vgate iCar Pro BLE, OBDLink CX, cheap FFF0/FFE0 clones) don't pair in
system settings: use **Search for devices** under *Bluetooth LE adapter* instead.

If an adapter connects but doesn't answer like an ELM327 (e.g. brand-locked dongles such as the
Innova 3215RS), the app shows an **adapter diagnostics** report: the GATT services and characteristics
it offers and every byte exchanged. That report is what's needed to add support for it.
