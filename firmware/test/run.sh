#!/bin/sh
# Прогін прошивки на ПК з тепловою моделлю хотенда (без Arduino).
# Перевіряє нагрів, автотюн, усі кроки cold pull, захист і автовимкнення.
set -e
cd "$(dirname "$0")"
g++ -std=gnu++17 -O1 -Istubs -I../ColdPullStation -x c++ sim.cpp -o /tmp/coldpull_sim
/tmp/coldpull_sim
