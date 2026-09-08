/**
 * ESP32-S3 Camera Node — V1 (§17)
 * Camera → JPEG → Wi-Fi → MJPEG/snapshot/status. No fatigue AI.
 */
#include <Arduino.h>
#include <WiFi.h>
#include <esp_camera.h>
#include <esp_http_server.h>
#include <driver/i2s.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>
#include <strings.h>

#if __has_include("secrets.h")
#include "secrets.h"
#endif
#ifndef AP_SSID
#define AP_SSID "DRIVER-CAM"
#endif
#ifndef AP_PASS
#define AP_PASS "drowsy123"
#endif
#ifndef AP_CHANNEL
#define AP_CHANNEL 6
#endif
#ifndef AP_MAX_CLIENTS
#define AP_MAX_CLIENTS 1
#endif

static int frameWidth = 640;
static int frameHeight = 480;

static httpd_handle_t stream_httpd = NULL;
static httpd_handle_t snapshot_httpd = NULL;
static bool nightVisionOn = false;
static bool sceneAuto = true;
static float lastSceneLuma = 128.f;
static SemaphoreHandle_t cameraMux = NULL;
static SemaphoreHandle_t i2sMux = NULL;

static void withCameraMux(void (*fn)(void *), void *arg) {
  if (cameraMux && xSemaphoreTake(cameraMux, pdMS_TO_TICKS(3000)) == pdTRUE) {
    fn(arg);
    xSemaphoreGive(cameraMux);
  }
}

struct FreshFrameCtx { camera_fb_t **out; int discard; };
static void grabFreshFrameFn(void *arg) {
  auto *ctx = (FreshFrameCtx *)arg;
  for (int i = 0; i < ctx->discard; i++) {
    camera_fb_t *stale = esp_camera_fb_get();
    if (!stale) { *ctx->out = nullptr; return; }
    esp_camera_fb_return(stale);
  }
  *ctx->out = esp_camera_fb_get();
}

static camera_fb_t *grabFreshFrame() {
  camera_fb_t *fb = nullptr;
  FreshFrameCtx ctx = { &fb, 1 };
  withCameraMux(grabFreshFrameFn, &ctx);
  return fb;
}

struct StreamFrameCtx { camera_fb_t **out; };
static void grabStreamFrameFn(void *arg) {
  auto *ctx = (StreamFrameCtx *)arg;
  *ctx->out = esp_camera_fb_get();
}

static camera_fb_t *grabStreamFrame() {
  camera_fb_t *fb = nullptr;
  StreamFrameCtx ctx = { &fb };
  withCameraMux(grabStreamFrameFn, &ctx);
  return fb;
}

static void applyNightVisionFn(void *) {
#ifdef LED_GPIO_NUM
  digitalWrite(LED_GPIO_NUM, nightVisionOn ? HIGH : LOW);
#endif
  sensor_t *s = esp_camera_sensor_get();
  if (s == nullptr) return;
  s->set_whitebal(s, 1);
  s->set_awb_gain(s, 1);
  s->set_exposure_ctrl(s, 1);
  s->set_gain_ctrl(s, 1);
  s->set_raw_gma(s, 1);
  s->set_lenc(s, 1);
  s->set_bpc(s, 0);
  s->set_wpc(s, 1);
  s->set_dcw(s, 1);
  if (nightVisionOn) {
    s->set_aec2(s, 1);
    s->set_gainceiling(s, GAINCEILING_16X);
    s->set_ae_level(s, 1);
    s->set_brightness(s, 1);
    s->set_contrast(s, 0);
    s->set_saturation(s, -1);
    s->set_wb_mode(s, 0);
    s->set_aec_value(s, 400);
    s->set_agc_gain(s, 8);
  } else {
    // Daylight / cabin: IR off — lift exposure so faces are not silhouetted.
    s->set_aec2(s, 1);
    s->set_gainceiling(s, GAINCEILING_128X);
    s->set_ae_level(s, 2);
    s->set_brightness(s, 2);
    s->set_contrast(s, 0);
    s->set_saturation(s, 0);
    s->set_wb_mode(s, 4); // indoor / home white balance
    s->set_aec_value(s, 600);
    s->set_agc_gain(s, 16);
  }
}

static void warmupCameraExposure() {
  for (int i = 0; i < 10; i++) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb);
    delay(60);
  }
}

