#!/usr/bin/env bash
# BootLog 构建脚本 —— 纯命令行，不依赖 Gradle
#
# 本地用法:   ./build.sh
# CI 用法:    ANDROID_SDK_ROOT=$ANDROID_HOME ./build.sh
#
# 可用环境变量:
#   ANDROID_SDK_ROOT / ANDROID_HOME  SDK 根目录（默认 /opt/android-sdk）
#   ANDROID_PLATFORM_JAR             直接指定 android.jar
#   BUILD_TOOLS_DIR                  直接指定 build-tools 目录
#   VERSION_CODE / VERSION_NAME      覆盖清单里的版本号
#   KEYSTORE_FILE                    签名用的 keystore（不存在则自动生成一个）
#   KS_PASS / KEY_ALIAS / KEY_PASS   签名口令，默认 android / bootlog / android
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app/src/main"
MANIFEST="$APP/AndroidManifest.xml"
OUT="$ROOT/build"

# ---------------------------------------------------------------- 定位 SDK
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for c in /opt/android-sdk "$HOME/Android/Sdk" /usr/local/lib/android/sdk; do
    [ -d "$c" ] && SDK="$c" && break
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "找不到 Android SDK，请设置 ANDROID_SDK_ROOT"; exit 1; }

if [ -n "${BUILD_TOOLS_DIR:-}" ]; then
  BT="$BUILD_TOOLS_DIR"
else
  BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$BT" ] && [ -x "$BT/aapt2" ] || { echo "找不到 build-tools（需要 aapt2）"; exit 1; }

if [ -n "${ANDROID_PLATFORM_JAR:-}" ]; then
  PLATFORM="$ANDROID_PLATFORM_JAR"
else
  PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1 || true)/android.jar"
fi
[ -f "$PLATFORM" ] || { echo "找不到 android.jar: $PLATFORM"; exit 1; }

# ------------------------------------------------------------ 解析版本号
: "${VERSION_CODE:=$(grep -o 'android:versionCode="[0-9]*"' "$MANIFEST" | grep -o '[0-9]*')}"
: "${VERSION_NAME:=$(grep -o 'android:versionName="[^"]*"' "$MANIFEST" | sed 's/.*="//; s/"$//')}"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "无法解析版本号"; exit 1; }

# ---------------------------------------------------------------- 签名配置
: "${KEYSTORE_FILE:=$OUT/bootlog.keystore}"
: "${KS_PASS:=android}"
: "${KEY_ALIAS:=bootlog}"
: "${KEY_PASS:=android}"

rm -rf "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/base.apk" "$OUT/aligned.apk"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 0/6 环境"
echo "    SDK        : $SDK"
echo "    build-tools: $(basename "$BT")"
echo "    platform   : $(basename "$(dirname "$PLATFORM")")"
echo "    版本       : $VERSION_NAME ($VERSION_CODE)"

echo "==> 1/6 编译资源"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res"

echo "==> 2/6 链接资源与清单"
FLATS=$(find "$OUT/res" -name '*.flat' | sort)
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$MANIFEST" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  $FLATS

echo "==> 3/6 编译 Java"
find "$APP/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$PLATFORM" \
  -classpath "$BT/core-lambda-stubs.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> 4/6 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --min-api 21 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)

echo "==> 5/6 对齐"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名"
if [ ! -f "$KEYSTORE_FILE" ]; then
  echo "    未提供 keystore，生成临时测试签名（不同构建之间无法覆盖安装）"
  mkdir -p "$(dirname "$KEYSTORE_FILE")"
  keytool -genkeypair -keystore "$KEYSTORE_FILE" -storepass "$KS_PASS" \
    -alias "$KEY_ALIAS" -keypass "$KEY_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=BootLog, OU=Dev, O=BootLog, L=Shenzhen, ST=Guangdong, C=CN" >/dev/null 2>&1
fi

APK="$OUT/BootLog-v$VERSION_NAME.apk"
rm -f "$APK"
"$BT/apksigner" sign \
  --ks "$KEYSTORE_FILE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --min-sdk-version 21 \
  --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify "$APK" >/dev/null && echo "    签名校验通过"
ls -lh "$APK"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$APK"
fi
echo "BUILD OK -> $APK"
