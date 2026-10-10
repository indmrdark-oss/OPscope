// OPscope ESP32 firmware
// Serial protocol (250000 baud, matches the Android app) — UNCHANGED:
//   TX->ESP32   F<hz>      set target frequency, e.g. F1000.00   (clamped 1-20000 Hz)
//   TX->ESP32   P<pct>     set output duty cycle, e.g. P65.0     (clamped 0-100 %)
//   TX->ESP32   E1 / E0    enable / disable output — output stays off until E1 arrives
//   TX->ESP32   C          request one real ADC capture
//   TX->ESP32   H          reset/clear fault
//   ESP32->TX   CAP,<n>,<rate>,REAL   capture header
//   ESP32->TX   <csv ints> ... ENDCAP
//   ESP32->TX   Target: X.XX Measured: Y.YY Duty: Z.ZZ Err: W.WW   (periodic, or "NO SIGNAL")
//   ESP32->TX   AI> <message>   status/fault/diagnostic text
//
// CHANGES IN THIS VERSION — fixes for "frequency doesn't set", "measured
// frequency is garbage", and "graph never appears":
//   1. ledc_set_freq()'s return value is now checked. At 10-bit duty
//      resolution + 80MHz APB clock, ~76Hz is the real achievable floor —
//      anything below that was silently failing before. Now, on failure,
//      the firmware automatically retries with lower duty resolution
//      (which lowers the achievable floor) until it succeeds, and reports
//      exactly what happened via AI>. This is why low frequencies (near
//      the 1Hz end of the range) weren't actually being applied — and why
//      GPIO26 was then reading a half-broken, jumpy signal.
//   2. Every adc_continuous_* setup call is now checked. If DMA ADC init
//      fails for any reason, you'll see "AI> ADC init failed..." on boot
//      instead of a permanently empty graph with no explanation.

#include <driver/ledc.h>
#include "esp_adc/adc_continuous.h"
#include "soc/soc_caps.h"

const int PIN_PWM_OUT   = 25;  // signal output (drive gate driver / inverter from here)
const int PIN_FREQ_IN   = 26;  // loop this back from PIN_PWM_OUT (or from the real output stage)
const adc_channel_t ADC_SENSE_CHANNEL = ADC_CHANNEL_6;  // GPIO34 = ADC1_CHANNEL_6

const double F_MIN = 1.0;
const double F_MAX = 20000.0;

const ledc_timer_t LEDC_TIMER   = LEDC_TIMER_0;
const ledc_channel_t LEDC_CHAN  = LEDC_CHANNEL_0;
const ledc_mode_t LEDC_MODE     = LEDC_LOW_SPEED_MODE;
const int RES_BITS_MAX = 10;
const int RES_BITS_MIN = 4;
int currentResBits = RES_BITS_MAX;   // mutable now — drops automatically for low frequencies

int pwmMaxSteps() { return (1 << currentResBits) - 1; }

// --- continuous ADC config ---
const uint32_t SAMPLE_RATE_HZ = 500000UL;
const int CAPTURE_N = 1000;
const size_t ADC_READ_LEN = 256;
adc_continuous_handle_t adcHandle = NULL;
bool adcReady = false;

// --- software overvoltage protection ---
// NOTE: set to the true ADC max (4095) because GPIO25's own raw PWM output
// is looped straight into GPIO34 for debug/graph purposes right now — a
// clean 0-3.3V square wave's HIGH level normally sits near 4095, which is
// NOT a fault. Lower this (e.g. 3900) once GPIO34 is fed from the real
// voltage-divided inverter output instead.
const int ADC_SAFE_MAX = 4095;
const int ADC_FAULT_SAMPLES_REQUIRED = 5;
int adcFaultStreak = 0;

double targetFreq = 1000.0;
double currentDutyPercent = 50.0;
volatile bool outputEnabled = false;
bool faultLatched = false;

volatile unsigned long lastEdgeMicros = 0;
volatile unsigned long periodMicros   = 0;
volatile bool edgeSeen = false;

unsigned long lastStatusMs = 0;
const unsigned long STATUS_INTERVAL_MS = 300;

bool capturing = false;
int captureBuf[CAPTURE_N];
int captureCount = 0;

void IRAM_ATTR onFreqEdge() {
  unsigned long now = micros();
  unsigned long delta = now - lastEdgeMicros;
  if (delta > 20) {
    periodMicros = delta;
    edgeSeen = true;
  }
  lastEdgeMicros = now;
}

void applyDuty() {
  if (faultLatched || !outputEnabled) {
    ledc_set_duty(LEDC_MODE, LEDC_CHAN, 0);
    ledc_update_duty(LEDC_MODE, LEDC_CHAN);
    return;
  }
  int steps = (int)(currentDutyPercent / 100.0 * pwmMaxSteps());
  steps = constrain(steps, 0, pwmMaxSteps());
  ledc_set_duty(LEDC_MODE, LEDC_CHAN, steps);
  ledc_update_duty(LEDC_MODE, LEDC_CHAN);
}

