#pragma once
#include "Arduino.h"
extern const uint8_t u8g2_font_6x12_t_cyrillic[]; extern const uint8_t u8g2_font_logisoso24_tn[];
#define U8G2_R0 0
#define U8X8_PIN_NONE 255
class U8G2 : public Print { public:
 void setBusClock(uint32_t); bool begin(); void enableUTF8Print(); void setFont(const uint8_t*); void setCursor(int,int);
 int getCursorX(); void drawCircle(int,int,int); void drawFrame(int,int,int,int); void drawBox(int,int,int,int);
 void drawVLine(int,int,int); void setDrawColor(int); void firstPage(); uint8_t nextPage(); };
class U8G2_SSD1306_128X64_NONAME_1_HW_I2C : public U8G2 { public: U8G2_SSD1306_128X64_NONAME_1_HW_I2C(int,int); };
class U8G2_SH1106_128X64_NONAME_1_HW_I2C : public U8G2 { public: U8G2_SH1106_128X64_NONAME_1_HW_I2C(int,int); };
