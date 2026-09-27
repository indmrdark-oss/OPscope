// OPscope ESP32 firmware
// Serial protocol (250000 baud, matches the Android app):
//   TX->ESP32   F<hz>      set target frequency, e.g. F1000.00   (clamped 1-20000 Hz)
//   TX->ESP32   C          request one real ADC capture
//   TX->ESP32   H          reset/clear fault
//   ESP32->TX   CAP,<n>,<rate>,REAL   capture header
//   ESP32->TX   <csv ints> ... ENDCAP
//   ESP32->TX   Target: X.XX Measured: Y.YY Duty: Z.ZZ Err: W.WW   (periodic, or "NO SIGNAL")
//   ESP32->TX   AI> <message>   status/fault text

#include <driver/ledc.h>

// ---- pin map (change to match your wiring) ----
const int PIN_PWM_OUT   = 25;  // signal output (drive gate driver / inverter from here)
const int PIN_FREQ_IN   = 26;  // loop this back from PIN_PWM_OUT (or from the real output stage)
const int PIN_ADC       = 34;  // ADC1 channel, samples the real output/inverter waveform
const int PIN_FAULT_IN  = 27;  // from external protection comparator: HIGH = reverse/overvoltage

const double F_MIN = 1.0;
const double F_MAX = 20000.0;

const ledc_timer_t LEDC_TIMER   = LEDC_TIMER_0;
const ledc_channel_t LEDC_CHAN  = LEDC_CHANNEL_0;
const ledc_mode_t LEDC_MODE     = LEDC_LOW_SPEED_MODE;
const int PWM_RES_BITS = 10;              // 0-1023 duty steps
const int PWM_DUTY     = 512;             // ~50% duty

double targetFreq = 1000.0;
volatile unsigned long lastEdgeMicros = 0;
volatile unsigned long periodMicros   = 0;
volatile bool edgeSeen = false;

volatile bool faultLatched = false;

unsigned long lastStatusMs = 0;
const unsigned long STATUS_INTERVAL_MS = 300;

void IRAM_ATTR onFreqEdge() {
  unsigned long now = micros();
  unsigned long delta = now - lastEdgeMicros;
  if (delta > 20) {
    periodMicros = delta;
    edgeSeen = true;
  }
  lastEdgeMicros = now;
}

void IRAM_ATTR onFault() {
  faultLatched = true;
  ledc_set_duty(LEDC_MODE, LEDC_CHAN, 0);
  ledc_update_duty(LEDC_MODE, LEDC_CHAN);
}

void setPwmFrequency(double hz) {
  hz = constrain(hz, F_MIN, F_MAX);
  targetFreq = hz;
  ledc_set_freq(LEDC_MODE, LEDC_TIMER, (uint32_t)hz);
  if (!faultLatched) {
    ledc_set_duty(LEDC_MODE, LEDC_CHAN, PWM_DUTY);
    ledc_update_duty(LEDC_MODE, LEDC_CHAN);
  }
}

void doCapture() {
  const int N = 200;
  int samples[N];
  unsigned long t0 = micros();
  for (int i = 0; i < N; i++) {
    samples[i] = analogRead(PIN_ADC);
  }
  unsigned long elapsed = micros() - t0;
  double rate = (elapsed > 0) ? (1000000.0 * N / elapsed) : 0.0;

  Serial.print("CAP,");
  Serial.print(N);
  Serial.print(",");
  Serial.print(rate, 1);
  Serial.println(",REAL");

  const int CHUNK = 40;
  for (int i = 0; i < N; i += CHUNK) {
    String line = "";
    for (int j = i; j < min(i + CHUNK, N); j++) {
      if (j > i) line += ",";
      line += String(samples[j]);
    }
    Serial.println(line);
  }
  Serial.println("ENDCAP");
}

void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  if (cmd[0] == 'F' || cmd[0] == 'f') {
    double hz = cmd.substring(1).toDouble();
    if (hz > 0) {
      setPwmFrequency(hz);
    }
  } else if (cmd == "C") {
    doCapture();
  } else if (cmd == "H") {
    faultLatched = false;
    setPwmFrequency(targetFreq);
    Serial.println("AI> fault cleared, output re-enabled");
  } else if (cmd == "M") {
    Serial.print("AI> measured ");
    Serial.print(periodMicros > 0 ? (1000000.0 / periodMicros) : 0.0, 2);
    Serial.println(" Hz");
  } else if (cmd == "D") {
    Serial.println("AI> duty fixed at 50%");
  } else if (cmd == "S") {
    Serial.println("AI> status requested");
  } else {
    Serial.print("AI> unknown command: ");
    Serial.println(cmd);
  }
}

void setup() {
  Serial.begin(250000);

  pinMode(PIN_FREQ_IN, INPUT);
  pinMode(PIN_FAULT_IN, INPUT);
  attachInterrupt(digitalPinToInterrupt(PIN_FREQ_IN), onFreqEdge, RISING);
  attachInterrupt(digitalPinToInterrupt(PIN_FAULT_IN), onFault, RISING);

  analogReadResolution(12);

  ledc_timer_config_t timerCfg = {
    .speed_mode       = LEDC_MODE,
    .duty_resolution  = (ledc_timer_bit_t)PWM_RES_BITS,
    .timer_num        = LEDC_TIMER,
    .freq_hz          = (uint32_t)targetFreq,
    .clk_cfg          = LEDC_AUTO_CLK
  };
  ledc_timer_config(&timerCfg);

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
  Serial.println("AI> OPscope ESP32 ready. 1-20000 Hz, USB only.");
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

  unsigned long now = millis();
  if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
    lastStatusMs = now;

    if (faultLatched) {
      Serial.println("AI> FAULT: reverse/overvoltage detected, output disabled. Send H to reset.");
      return;
    }

    noInterrupts();
    unsigned long p = periodMicros;
    bool seen = edgeSeen;
    edgeSeen = false;
    interrupts();

    if (seen && p > 0) {
      double measured = 1000000.0 / (double)p;
      double err = fabs(measured - targetFreq) / targetFreq * 100.0;
      Serial.print("Target: ");
      Serial.print(targetFreq, 2);
      Serial.print(" Measured: ");
      Serial.print(measured, 2);
      Serial.print(" Duty: 50.00 Err: ");
      Serial.println(err, 2);
    } else {
      Serial.print("Target: ");
      Serial.print(targetFreq, 2);
      Serial.println(" Measured: NO SIGNAL");
    }
  }
}
