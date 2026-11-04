// =====================================================================
//  ColdPull Station — стенд для прогріву та чистки хотендів (cold pull)
//
//  Arduino Nano (ATmega328P) + OLED 128x64 (I2C) + енкодер з кнопкою +
//  MOSFET нагрівача + MOSFET вентилятора + термістор/PT1000 хотенда.
//
//  Керування:
//    обертання     — змінити температуру / пункт меню
//    клік          — увімкнути / вимкнути нагрів, підтвердити
//    утримання 1с  — меню (Cold pull, Автотюн PID, ...), або вихід
//
//  Бібліотека: U8g2 (olikraus). Налаштування заліза — у config.h.
// =====================================================================
#include <Arduino.h>
#include <Wire.h>
#include <EEPROM.h>
#include <avr/wdt.h>
#include <U8g2lib.h>
#include "config.h"

// ---------------------------------------------------------------------
//  Дисплей (сторінковий буфер, щоб зекономити RAM)
// ---------------------------------------------------------------------
#if DISPLAY_TYPE == DISPLAY_SH1106
U8G2_SH1106_128X64_NONAME_1_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);
#else
U8G2_SSD1306_128X64_NONAME_1_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);
#endif

#define FONT_TEXT u8g2_font_6x12_t_cyrillic
#define FONT_BIG  u8g2_font_logisoso24_tn

// ---------------------------------------------------------------------
//  Налаштування в EEPROM
// ---------------------------------------------------------------------
struct Settings {
  uint16_t magic;
  float kp, ki, kd;
  int16_t setTemp;
  int16_t flushTemp;
  int16_t pullTemp;
  uint8_t checksum;
};

static const uint16_t SETTINGS_MAGIC = 0xC01D + 2;  // змінюйте при зміні структури
Settings cfg;

uint8_t settingsChecksum(const Settings &s) {
  const uint8_t *p = (const uint8_t *)&s;
  uint8_t sum = 0x5A;
  for (uint8_t i = 0; i < sizeof(Settings) - 1; i++) sum = (sum << 1 | sum >> 7) ^ p[i];
  return sum;
}

void settingsDefaults() {
  cfg.magic = SETTINGS_MAGIC;
  cfg.kp = PID_KP_DEFAULT;
  cfg.ki = PID_KI_DEFAULT;
  cfg.kd = PID_KD_DEFAULT;
  cfg.setTemp = 240;
  cfg.flushTemp = CP_FLUSH_TEMP;
  cfg.pullTemp = CP_PULL_TEMP;
}

void settingsLoad() {
  EEPROM.get(0, cfg);
  if (cfg.magic != SETTINGS_MAGIC || cfg.checksum != settingsChecksum(cfg) ||
      isnan(cfg.kp) || isnan(cfg.ki) || isnan(cfg.kd)) {
    settingsDefaults();
  }
  cfg.setTemp   = constrain(cfg.setTemp, TEMP_MIN_SET, TEMP_MAX_SET);
  cfg.flushTemp = constrain(cfg.flushTemp, TEMP_MIN_SET, TEMP_MAX_SET);
  cfg.pullTemp  = constrain(cfg.pullTemp, TEMP_MIN_SET, TEMP_MAX_SET);
}

bool settingsDirty = false;
uint32_t settingsDirtyAt = 0;

void settingsSave() {
  cfg.checksum = settingsChecksum(cfg);
  EEPROM.put(0, cfg);  // put() пише лише змінені байти
  settingsDirty = false;
}

void settingsTouch() {  // відкладений запис, щоб не зношувати EEPROM
  settingsDirty = true;
  settingsDirtyAt = millis();
}

// ---------------------------------------------------------------------
//  Енкодер (переривання, табличне декодування квадратури)
// ---------------------------------------------------------------------
volatile int16_t encRaw = 0;
volatile uint8_t encState = 0;
static const int8_t ENC_TABLE[16] = {0, -1, 1, 0, 1, 0, 0, -1, -1, 0, 0, 1, 0, 1, -1, 0};

