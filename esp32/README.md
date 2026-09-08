# ESP32-S3 Camera Node — V1 (§17-19)

ESP32-S3 is **only camera/transport** for V1 (§17). No fatigue AI on device.

## Responsibilities
- Camera init (OV2640 / OV3660)
- JPEG encode
- Wi-Fi AP + STA
- HTTP MJPEG + snapshot + status

## Network modes (§18)
- **Direct demo (preferred first):** ESP32 AP `DRIVER-CAM` (no router) → Android `NetworkCameraSource("http://192.168.4.1")`
- **Existing network later:** ESP32 joins phone hotspot / vehicle Wi-Fi

## Image settings (§19)
- Resolution 640×480 @ 10–20 FPS, JPEG quality 60–75, tolerate dropped frames

## Endpoints
```
GET /stream       → multipart/x-mixed-replace MJPEG
GET /snapshot     → single JPEG
GET /status       → JSON { uptime, fps, width, height, clients, nightVision }
GET /alert        → two-tone beep on the MAX98357 speaker (DFR1154)
GET /audio-clip?sec=N → WAV from onboard PDM mic (1–8 s)
GET /night-vision?on=0|1 → IR LED (GPIO47) + low-light AGC
```

## Flash
See `platformio.ini` + `src/main.cpp`. Requires ESP32-S3 board with PSRAM.