static void applyNightVisionHardware() {
  withCameraMux(applyNightVisionFn, nullptr);
}

static float estimateJpegLuma(camera_fb_t *fb) {
  if (!fb || fb->len < 200) return 128.f;
  uint32_t sum = 0;
  uint32_t count = 0;
  for (size_t i = 200; i + 64 < fb->len; i += 64) {
    sum += fb->buf[i];
    count++;
  }
  return count ? (float)sum / count : 128.f;
}

static void autoSceneAdjust() {
  if (!sceneAuto) return;
  camera_fb_t *fb = grabFreshFrame();
  if (!fb) return;
  float luma = estimateJpegLuma(fb);
  esp_camera_fb_return(fb);
  lastSceneLuma = luma;
  bool wantIr = nightVisionOn;
  if (luma < 72.f) wantIr = true;
  else if (luma > 94.f) wantIr = false;
  if (wantIr != nightVisionOn) {
    nightVisionOn = wantIr;
    applyNightVisionHardware();
    Serial.printf("auto scene IR=%d luma=%.0f\n", nightVisionOn, luma);
  }
}

#ifdef SPK_BCLK_GPIO_NUM
// Vehicle-mounted audible alert — I2S STD TX to onboard MAX98357 amp (§ AIS-184 acoustic warning).
static bool spk_ready = false;

static bool ensureSpeaker() {
  if (spk_ready) return true;
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = 16000;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 4;
  cfg.dma_buf_len = 256;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = true;
  if (i2s_driver_install(I2S_NUM_1, &cfg, 0, NULL) != ESP_OK) return false;
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = SPK_BCLK_GPIO_NUM;
  pins.ws_io_num = SPK_WS_GPIO_NUM;
  pins.data_out_num = SPK_DOUT_GPIO_NUM;
  pins.data_in_num = I2S_PIN_NO_CHANGE;
  if (i2s_set_pin(I2S_NUM_1, &pins) != ESP_OK) return false;
  spk_ready = true;
  return true;
}

// Rising two-tone beep (900Hz -> 1400Hz), stereo-duplicated so it plays regardless of the
// MAX98357's L/R gain-pin strapping.
static void playToneBurst(int freqHz, int toneMs) {
  const int sampleRate = 16000;
  int period = sampleRate / freqHz;
  int samples = (sampleRate * toneMs) / 1000;
  int16_t buf[128];
  int written = 0;
  while (written < samples) {
    int chunkSamples = min(128, samples - written);
    for (int i = 0; i < chunkSamples; i++) {
      int idx = written + i;
      bool high = (idx % period) < (period / 2);
      buf[i] = high ? 22000 : -22000;
    }
    size_t bytesWritten = 0;
    i2s_write(I2S_NUM_1, buf, chunkSamples * sizeof(int16_t), &bytesWritten, portMAX_DELAY);
    written += chunkSamples;
  }
}

static void playAlertToneTyped(int type) {
  if (i2sMux && xSemaphoreTake(i2sMux, pdMS_TO_TICKS(5000)) != pdTRUE) return;
  if (!ensureSpeaker()) { xSemaphoreGive(i2sMux); return; }
  if (type == 0) {
    // attention — single short chirp
    playToneBurst(700, 120);
  } else if (type == 2) {
    // high risk — three rapid high tones
    for (int i = 0; i < 3; i++) {
      playToneBurst(1200, 160);
      delay(30);
    }
  } else {
    // fatigue — rising two-tone
    playToneBurst(900, 220);
    delay(40);
    playToneBurst(1400, 220);
  }
  xSemaphoreGive(i2sMux);
}

struct AlertTaskArg { int type; };
static void alertTask(void *param) {
  int type = param ? ((AlertTaskArg *)param)->type : 1;
  playAlertToneTyped(type);
  if (param) free(param);
  vTaskDelete(NULL);
}

static esp_err_t alert_handler(httpd_req_t *req) {
  int type = 1;
  char query[32];
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK) {
    char val[16];
    if (httpd_query_key_value(query, "type", val, sizeof(val)) == ESP_OK) {
      if (strcasecmp(val, "attention") == 0) type = 0;
      else if (strcasecmp(val, "high") == 0) type = 2;
      else type = 1;
    }
  }
  auto *arg = (AlertTaskArg *)malloc(sizeof(AlertTaskArg));
  if (arg) { arg->type = type; xTaskCreate(alertTask, "alert", 4096, arg, 1, NULL); }
  else { xTaskCreate(alertTask, "alert", 4096, NULL, 1, NULL); }
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, "{\"ok\":true}", HTTPD_RESP_USE_STRLEN);
}
#endif

