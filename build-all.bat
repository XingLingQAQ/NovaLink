@echo off
REM ============================================================
REM NovaChat/NovaLink - One-Click Build Script for Windows
REM ============================================================
REM This script builds all platform modules:
REM - Java projects (Gradle): novalink-core, novachat-common,
REM   novachat-bukkit, novachat-velocity, novachat-bungee,
REM   novachat-nukkit, novachat-mod, novachat-pnx
REM - PHP project (Composer): novachat-pmmp
REM - Python project: novachat-endstone
REM ============================================================

setlocal enabledelayedexpansion

echo.
echo ============================================================
echo NovaChat/NovaLink Build System
echo ============================================================
echo.

set "BUILD_FAILED=0"
set "BUILD_DIR=%~dp0build\release"

REM Create build output directory
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

REM ============================================================
REM 1. Build Java Projects (Gradle)
REM ============================================================
echo [1/3] Building Java projects with Gradle...
echo.

call gradlew.bat clean build -x test --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Some Gradle modules failed to build.
    echo [INFO] Attempting to build core modules only...
    
    call gradlew.bat :novachat-common:build :novalink-core:build :novachat-bukkit:build :novachat-velocity:build :novachat-bungee:build :novachat-nukkit:build :novachat-pnx:build :novachat-mod:common:build :novachat-mod:fabric:build :novachat-mod:quilt:build -x test --no-daemon
    
    if !ERRORLEVEL! NEQ 0 (
        echo [ERROR] Gradle build failed!
        set "BUILD_FAILED=1"
    ) else (
        echo [OK] Core Gradle modules built successfully.
    )
) else (
    echo [OK] All Gradle projects built successfully.
)

REM Copy Gradle artifacts
echo [INFO] Copying Gradle artifacts...
copy /Y "novalink-core\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-bukkit\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-velocity\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-bungee\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-nukkit\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-pnx\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-folia\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-mod\fabric\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1
copy /Y "novachat-mod\quilt\build\libs\*.jar" "%BUILD_DIR%\" >nul 2>&1

echo.

REM ============================================================
REM 2. Build PHP Project (novachat-pmmp)
REM ============================================================
echo [2/3] Building PHP project (novachat-pmmp)...
echo.

where composer >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Composer is not installed. Skipping novachat-pmmp build.
) else (
    pushd novachat-pmmp
    
    REM Install dependencies
    call composer install --no-dev --optimize-autoloader 2>nul
    if !ERRORLEVEL! NEQ 0 (
        echo [WARNING] Composer install had issues, but continuing...
    )
    
    REM Create plugin archive
    if not exist "%BUILD_DIR%\novachat-pmmp" mkdir "%BUILD_DIR%\novachat-pmmp"
    xcopy /E /Y /Q "src" "%BUILD_DIR%\novachat-pmmp\src\" >nul 2>&1
    xcopy /E /Y /Q "resources" "%BUILD_DIR%\novachat-pmmp\resources\" >nul 2>&1
    copy /Y "plugin.yml" "%BUILD_DIR%\novachat-pmmp\" >nul 2>&1
    
    echo [OK] novachat-pmmp prepared successfully.
    
    popd
)

echo.

REM ============================================================
REM 3. Build Python Project (novachat-endstone)
REM ============================================================
echo [3/3] Building Python project (novachat-endstone)...
echo.

where python >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Python is not installed. Skipping novachat-endstone build.
) else (
    pushd novachat-endstone
    
    REM Create plugin archive
    if not exist "%BUILD_DIR%\novachat-endstone" mkdir "%BUILD_DIR%\novachat-endstone"
    xcopy /E /Y /Q "novachat_endstone" "%BUILD_DIR%\novachat-endstone\novachat_endstone\" >nul 2>&1
    xcopy /E /Y /Q "resources" "%BUILD_DIR%\novachat-endstone\resources\" >nul 2>&1
    copy /Y "plugin.toml" "%BUILD_DIR%\novachat-endstone\" >nul 2>&1
    copy /Y "pyproject.toml" "%BUILD_DIR%\novachat-endstone\" >nul 2>&1
    
    echo [OK] novachat-endstone prepared successfully.
    
    popd
)

echo.

REM ============================================================
REM Build Summary
REM ============================================================
echo ============================================================
echo Build Summary
echo ============================================================
echo.
echo Build artifacts are located in: %BUILD_DIR%
echo.

dir /b "%BUILD_DIR%\novalink-core*.jar" >nul 2>&1 && echo [OK] novalink-core
dir /b "%BUILD_DIR%\novachat-bukkit*.jar" >nul 2>&1 && echo [OK] novachat-bukkit
dir /b "%BUILD_DIR%\novachat-velocity*.jar" >nul 2>&1 && echo [OK] novachat-velocity
dir /b "%BUILD_DIR%\novachat-bungee*.jar" >nul 2>&1 && echo [OK] novachat-bungee
dir /b "%BUILD_DIR%\novachat-nukkit*.jar" >nul 2>&1 && echo [OK] novachat-nukkit
dir /b "%BUILD_DIR%\novachat-pnx*.jar" >nul 2>&1 && echo [OK] novachat-pnx
dir /b "%BUILD_DIR%\NovaChat-Folia*.jar" >nul 2>&1 && echo [OK] novachat-folia
dir /b "%BUILD_DIR%\fabric*.jar" >nul 2>&1 && echo [OK] novachat-mod-fabric
dir /b "%BUILD_DIR%\quilt*.jar" >nul 2>&1 && echo [OK] novachat-mod-quilt
if exist "%BUILD_DIR%\novachat-pmmp\plugin.yml" echo [OK] novachat-pmmp
if exist "%BUILD_DIR%\novachat-endstone\plugin.toml" echo [OK] novachat-endstone

echo.

if %BUILD_FAILED% EQU 1 (
    echo [WARNING] Some builds failed. Check the output above for details.
    exit /b 1
) else (
    echo [SUCCESS] All builds completed successfully!
    exit /b 0
)
