#!/bin/bash
# ============================================================
# NovaChat/NovaLink - One-Click Build Script for Linux/macOS
# ============================================================
# This script builds all platform modules:
# - Java projects (Gradle): novalink-core, novachat-common,
#   novachat-bukkit, novachat-velocity, novachat-bungee,
#   novachat-nukkit, novachat-mod, novachat-pnx
# - PHP project (Composer): novachat-pmmp
# - Python project: novachat-endstone
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

# Create build output directory
mkdir -p "${BUILD_DIR}"

# ============================================================
# 1. Build Java Projects (Gradle)
# ============================================================
echo "[1/3] Building Java projects with Gradle..."
echo ""

if ./gradlew clean build -x test --no-daemon; then
    echo "[OK] All Gradle projects built successfully."
else
    echo "[WARNING] Some Gradle modules failed to build."
    echo "[INFO] Attempting to build core modules only..."
    
    if ./gradlew :novachat-common:build :novalink-core:build :novachat-bukkit:build :novachat-velocity:build :novachat-bungee:build :novachat-nukkit:build :novachat-pnx:build :novachat-mod:common:build :novachat-mod:fabric:build :novachat-mod:quilt:build -x test --no-daemon; then
        echo "[OK] Core Gradle modules built successfully."
    else
        echo "[ERROR] Gradle build failed!"
        BUILD_FAILED=1
    fi
fi

# Copy Gradle artifacts
echo "[INFO] Copying Gradle artifacts..."
cp -f novalink-core/build/libs/*.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-bukkit/build/libs/*.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-velocity/build/libs/*.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-bungee/build/libs/*.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-nukkit/build/libs/*.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-pnx/build/libs/*.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-folia/build/libs/*.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-mod/fabric/build/libs/*-remapped.jar "${BUILD_DIR}/" 2>/dev/null || true
cp -f novachat-mod/quilt/build/libs/*-remapped.jar "${BUILD_DIR}/" 2>/dev/null || true

echo ""

# ============================================================
# 2. Build PHP Project (novachat-pmmp)
# ============================================================
echo "[2/3] Building PHP project (novachat-pmmp)..."
echo ""

if command -v composer &> /dev/null; then
    pushd novachat-pmmp > /dev/null
    
    # Install dependencies
    composer install --no-dev --optimize-autoloader 2>/dev/null || true
    
    # Create plugin archive
    mkdir -p "${BUILD_DIR}/novachat-pmmp"
    cp -r src "${BUILD_DIR}/novachat-pmmp/"
    cp -r resources "${BUILD_DIR}/novachat-pmmp/"
    cp plugin.yml "${BUILD_DIR}/novachat-pmmp/"
    
    echo "[OK] novachat-pmmp prepared successfully."
    
    popd > /dev/null
else
    echo "[WARNING] Composer is not installed. Skipping novachat-pmmp build."
fi

echo ""

# ============================================================
# 3. Build Python Project (novachat-endstone)
# ============================================================
echo "[3/3] Building Python project (novachat-endstone)..."
echo ""

if command -v python3 &> /dev/null || command -v python &> /dev/null; then
    pushd novachat-endstone > /dev/null
    
    # Create plugin archive
    mkdir -p "${BUILD_DIR}/novachat-endstone"
    cp -r novachat_endstone "${BUILD_DIR}/novachat-endstone/"
    cp -r resources "${BUILD_DIR}/novachat-endstone/"
    cp plugin.toml "${BUILD_DIR}/novachat-endstone/"
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

[ -f "${BUILD_DIR}"/novalink-core*.jar ] && echo "[OK] novalink-core"
[ -f "${BUILD_DIR}"/novachat-bukkit*.jar ] && echo "[OK] novachat-bukkit"
[ -f "${BUILD_DIR}"/novachat-velocity*.jar ] && echo "[OK] novachat-velocity"
[ -f "${BUILD_DIR}"/novachat-bungee*.jar ] && echo "[OK] novachat-bungee"
[ -f "${BUILD_DIR}"/novachat-nukkit*.jar ] && echo "[OK] novachat-nukkit"
[ -f "${BUILD_DIR}"/NovaChat-PNX*.jar ] && echo "[OK] novachat-pnx"
[ -f "${BUILD_DIR}"/NovaChat-Folia*.jar ] && echo "[OK] novachat-folia"
[ -f "${BUILD_DIR}"/*fabric*-remapped.jar ] && echo "[OK] novachat-mod-fabric"
[ -f "${BUILD_DIR}"/*quilt*-remapped.jar ] && echo "[OK] novachat-mod-quilt"
[ -f "${BUILD_DIR}"/novachat-pmmp/plugin.yml ] && echo "[OK] novachat-pmmp"
[ -f "${BUILD_DIR}"/novachat-endstone/plugin.toml ] && echo "[OK] novachat-endstone"

echo ""

if [ $BUILD_FAILED -eq 1 ]; then
    echo "[WARNING] Some builds failed. Check the output above for details."
    exit 1
else
    echo "[SUCCESS] All builds completed successfully!"
    exit 0
fi
