# Handover Notes — Driver Drowsiness MVP (Claude Code → Cursor)

Written 2026-09-08. This documents everything done in this session so work can continue without re-discovering it the hard way.

## Goal

Government/CM demo proving a low-cost drowsiness-detection retrofit device is viable for Indian commercial vehicles, positioned against the AIS-184 mandate (MoRTH, mandatory April 2026 new / Oct 2026 existing models for M2/M3/N2/N3 — buses/trucks). Incumbent: Roadzen's DriveBuddyAI (ARAI-validated, Nasdaq-listed). Our angle: aftermarket ADAS/DMS kits cost ₹25k–75k in India; an ESP32-S3-based unit is a fraction of that, aimed at the existing fleet and price-sensitive segments AIS-184 doesn't reach.

## Hardware

- **DFRobot DFR1154** — ESP32-S3 AI Camera Module (OV3660 camera, onboard PDM mic, MAX98357 I2S amp header for external speaker, WiFi+BT5, IR LED). SKU DFR1154. Wiki: https://wiki.dfrobot.com/dfr1154/ . Official examples: https://github.com/DFRobot/DFR1154_Examples (branch `master`, not `main`).
- Connects to this laptop via **native USB** (no separate USB-UART chip) — shows up as `USB Serial Device`, VID_303A (Espressif) PID_1001, on whatever COM port Windows assigns (was COM9 this session).
- Board pinout (camera, speaker, mic) is verified **directly from DFRobot's own example sketches**, not guessed — see `esp32/boards/dfr1154.h`. Speaker: I2S STD TX, BCLK=45/WS=46/DOUT=42. Mic: I2S PDM RX, CLK=38/DATA=39. **Important ESP32-S3 hardware constraint discovered the hard way: PDM RX only works on I2S_NUM_0** — the speaker (STD TX) had to be moved to I2S_NUM_1 to avoid a boot-looping crash.

## Repo layout (this is `Mayaskara25/drowsy` on GitHub, cloned locally)

- `esp32/` — PlatformIO firmware. Board target `esp32-s3-dfr1154` in `platformio.ini`. **AP-only** (`esp32/include/secrets.h`, gitignored, `USE_STA 0`) — see "Critical lesson" below for why.
- `android/` — the real, full Kotlin companion app (MediaPipe Face Landmarker, EAR/MAR/PERCLOS fatigue engine, Room DB, WorkManager sync, Jetpack Compose UI). This had **never been built once** before this session — see build fixes below.
- `src/drowsy/` + `backend/` — Python reference implementation (FastAPI backend, SQLite, dashboard). Fully tested (`uv run pytest -q` → 10 passed), was already working before this session.
- `dashboard/` — static HTML fleet view served by the backend.

## Toolchain on this laptop