void encoderISR() {
  encState = ((encState << 2) | (digitalRead(PIN_ENC_A) << 1) | digitalRead(PIN_ENC_B)) & 0x0F;
  encRaw += ENC_TABLE[encState];
}

int8_t encoderRead() {
  int8_t steps = 0;
  noInterrupts();
  int16_t r = encRaw;
  if (r >= ENC_STEPS_PER_NOTCH || r <= -ENC_STEPS_PER_NOTCH) {
    steps = r / ENC_STEPS_PER_NOTCH;
    encRaw = r - steps * ENC_STEPS_PER_NOTCH;
  }
  interrupts();
#if ENC_REVERSE
  steps = -steps;
#endif
  return steps;
}

// ---------------------------------------------------------------------
//  Кнопка: короткий клік (на відпускання) і утримання (>= 800 мс)
// ---------------------------------------------------------------------
enum BtnEvent : uint8_t { BTN_NONE, BTN_CLICK, BTN_LONG };

BtnEvent buttonRead() {
  static bool stable = false, lastRaw = false, longFired = false;
  static uint32_t changedAt = 0, pressedAt = 0;
  bool raw = digitalRead(PIN_ENC_BTN) == LOW;
  uint32_t now = millis();
  if (raw != lastRaw) { lastRaw = raw; changedAt = now; }
  if (now - changedAt < 25) return BTN_NONE;  // антидребезг
  if (raw != stable) {
    stable = raw;
    if (stable) { pressedAt = now; longFired = false; return BTN_NONE; }
    if (!longFired) return BTN_CLICK;
    return BTN_NONE;
  }
  if (stable && !longFired && now - pressedAt >= 800) { longFired = true; return BTN_LONG; }
  return BTN_NONE;
}

// ---------------------------------------------------------------------
//  Бузер (неблокуючі серії сигналів)
// ---------------------------------------------------------------------
uint8_t beepsLeft = 0;
uint16_t beepLen = 0;
uint32_t beepNextAt = 0;

void beep(uint8_t count, uint16_t len = 120) {
  beepsLeft = count;
  beepLen = len;
  beepNextAt = millis();
}

void beeperUpdate() {
  if (beepsLeft && (int32_t)(millis() - beepNextAt) >= 0) {
    tone(PIN_BUZZER, 2700, beepLen);
    beepsLeft--;
    beepNextAt = millis() + beepLen + 110;
  }
}

// ---------------------------------------------------------------------
//  Температура
// ---------------------------------------------------------------------
enum ErrCode : uint8_t { ERR_NONE, ERR_SENSOR_OPEN, ERR_SENSOR_SHORT, ERR_OVERTEMP,
                         ERR_HEAT_FAIL, ERR_TEMP_DROP, ERR_RUNAWAY_OFF, ERR_TUNE };

float tempC = 25.0f;
bool tempValid = false;
ErrCode sensorErr = ERR_NONE;

float adcToCelsius(float adc) {
  float r = PULLUP_OHMS * adc / (1023.0f - adc);
#if SENSOR_TYPE == SENSOR_PT1000
  // Каллендар — Ван Дюзен, t >= 0 °C
  const float A = 3.9083e-3f, B = -5.775e-7f;
  float t = (-A + sqrt(A * A - 4.0f * B * (1.0f - r / PT_R0))) / (2.0f * B);
#else
  float invT = 1.0f / 298.15f + log(r / NTC_R25) / NTC_BETA;
  float t = 1.0f / invT - 273.15f;
#endif
  return t + TEMP_OFFSET;
}

