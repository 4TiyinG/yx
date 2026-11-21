#!/usr/bin/env bash
# 本机（aarch64 Linux）Android 构建入口。
# 官方 SDK 工具仅提供 x86_64，本脚本通过 qemu 包装的 CMake/NDK/aapt2 运行。
set -euo pipefail

export JAVA_HOME=/opt/cloud/devTools/java/jdk-17
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
# CMake 以 basename 作为 argv[0] 启动，必须能从 PATH 找到自身来推导 CMAKE_ROOT。
export PATH=/opt/toolchains/cmake-qemu/bin:/opt/toolchains/ndk-qemu/toolchains/llvm/prebuilt/linux-x86_64/bin:/opt/gradle/bin:$PATH

cd "$(dirname "$0")"
rm -rf app/.cxx

ABI="${DSH_ABI:-arm64-v8a}"
PACKAGE="${DSH_PACKAGE:-auto}"
RUNTIME_ZIP="${DSH_RUNTIME_ZIP:-}"

if [ "$PACKAGE" = auto ]; then
  if [ -n "$RUNTIME_ZIP" ] || [ -f "runtime-$ABI.zip" ]; then
    PACKAGE=full
  else
    PACKAGE=noruntime
  fi
fi

args=(":app:assembleDebug" "-Ppackage=$PACKAGE" "-Pabi=$ABI")
if [ "$PACKAGE" = full ]; then
  if [ -z "$RUNTIME_ZIP" ]; then
    RUNTIME_ZIP="$PWD/runtime-$ABI.zip"
  fi
  if [ ! -f "$RUNTIME_ZIP" ]; then
    echo "runtime zip not found: $RUNTIME_ZIP" >&2
    exit 1
  fi
  args+=("-PruntimeZip=$RUNTIME_ZIP")
fi

gradle "${args[@]}" --console=plain "$@"
status=$?
if [ "$status" -eq 0 ]; then
  # 构建成功后自动保存最新源码快照，平台重置后可恢复。
  bash scripts/snapshot.sh || true
fi
exit "$status"