#ifdef MIC_CLK_GPIO_NUM
// Evidentiary audio clip captured alongside a fatigue event — onboard PDM mic.
static bool mic_ready = false;

static bool ensureMic() {
  if (mic_ready) return true;
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM);
  cfg.sample_rate = 16000;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 4;
  cfg.dma_buf_len = 256;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = false;
  if (i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL) != ESP_OK) return false;
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = I2S_PIN_NO_CHANGE;
  pins.ws_io_num = MIC_CLK_GPIO_NUM;
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = MIC_DATA_GPIO_NUM;
  if (i2s_set_pin(I2S_NUM_0, &pins) != ESP_OK) return false;
  mic_ready = true;
  return true;
}

struct __attribute__((packed)) WavHeader {
  char riff[4] = {'R','I','F','F'};
  uint32_t chunkSize = 0;
  char wave[4] = {'W','A','V','E'};
  char fmt[4] = {'f','m','t',' '};
  uint32_t subchunk1Size = 16;
  uint16_t audioFormat = 1;
  uint16_t numChannels = 1;
  uint32_t sampleRate = 16000;
  uint32_t byteRate = 16000 * 2;
  uint16_t blockAlign = 2;
  uint16_t bitsPerSample = 16;
  char data[4] = {'d','a','t','a'};
  uint32_t dataSize = 0;
};

static esp_err_t audioclip_handler(httpd_req_t *req) {
  int seconds = 3;
  char query[32];
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK) {
    char val[8];
    if (httpd_query_key_value(query, "sec", val, sizeof(val)) == ESP_OK) seconds = atoi(val);
  }
  if (seconds < 1) seconds = 1;
  if (seconds > 8) seconds = 8;

  if (!ensureMic()) { httpd_resp_send_500(req); return ESP_FAIL; }
  if (i2sMux && xSemaphoreTake(i2sMux, pdMS_TO_TICKS(5000)) != pdTRUE) {
    httpd_resp_send_500(req); return ESP_FAIL;
  }

  size_t dataBytes = (size_t)16000 * 2 * seconds;
  uint8_t *pcm = (uint8_t *)malloc(dataBytes);
  if (!pcm) { httpd_resp_send_500(req); return ESP_FAIL; }
  size_t totalRead = 0;
  while (totalRead < dataBytes) {
    size_t bytesRead = 0;
    esp_err_t err = i2s_read(I2S_NUM_0, pcm + totalRead, dataBytes - totalRead, &bytesRead, pdMS_TO_TICKS(2000));
    if (err != ESP_OK || bytesRead == 0) break;
    totalRead += bytesRead;
  }

  WavHeader hdr;
  hdr.dataSize = totalRead;
  hdr.chunkSize = 36 + totalRead;

  httpd_resp_set_type(req, "audio/wav");
  httpd_resp_send_chunk(req, (const char *)&hdr, sizeof(hdr));
  httpd_resp_send_chunk(req, (const char *)pcm, totalRead);
  httpd_resp_send_chunk(req, NULL, 0);
  free(pcm);
  if (i2sMux) xSemaphoreGive(i2sMux);
  return ESP_OK;
}
#endif

static esp_err_t ping_handler(httpd_req_t *req) {
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-cache");
  return httpd_resp_send(req, "{\"ok\":true}", HTTPD_RESP_USE_STRLEN);
}

static esp_err_t status_handler(httpd_req_t *req) {
  char buf[320];
  snprintf(buf, sizeof(buf),
    "{\"uptime\":%lu,\"width\":%d,\"height\":%d,\"fps\":15,\"clients\":%d,"
    "\"nightVision\":%s,\"sceneAuto\":%s,\"sceneLuma\":%.1f}",
    millis()/1000, frameWidth, frameHeight, WiFi.softAPgetStationNum(),
    nightVisionOn ? "true" : "false",
    sceneAuto ? "true" : "false",
    lastSceneLuma);
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, buf, strlen(buf));
}

