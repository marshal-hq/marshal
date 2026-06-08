#!/bin/bash
# Build release artifacts for Marshal.
# Run from repo root: bash scripts/release.sh <version>
# Example: bash scripts/release.sh 0.1.0
set -euo pipefail

VERSION="${1:?Usage: release.sh <version>  e.g. 0.1.0}"
JAR_NAME="marshal-cli-${VERSION}.jar"
IMAGE_NAME="ghcr.io/marshal-hq/marshal-action:${VERSION}"

echo "=== Building shadow JAR ==="
./gradlew :marshal-cli:shadowJar --no-daemon

echo "=== Copying JAR to dist/ ==="
mkdir -p dist
cp marshal-cli/build/libs/marshal-cli-*-SNAPSHOT.jar "dist/${JAR_NAME}"

echo "=== Generating checksums ==="
cd dist
sha256sum "${JAR_NAME}" > sha256sums.txt
cd ..

if ! command -v docker &> /dev/null; then
    echo "Docker not found — skipping image build. Use .github/workflows/publish.yml instead."
    echo ""
    echo "Done. Artifacts in dist/:"
    ls -lh dist/
    exit 0
fi

echo "=== Building Docker image ==="
cp "dist/${JAR_NAME}" marshal-action/marshal-cli.jar
docker build -t "${IMAGE_NAME}" marshal-action/
rm marshal-action/marshal-cli.jar

echo ""
echo "Done. Artifacts in dist/:"
ls -lh dist/
echo ""
echo "Next steps (manual):"
echo "  docker push ${IMAGE_NAME}"
echo "  Create GitHub Release with dist/${JAR_NAME} and dist/sha256sums.txt"
echo "  Tag: v${VERSION}"
