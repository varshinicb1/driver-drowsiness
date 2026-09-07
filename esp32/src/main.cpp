/**
 * ESP32-S3 Camera Node — V1 (§17)
 * Camera → JPEG → Wi-Fi → MJPEG/snapshot/status. No fatigue AI.
 */
#include <Arduino.h>
#include <WiFi.h>
#include <esp_camera.h>
#include <esp_http_server.h>
#include <driver/i2s.h>

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
#define AP_MAX_CLIENTS 4
#endif

static httpd_handle_t stream_httpd = NULL;
static httpd_handle_t snapshot_httpd = NULL;
static bool nightVisionOn = true; // IR LED on by default — indoor/vehicle use is usually dim

#ifdef SPK_BCLK_GPIO_NUM
// Vehicle-mounted audible alert — I2S STD TX to onboard MAX98357 amp (§ AIS-184 acoustic warning).
static bool spk_ready = false;

static bool ensureSpeaker() {
  if (spk_ready) return true;
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = 16000;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
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
static void playAlertTone() {
  if (!ensureSpeaker()) return;
  const int sampleRate = 16000;
  const int freqs[2] = {900, 1400};
  const int toneMs = 220;
  int16_t buf[128 * 2];
  for (int t = 0; t < 2; t++) {
    int period = sampleRate / freqs[t];
    int samples = (sampleRate * toneMs) / 1000;
    int written = 0;
    while (written < samples) {
      int chunkSamples = min(128, samples - written);
      for (int i = 0; i < chunkSamples; i++) {
        int idx = written + i;
        bool high = (idx % period) < (period / 2);
        int16_t v = high ? 12000 : -12000;
        buf[i * 2] = v; buf[i * 2 + 1] = v;
      }
      size_t bytesWritten = 0;
      i2s_write(I2S_NUM_1, buf, chunkSamples * 2 * sizeof(int16_t), &bytesWritten, portMAX_DELAY);
      written += chunkSamples;
    }
    delay(40);
  }
}

static esp_err_t alert_handler(httpd_req_t *req) {
  playAlertTone();
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
  return ESP_OK;
}
#endif

static esp_err_t status_handler(httpd_req_t *req) {
  char buf[256];
  snprintf(buf, sizeof(buf),
    "{\"uptime\":%lu,\"width\":%d,\"height\":%d,\"fps\":10,\"clients\":%d}",
    millis()/1000, 640, 480, WiFi.softAPgetStationNum());
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, buf, strlen(buf));
}

static esp_err_t snapshot_handler(httpd_req_t *req) {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) { httpd_resp_send_500(req); return ESP_FAIL; }
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return ESP_OK;
}

static esp_err_t stream_handler(httpd_req_t *req) {
  httpd_resp_set_type(req, "multipart/x-mixed-replace;boundary=frame");
  char part_hdr[64];
  while (true) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) continue;
    snprintf(part_hdr, sizeof(part_hdr),
      "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", fb->len);
    if (httpd_resp_send_chunk(req, part_hdr, strlen(part_hdr)) != ESP_OK) { esp_camera_fb_return(fb); break; }
    if (httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len) != ESP_OK) { esp_camera_fb_return(fb); break; }
    if (httpd_resp_send_chunk(req, "\r\n", 2) != ESP_OK) { esp_camera_fb_return(fb); break; }
    esp_camera_fb_return(fb);
    vTaskDelay(100 / portTICK_PERIOD_MS); // ~10 FPS
  }
  return ESP_OK;
}

#ifdef LED_GPIO_NUM
static esp_err_t nightvision_handler(httpd_req_t *req) {
  char query[16];
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK) {
    char val[4];
    if (httpd_query_key_value(query, "on", val, sizeof(val)) == ESP_OK) {
      nightVisionOn = atoi(val) != 0;
      digitalWrite(LED_GPIO_NUM, nightVisionOn ? HIGH : LOW);
    }
  }
  char buf[32];
  snprintf(buf, sizeof(buf), "{\"nightVision\":%s}", nightVisionOn ? "true" : "false");
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, buf, HTTPD_RESP_USE_STRLEN);
}
#endif

void startServer() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  httpd_handle_t server = NULL;
  if (httpd_start(&server, &config) == ESP_OK) {
    httpd_uri_t s1 = {.uri="/status", .method=HTTP_GET, .handler=status_handler, .user_ctx=NULL};
    httpd_uri_t s2 = {.uri="/snapshot", .method=HTTP_GET, .handler=snapshot_handler, .user_ctx=NULL};
    httpd_uri_t s3 = {.uri="/stream", .method=HTTP_GET, .handler=stream_handler, .user_ctx=NULL};
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
  // Wi-Fi — secrets.h optional; defaults to direct demo AP (§18, docs/hardware.md:3)
  WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL, 0, AP_MAX_CLIENTS);
  Serial.printf("AP %s at %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
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
  cfg.frame_size = FRAMESIZE_VGA; // 640x480 (§19)
  cfg.jpeg_quality = 12; // 0-63 lower=better, ~70 quality (spec 60-75 JPEG ≈ 12)
  cfg.fb_count = 2; cfg.fb_location = CAMERA_FB_IN_PSRAM; cfg.grab_mode = CAMERA_GRAB_LATEST;
  esp_err_t err = esp_camera_init(&cfg);
  if (err != ESP_OK) Serial.printf("Camera init failed %d\n", err);

  sensor_t *s = esp_camera_sensor_get();
  if (s != nullptr) {
    if (s->id.PID == OV3660_PID) {
      s->set_vflip(s, 1);
      s->set_brightness(s, 1);
      s->set_saturation(s, -2);
    }
    // Low-light tuning: cap AGC gain now that the IR LED below supplies real illumination —
    // relying on high sensor gain alone in the dark is what produces the grainy/noisy image.
    s->set_gain_ctrl(s, 1);
    s->set_gainceiling(s, GAINCEILING_4X);
    s->set_exposure_ctrl(s, 1);
    s->set_aec2(s, 1);
  }
#ifdef LED_GPIO_NUM
  pinMode(LED_GPIO_NUM, OUTPUT);
  digitalWrite(LED_GPIO_NUM, nightVisionOn ? HIGH : LOW);
#endif
  startServer();
}

void loop() { delay(1000); }
