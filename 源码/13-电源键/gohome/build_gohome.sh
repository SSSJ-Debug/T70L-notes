#!/bin/bash
# build_gohome.sh —— 编译「GOHOME 接收器」（Android 4.4 / API19，无第三方依赖）
# 产物只有一个几千字节的 APK，没有桌面图标、没有界面、常驻零资源。
set -e
export JAVA_HOME=$HOME/re-tools/jdk-21.0.12.1+1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

SDK=$HOME/re-tools/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$HOME/re-tools/android-19.jar

P="$(cd "$(dirname "$0")" && pwd)"
B=$P/build
PKG=com.lm.gohome
OUT=$P/gohome.apk

rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "==> [1/4] aapt 生成 R.java 与资源包"
"$BT/aapt" package -f -m \
    -J "$B/gen" \
    -M "$P/AndroidManifest.xml" \
    -I "$PLATFORM" \
    -F "$B/res.apk"

echo "==> [2/4] javac"
find "$P/src" "$B/gen" -name "*.java" > "$B/sources.txt"
set +e
javac -nowarn -source 8 -target 8 \
    -bootclasspath "$PLATFORM" \
    -classpath "$PLATFORM" \
    -d "$B/classes" \
    @"$B/sources.txt" > "$B/javac.log" 2>&1
JRC=$?
set -e
grep -v "bootstrap class path" "$B/javac.log" | grep -v "^$" || true
if [ $JRC -ne 0 ]; then echo "!!! javac failed rc=$JRC"; exit 1; fi

echo "==> [3/4] d8 → dex"
java -cp $HOME/re-tools/r8/r8-8.5.35.jar com.android.tools.r8.D8 \
    --min-api 19 --lib "$PLATFORM" --output "$B/dex" \
    $(find "$B/classes" -name "*.class")

cp "$B/res.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -X "$B/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "==> [4/4] 签名"
KS=$HOME/Desktop/中移T70L/核心资产/源码/12-抖音/douyin.jks
[ -f "$KS" ] || KS=$P/gohome.jks
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias gohome -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=Android,OU=Dev,O=OpenSource,L=City,ST=State,C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias douyin \
    --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
    --min-sdk-version 19 --out "$OUT" "$B/aligned.apk"

ls -l "$OUT"
echo "OK -> $OUT"
