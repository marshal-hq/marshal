#!/bin/bash
# Build the Marshal release artifact locally (the fat-jar the composite action
# downloads). For the real release, push a v<version> tag or run the
# "Publish Marshal release" workflow — this script is for local verification.
#
# Run from repo root: bash scripts/release.sh <version>
# Example: bash scripts/release.sh 0.1.0
set -euo pipefail

VERSION="${1:?Usage: release.sh <version>  e.g. 0.1.0}"
JAR_NAME="marshal-cli-${VERSION}.jar"

echo "=== Building shadow JAR ==="
./gradlew :marshal-cli:shadowJar --no-daemon

echo "=== Staging artifact in dist/ ==="
mkdir -p dist
# The shadowJar carries no classifier; exclude the -thin (non-fat) jar.
SHADOW=$(ls marshal-cli/build/libs/marshal-cli-*.jar | grep -v -- '-thin\.jar$')
cp "${SHADOW}" "dist/${JAR_NAME}"

echo "=== Generating checksums ==="
( cd dist && sha256sum "${JAR_NAME}" > sha256sums.txt )

echo ""
echo "Done. Artifacts in dist/:"
ls -lh dist/
echo ""
echo "To publish: push tag v${VERSION} (triggers .github/workflows/publish.yml),"
echo "which uploads ${JAR_NAME} as a release asset on the v${VERSION} release."
echo "The composite action downloads exactly that asset name + tag."
