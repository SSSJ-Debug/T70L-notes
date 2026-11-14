#!/bin/bash
# build_douyin.sh —— 编译「抖音」App（Android 4.4 / API19 / armv7，零第三方依赖）
set -e
export JAVA_HOME=$HOME/re-tools/jdk-21.0.12.1+1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

SDK=$HOME/re-tools/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$HOME/re-tools/android-19.jar

P="$(cd "$(dirname "$0")" && pwd)"
B=$P/build
PKG=com.lm.douyin

rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "==> [1/5] aapt 打包资源"
"$BT/aapt" package -f -m \
    -J "$B/gen" \
    -M "$P/AndroidManifest.xml" \
    -S "$P/res" \
    -I "$PLATFORM" \
    -F "$B/res.apk"

echo "==> [2/5] javac 编译"
find "$P/src" "$B/gen" -name "*.java" > "$B/sources.txt"
set +e
javac -nowarn -source 8 -target 8 \
    -bootclasspath "$PLATFORM" \
    -classpath "$PLATFORM" \
    -d "$B/classes" \
    @"$B/sources.txt" > "$B/javac.log" 2>&1
JRC=$?
set -e
grep -v "^注:" "$B/javac.log" | grep -v "bootstrap class path" | grep -v "^$" || true
if [ $JRC -ne 0 ]; then
    echo "!!! javac failed rc=$JRC log=$B/javac.log"
    exit 1
fi

PKGP=$(echo "$PKG" | tr '.' '/')
if [ ! -f "$B/classes/$PKGP/MainActivity.class" ]; then
    echo "!!! 找不到 MainActivity.class"; exit 1
fi

echo "==> [3/5] d8 转 dex"
java -cp $HOME/re-tools/r8/r8-8.5.35.jar com.android.tools.r8.D8 \
    --min-api 19 --lib "$PLATFORM" --output "$B/dex" \
    $(find "$B/classes" -name "*.class")

echo "==> [4/5] 合入 + zipalign"
cp "$B/res.apk" "$B/unsigned.apk"
cd "$B/dex" && zip -q -X "$B/unsigned.apk" classes.dex
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "==> [5/5] 签名"
KS=$P/douyin.jks
KS_PASS="${KS_PASS:-android}"   # 调试签名口令，可用环境变量覆盖；仓库不含 .jks，首次编译会自动生成
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias douyin -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=Android,OU=Dev,O=OpenSource,L=City,ST=State,C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias douyin \
    --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
    --min-sdk-version 19 \
    --out "$P/douyin.apk" "$B/aligned.apk"

ls -la "$P/douyin.apk"
echo "OK -> $P/douyin.apk"
