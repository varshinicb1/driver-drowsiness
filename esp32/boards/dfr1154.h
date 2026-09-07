#pragma once
// DFRobot DFR1154 — ESP32-S3 AI Camera Module (OV3660, 42x42mm)
// Pins verified verbatim against DFRobot's own CameraWebServer example:
// https://github.com/DFRobot/DFR1154_Examples/blob/master/5.3%20CameraWebServer/source%20code/5_3/5_3.ino
#define PWDN_GPIO_NUM  -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM   5
#define SIOD_GPIO_NUM   8
#define SIOC_GPIO_NUM   9
#define Y9_GPIO_NUM     4
#define Y8_GPIO_NUM     6
#define Y7_GPIO_NUM     7
#define Y6_GPIO_NUM    14
#define Y5_GPIO_NUM    17
#define Y4_GPIO_NUM    21
#define Y3_GPIO_NUM    18
#define Y2_GPIO_NUM    16
#define VSYNC_GPIO_NUM  1
#define HREF_GPIO_NUM   2
#define PCLK_GPIO_NUM  15

// Onboard IR/status LED (DFRobot wiki: GPIO47 = infrared illumination)
#define LED_GPIO_NUM   47

// Speaker (I2S STD TX -> onboard MAX98357 amp -> external 4R1.5W/8R1W speaker)
// Pins verified from DFRobot's own Recording & Playback example.
#define SPK_BCLK_GPIO_NUM 45
#define SPK_WS_GPIO_NUM   46
#define SPK_DOUT_GPIO_NUM 42

// Onboard microphone (I2S PDM RX)
#define MIC_CLK_GPIO_NUM  38
#define MIC_DATA_GPIO_NUM 39
