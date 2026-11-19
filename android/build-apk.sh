#!/usr/bin/env bash
# Build the Android app inside Docker, so you don't need Android Studio.
# Output: android/daybook.apk
set -euo pipefail
cd "$(dirname "$0")"
IMAGE=calorie-android-build

docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -t "$IMAGE" -f Dockerfile.build .

# First build: create a signing key. Keep the signing/ folder: Android only installs
# an update over the existing app if it is signed with the same key.
if [ ! -f signing/keystore.properties ]; then
  mkdir -p signing
  PASS=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  docker run --rm -v "$PWD/signing:/out" "$IMAGE" bash -c "
    keytool -genkeypair -keystore /out/release.jks -storepass '$PASS' -keypass '$PASS' \
      -alias calories -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Daybook' >/dev/null 2>&1
    chown $(id -u):$(id -g) /out/release.jks"
  printf 'storeFile=signing/release.jks\nstorePassword=%s\nkeyAlias=calories\nkeyPassword=%s\n' "$PASS" "$PASS" > signing/keystore.properties
  chmod 600 signing/keystore.properties
fi

docker run --rm \
  -v "$PWD:/project" \
  -v calorie-gradle-cache:/root/.gradle \
  "$IMAGE" bash -c "
    set -e
    [ -f gradlew ] || gradle wrapper --gradle-version 8.11.1 -q
    gradle testReleaseUnitTest assembleRelease --no-daemon --console=plain -q
    cp app/build/outputs/apk/release/app-release.apk daybook.apk
    chown -R $(id -u):$(id -g) /project"

echo "Built: $PWD/daybook.apk"