void sensorUpdate() {
  uint16_t sum = 0;
  for (uint8_t i = 0; i < 16; i++) sum += analogRead(PIN_TEMP);
  float adc = sum / 16.0f;

#if SENSOR_TYPE == SENSOR_PT1000
  // PT1000 із підтяжкою: малий ADC = малий опір (замикання), великий — обрив
  if (adc > 1015.0f) { sensorErr = ERR_SENSOR_OPEN; tempValid = false; return; }
  if (adc < 40.0f)   { sensorErr = ERR_SENSOR_SHORT; tempValid = false; return; }
#else
  if (adc > 1018.0f) { sensorErr = ERR_SENSOR_OPEN; tempValid = false; return; }
  if (adc < 2.0f)    { sensorErr = ERR_SENSOR_SHORT; tempValid = false; return; }
#endif
  float t = adcToCelsius(adc);
  if (t < TEMP_SENSOR_MIN) { sensorErr = ERR_SENSOR_OPEN; tempValid = false; return; }
  sensorErr = ERR_NONE;
  tempC = tempValid ? tempC + 0.35f * (t - tempC) : t;
  tempValid = true;
}

// ---------------------------------------------------------------------
//  Стан програми
// ---------------------------------------------------------------------
enum Mode : uint8_t {
  M_IDLE, M_HEAT, M_MENU,
  M_CP_FLUSH, M_CP_COOL, M_CP_PULL, M_CP_AGAIN,
  M_TUNE_SET, M_TUNE, M_TUNE_DONE,
  M_ERROR
};

Mode mode = M_IDLE;
ErrCode errCode = ERR_NONE;
uint8_t menuPos = 0;
int16_t tuneTemp = 240;
bool autoOffShown = false;
uint32_t lastActivity = 0;

int16_t target = 0;      // 0 = нагрівач вимкнено
uint8_t heaterPwm = 0;
bool fanOn = false;
bool fanForced = false;
bool reached = false;    // ціль досягнута (для захисту і підказок)
bool stepBeeped = false; // сигнал "досягнуто" вже подано

// ---------------------------------------------------------------------
//  PID
// ---------------------------------------------------------------------
float pidI = 0, pidLastT = 0, pidDFilt = 0;
static const float CONTROL_DT = 0.25f;  // с
static const uint16_t CONTROL_MS = 250;

void pidReset() { pidI = 0; pidLastT = tempC; pidDFilt = 0; }

uint8_t pidCompute(float t, float sp) {
  float err = sp - t;
  float d = (t - pidLastT) / CONTROL_DT;
  pidLastT = t;
  pidDFilt += 0.3f * (d - pidDFilt);

  // Далеко від цілі — повна потужність/вимкнено, без накопичення інтегралу
  if (err > 25.0f)  { pidI = 0; return HEATER_MAX_PWM; }
  if (err < -15.0f) { pidI = 0; return 0; }

  float p = cfg.kp * err;
  float newI = pidI + cfg.ki * err * CONTROL_DT;
  float out = p + newI - cfg.kd * pidDFilt;
  // антинасичення: не інтегруємо, якщо вихід уже впирається в межу
  if (!((out > HEATER_MAX_PWM && err > 0) || (out < 0 && err < 0))) pidI = newI;
  pidI = constrain(pidI, 0.0f, (float)HEATER_MAX_PWM);
  out = p + pidI - cfg.kd * pidDFilt;
  return (uint8_t)constrain(out, 0.0f, (float)HEATER_MAX_PWM);
}

// ---------------------------------------------------------------------
//  Автотюн (релейний метод з корекцією зміщення, як у Marlin)
// ---------------------------------------------------------------------
struct {
  bool heating;
  uint8_t cycles;
  int16_t bias, d;
  uint32_t t1, t2, tHigh, tLow, startedAt;
  float maxT, minT;
  float kuSum, tuSum;
  uint8_t samples;
} tune;

void tuneStart() {
  tune.heating = true;
  tune.cycles = 0;
  tune.bias = tune.d = HEATER_MAX_PWM / 2;
  tune.t1 = tune.t2 = tune.startedAt = millis();
  tune.tHigh = tune.tLow = 0;
  tune.maxT = -1000; tune.minT = 1000;
  tune.kuSum = tune.tuSum = 0;
  tune.samples = 0;
}

