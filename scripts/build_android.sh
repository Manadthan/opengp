#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the Android APK of Mario Kart Arcade GP 2 (see docs/android.md):
#   1. gekkorecomp and glslang for this machine (the cross build runs them),
#   2. the game for Android arm64 (libmain.so) with SDL3 (libSDL3.so),
#   3. the APK, with Gradle.
# Finished steps are skipped. Needs orig/GGPE02/sys/main.dol (python3
# tools/setup.py), ANDROID_HOME with an NDK, CMake, Ninja and a JDK 17+.
#
#   OPENGP_ANDROID_VERSION=N scripts/build_android.sh    -> out/opengp-android-vN.apk
#
# The APK holds code recompiled from the player's own game: it is for their
# own device only. Never publish it.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
ndk="${ANDROID_NDK:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
version="${OPENGP_ANDROID_VERSION:-1}"
project_version="$(sed -n 's/^project(OpenGP VERSION \([0-9.]*\).*/\1/p' CMakeLists.txt)"
gradle_version=8.11.1
jobs="$(nproc)"

if [ ! -f orig/GGPE02/sys/main.dol ]; then
  echo "orig/GGPE02/sys/main.dol is missing: run python3 tools/setup.py with your image in ISO/" >&2
  exit 1
fi

# 1. Tools for this machine.
host="$root/build/android-host"
if [ ! -x "$host/tools/gekkorecomp/gekkorecomp" ]; then
  cmake -S . -B "$host" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DOPENGP_BUILD_TESTS=OFF -DOPENGP_BUILD_GAMES=OFF -DOPENGP_BUILD_VIDEO=OFF
  ninja -C "$host" gekkorecomp
fi
deps="$root/build/android-deps"
mkdir -p "$deps"
# Git checkouts of the pinned releases (cmake/runtime: the same tags), for
# machines that can clone from GitHub but not download its archives.
[ -d "$deps/glslang" ] || git clone -q -c advice.detachedHead=false --depth 1 --branch 16.6.0 \
  https://github.com/KhronosGroup/glslang "$deps/glslang"
[ -d "$deps/imgui" ] || git clone -q -c advice.detachedHead=false --depth 1 --branch v1.92.9b \
  https://github.com/ocornut/imgui "$deps/imgui"
glslang="$root/build/android-glslang/StandAlone/glslang"
if [ ! -x "$glslang" ]; then
  cmake -S "$deps/glslang" -B "$root/build/android-glslang" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_OPT=OFF -DENABLE_HLSL=OFF -DENABLE_SPVREMAPPER=OFF -DENABLE_GLSLANG_BINARIES=ON \
    -DGLSLANG_TESTS=OFF -DGLSLANG_ENABLE_INSTALL=OFF -DBUILD_EXTERNAL=OFF
  ninja -C "$root/build/android-glslang" glslang-standalone
fi

# 2. The game for Android.
build="$root/build/android"
if [ ! -f "$build/CMakeCache.txt" ]; then
  cmake -S . -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared \
    -DOPENGP_BUILD_TESTS=OFF \
    -DOPENGP_GEKKORECOMP="$host/tools/gekkorecomp/gekkorecomp" \
    -DOPENGP_GLSLANG="$glslang" \
    -DFETCHCONTENT_SOURCE_DIR_IMGUI="$deps/imgui"
fi
ninja -C "$build" -j "$jobs" opengp-mkgp2 SDL3-shared

libs="$root/build/android-libs/arm64-v8a"
mkdir -p "$libs"
strip="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
"$strip" --strip-unneeded -o "$libs/libmain.so" "$build/games/mkgp2/libmain.so"
"$strip" --strip-unneeded -o "$libs/libSDL3.so" "$(find "$build/_deps/sdl3-build" -maxdepth 1 -name 'libSDL3.so' | head -1)"
cp "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$libs/"
# Kept unstripped for reading crash reports (llvm-symbolizer / ndk-stack).
mkdir -p "$root/build/android-symbols"
cp "$build/games/mkgp2/libmain.so" "$root/build/android-symbols/libmain-v$version.so"

# 3. The APK.
gradle="$root/build/android-gradle/gradle-$gradle_version/bin/gradle"
if [ ! -x "$gradle" ]; then
  mkdir -p "$root/build/android-gradle"
  curl -sSfL -o "$root/build/android-gradle/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip"
  (cd "$root/build/android-gradle" && unzip -q gradle.zip && rm gradle.zip)
fi
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
(cd android && "$gradle" --no-daemon -q assembleRelease \
  -PopengpVersionCode="$version" -PopengpVersionName="$project_version-android.$version")
mkdir -p out
cp android/app/build/outputs/apk/release/app-release.apk "out/opengp-android-v$version.apk"
echo "out/opengp-android-v$version.apk"
