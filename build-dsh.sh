#!/usr/bin/env sh
# DSH Harness 本地 Android 构建脚本（环境全部在 /workspace，沙箱重置不丢）
# 用法：sh /workspace/build-dsh.sh [额外的 gradle 参数]
# 可选环境变量：PACKAGE=noruntime|full  ABI=arm64-v8a|armeabi-v7a  JVMARGS="..."
set -eu

WS=/workspace
export JAVA_HOME="$WS/env/jdk-17/jdk-17.0.21+9"
export GRADLE_USER_HOME="$WS/env/gradle-home/.gradle"
export ANDROID_HOME="$WS/env/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$JAVA_HOME/bin:$PATH"

# 沙箱只有 egress 代理可达外网；JVM 不读 HTTP_PROXY，必须显式注入系统属性。
PROXY_URL="${https_proxy:-${HTTPS_PROXY:-}}"
PROXY_ARGS=""
if [ -n "$PROXY_URL" ]; then
  HOSTPORT="${PROXY_URL#*://}"
  PXH="${HOSTPORT%%:*}"
  PXP="${HOSTPORT##*:}"
  PXP="${PXP%%/*}"
  PROXY_ARGS="-Dhttp.proxyHost=$PXH -Dhttp.proxyPort=$PXP -Dhttps.proxyHost=$PXH -Dhttps.proxyPort=$PXP -Dhttp.nonProxyHosts=localhost|127.0.0.1"
fi

PROJ=$(find "$WS/src" -maxdepth 4 -name gradlew -type f 2>/dev/null | head -n 1)
if [ -z "$PROJ" ]; then
  echo "[build-dsh] 未找到 gradlew：请先把源码工程放到 $WS/src/pkg1/" >&2
  exit 1
fi
cd "$(dirname "$PROJ")"

echo "[build-dsh] project = $(pwd)"
echo "[build-dsh] JAVA_HOME=$JAVA_HOME"
echo "[build-dsh] proxy = ${PROXY_URL:-none}"

exec ./gradlew \
  -Ppackage="${PACKAGE:-noruntime}" \
  -Pabi="${ABI:-arm64-v8a}" \
  assembleDebug --no-daemon \
  $PROXY_ARGS \
  -Dorg.gradle.jvmargs="${JVMARGS:--Xmx3g -XX:MaxMetaspaceSize=768m $PROXY_ARGS}" \
  "$@"
