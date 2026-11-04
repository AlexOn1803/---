#include <cstdio>
#include "Arduino.h"
#include "EEPROM.h"
#include "U8g2lib.h"
static uint32_t simMs=0; static int pwm=0; static bool btnDown=false; static double Th=25, Ts=25; static bool brokenHeater=false;
uint32_t millis(){return simMs;}
int digitalRead(uint8_t p){ if(p==4) return btnDown?0:1; return 1;}
void digitalWrite(uint8_t p,uint8_t v){ if(p==9 && v==0) pwm=0; }
void pinMode(uint8_t,uint8_t){} void analogWrite(uint8_t p,int v){ if(p==9) pwm=v; }
int analogRead(uint8_t){ double R=100000.0*exp(3950.0*(1/(Ts+273.15)-1/298.15)); double a=1023.0*R/(R+4700.0); return (int)(a+0.5); }
void tone(uint8_t,unsigned,unsigned long){} void noInterrupts(){} void interrupts(){} void attachInterrupt(uint8_t,void(*)(),int){}
size_t Print::print(const __FlashStringHelper*){return 0;} size_t Print::print(const char*){return 0;} size_t Print::print(char){return 0;}
size_t Print::print(int){return 0;} size_t Print::print(unsigned){return 0;} size_t Print::print(long){return 0;} size_t Print::print(unsigned long){return 0;} size_t Print::print(double,int){return 0;}
void U8G2::setBusClock(uint32_t){} bool U8G2::begin(){return 1;} void U8G2::enableUTF8Print(){} void U8G2::setFont(const uint8_t*){} void U8G2::setCursor(int,int){}
int U8G2::getCursorX(){return 0;} void U8G2::drawCircle(int,int,int){} void U8G2::drawFrame(int,int,int,int){} void U8G2::drawBox(int,int,int,int){}
void U8G2::drawVLine(int,int,int){} void U8G2::setDrawColor(int){} void U8G2::firstPage(){} uint8_t U8G2::nextPage(){return 0;}
U8G2_SSD1306_128X64_NONAME_1_HW_I2C::U8G2_SSD1306_128X64_NONAME_1_HW_I2C(int,int){}
const uint8_t u8g2_font_6x12_t_cyrillic[1]={0}; const uint8_t u8g2_font_logisoso24_tn[1]={0}; EEPROMClass EEPROM;
void wdt_enable(int){} void wdt_reset(){}
#include "../ColdPullStation/ColdPullStation.ino"
static bool fanState(){ return fanOn||fanForced; }
void step(){ simMs+=10; double P = brokenHeater?0:40.0*pwm/255.0; double fan = fanState()?0.015:0; Th += 0.01*(P-(0.10+fan)*(Th-25))/12.0; Ts += 0.01*(Th-Ts)/2.0; loop(); }
void run(double sec){ for(long i=0;i<sec*100;i++) step(); }
void click(int ms=100){ btnDown=true; run(ms/1000.0); btnDown=false; run(0.1); }
const char* M[]={"IDLE","HEAT","MENU","CP_FLUSH","CP_COOL","CP_PULL","CP_AGAIN","TUNE_SET","TUNE","TUNE_DONE","ERROR"};
void st(const char*l){ printf("%-34s t=%7.1fs mode=%-9s T=%6.1f tgt=%3d pwm=%3d fan=%d err=%d\n",l,simMs/1000.0,M[mode],tempC,target,heaterPwm,fanState(),errCode); }
int main(int argc,char**argv){
  setup(); run(1); st("start");
  click(); double mx=0; for(int i=0;i<1200;i++){ run(0.25); mx = std::max(mx,(double)tempC);} st("heat 240 (default PID) 300s");
  printf("  overshoot max=%.1f\n",mx);
  click(); run(1); st("stop");
  click(1000); st("long press -> menu");
  // autotune: menu pos 1
  encRaw=4; run(0.1); click(); st("tune set"); click(); 
  for(int i=0;i<1200 && mode==M_TUNE;i++) run(1); st("autotune finished");
  printf("  Kp=%.2f Ki=%.3f Kd=%.1f\n",cfg.kp,cfg.ki,cfg.kd);
  click(); run(60); st("idle after tune");
  click(); mx=0; double mn=999; for(int i=0;i<1200;i++){ run(0.25); if(i>800){mx=std::max(mx,(double)tempC); mn=std::min(mn,(double)tempC);} } st("heat 240 tuned PID 300s");
  printf("  steady band %.1f..%.1f\n",mn,mx);
  click(); run(1);
  // cold pull
  click(1000); click(); st("cold pull start");
  for(int i=0;i<600 && !reached;i++) run(1); st("flush temp reached");
  run(20); click(); st("click -> cooling");
  for(int i=0;i<1200 && mode==M_CP_COOL;i++) run(1); st("cooled -> pull heat");
  for(int i=0;i<600 && !reached;i++) run(1); st("pull temp reached");
  run(5); st("hold pull"); click(); st("pulled -> again?"); click(1000); st("exit");
  // safety: broken heater
  click(); run(2); brokenHeater=true; run(90); st("broken heater (no heat)");
  click(1000); brokenHeater=false; st("reset error");
  // idle timeout
  click(); run(16*60); st("16 min no input -> auto off");
  return 0; }