// повертає PWM; по завершенні виставляє mode = M_TUNE_DONE
uint8_t tuneStep(float t) {
  uint32_t now = millis();
  tune.maxT = max(tune.maxT, t);
  tune.minT = min(tune.minT, t);

  if (tune.heating && t > tuneTemp) {
    tune.heating = false;
    tune.t2 = now;
    tune.tHigh = tune.t2 - tune.t1;
    tune.maxT = t;
  } else if (!tune.heating && t < tuneTemp) {
    tune.heating = true;
    tune.t1 = now;
    tune.tLow = tune.t1 - tune.t2;
    if (tune.cycles > 0) {
      int32_t span = (int32_t)tune.tLow + (int32_t)tune.tHigh;
      tune.bias += (int32_t)tune.d * ((int32_t)tune.tHigh - (int32_t)tune.tLow) / max(span, (int32_t)1);
      tune.bias = constrain(tune.bias, 20, HEATER_MAX_PWM - 20);
      tune.d = (tune.bias > HEATER_MAX_PWM / 2) ? HEATER_MAX_PWM - 1 - tune.bias : tune.bias;
      if (tune.cycles > 2) {  // перші цикли пропускаємо — система ще не усталилась
        float a = (tune.maxT - tune.minT) / 2.0f;
        float ku = (4.0f * tune.d) / (PI * max(a, 0.5f));
        float tu = span / 1000.0f;
        tune.kuSum += ku;
        tune.tuSum += tu;
        tune.samples++;
      }
    }
    tune.cycles++;
    tune.minT = t;
  }

  if (tune.cycles > AUTOTUNE_CYCLES + 2 && tune.samples > 0) {
    float ku = tune.kuSum / tune.samples;
    float tu = tune.tuSum / tune.samples;
    cfg.kp = 0.6f * ku;
    cfg.ki = 2.0f * cfg.kp / tu;
    cfg.kd = cfg.kp * tu / 8.0f;
    settingsSave();
    mode = M_TUNE_DONE;
    beep(3);
    return 0;
  }
  return tune.heating ? tune.bias + tune.d : tune.bias - tune.d;
}

uint8_t tuneProgress() {
  return tune.cycles > 2 ? min(tune.cycles - 2, AUTOTUNE_CYCLES) : 0;
}

// ---------------------------------------------------------------------
//  Безпека
// ---------------------------------------------------------------------
uint32_t heatWindowAt = 0;
float heatWindowT = 0;
uint32_t belowSince = 0;
uint32_t offWindowAt = 0;
float offWindowT = 0;

void heaterOff() {
  target = 0;
  heaterPwm = 0;
  analogWrite(PIN_HEATER, 0);
  digitalWrite(PIN_HEATER, LOW);
}

void enterError(ErrCode e) {
  heaterOff();
  errCode = e;
  mode = M_ERROR;
  beep(5, 200);
}

void setTarget(int16_t t) {
  t = constrain(t, 0, TEMP_MAX_SET);
  if (target == 0 && t > 0) pidReset();
  target = t;
  reached = t > 0 && tempC >= t - 2;
  stepBeeped = false;
  heatWindowAt = millis();
  heatWindowT = tempC;
  belowSince = 0;
}

