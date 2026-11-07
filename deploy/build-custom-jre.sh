#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Script: build-custom-jre.sh
# Purpose: Builds an ultra-lightweight custom JRE (~50 MB) using jlink for
#          Bolivia P2P Rates Monitor on Ubuntu (x86_64).
# ==============================================================================

# Resuelve la raíz del proyecto esté donde esté el script (en deploy/ o en la raíz)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "${SCRIPT_DIR}/bolivia-p2p-rates.jar" ] || [ -f "${SCRIPT_DIR}/pom.xml" ]; then
    APP_DIR="${SCRIPT_DIR}"
else
    APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
fi
TARGET_JRE="${APP_DIR}/jre"

echo "=== [jlink] Custom JRE Builder for Bolivia P2P Rates ==="
echo "Directorio de la aplicación: ${APP_DIR}"

# 1. Idempotencia: Si ya existe un JRE funcional, omitir reconstrucción
if [ -x "${TARGET_JRE}/bin/java" ] && "${TARGET_JRE}/bin/java" -version > /dev/null 2>&1 && [ "${1:-}" != "--force" ]; then
    echo "Custom JRE funcional ya existe en: ${TARGET_JRE}"
    "${TARGET_JRE}/bin/java" -version 2>&1 | head -n 2
    echo "Saltando compilación (usa --force para regenerar)."
    exit 0
fi

# 2. Localizar binarios jlink y java de JDK 25
JLINK_BIN=""
JAVA_SRC_BIN=""

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/jlink" ]; then
    JLINK_BIN="${JAVA_HOME}/bin/jlink"
    JAVA_SRC_BIN="${JAVA_HOME}/bin/java"
elif [ -x "/home/ubuntu/.sdkman/candidates/java/current/bin/jlink" ]; then
    JLINK_BIN="/home/ubuntu/.sdkman/candidates/java/current/bin/jlink"
    JAVA_SRC_BIN="/home/ubuntu/.sdkman/candidates/java/current/bin/java"
elif compgen -G "/home/ubuntu/.sdkman/candidates/java/*/bin/jlink" > /dev/null; then
    JLINK_BIN=$(ls -1d /home/ubuntu/.sdkman/candidates/java/*/bin/jlink | tail -n 1)
    JAVA_SRC_BIN="$(dirname "$JLINK_BIN")/java"
elif command -v jlink > /dev/null 2>&1; then
    JLINK_BIN="$(command -v jlink)"
    JAVA_SRC_BIN="$(command -v java)"
fi

if [ -z "${JLINK_BIN}" ] || [ ! -x "${JLINK_BIN}" ]; then
    echo "ERROR: No se encontró 'jlink'. Verifique instalación de JDK 25." >&2
    exit 1
fi

echo "Usando jlink: ${JLINK_BIN}"

# 3. Definir módulos requeridos (HTTP, SQL, Logging, XML, Security, Unsafe)
MODULES="java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.rmi,java.security.jgss,java.sql,java.xml,jdk.unsupported"

# 4. Manejo seguro de stripping (evita el error 'objcopy' not found en Ubuntu minimal)
STRIP_OPTS=()
if command -v objcopy > /dev/null 2>&1; then
    echo "Herramienta nativa 'objcopy' detectada: aplicando --strip-debug completo."
    STRIP_OPTS+=("--strip-debug")
else
    echo "Aviso: 'objcopy' no instalado en el sistema. Usando '--strip-java-debug-attributes' (portable sin dependencias)."
    STRIP_OPTS+=("--strip-java-debug-attributes")
fi

echo "Compilando minimal JRE con módulos: ${MODULES}..."
TMP_JRE="${APP_DIR}/jre_tmp_$$"
rm -rf "${TMP_JRE}"

if "${JLINK_BIN}" \
    --add-modules "${MODULES}" \
    "${STRIP_OPTS[@]}" \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output "${TMP_JRE}"; then

    rm -rf "${TARGET_JRE}"
    mv "${TMP_JRE}" "${TARGET_JRE}"
    chmod +x "${TARGET_JRE}/bin/java"
    echo "=== Custom JRE generado con éxito en ${TARGET_JRE} ==="
    "${TARGET_JRE}/bin/java" -version
    du -sh "${TARGET_JRE}"
else
    echo "ADVERTENCIA: jlink falló. Activando fallback seguro a Java de SDKMAN..."
    rm -rf "${TMP_JRE}"
    mkdir -p "${TARGET_JRE}/bin"
    ln -sf "${JAVA_SRC_BIN}" "${TARGET_JRE}/bin/java"
    echo "Fallback configurado: ${TARGET_JRE}/bin/java -> ${JAVA_SRC_BIN}"
fi
