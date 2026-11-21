#!/usr/bin/env bash
# Збирає NeoRemote.apk без Android Studio і Gradle.
#
# Потрібно (Ubuntu/Debian):
#   sudo apt install openjdk-17-jdk aapt dalvik-exchange zipalign apksigner
# Android API 34 (android.jar) скрипт сам завантажить з Maven Central
# (пакет org.robolectric:android-all), якщо не вказано ANDROID_JAR.
set -euo pipefail
cd "$(dirname "$0")"

OUT=build
APK_NAME=NeoRemote.apk
ALL_VER=14-robolectric-10818077
JAR=${ANDROID_JAR:-$OUT/android-all-$ALL_VER.jar}
KS=keystore/neoremote-debug.jks

mkdir -p "$OUT" release keystore

if [ ! -f "$JAR" ]; then
  echo "== Завантажую android-all $ALL_VER (~130 МБ)"
  curl -fL -o "$JAR" \
    "https://repo.maven.apache.org/maven2/org/robolectric/android-all/$ALL_VER/android-all-$ALL_VER.jar"
fi

DX=$(command -v dalvik-exchange || command -v dx)

rm -rf "$OUT/gen" "$OUT/classes" "$OUT"/*.apk "$OUT/classes.dex"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "== Ресурси (aapt)"
aapt package -f -m -0 arsc \
  -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$JAR" \
  -F "$OUT/unaligned.apk"

echo "== Компіляція Java"
javac --release 8 -encoding UTF-8 -nowarn -Xlint:-options \
  -cp "$JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "== DEX"
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"

echo "== Пакування"
(cd "$OUT" && aapt add unaligned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  echo "== Створюю ключ для підпису"
  keytool -genkeypair -keystore "$KS" -storepass neoremote -keypass neoremote \
    -alias neoremote -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NeoRemote" >/dev/null
fi

echo "== Підпис"
apksigner sign --ks "$KS" --ks-pass pass:neoremote --key-pass pass:neoremote \
  --out "release/$APK_NAME" "$OUT/aligned.apk"
apksigner verify "release/$APK_NAME"
rm -f "release/$APK_NAME.idsig"
echo "== Готово: $(pwd)/release/$APK_NAME ($(du -h release/$APK_NAME | cut -f1))"