void safetyCheck() {
  if (mode == M_ERROR) return;  // вже в аварії, нагрів вимкнено
  uint32_t now = millis();
  if (sensorErr != ERR_NONE) { enterError(sensorErr); return; }
  if (tempC > TEMP_HARD_LIMIT) { enterError(ERR_OVERTEMP); return; }

  bool heaterActive = (target > 0) || mode == M_TUNE;

  if (heaterActive && mode != M_TUNE) {
    if (!reached && tempC >= target - 2) { reached = true; belowSince = 0; }

    // 1) нагрівається, але температура не росте
    if (!reached && tempC < target - 15) {
      if (now - heatWindowAt >= (uint32_t)RUNAWAY_HEAT_SEC * 1000UL) {
        if (tempC - heatWindowT < RUNAWAY_HEAT_RISE) { enterError(ERR_HEAT_FAIL); return; }
        heatWindowAt = now;
        heatWindowT = tempC;
      }
    } else {
      heatWindowAt = now;
      heatWindowT = tempC;
    }

    // 2) ціль була досягнута, але температура сильно впала і не повертається
    if (reached && tempC < target - RUNAWAY_HOLD_BAND) {
      if (!belowSince) belowSince = now;
      else if (now - belowSince >= (uint32_t)RUNAWAY_HOLD_SEC * 1000UL) { enterError(ERR_TEMP_DROP); return; }
    } else {
      belowSince = 0;
    }
  }

  // 3) нагрівач вимкнено, а температура стрімко росте — пробитий MOSFET
  if (!heaterActive) {
    if (now - offWindowAt >= 20000UL) {
      if (offWindowAt && tempC > 60 && tempC - offWindowT > 15) { enterError(ERR_RUNAWAY_OFF); return; }
      offWindowAt = now;
      offWindowT = tempC;
    }
  } else {
    offWindowAt = 0;
  }

  // 4) автотюн не може тривати вічно
  if (mode == M_TUNE) {
    if (now - tune.startedAt > 20UL * 60UL * 1000UL || tempC > tuneTemp + 40) { enterError(ERR_TUNE); return; }
  }

  // 5) автовимкнення без дій користувача
#if IDLE_TIMEOUT_MIN > 0
  if (target > 0 && mode != M_TUNE && now - lastActivity > (uint32_t)IDLE_TIMEOUT_MIN * 60000UL) {
    heaterOff();
    mode = M_IDLE;
    autoOffShown = true;
    beep(2, 300);
  }
#endif
}

// ---------------------------------------------------------------------
//  Регулювання (кожні 250 мс)
// ---------------------------------------------------------------------
void controlUpdate() {
  safetyCheck();

  if (mode == M_ERROR) {
    heaterPwm = 0;
  } else if (mode == M_TUNE) {
    heaterPwm = tuneStep(tempC);
  } else if (target > 0) {
    heaterPwm = pidCompute(tempC, target);
  } else {
    heaterPwm = 0;
  }
  analogWrite(PIN_HEATER, heaterPwm);

  // Вентилятор радіатора з гістерезисом
  if (tempC >= FAN_ON_TEMP) fanOn = true;
  else if (tempC <= FAN_OFF_TEMP) fanOn = false;
  digitalWrite(PIN_FAN, (fanOn || fanForced || mode == M_ERROR) ? HIGH : LOW);

  // Логіка майстра cold pull
  switch (mode) {
    case M_CP_FLUSH:
      if (reached && !stepBeeped) { beep(2); stepBeeped = true; }
      break;
    case M_CP_COOL:
      if (tempC <= CP_COOL_TEMP) {
        fanForced = false;
        setTarget(cfg.pullTemp);
        mode = M_CP_PULL;
        beep(2);
      }
      break;
    case M_CP_PULL:
      if (reached && !stepBeeped) { beep(1, 700); stepBeeped = true; }
      break;
    default: break;
  }
}

// ---------------------------------------------------------------------
//  Інтерфейс
// ---------------------------------------------------------------------
static const uint8_t MENU_COUNT = 4;

void startColdPull() {
  fanForced = false;
  setTarget(cfg.flushTemp);
  mode = M_CP_FLUSH;
}

void exitToIdle() {
  heaterOff();
  fanForced = false;
  mode = M_IDLE;
}

