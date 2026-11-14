#!/bin/bash
set -e
export JAVA_HOME=$HOME/re-tools/jdk-21.0.12.1+1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
BT=$HOME/re-tools/android-sdk/build-tools/34.0.0
ANDROID_JAR=$HOME/re-tools/android-19.jar
P=$HOME/re-tools/t70l/wxwidget
KEYSTORE=$HOME/re-tools/t70l/nemodesk/desk.keystore

cd "$P"
rm -rf gen obj dex build unsigned.apk aligned.apk nemowx.apk
mkdir -p gen obj dex build

echo "[1/5] aapt 生成 R.java"
"$BT/aapt" package -f -m \
  -J gen \
  -M AndroidManifest.xml \
  -S res \
  -I "$ANDROID_JAR"

echo "[2/5] javac"
javac -nowarn -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -d obj \
  src/com/nemo/weather/*.java gen/com/nemo/weather/R.java 2>&1 | grep -v "^Note:" || true

echo "[3/5] d8"
java -cp $HOME/re-tools/r8/r8-8.5.35.jar com.android.tools.r8.D8 \
  --min-api 15 --lib "$ANDROID_JAR" --output dex \
  $(find obj -name "*.class")

echo "[4/5] 打包"
"$BT/aapt" package -f \
  -M AndroidManifest.xml \
  -S res \
  -I "$ANDROID_JAR" \
  -F unsigned.apk
cd build
unzip -o -q ../unsigned.apk
cp ../dex/classes.dex .
zip -q -r ../aligned.apk .
cd ..

echo "[5/5] 签名"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:"$KS_PASS" \
  --key-pass pass:"$KS_PASS" --out nemowx.apk aligned.apk

"$BT/aapt" dump badging nemowx.apk | head -5
ls -lh nemowx.apk
echo "BUILD_OK"
