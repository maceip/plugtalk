#!/usr/bin/env bash
# Install everything needed to build the Trap APK on a Linux x86_64 machine:
# JDK 25, Android SDK 37, NDK r27c, the Rust Android targets and cargo-ndk.
#
# The engine of the browser is **not** installed here: it is CEF, built from
# its own sources by the bundling service of the workspace (`crates/cefpack`),
# and that build takes hours.  The bundle it produces is what
# CEF_ANDROID_ROOT points at.
#
#   scripts/setup-toolchain.sh            # installs into /opt/trap-toolchain
#   source /opt/trap-toolchain/env.sh
#   cd android && ./gradlew :app:assembleDebug
#
# Idempotent: finished steps are skipped.
set -euo pipefail

TC="${TRAP_TOOLCHAIN:-/opt/trap-toolchain}"
NDK_VERSION="r27c"
NDK_PACKAGE="27.2.12479018"

mkdir -p "$TC"
cd "$TC"

if [ ! -x jdk25/bin/java ]; then
  echo "== JDK 25"
  curl -fsSL -o jdk25.tgz "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk25 && tar xzf jdk25.tgz -C jdk25 --strip-components=1 && rm jdk25.tgz
fi
export JAVA_HOME="$TC/jdk25"

if [ ! -d "ndk-$NDK_VERSION" ]; then
  echo "== NDK $NDK_VERSION"
  curl -fsSL -o ndk.zip "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
  unzip -q ndk.zip && rm ndk.zip && mv "android-ndk-$NDK_VERSION" "ndk-$NDK_VERSION"
fi

SDK="$TC/android-sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== Android command line tools"
  mkdir -p "$SDK/cmdline-tools"
  curl -fsSL -o cmdline.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  unzip -q cmdline.zip && rm cmdline.zip && mv cmdline-tools "$SDK/cmdline-tools/latest"
fi
if [ ! -d "$SDK/platforms/android-37.0" ]; then
  echo "== Android SDK 37"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" >/dev/null
fi
mkdir -p "$SDK/ndk"
ln -sfn "$TC/ndk-$NDK_VERSION" "$SDK/ndk/$NDK_PACKAGE"

echo "== Rust targets and cargo-ndk"
rustup target add aarch64-linux-android x86_64-linux-android >/dev/null
command -v cargo-ndk >/dev/null || cargo install cargo-ndk

# The engine of the browser is CEF, built from its own sources with the NDK
# by the bundling service of the workspace.  The service is the crate
# `crates/cefpack`; the build of the engine takes hours and needs a lot of
# memory and disk, therefore it is **not** part of this script.  Run it by
# hand when the engine is needed:
#
#   cargo run -p cefpack -- fetch     # the pinned CEF sources
#   cargo run -p cefpack -- build     # the engine, the headers, the resources
#
# and point CEF_ANDROID_ROOT at the bundle it produces.
CEF="${CEF_ANDROID_ROOT:-$TC/cef-bundle}"

cat > "$TC/env.sh" <<EOF
export JAVA_HOME=$TC/jdk25
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
export ANDROID_NDK_HOME=$TC/ndk-$NDK_VERSION
export CEF_ANDROID_ROOT=$CEF
export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH
EOF

echo "Done. Run: source $TC/env.sh && cd android && ./gradlew :app:assembleDebug"