void handleInput() {
  int8_t steps = encoderRead();
  BtnEvent btn = buttonRead();
  if (steps || btn) { lastActivity = millis(); autoOffShown = false; }

  switch (mode) {
    case M_IDLE:
    case M_HEAT:
      if (steps) {
        cfg.setTemp = constrain(cfg.setTemp + steps * 5, TEMP_MIN_SET, TEMP_MAX_SET);
        settingsTouch();
        if (mode == M_HEAT) setTarget(cfg.setTemp);
      }
      if (btn == BTN_CLICK) {
        if (mode == M_IDLE) { setTarget(cfg.setTemp); mode = M_HEAT; beep(1, 60); }
        else exitToIdle();
      } else if (btn == BTN_LONG) {
        mode = M_MENU;
        menuPos = 0;
      }
      break;

    case M_MENU:
      if (steps) menuPos = (menuPos + MENU_COUNT + (steps > 0 ? 1 : -1)) % MENU_COUNT;
      if (btn == BTN_LONG) { exitToIdle(); break; }
      if (btn == BTN_CLICK) {
        if (menuPos == 0) startColdPull();
        else if (menuPos == 1) { heaterOff(); tuneTemp = cfg.setTemp; mode = M_TUNE_SET; }
        else if (menuPos == 2) { settingsDefaults(); settingsSave(); beep(2); exitToIdle(); }
        else exitToIdle();
      }
      break;

    case M_CP_FLUSH:
      if (steps) {
        cfg.flushTemp = constrain(cfg.flushTemp + steps * 5, TEMP_MIN_SET, TEMP_MAX_SET);
        settingsTouch();
        setTarget(cfg.flushTemp);
      }
      if (btn == BTN_CLICK) {  // промили — остуджуємо
        heaterOff();
        fanForced = true;
        mode = M_CP_COOL;
      } else if (btn == BTN_LONG) exitToIdle();
      break;

    case M_CP_COOL:
      if (btn == BTN_CLICK) {  // пропустити очікування
        fanForced = false;
        setTarget(cfg.pullTemp);
        mode = M_CP_PULL;
      } else if (btn == BTN_LONG) exitToIdle();
      break;

    case M_CP_PULL:
      if (steps) {
        cfg.pullTemp = constrain(cfg.pullTemp + steps * 5, TEMP_MIN_SET, TEMP_MAX_SET);
        settingsTouch();
        setTarget(cfg.pullTemp);
      }
      if (btn == BTN_CLICK) { heaterOff(); mode = M_CP_AGAIN; }
      else if (btn == BTN_LONG) exitToIdle();
      break;

    case M_CP_AGAIN:
      if (btn == BTN_CLICK) startColdPull();
      else if (btn == BTN_LONG) exitToIdle();
      break;

    case M_TUNE_SET:
      if (steps) tuneTemp = constrain(tuneTemp + steps * 5, 150, TEMP_MAX_SET - 20);
      if (btn == BTN_CLICK) { heaterOff(); pidReset(); tuneStart(); mode = M_TUNE; }
      else if (btn == BTN_LONG) exitToIdle();
      break;

    case M_TUNE:
      if (btn == BTN_LONG) exitToIdle();
      break;

    case M_TUNE_DONE:
      if (btn) exitToIdle();
      break;

    case M_ERROR:
      if (btn == BTN_LONG && sensorErr == ERR_NONE && tempValid) {
        errCode = ERR_NONE;
        exitToIdle();
      }
      break;
  }
}

// ---------- малювання ----------
void drawBigTemp(uint8_t x, uint8_t y) {
  u8g2.setFont(FONT_BIG);
  u8g2.setCursor(x, y);
  if (tempValid) u8g2.print((int)(tempC + 0.5f));
  else u8g2.print(F("---"));
  uint8_t cx = u8g2.getCursorX() + 2;
  u8g2.setFont(FONT_TEXT);
  u8g2.drawCircle(cx + 2, y - 21, 2);
  u8g2.setCursor(cx + 6, y - 14);
  u8g2.print('C');
}

void drawPowerBar() {
  uint8_t w = (uint16_t)heaterPwm * 124 / 255;
  u8g2.drawFrame(0, 50, 128, 3);
  if (w) u8g2.drawBox(2, 51, w, 1);
}

