#!/usr/bin/env bash
# Install everything needed to build the Trap APK on a Linux x86_64 machine:
# JDK 25, Android SDK 37, NDK r27c, the Rust Android targets, and cargo-ndk.
#
# Browser rendering is provided by Cefpack (`https://github.com/maceip/cefpack`),
# resolved and SHA-256 verified automatically by Gradle (`:app:resolveCefpackAar`).
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
  set +o pipefail
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
  set -o pipefail
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" >/dev/null
fi
mkdir -p "$SDK/ndk"
ln -sfn "$TC/ndk-$NDK_VERSION" "$SDK/ndk/$NDK_PACKAGE"

echo "== Rust targets and cargo-ndk"
rustup target add aarch64-linux-android x86_64-linux-android >/dev/null
command -v cargo-ndk >/dev/null || cargo install cargo-ndk --locked

cat > "$TC/env.sh" <<EOF
export JAVA_HOME=$TC/jdk25
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
export ANDROID_NDK_HOME=$TC/ndk-$NDK_VERSION
export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH
EOF

echo "Done. Run: source $TC/env.sh && cd android && ./gradlew :app:assembleDebug"
