#pragma once
#include <stdint.h>
#include <stddef.h>
#include <math.h>
#include <string.h>
#include <algorithm>
typedef bool boolean; typedef uint8_t byte;
#define PROGMEM
#define HIGH 1
#define LOW 0
#define INPUT_PULLUP 2
#define OUTPUT 1
#define CHANGE 1
#define A0 14
#define PI 3.14159265f
#define F(s) (reinterpret_cast<const __FlashStringHelper*>(s))
class __FlashStringHelper;
#define constrain(x,a,b) ((x)<(a)?(a):((x)>(b)?(b):(x)))
using std::min; using std::max;
uint32_t millis(); int digitalRead(uint8_t); void digitalWrite(uint8_t,uint8_t); void pinMode(uint8_t,uint8_t);
int analogRead(uint8_t); void analogWrite(uint8_t,int); void tone(uint8_t,unsigned,unsigned long);
void noInterrupts(); void interrupts(); void attachInterrupt(uint8_t, void(*)(), int);
#define digitalPinToInterrupt(p) ((p)-2)
class Print { public:
 size_t print(const __FlashStringHelper*); size_t print(const char*); size_t print(char);
 size_t print(int); size_t print(unsigned); size_t print(long); size_t print(unsigned long); size_t print(double,int=2); };
