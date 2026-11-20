#!/usr/bin/env bash
# Build the release APK into dist/. Runs at low priority so it doesn't slow a running Shorts job.
set -euo pipefail
cd "$(dirname "$0")/android"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
nice -n 19 ./gradlew assembleRelease --console=plain -q
v=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
mkdir -p ../dist && cp app/build/outputs/apk/release/app-release.apk "../dist/Pocket-$v.apk"
echo "dist/Pocket-$v.apk"
