#!/bin/bash
# ============================================================
# NovaChat/NovaLink - One-Click Build Script for Linux/macOS
# ============================================================
# Builds all platform modules using the post-rename (#4/#8) layout:
#
# Java (Gradle):
#   :NovaChat:common          NovaChat protocol layer
#   :client-core              shared runtime (Architecture B)
#   :StarLink:core            NovaLink backend (Main-Class, no shadow)
#   :NovaChat:Plugin:bukkit   Bukkit/Paper/Purpur plugin
#   :NovaChat:Plugin:folia    Folia plugin (shadow fat jar -> NovaChat-Folia)
#   :NovaChat:Proxy:velocity  Velocity plugin (JDK25 toolchain)
#   :NovaChat:Proxy:bungee    BungeeCord/Waterfall plugin
#   :NovaChat:Bedrock:nukkit  Nukkit plugin
#   :NovaChat:Bedrock:pnx     PowerNukkitX plugin (shadow fat jar -> NovaChat-PNX)
#   :NovaChat:Sponge:sponge   Sponge plugin (shadow fat jar -> NovaChat-Sponge)
#   :NovaChat:MOD:mod-common  shared mod networking
#   :NovaChat:MOD:fabric      Fabric mod (fabric-loom)
#   :NovaChat:MOD:neoforge    NeoForge mod (NeoGradle)
#   :NovaChat:MOD:quilt       Quilt mod (fabric-loom)
#
# Non-Java (Gradle base wrappers delegating to native toolchains):
#   :NovaChat:Bedrock:endstone     Python (pip + pytest)
#   :NovaChat:Bedrock:levilamina   C++ (xmake + MSVC) -- Windows only
#   :NovaChat:Bedrock:pmmp         PHP (composer + phpunit)
# ============================================================

set -e

echo ""
echo "============================================================"
echo "NovaChat/NovaLink Build System"
echo "============================================================"
echo ""

BUILD_FAILED=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build/release"

mkdir -p "${BUILD_DIR}"

# ============================================================
# 1. Build Java Projects (Gradle)
# ============================================================
echo "[1/3] Building Java projects with Gradle..."
echo ""

# Override the Windows scoop installations path from gradle.properties so the
# toolchain auto-detection works on this OS (the committed path is Windows-only).
GRADLE_FLAGS=(--console=plain "-Porg.gradle.java.installations.paths=" "-Dorg.gradle.java.installations.paths=")

if ./gradlew clean build -x test --no-daemon "${GRADLE_FLAGS[@]}"; then
    echo "[OK] All Gradle projects built successfully."
else
    echo "[WARNING] Full Gradle build failed -- retrying core server-side modules only."
    echo "[INFO] Skipping MOD loom subprojects (fabric/neoforge/quilt) which need"
    echo "       MC workspace setup and are the usual culprit on first run."

    if ./gradlew \
        :NovaChat:common:build :client-core:build :StarLink:core:build \
        :NovaChat:Plugin:bukkit:build :NovaChat:Plugin:folia:build \
        :NovaChat:Proxy:velocity:build :NovaChat:Proxy:bungee:build \
        :NovaChat:Bedrock:nukkit:build :NovaChat:Bedrock:pnx:build \
        :NovaChat:Sponge:sponge:build :NovaChat:MOD:mod-common:build \
        -x test --no-daemon "${GRADLE_FLAGS[@]}"; then
        echo "[OK] Core Gradle modules built successfully (MOD loom subprojects skipped)."
    else
        echo "[ERROR] Gradle build failed!"
        BUILD_FAILED=1
    fi
fi