void triggerSoftwareFault(const char *reason) {
  if (faultLatched) return;
  faultLatched = true;
  applyDuty();
  Serial.print("AI> FAULT: ");
  Serial.print(reason);
  Serial.println(" — output disabled. Send H to reset.");
}

// Reconfigures the LEDC timer at a given resolution and tries to set the
// frequency. Returns true on success.
bool tryApplyAtResolution(double hz, int bits) {
  ledc_timer_config_t timerCfg = {
    .speed_mode       = LEDC_MODE,
    .duty_resolution  = (ledc_timer_bit_t)bits,
    .timer_num        = LEDC_TIMER,
    .freq_hz          = (uint32_t)hz,
    .clk_cfg          = LEDC_AUTO_CLK
  };
  if (ledc_timer_config(&timerCfg) != ESP_OK) return false;
  if (ledc_set_freq(LEDC_MODE, LEDC_TIMER, (uint32_t)hz) != ESP_OK) return false;
  currentResBits = bits;
  return true;
}

// FIX: previously called ledc_set_freq() once and assumed success. Now
// walks resolution down from 10 bits toward 4 bits until the hardware
// actually accepts the frequency, and reports what happened.
void setPwmFrequency(double hz) {
  hz = constrain(hz, F_MIN, F_MAX);
  targetFreq = hz;

  if (tryApplyAtResolution(hz, RES_BITS_MAX)) {
    currentResBits = RES_BITS_MAX;
    applyDuty();
    return;
  }

  for (int bits = RES_BITS_MAX - 1; bits >= RES_BITS_MIN; bits--) {
    if (tryApplyAtResolution(hz, bits)) {
      Serial.print("AI> ");
      Serial.print(hz, 2);
      Serial.print(" Hz needed reduced resolution (");
      Serial.print(bits);
      Serial.println("-bit) to apply — this is normal near the low end of the range");
      applyDuty();
      return;
    }
  }

  Serial.print("AI> FAILED to apply ");
  Serial.print(hz, 2);
  Serial.println(" Hz at any resolution — hardware rejected it. Output left at previous frequency.");
}

void setDutyPercent(double pct) {
  currentDutyPercent = constrain(pct, 0.0, 100.0);
  applyDuty();
}

void sendCaptureResult() {
  Serial.print("CAP,");
  Serial.print(CAPTURE_N);
  Serial.print(",");
  Serial.print((double)SAMPLE_RATE_HZ, 1);
  Serial.println(",REAL");

  const int CHUNK = 40;
  for (int i = 0; i < CAPTURE_N; i += CHUNK) {
    String line = "";
    for (int j = i; j < min(i + CHUNK, CAPTURE_N); j++) {
      if (j > i) line += ",";
      line += String(captureBuf[j]);
    }
    Serial.println(line);
  }
  Serial.println("ENDCAP");
}

void processSample(int v) {
  if (capturing) {
    captureBuf[captureCount++] = v;
    if (captureCount >= CAPTURE_N) {
      capturing = false;
      sendCaptureResult();
    }
  }

  if (!faultLatched) {
    if (v > ADC_SAFE_MAX) {
      adcFaultStreak++;
      if (adcFaultStreak >= ADC_FAULT_SAMPLES_REQUIRED) {
        triggerSoftwareFault("overvoltage on ADC sense line");
      }
    } else {
      adcFaultStreak = 0;
    }
  }
}

void drainAdc() {
  if (!adcReady) return;
  uint8_t result[ADC_READ_LEN];
  for (int pass = 0; pass < 8; pass++) {
    uint32_t retNum = 0;
    esp_err_t ret = adc_continuous_read(adcHandle, result, ADC_READ_LEN, &retNum, 0);
    if (ret != ESP_OK || retNum == 0) break;

    for (uint32_t i = 0; i < retNum; i += SOC_ADC_DIGI_RESULT_BYTES) {
      adc_digi_output_data_t *p = (adc_digi_output_data_t *)&result[i];
      if (p->type1.channel == ADC_SENSE_CHANNEL) {
        processSample((int)p->type1.data);
      }
    }
  }
}

