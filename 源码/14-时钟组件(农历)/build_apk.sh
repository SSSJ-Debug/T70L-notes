#!/bin/zsh
# 构建 com.xylink.timewidget 时钟组件（加秒显示版）
set -e
cd "$(dirname "$0")"

export JAVA_HOME="$PWD/jdk-17.0.21+9/Contents/Home"
BT=$HOME/re-tools/android-sdk/build-tools/34.0.0
AJAR="$PWD/android-4.4.2/android.jar"

rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== aapt2 compile =="
$BT/aapt2 compile --dir res -o build/res.zip

echo "== aapt2 link =="
$BT/aapt2 link -I "$AJAR" --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 17 --target-sdk-version 19 \
    -o build/base.apk build/res.zip

echo "== javac =="
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn \
    -classpath "$AJAR" -d build/classes \
    build/gen/com/xylink/timewidget/R.java src/com/xylink/timewidget/*.java

echo "== d8 =="
$BT/d8 --lib "$AJAR" --release --min-api 19 \
    --output build/dex $(find build/classes -name '*.class')

echo "== pack =="
cd build && zip -j -q base.apk dex/classes.dex && cd ..

echo "== zipalign =="
$BT/zipalign -f 4 build/base.apk build/aligned.apk

echo "== sign =="
if [ ! -f debug.keystore ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore debug.keystore -storepass "$KS_PASS" \
    -keypass "$KS_PASS" -alias androiddebugkey -dname "CN=Android,OU=Dev,O=OpenSource,L=City,ST=State,C=CN" -keyalg RSA -validity 10000
fi
$BT/apksigner sign --ks debug.keystore --ks-pass pass:"$KS_PASS" \
    --min-sdk-version 17 --out timewidget-sec.apk build/aligned.apk

echo "== verify =="
$BT/aapt2 dump badging timewidget-sec.apk | grep -E "package|application-label"
ls -l timewidget-sec.apk
echo "OK -> $PWD/timewidget-sec.apk"