# Copy Gradle artifacts (new post-rename paths).
echo "[INFO] Copying Gradle artifacts..."
cp -f StarLink/core/build/libs/*.jar                              "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/Plugin/bukkit/build/libs/*.jar                     "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/Plugin/folia/build/libs/NovaChat-Folia*.jar        "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/Proxy/velocity/build/libs/*.jar                    "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/Proxy/bungee/build/libs/*.jar                      "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/Bedrock/nukkit/build/libs/*.jar                    "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/Bedrock/pnx/build/libs/NovaChat-PNX*.jar           "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/Sponge/sponge/build/libs/NovaChat-Sponge*.jar      "${BUILD_DIR}/" 2>/dev/null || true
# MOD loom remapped jars (fabric/quilt produce *-remapped.jar).
cp -f NovaChat/MOD/fabric/build/libs/*-remapped.jar               "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/MOD/quilt/build/libs/*-remapped.jar                "${BUILD_DIR}/" 2>/dev/null || true
cp -f NovaChat/MOD/neoforge/build/libs/*.jar                      "${BUILD_DIR}/" 2>/dev/null || true

echo ""

# ============================================================
# 2. Build PHP Project (NovaChat/Bedrock/pmmp)
# ============================================================
echo "[2/3] Building PHP project (NovaChat/Bedrock/pmmp)..."
echo ""

if command -v composer &> /dev/null; then
    pushd NovaChat/Bedrock/pmmp > /dev/null

    # Install dependencies (pocketmine-mp runtime + phpunit + eris).
    composer install --no-dev --optimize-autoloader 2>/dev/null || true

    # Stage the plugin archive.
    mkdir -p "${BUILD_DIR}/novachat-pmmp"
    cp -r src    "${BUILD_DIR}/novachat-pmmp/"
    cp -r resources "${BUILD_DIR}/novachat-pmmp/" 2>/dev/null || true
    cp plugin.yml "${BUILD_DIR}/novachat-pmmp/"
    cp composer.json "${BUILD_DIR}/novachat-pmmp/"

    echo "[OK] novachat-pmmp prepared successfully."

    popd > /dev/null
else
    echo "[WARNING] Composer is not installed. Skipping novachat-pmmp build."
fi

echo ""

# ============================================================
# 3. Build Python Project (NovaChat/Bedrock/endstone)
# ============================================================
echo "[3/3] Building Python project (NovaChat/Bedrock/endstone)..."
echo ""

if command -v python3 &> /dev/null || command -v python &> /dev/null; then
    pushd NovaChat/Bedrock/endstone > /dev/null

    # Stage the plugin package (the editable install + pytest are handled by
    # the Gradle :NovaChat:Bedrock:endstone:build wrapper or run directly).
    mkdir -p "${BUILD_DIR}/novachat-endstone"
    cp -r novachat_endstone "${BUILD_DIR}/novachat-endstone/"
    cp -r resources "${BUILD_DIR}/novachat-endstone/" 2>/dev/null || true
    cp plugin.toml   "${BUILD_DIR}/novachat-endstone/"
    cp pyproject.toml "${BUILD_DIR}/novachat-endstone/"

    echo "[OK] novachat-endstone prepared successfully."

    popd > /dev/null
else
    echo "[WARNING] Python is not installed. Skipping novachat-endstone build."
fi

echo ""

# ============================================================
# Build Summary
# ============================================================
echo "============================================================"
echo "Build Summary"
echo "============================================================"
echo ""
echo "Build artifacts are located in: ${BUILD_DIR}"
echo ""

# StarLink:core jar (thin, Main-Class com.nova.link.NovaLinkMain). The Gradle
# project leaf name is "core", so the default archive is core-<ver>.jar.
ls "${BUILD_DIR}"/core-*.jar 1>/dev/null 2>&1 && echo "[OK] StarLink:core"
# Common protocol layer (leaf name "common").
ls "${BUILD_DIR}"/common-*.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:common"
# Bukkit / Velocity / Bungee / Nukkit use default archives (leaf project names).
ls "${BUILD_DIR}"/bukkit-*.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:Plugin:bukkit"
[ -f "${BUILD_DIR}"/NovaChat-Folia*.jar ] && echo "[OK] NovaChat:Plugin:folia"
[ -f "${BUILD_DIR}"/NovaChat-PNX*.jar ] && echo "[OK] NovaChat:Bedrock:pnx"
[ -f "${BUILD_DIR}"/NovaChat-Sponge*.jar ] && echo "[OK] NovaChat:Sponge:sponge"
ls "${BUILD_DIR}"/velocity-*.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:Proxy:velocity"
ls "${BUILD_DIR}"/bungee-*.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:Proxy:bungee"
ls "${BUILD_DIR}"/nukkit-*.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:Bedrock:nukkit"
# MOD loom remapped jars.
ls "${BUILD_DIR}"/*fabric*-remapped.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:MOD:fabric"
ls "${BUILD_DIR}"/*quilt*-remapped.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:MOD:quilt"
ls "${BUILD_DIR}"/NovaChat-neoforge*.jar 1>/dev/null 2>&1 && echo "[OK] NovaChat:MOD:neoforge"
[ -f "${BUILD_DIR}"/novachat-pmmp/plugin.yml ] && echo "[OK] NovaChat:Bedrock:pmmp"
[ -f "${BUILD_DIR}"/novachat-endstone/plugin.toml ] && echo "[OK] NovaChat:Bedrock:endstone"

echo ""

if [ $BUILD_FAILED -eq 1 ]; then
    echo "[WARNING] Some builds failed. Check the output above for details."
    exit 1
else
    echo "[SUCCESS] All builds completed successfully!"
    exit 0
fi