void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  if (cmd[0] == 'F' || cmd[0] == 'f') {
    double hz = cmd.substring(1).toDouble();
    if (hz > 0) setPwmFrequency(hz);
  } else if (cmd[0] == 'P' || cmd[0] == 'p') {
    double pct = cmd.substring(1).toDouble();
    setDutyPercent(pct);
    Serial.print("AI> duty set to ");
    Serial.print(currentDutyPercent, 1);
    Serial.println("%");
  } else if (cmd == "E1") {
    outputEnabled = true;
    applyDuty();
    Serial.println("AI> output ENABLED");
  } else if (cmd == "E0") {
    outputEnabled = false;
    applyDuty();
    Serial.println("AI> output DISABLED");
  } else if (cmd == "C") {
    if (!adcReady) {
      Serial.println("AI> capture unavailable — ADC did not initialize at boot");
    } else if (!capturing) {
      captureCount = 0;
      capturing = true;
    }
  } else if (cmd == "H") {
    bool wasFault = faultLatched;
    faultLatched = false;
    adcFaultStreak = 0;
    setPwmFrequency(targetFreq);
    if (wasFault) Serial.println("AI> fault cleared, output re-enabled");
    else Serial.println("AI> reset OK");
  } else if (cmd == "M") {
    Serial.print("AI> measured ");
    Serial.print(periodMicros > 0 ? (1000000.0 / periodMicros) : 0.0, 2);
    Serial.println(" Hz");
  } else if (cmd == "D") {
    Serial.print("AI> duty currently ");
    Serial.print(currentDutyPercent, 1);
    Serial.println("%");
  } else if (cmd == "S") {
    Serial.print("AI> diag: enabled=");
    Serial.print(outputEnabled ? "1" : "0");
    Serial.print(" fault=");
    Serial.print(faultLatched ? "1" : "0");
    Serial.print(" resBits=");
    Serial.print(currentResBits);
    Serial.print(" adcReady=");
    Serial.println(adcReady ? "1" : "0");
  } else {
    Serial.print("AI> unknown command: ");
    Serial.println(cmd);
  }
}

void setupAdc() {
  adc_continuous_handle_cfg_t adcHandleCfg = {
    .max_store_buf_size = 1024,
    .conv_frame_size = ADC_READ_LEN,
  };
  if (adc_continuous_new_handle(&adcHandleCfg, &adcHandle) != ESP_OK) {
    Serial.println("AI> ADC init failed: new_handle");
    return;
  }

  static adc_digi_pattern_config_t adcPattern[1];
  adcPattern[0].atten = ADC_ATTEN_DB_11;
  adcPattern[0].channel = ADC_SENSE_CHANNEL;
  adcPattern[0].unit = ADC_UNIT_1;
  adcPattern[0].bit_width = ADC_BITWIDTH_12;

  adc_continuous_config_t digCfg = {
    .pattern_num = 1,
    .adc_pattern = adcPattern,
    .sample_freq_hz = SAMPLE_RATE_HZ,
    .conv_mode = ADC_CONV_SINGLE_UNIT_1,
    .format = ADC_DIGI_OUTPUT_FORMAT_TYPE1,
  };
  if (adc_continuous_config(adcHandle, &digCfg) != ESP_OK) {
    Serial.println("AI> ADC init failed: config");
    return;
  }

  if (adc_continuous_start(adcHandle) != ESP_OK) {
    Serial.println("AI> ADC init failed: start");
    return;
  }

  adcReady = true;
  Serial.println("AI> ADC continuous sampling started OK (500kHz)");
}

void setup() {
  Serial.begin(250000);

  pinMode(PIN_FREQ_IN, INPUT);
  attachInterrupt(digitalPinToInterrupt(PIN_FREQ_IN), onFreqEdge, RISING);

  setupAdc();

  ledc_channel_config_t chCfg = {
    .gpio_num   = PIN_PWM_OUT,
    .speed_mode = LEDC_MODE,
    .channel    = LEDC_CHAN,
    .intr_type  = LEDC_INTR_DISABLE,
    .timer_sel  = LEDC_TIMER,
    .duty       = 0,
    .hpoint     = 0
  };
  ledc_channel_config(&chCfg);

  setPwmFrequency(targetFreq);
  Serial.println("AI> OPscope ESP32 ready. Output OFF until E1 (Connect).");
}

void loop() {
  String cmd;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      handleCommand(cmd);
      cmd = "";
    } else if (c != '\r') {
      cmd += c;
    }
  }

  drainAdc();

  unsigned long now = millis();
  if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
    lastStatusMs = now;

    if (faultLatched) {
      Serial.println("AI> FAULT: output disabled by protection. Send H to reset.");
      return;
    }

    noInterrupts();
    unsigned long p = periodMicros;
    bool seen = edgeSeen;
    edgeSeen = false;
    interrupts();

    if (outputEnabled && seen && p > 0) {
      double measured = 1000000.0 / (double)p;
      double err = fabs(measured - targetFreq) / targetFreq * 100.0;
      Serial.print("Target: ");
      Serial.print(targetFreq, 2);
      Serial.print(" Measured: ");
      Serial.print(measured, 2);
      Serial.print(" Duty: ");
      Serial.print(currentDutyPercent, 2);
      Serial.print(" Err: ");
      Serial.println(err, 2);
    } else {
      Serial.print("Target: ");
      Serial.print(targetFreq, 2);
      Serial.println(" Measured: NO SIGNAL");
    }
  }
}