- PlatformIO Core 6.1.18 (`pio` on PATH).
- Android SDK at `C:\Users\varsh\AppData\Local\Android\Sdk`, Android Studio installed, but **no Gradle wrapper existed in `android/`** — generated one this session using a cached Gradle 9.1.0 distro to bootstrap `gradlew` pinned to Gradle 8.7 (matches AGP 8.4.1).
- `JAVA_HOME` env var is **broken system-wide** — points to a deleted Zulu install (`C:\Program Files\Zulu\zulu-17\`). The real, working JDK is Eclipse Adoptium 21 at `C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot`. Every gradle/adb invocation in this session passed `JAVA_HOME` explicitly rather than fixing the system variable — Cursor should do the same or fix it properly:
  ```
  JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot" ANDROID_HOME="C:/Users/varsh/AppData/Local/Android/Sdk" ./gradlew.bat :app:assembleDebug
  ```
- `adb` at `C:\Users\varsh\AppData\Local\Android\Sdk\platform-tools\adb.exe`. The phone is an Oppo/OnePlus CPH2381 (ColorOS). **USB connectivity to this specific phone was extremely flaky all session** — frequently drops to "no devices" or "offline" and needs `adb kill-server && adb start-server`, sometimes a physical unplug/replug. This is a real, recurring annoyance, not a one-off. The Google USB driver had to be manually installed (Windows Device Manager → Update driver → point at the extracted `https://dl.google.com/android/repository/latest_usb_driver_windows.zip`) before `adb` would see the phone at all.
- The MediaPipe face landmarker model (`face_landmarker.task`, 3.7MB float16 build, valid ZIP/TFLite container) is downloaded into `android/app/src/main/assets/` — **gitignored**, re-download if missing:
  ```
  curl -L -o android/app/src/main/assets/face_landmarker.task https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
  ```

## Network topology — read this before touching WiFi config

**Production-correct topology: the ESP32 hosts its own AP (`DRIVER-CAM` / `drowsy123`), the phone joins it directly as a WiFi client.** This is intentional and mirrors a real installed vehicle unit (no router, no phone hotspot needed). Do not revert to "everyone joins one WiFi network" — that was tried first and has a fundamental flaw documented below.

### Critical lesson #1: a phone hosting its own hotspot cannot reach devices on that hotspot
Early in this session the plan was "phone hosts hotspot V, ESP32 and laptop join it." This **does not work**: Android does not let a phone's own apps route to devices connected to a hotspot that same phone is hosting (its own traffic stays on cellular/whatever it had before). This is why the architecture changed to "ESP32 hosts, phone joins."

### Critical lesson #2: ESP32 AP+STA concurrent mode shares one radio/channel
When the ESP32 was configured to run **both** its own AP (`DRIVER-CAM`) and a STA connection to "V" simultaneously (to let the laptop reach it too), AP clients (the phone) got **100% packet loss** despite a valid IP/association. Root cause: ESP32's single WiFi radio forces AP+STA onto the same channel; this concurrent-mode combination was unstable on this hardware. Fix: `USE_STA 0` in `esp32/include/secrets.h` — **AP-only**. Do not re-enable STA unless you have a specific reason and are prepared to re-diagnose this.

### Critical lesson #3: Android blocks cleartext (plain HTTP) traffic by default since API 28
The ESP32 only serves plain HTTP (no TLS — not feasible on a microcontroller for this MVP). `targetSdk 34` blocks cleartext to any host by default → every camera-fetch request silently failed with `Cleartext HTTP traffic to 192.168.4.1 not permitted`. Fixed via `android/app/src/main/res/xml/network_security_config.xml` (allows cleartext to `192.168.4.1` and the backend's LAN IP) wired into `AndroidManifest.xml` via `android:networkSecurityConfig`. **If you add new local-network IPs (e.g. a different backend host), add them here too.**

### Critical lesson #4: MediaPipe recycles the bitmap you hand it
`MediaPipeLandmarkerEngine` passes `frame.bitmap` to `BitmapImageBuilder`, which appears to recycle the source bitmap after use. The UI was crashing (`Canvas: trying to use a recycled bitmap`) because `MonitorViewModel` was also handing that same bitmap object to Compose for the live preview. Fixed by taking a defensive `.copy()` of the bitmap **before** calling `perception.processFrame()`, specifically for UI display (`MonitorViewModel.kt` around the `previewBmp` local).

### Critical lesson #5: ColorOS has a buggy `WifiNetworkSpecifier` approval dialog
The app uses `android.net.wifi.WifiNetworkSpecifier` (via `com/drowsy/network/DeviceWifiConnector.kt`) to programmatically join the ESP32's AP without touching the phone's system WiFi settings — this is the "production grade" fix so the app can auto-connect to its own device. On this specific ColorOS phone, the system approval UI sometimes shows a broken "Open with: Settings / Wireless Settings" chooser that cancels the request instead of showing the real approval sheet. The code's `onFailed` fallback (falls back to whatever the phone's default route already is) is what actually saved the day — **manually joining `DRIVER-CAM` via system WiFi settings** (and tapping "Yes/stay connected" when Android warns about no internet) works reliably and is the fallback path users may need on this OEM. Don't be surprised if the automatic path is flaky here; it's a known-class OEM bug, not an app bug.

## Current state / what's verified working

- ESP32 firmware: camera (`/stream`, `/snapshot`, `/status`), speaker alert (`GET /alert` — two-tone beep via MAX98357), mic capture (`GET /audio-clip?sec=N` — returns WAV), and **just-added, not yet re-verified after the last reflash**: night vision (`GET /night-vision?on=0|1` toggles IR LED at GPIO47 + tunes sensor AGC/AEC for low light — was mid-flash-and-verify when this session ended, USB dropped before confirming). All of these were individually curl-tested and worked before the night-vision addition.
- Android app: **builds clean**, installs, and was confirmed showing a **live camera feed with real face detection** (screenshot evidence: "Driver detected" / FPS 8.9 / 2ms inference) before this session ended. The full pipeline — ESP32 camera → phone WiFi client → MediaPipe → EAR/MAR/PERCLOS → fatigue state machine → phone beep + ESP32 speaker beep trigger — was working end-to-end at that point.
- Backend (`uv run uvicorn backend.main:app --host 0.0.0.0 --port 8000`) and dashboard: unchanged from before this session, tested, working — but note the phone **cannot reach it** while joined to the ESP32's isolated AP (no route back to the laptop). This is expected/by-design (local-first, sync-when-online), not a bug — see README's own offline-first philosophy.
- All 10 Python tests still pass (`uv run pytest -q`).

## Not yet done / immediate next steps

1. **Reflash and re-verify night vision.** Firmware compiled clean and was flashed via `pio run -e esp32-s3-dfr1154 -t upload --upload-port <COMx>`, but USB dropped before confirming `/night-vision` actually works and before the phone reconnected to re-test the camera feed with IR on. Start here.
2. Clean up temporary diagnostic `Log.d`/`Log.e` calls added to `NetworkCameraSource.kt` and `DeviceWifiConnector.kt` for debugging — harmless to leave, but noisy; remove or gate behind `BuildConfig.DEBUG` if shipping further.
3. Nothing has been committed to git this session — see below.
4. On-device ML (running detection on the ESP32 chip itself instead of the phone) was requested but explicitly deferred: DFRobot's own examples only cover generic person detection (no face/eye/drowsiness model exists off-the-shelf); building one means training a custom Edge Impulse model from scratch (dataset collection + labeling + training + export), a multi-day-to-weeks effort on its own. Don't attempt this under demo time pressure without a real scoping conversation first.
5. Bluetooth was explicitly scoped out of this MVP (chip supports it, zero code exists).

## Uncommitted changes

Everything above is **uncommitted** in git (`git status` shows all the modified/new files). Repo is 1 commit behind `origin/main`. Decide whether to commit before/as part of continuing in Cursor — nothing has been pushed anywhere.
