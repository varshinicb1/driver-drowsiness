# Boards — pin maps (§17, §32)

Add a new board by copying `eye.h` → `myboard.h` and adding an env in `platformio.ini`. **Never edit `src/main.cpp` directly for pins.**

## Supported

| Env | Board | Header | Notes |
|-----|-------|--------|-------|
| `esp32-s3-eye` | Freenove ESP32-S3-EYE / ESP32-S3-CAM | `boards/eye.h` | Default, PSRAM required, OV2640 on board |
| `esp32-s3-devkitc-1` | Generic S3 + external OV2640 | `boards/devkitc.h` | Wire external camera as EYE pins + 5V/GND |
| `esp32-s3-xiao` | Seeed XIAO ESP32S3 Sense | `boards/xiao.h` | On-board OV2640, different pins, 8MB PSRAM |
| `esp32-s3-dfr1154` | DFRobot DFR1154 (ESP32-S3 AI Camera Module, SKU DFR1154) | `boards/dfr1154.h` | On-board OV3660, N16R8 (16MB flash/8MB octal PSRAM), pins verified from DFRobot's own CameraWebServer example |

## eye.h (default)

```cpp
// Freenove EYE — matches main.cpp pins before refactor
#define PWDN_GPIO_NUM  32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM   0
#define SIOD_GPIO_NUM  26
#define SIOC_GPIO_NUM  27
#define Y9_GPIO_NUM    35
#define Y8_GPIO_NUM    34
#define Y7_GPIO_NUM    39
#define Y6_GPIO_NUM    36
#define Y5_GPIO_NUM    21
#define Y4_GPIO_NUM    19
#define Y3_GPIO_NUM    18
#define Y2_GPIO_NUM     5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM  23
#define PCLK_GPIO_NUM  22
```

## xiao.h (XIAO Sense)

```cpp
// Seeed XIAO ESP32S3 Sense — OV2640 on board (verify with Seeed wiki for your rev)
#define PWDN_GPIO_NUM  -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM  10
#define SIOD_GPIO_NUM  40
#define SIOC_GPIO_NUM  39
#define Y9_GPIO_NUM    48
#define Y8_GPIO_NUM    11
#define Y7_GPIO_NUM    12
#define Y6_GPIO_NUM    14
#define Y5_GPIO_NUM    16
#define Y4_GPIO_NUM    18
#define Y3_GPIO_NUM    17
#define Y2_GPIO_NUM    15
#define VSYNC_GPIO_NUM 38
#define HREF_GPIO_NUM  47
#define PCLK_GPIO_NUM  13
```

## devkitc.h

Same as `eye.h` — wire an external OV2640 module to those GPIOs + 3V3/GND.

## Adding a new board

1. `cp esp32/boards/eye.h esp32/boards/myboard.h` and edit pins per your board's schematic.
2. In `platformio.ini` add:
   ```ini
   [env:myboard]
   platform = espressif32
   board = esp32-s3-devkitc-1
   framework = arduino
   build_flags =
     -DBOARD_HAS_PSRAM
     -I include/boards
     -include boards/myboard.h
   ```
3. Flash: `pio run -e myboard -t upload`

Pin mismatches cause `Camera init failed 0x105` — always try another env before rewiring.