static esp_err_t snapshot_handler(httpd_req_t *req) {
  camera_fb_t *fb = grabFreshFrame();
  if (!fb) { httpd_resp_send_500(req); return ESP_FAIL; }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store, no-cache, must-revalidate");
  httpd_resp_set_hdr(req, "Connection", "close");
  esp_err_t res = httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return res;
}

static esp_err_t stream_handler(httpd_req_t *req) {
  httpd_resp_set_type(req, "multipart/x-mixed-replace;boundary=frame");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  char part_hdr[64];
  while (true) {
    camera_fb_t *fb = grabStreamFrame();
    if (!fb) { delay(1); continue; }
    snprintf(part_hdr, sizeof(part_hdr),
      "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", fb->len);
    if (httpd_resp_send_chunk(req, part_hdr, strlen(part_hdr)) != ESP_OK) { esp_camera_fb_return(fb); break; }
    if (httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len) != ESP_OK) { esp_camera_fb_return(fb); break; }
    if (httpd_resp_send_chunk(req, "\r\n", 2) != ESP_OK) { esp_camera_fb_return(fb); break; }
    esp_camera_fb_return(fb);
    // No artificial delay — send as fast as WiFi + sensor allow (~15–25 FPS).
    if (httpd_req_to_sockfd(req) < 0) break;
  }
  return ESP_OK;
}

#ifdef LED_GPIO_NUM
static esp_err_t nightvision_handler(httpd_req_t *req) {
  char query[24];
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK) {
    char val[8];
    if (httpd_query_key_value(query, "on", val, sizeof(val)) == ESP_OK) {
      if (strcasecmp(val, "auto") == 0) {
        sceneAuto = true;
        autoSceneAdjust();
      } else {
        sceneAuto = false;
        nightVisionOn = atoi(val) != 0;
        applyNightVisionHardware();
      }
    }
  }
  char buf[64];
  snprintf(buf, sizeof(buf),
    "{\"nightVision\":%s,\"sceneAuto\":%s,\"sceneLuma\":%.1f}",
    nightVisionOn ? "true" : "false",
    sceneAuto ? "true" : "false",
    lastSceneLuma);
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, buf, HTTPD_RESP_USE_STRLEN);
}
#endif

void startServer() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  config.lru_purge_enable = true;
  config.max_open_sockets = 7;
  config.recv_wait_timeout = 5;
  config.send_wait_timeout = 5;
  config.stack_size = 10240;
  httpd_handle_t server = NULL;
  if (httpd_start(&server, &config) == ESP_OK) {
    httpd_uri_t s0 = {.uri="/ping", .method=HTTP_GET, .handler=ping_handler, .user_ctx=NULL};
    httpd_uri_t s1 = {.uri="/status", .method=HTTP_GET, .handler=status_handler, .user_ctx=NULL};
    httpd_uri_t s2 = {.uri="/snapshot", .method=HTTP_GET, .handler=snapshot_handler, .user_ctx=NULL};
    httpd_uri_t s3 = {.uri="/stream", .method=HTTP_GET, .handler=stream_handler, .user_ctx=NULL};
    httpd_register_uri_handler(server, &s0);
    httpd_register_uri_handler(server, &s1);
    httpd_register_uri_handler(server, &s2);
    httpd_register_uri_handler(server, &s3);
#ifdef SPK_BCLK_GPIO_NUM
    httpd_uri_t s4 = {.uri="/alert", .method=HTTP_GET, .handler=alert_handler, .user_ctx=NULL};
    httpd_register_uri_handler(server, &s4);
#endif
#ifdef MIC_CLK_GPIO_NUM
    httpd_uri_t s5 = {.uri="/audio-clip", .method=HTTP_GET, .handler=audioclip_handler, .user_ctx=NULL};
    httpd_register_uri_handler(server, &s5);
#endif
#ifdef LED_GPIO_NUM
    httpd_uri_t s6 = {.uri="/night-vision", .method=HTTP_GET, .handler=nightvision_handler, .user_ctx=NULL};
    httpd_register_uri_handler(server, &s6);
#endif
  }
}

