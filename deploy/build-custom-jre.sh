#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Script: build-custom-jre.sh
# Purpose: Builds an ultra-lightweight custom JRE (~50 MB) using jlink for
#          Bolivia P2P Rates Monitor on Ubuntu (x86_64).
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$SCRIPT_DIR")"
TARGET_JRE="${APP_DIR}/jre"

echo "=== [jlink] Custom JRE Builder for Bolivia P2P Rates ==="

# 1. Check if healthy JRE already exists (Idempotent for fast CI/CD)
if [ -x "${TARGET_JRE}/bin/java" ] && [ "${1:-}" != "--force" ]; then
    echo "Custom JRE already exists at: ${TARGET_JRE}"
    "${TARGET_JRE}/bin/java" -version 2>&1 | head -n 2
    echo "Skipping rebuild (use --force to regenerate)."
    exit 0
fi

# 2. Locate jlink binary from Java 25 installation
JLINK_BIN=""

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/jlink" ]; then
    JLINK_BIN="${JAVA_HOME}/bin/jlink"
elif [ -x "/home/ubuntu/.sdkman/candidates/java/current/bin/jlink" ]; then
    JLINK_BIN="/home/ubuntu/.sdkman/candidates/java/current/bin/jlink"
elif compgen -G "/home/ubuntu/.sdkman/candidates/java/*/bin/jlink" > /dev/null; then
    JLINK_BIN=$(ls -1d /home/ubuntu/.sdkman/candidates/java/*/bin/jlink | tail -n 1)
elif command -v jlink > /dev/null 2>&1; then
    JLINK_BIN="$(command -v jlink)"
fi

if [ -z "${JLINK_BIN}" ] || [ ! -x "${JLINK_BIN}" ]; then
    echo "ERROR: Could not locate 'jlink' binary. Please ensure JDK 25 is installed." >&2
    exit 1
fi

echo "Using jlink binary: ${JLINK_BIN}"

# 3. Define strictly required modules (detected via jdeps)
MODULES="java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.rmi,java.security.jgss,java.sql,jdk.unsupported"

echo "Building custom minimal JRE with modules: ${MODULES}..."

TMP_JRE="${APP_DIR}/jre_tmp_$$"
rm -rf "${TMP_JRE}"

"${JLINK_BIN}" \
    --add-modules "${MODULES}" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output "${TMP_JRE}"

# 4. Atomically replace old JRE
rm -rf "${TARGET_JRE}"
mv "${TMP_JRE}" "${TARGET_JRE}"
chmod +x "${TARGET_JRE}/bin/java"

echo "=== Custom JRE built successfully! ==="
"${TARGET_JRE}/bin/java" -version
du -sh "${TARGET_JRE}"