void drawTargetBox(int16_t t) {
  u8g2.setFont(FONT_TEXT);
  u8g2.setCursor(84, 25);
  u8g2.print(F("ціль"));
  u8g2.setCursor(84, 39);
  u8g2.print(t);
  uint8_t cx = u8g2.getCursorX() + 1;
  u8g2.drawCircle(cx + 1, 31, 1);
  u8g2.setCursor(cx + 4, 39);
  u8g2.print('C');
  u8g2.drawVLine(79, 15, 30);
}

void drawFooter(const __FlashStringHelper *s) {
  u8g2.setFont(FONT_TEXT);
  u8g2.setCursor(0, 63);
  u8g2.print(s);
}

void drawHeader(const __FlashStringHelper *s) {
  u8g2.setFont(FONT_TEXT);
  u8g2.setCursor(0, 10);
  u8g2.print(s);
  if (fanOn || fanForced) {
    u8g2.setCursor(104, 10);
    u8g2.print(F("ВЕН"));
  }
}

void drawStepHeader(uint8_t step, const __FlashStringHelper *s) {
  u8g2.setFont(FONT_TEXT);
  u8g2.drawBox(0, 0, 11, 12);
  u8g2.setDrawColor(0);
  u8g2.setCursor(3, 10);
  u8g2.print(step);
  u8g2.setDrawColor(1);
  u8g2.setCursor(15, 10);
  u8g2.print(s);
}

void drawScreen() {
  switch (mode) {
    case M_IDLE:
      drawHeader(autoOffShown ? F("АВТОВИМКНЕНО") : F("НАГРІВ ВИМК."));
      drawBigTemp(0, 42);
      drawTargetBox(cfg.setTemp);
      drawFooter(F("клік-старт утр-меню"));
      break;

    case M_HEAT:
      drawHeader(reached ? F("ТРИМАЄ") : F("НАГРІВ..."));
      drawBigTemp(0, 42);
      drawTargetBox(target);
      drawPowerBar();
      drawFooter(F("клік-стоп  утр-меню"));
      break;

    case M_MENU: {
      drawHeader(F("МЕНЮ"));
      const __FlashStringHelper *items[MENU_COUNT] = {
        F("Cold pull (майстер)"), F("Автотюн PID"), F("Скинути налашт."), F("Назад")};
      for (uint8_t i = 0; i < MENU_COUNT; i++) {
        uint8_t y = 25 + i * 12;
        if (i == menuPos) { u8g2.drawBox(0, y - 10, 128, 12); u8g2.setDrawColor(0); }
        u8g2.setCursor(4, y);
        u8g2.print(items[i]);
        u8g2.setDrawColor(1);
      }
      break;
    }

    case M_CP_FLUSH:
      drawStepHeader(reached ? 2 : 1, reached ? F("Промивка нейлоном") : F("Нагрів"));
      drawBigTemp(0, 42);
      drawTargetBox(target);
      drawPowerBar();
      drawFooter(reached ? F("промили? > клік") : F("чекайте нагріву..."));
      break;

    case M_CP_COOL:
      drawStepHeader(3, F("Остигання"));
      drawBigTemp(0, 42);
      drawTargetBox(CP_COOL_TEMP);
      drawFooter(F("пруток не чіпати"));
      break;

    case M_CP_PULL:
      drawStepHeader(4, reached ? F("РИВОК! Тягніть") : F("Нагрів до ривка"));
      drawBigTemp(0, 42);
      drawTargetBox(target);
      drawPowerBar();
      drawFooter(reached ? F("витягли? > клік") : F("тримайте пруток"));
      break;

    case M_CP_AGAIN:
      drawHeader(F("РЕЗУЛЬТАТ"));
      u8g2.setCursor(0, 26);
      u8g2.print(F("Кінчик брудний?"));
      u8g2.setCursor(0, 40);
      u8g2.print(F("клік: ще раз"));
      drawFooter(F("утримати: вихід"));
      break;

    case M_TUNE_SET:
      drawHeader(F("АВТОТЮН PID"));
      drawBigTemp(0, 42);
      drawTargetBox(tuneTemp);
      drawFooter(F("клік: почати"));
      break;

    case M_TUNE:
      drawHeader(F("АВТОТЮН"));
      u8g2.setCursor(56, 10);
      u8g2.print(tuneProgress());
      u8g2.print('/');
      u8g2.print(AUTOTUNE_CYCLES);
      drawBigTemp(0, 42);
      drawTargetBox(tuneTemp);
      drawPowerBar();
      drawFooter(F("утримати: скасувати"));
      break;

    case M_TUNE_DONE:
      drawHeader(F("АВТОТЮН ГОТОВО"));
      u8g2.setCursor(0, 26); u8g2.print(F("Kp ")); u8g2.print(cfg.kp, 2);
      u8g2.setCursor(0, 38); u8g2.print(F("Ki ")); u8g2.print(cfg.ki, 3);
      u8g2.setCursor(0, 50); u8g2.print(F("Kd ")); u8g2.print(cfg.kd, 1);
      drawFooter(F("збережено. клік"));
      break;

    case M_ERROR: {
      drawHeader(F("!! ПОМИЛКА !!"));
      u8g2.setCursor(0, 28);
      switch (errCode) {
        case ERR_SENSOR_OPEN:  u8g2.print(F("Обрив датчика")); break;
        case ERR_SENSOR_SHORT: u8g2.print(F("Замикання датчика")); break;
        case ERR_OVERTEMP:     u8g2.print(F("Перегрів!")); break;
        case ERR_HEAT_FAIL:    u8g2.print(F("Не гріє")); break;
        case ERR_TEMP_DROP:    u8g2.print(F("Темп. впала")); break;
        case ERR_RUNAWAY_OFF:  u8g2.print(F("Гріє без команди!")); break;
        case ERR_TUNE:         u8g2.print(F("Автотюн перервано")); break;
        default:               u8g2.print(F("Невідома")); break;
      }
      u8g2.setCursor(0, 42);
      u8g2.print(F("Нагрів вимкнено"));
      drawFooter(F("утримати: скинути"));
      break;
    }
  }
}