void setup() {
  Serial.begin(115200);
  delay(1500); // USB-CDC on ESP32-S3: wait for host serial
  cameraMux = xSemaphoreCreateMutex();
  i2sMux = xSemaphoreCreateMutex();
#ifdef LED_GPIO_NUM
  pinMode(LED_GPIO_NUM, OUTPUT);
  digitalWrite(LED_GPIO_NUM, LOW); // IR off before camera init
#endif
  WiFi.setSleep(false);
  WiFi.setTxPower(WIFI_POWER_19_5dBm);
  WiFi.mode(WIFI_AP);
  WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1), IPAddress(255, 255, 255, 0));
  const bool apOk = WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL, 0, AP_MAX_CLIENTS);
  delay(100);
  Serial.printf("AP %s %s at %s ch=%d clients=%d\n",
    AP_SSID, apOk ? "OK" : "FAIL", WiFi.softAPIP().toString().c_str(), AP_CHANNEL, AP_MAX_CLIENTS);
#ifdef USE_STA
#if USE_STA
  if (String(STA_SSID).length() > 0) {
    WiFi.begin(STA_SSID, STA_PASS);
    Serial.printf("STA connecting to %s ...\n", STA_SSID);
    int retries = 0;
    while (WiFi.status() != WL_CONNECTED && retries < 30) {
      delay(500);
      Serial.print(".");
      retries++;
    }
    if (WiFi.status() == WL_CONNECTED) {
      Serial.printf("\nSTA connected, IP: %s\n", WiFi.localIP().toString().c_str());
    } else {
      Serial.println("\nSTA connect failed, continuing on AP only");
    }
  }
#endif
#endif

  camera_config_t cfg{};
  cfg.ledc_channel = LEDC_CHANNEL_0; cfg.ledc_timer = LEDC_TIMER_0;
#ifdef PWDN_GPIO_NUM
  cfg.pin_pwdn = PWDN_GPIO_NUM; cfg.pin_reset = RESET_GPIO_NUM;
  cfg.pin_xclk = XCLK_GPIO_NUM; cfg.pin_pclk = PCLK_GPIO_NUM;
  cfg.pin_vsync = VSYNC_GPIO_NUM; cfg.pin_href = HREF_GPIO_NUM;
  cfg.pin_sccb_sda = SIOD_GPIO_NUM; cfg.pin_sccb_scl = SIOC_GPIO_NUM;
  cfg.pin_d0 = Y2_GPIO_NUM; cfg.pin_d1 = Y3_GPIO_NUM; cfg.pin_d2 = Y4_GPIO_NUM; cfg.pin_d3 = Y5_GPIO_NUM;
  cfg.pin_d4 = Y6_GPIO_NUM; cfg.pin_d5 = Y7_GPIO_NUM; cfg.pin_d6 = Y8_GPIO_NUM; cfg.pin_d7 = Y9_GPIO_NUM;
#else
  cfg.pin_d0 = 5; cfg.pin_d1 = 18; cfg.pin_d2 = 19; cfg.pin_d3 = 21;
  cfg.pin_d4 = 36; cfg.pin_d5 = 39; cfg.pin_d6 = 34; cfg.pin_d7 = 35;
  cfg.pin_xclk = 0; cfg.pin_pclk = 22; cfg.pin_vsync = 25; cfg.pin_href = 23;
  cfg.pin_sccb_sda = 26; cfg.pin_sccb_scl = 27; cfg.pin_pwdn = 32; cfg.pin_reset = -1;
#endif
  cfg.xclk_freq_hz = 20000000; cfg.pixel_format = PIXFORMAT_JPEG;
  // VGA 640×480 — best balance of face detail and WiFi throughput on ESP32-S3 AP.
  cfg.frame_size = FRAMESIZE_VGA;
  cfg.jpeg_quality = 12;
  cfg.fb_count = 3;
  cfg.fb_location = CAMERA_FB_IN_PSRAM; cfg.grab_mode = CAMERA_GRAB_LATEST;
  esp_err_t err = esp_camera_init(&cfg);
  if (err != ESP_OK) Serial.printf("Camera init failed %d\n", err);
  else {
    frameWidth = 640;
    frameHeight = 480;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s != nullptr) {
    if (s->id.PID == OV3660_PID) {
      s->set_vflip(s, 1);
    }
  }
#ifdef LED_GPIO_NUM
  // already configured LOW above
#endif
  applyNightVisionHardware();
  warmupCameraExposure();
  startServer();
}

void loop() {
  static uint32_t lastAuto = 0;
  uint32_t now = millis();
  if (now - lastAuto >= 1500) {
    lastAuto = now;
    autoSceneAdjust();
  }
  delay(20);
}
