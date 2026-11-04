// =====================================================================
//  ColdPull Station — налаштування заліза
//  Усе, що може відрізнятись у вашій збірці, змінюється тут.
// =====================================================================
#pragma once

// ---------------- Дисплей ----------------
// 0.96" OLED зазвичай SSD1306, 1.3" — часто SH1106.
#define DISPLAY_SSD1306 1
#define DISPLAY_SH1106  2
#define DISPLAY_TYPE    DISPLAY_SSD1306

// ---------------- Піни (Arduino Nano / Pro Mini 5V) ----------------
#define PIN_HEATER   9    // затвор MOSFET нагрівача (PWM, Timer1)
#define PIN_FAN      5    // MOSFET вентилятора радіатора
#define PIN_BUZZER   6    // пасивний п'єзо-бузер (можна не ставити)
#define PIN_ENC_A    2    // енкодер CLK  (переривання)
#define PIN_ENC_B    3    // енкодер DT   (переривання)
#define PIN_ENC_BTN  4    // енкодер SW   (кнопка, на GND)
#define PIN_TEMP     A0   // датчик температури (дільник з підтяжкою до 5V)
// OLED: SDA = A4, SCL = A5

// Скільки імпульсів дає енкодер на один "клік". Більшість модулів KY-040 — 4.
// Якщо одне клацання змінює значення на 2 кроки — поставте 4; якщо треба
// два клацання на крок — поставте 2.
#define ENC_STEPS_PER_NOTCH 4
// Якщо обертання працює навпаки — поміняйте на 1.
#define ENC_REVERSE 0

// ---------------- Датчик температури ----------------
// Більшість хотендів — NTC 100K (B≈3950). Перевірте мультиметром:
// при кімнатній температурі ~100 кОм → NTC 100K; ~1.08 кОм → PT1000.
#define SENSOR_NTC     1
#define SENSOR_PT1000  2
#define SENSOR_TYPE    SENSOR_NTC

#define PULLUP_OHMS    4700.0f   // резистор підтяжки від 5V до A0
                                 // (для PT1000 краще поставити 1000..2200 Ом)
// NTC
#define NTC_R25        100000.0f // опір при 25 °C
#define NTC_BETA       3950.0f   // коефіцієнт B
// PT1000
#define PT_R0          1000.0f

// Поправка показів (°C), якщо датчик трохи бреше.
#define TEMP_OFFSET    0.0f

// ---------------- Межі та безпека ----------------
#define TEMP_MAX_SET      300   // максимальна задана температура
#define TEMP_MIN_SET      40
#define TEMP_HARD_LIMIT   315   // вище — аварійне вимкнення
#define TEMP_SENSOR_MIN   -15   // нижче — обрив датчика

// Захист від "втечі" (thermal runaway):
// при нагріванні температура має зрости хоча б на RUNAWAY_HEAT_RISE °C
// за RUNAWAY_HEAT_SEC секунд, інакше — помилка.
#define RUNAWAY_HEAT_SEC    40
#define RUNAWAY_HEAT_RISE   2
// Коли ціль вже досягнута, температура не повинна падати нижче
// (ціль − RUNAWAY_HOLD_BAND) довше за RUNAWAY_HOLD_SEC.
#define RUNAWAY_HOLD_BAND   20
#define RUNAWAY_HOLD_SEC    60

// Автовимкнення нагріву без жодної дії користувача (хвилини). 0 = вимкнено.
#define IDLE_TIMEOUT_MIN    15

// Обмеження потужності нагрівача, 0..255. 255 = повна.
#define HEATER_MAX_PWM      255

// ---------------- Вентилятор ----------------
#define FAN_ON_TEMP   50   // увімкнути вище цієї температури
#define FAN_OFF_TEMP  45   // вимкнути нижче цієї

// ---------------- Cold pull за замовчуванням ----------------
#define CP_FLUSH_TEMP  265  // крок 1: прогрів і промивка нейлоном
#define CP_COOL_TEMP   45   // крок 3: охолодити до цієї температури
#define CP_PULL_TEMP   160  // крок 4: температура ривка (нейлон 140–170)

// ---------------- PID за замовчуванням ----------------
// Це лише стартові значення: зробіть автотюн у меню для свого хотенда.
#define PID_KP_DEFAULT  20.0f
#define PID_KI_DEFAULT  1.5f
#define PID_KD_DEFAULT  60.0f
#define AUTOTUNE_CYCLES 6