// ---------------------------------------------------------------------
void setup() {
  // Нагрівач вимкнений з першої мікросекунди
  digitalWrite(PIN_HEATER, LOW);
  pinMode(PIN_HEATER, OUTPUT);
  pinMode(PIN_FAN, OUTPUT);
  digitalWrite(PIN_FAN, LOW);
  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_ENC_A, INPUT_PULLUP);
  pinMode(PIN_ENC_B, INPUT_PULLUP);
  pinMode(PIN_ENC_BTN, INPUT_PULLUP);

  settingsLoad();

  encState = (digitalRead(PIN_ENC_A) << 1) | digitalRead(PIN_ENC_B);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_A), encoderISR, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_B), encoderISR, CHANGE);

  u8g2.setBusClock(400000);
  u8g2.begin();
  u8g2.enableUTF8Print();

  for (uint8_t i = 0; i < 4; i++) sensorUpdate();
  lastActivity = millis();
  beep(1, 80);

  wdt_enable(WDTO_2S);
}

void loop() {
  wdt_reset();
  uint32_t now = millis();

  static uint32_t sensorAt = 0, controlAt = 0, drawAt = 0;
  if (now - sensorAt >= 50) { sensorAt = now; sensorUpdate(); }
  if (now - controlAt >= CONTROL_MS) { controlAt = now; controlUpdate(); }

  handleInput();
  beeperUpdate();

  if (settingsDirty && now - settingsDirtyAt > 5000) settingsSave();

  if (now - drawAt >= 200) {
    drawAt = now;
    u8g2.firstPage();
    do { drawScreen(); } while (u8g2.nextPage());
  }
}
