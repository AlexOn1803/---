#pragma once
struct EEPROMClass { template<class T> T& get(int, T& t){return t;} template<class T> const T& put(int,const T& t){return t;} };
extern EEPROMClass EEPROM;
