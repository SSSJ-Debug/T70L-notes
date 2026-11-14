#!/bin/bash
# build_tv.sh —— 编译「看电视」App（Android 4.4 / API19 / armv7，无第三方依赖）
set -e
export JAVA_HOME=$HOME/re-tools/jdk-21.0.12.1+1/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

SDK=$HOME/re-tools/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$HOME/re-tools/android-19.jar

P="$(cd "$(dirname "$0")" && pwd)"
B=$P/build
PKG=com.lm.tv

rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "==> [1/5] aapt 打包资源（含 assets/channels.txt）"
"$BT/aapt" package -f -m \
    -J "$B/gen" \
    -M "$P/AndroidManifest.xml" \
    -S "$P/res" \
    -A "$P/assets" \
    -I "$PLATFORM" \
    -F "$B/res.apk"

echo "==> [2/5] javac 编译"
find "$P/src" "$B/gen" -name "*.java" > "$B/sources.txt"
javac -nowarn -source 8 -target 8 \
    -bootclasspath "$PLATFORM" \
    -classpath "$PLATFORM" \
    -d "$B/classes" \
    @"$B/sources.txt" 2>&1 | grep -v "^注:" | grep -v "bootstrap class path" || true

PKGP=$(echo "$PKG" | tr '.' '/')
if [ ! -f "$B/classes/$PKGP/MainActivity.class" ]; then
    echo "!!! 编译失败"; exit 1
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
KS=$P/tv.jks
KS_PASS="${KS_PASS:-android}"   # 调试签名口令，可用环境变量覆盖；仓库不含 .jks，首次编译会自动生成
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias tv -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=Android,OU=Dev,O=OpenSource,L=City,ST=State,C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias tv \
    --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
    --min-sdk-version 19 \
    --out "$P/tv.apk" "$B/aligned.apk"

ls -la "$P/tv.apk"
echo "OK -> $P/tv.apk"
