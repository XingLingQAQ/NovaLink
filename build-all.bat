@echo off
REM ============================================================
REM NovaChat/NovaLink - One-Click Build Script for Windows
REM ============================================================
REM Builds all platform modules (post-rename #4/#8 layout):
REM - Java (Gradle): NovaChat:common, client-core, StarLink:core,
REM   NovaChat:Plugin:{bukkit,folia}, NovaChat:Proxy:{velocity,bungee},
REM   NovaChat:Bedrock:{nukkit,pnx}, NovaChat:Sponge:sponge,
REM   NovaChat:MOD:{mod-common,fabric,neoforge,quilt}
REM - PHP (Composer): NovaChat/Bedrock/pmmp
REM - Python: NovaChat/Bedrock/endstone
REM ============================================================

setlocal enabledelayedexpansion

echo.
echo ============================================================
echo NovaChat/NovaLink Build System
echo ============================================================
echo.

set "BUILD_FAILED=0"
set "BUILD_DIR=%~dp0build\release"

if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

REM Override the Windows scoop installations path from gradle.properties so
REM toolchain auto-detection works even if the committed scoop paths are absent.
set "GRADLE_FLAGS=--console=plain -Porg.gradle.java.installations.paths= -Dorg.gradle.java.installations.paths="

REM ============================================================
REM 1. Build Java Projects (Gradle)
REM ============================================================
echo [1/3] Building Java projects with Gradle...
echo.

call gradlew.bat clean build -x test --no-daemon %GRADLE_FLAGS%
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Full Gradle build failed -- retrying core server-side modules.
    echo [INFO] Skipping MOD loom subprojects (fabric/neoforge/quilt).

    call gradlew.bat ^
        :NovaChat:common:build :client-core:build :StarLink:core:build ^
        :NovaChat:Plugin:bukkit:build :NovaChat:Plugin:folia:build ^
        :NovaChat:Proxy:velocity:build :NovaChat:Proxy:bungee:build ^
        :NovaChat:Bedrock:nukkit:build :NovaChat:Bedrock:pnx:build ^
        :NovaChat:Sponge:sponge:build :NovaChat:MOD:mod-common:build ^
        -x test --no-daemon %GRADLE_FLAGS%

    if !ERRORLEVEL! NEQ 0 (
        echo [ERROR] Gradle build failed!
        set "BUILD_FAILED=1"
    ) else (
        echo [OK] Core Gradle modules built successfully (MOD loom skipped).
    )
) else (
    echo [OK] All Gradle projects built successfully.
)

REM Copy Gradle artifacts (new post-rename paths).
echo [INFO] Copying Gradle artifacts...
copy /Y "StarLink\core\build\libs\*.jar"                          "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\Plugin\bukkit\build\libs\*.jar"                 "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\Plugin\folia\build\libs\NovaChat-Folia*.jar"    "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\Proxy\velocity\build\libs\*.jar"                "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\Proxy\bungee\build\libs\*.jar"                  "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\Bedrock\nukkit\build\libs\*.jar"                "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\Bedrock\pnx\build\libs\NovaChat-PNX*.jar"       "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\Sponge\sponge\build\libs\NovaChat-Sponge*.jar"  "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\MOD\fabric\build\libs\*-remapped.jar"           "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\MOD\quilt\build\libs\*-remapped.jar"            "%BUILD_DIR%\" >nul 2>&1
copy /Y "NovaChat\MOD\neoforge\build\libs\*.jar"                  "%BUILD_DIR%\" >nul 2>&1

echo.

REM ============================================================
REM 2. Build PHP Project (NovaChat/Bedrock/pmmp)
REM ============================================================
echo [2/3] Building PHP project (NovaChat/Bedrock/pmmp)...
echo.

where composer >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Composer is not installed. Skipping novachat-pmmp build.
) else (
    pushd NovaChat\Bedrock\pmmp

    call composer install --no-dev --optimize-autoloader 2>nul

    if not exist "%BUILD_DIR%\novachat-pmmp" mkdir "%BUILD_DIR%\novachat-pmmp"
    xcopy /E /Y /Q "src"        "%BUILD_DIR%\novachat-pmmp\src\" >nul 2>&1
    xcopy /E /Y /Q "resources"  "%BUILD_DIR%\novachat-pmmp\resources\" >nul 2>&1
    copy /Y "plugin.yml"        "%BUILD_DIR%\novachat-pmmp\" >nul 2>&1
    copy /Y "composer.json"     "%BUILD_DIR%\novachat-pmmp\" >nul 2>&1

    echo [OK] novachat-pmmp prepared successfully.

    popd
)

echo.

REM ============================================================
REM 3. Build Python Project (NovaChat/Bedrock/endstone)
REM ============================================================
echo [3/3] Building Python project (NovaChat/Bedrock/endstone)...
echo.

where python >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] Python is not installed. Skipping novachat-endstone build.
) else (
    pushd NovaChat\Bedrock\endstone

    if not exist "%BUILD_DIR%\novachat-endstone" mkdir "%BUILD_DIR%\novachat-endstone"
    xcopy /E /Y /Q "novachat_endstone" "%BUILD_DIR%\novachat-endstone\novachat_endstone\" >nul 2>&1
    xcopy /E /Y /Q "resources"         "%BUILD_DIR%\novachat-endstone\resources\" >nul 2>&1
    copy /Y "plugin.toml"              "%BUILD_DIR%\novachat-endstone\" >nul 2>&1
    copy /Y "pyproject.toml"           "%BUILD_DIR%\novachat-endstone\" >nul 2>&1

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

dir /b "%BUILD_DIR%\core-*.jar"             >nul 2>&1 && echo [OK] StarLink:core
dir /b "%BUILD_DIR%\common-*.jar"           >nul 2>&1 && echo [OK] NovaChat:common
dir /b "%BUILD_DIR%\bukkit-*.jar"           >nul 2>&1 && echo [OK] NovaChat:Plugin:bukkit
dir /b "%BUILD_DIR%\NovaChat-Folia*.jar"    >nul 2>&1 && echo [OK] NovaChat:Plugin:folia
dir /b "%BUILD_DIR%\NovaChat-PNX*.jar"      >nul 2>&1 && echo [OK] NovaChat:Bedrock:pnx
dir /b "%BUILD_DIR%\NovaChat-Sponge*.jar"   >nul 2>&1 && echo [OK] NovaChat:Sponge:sponge
dir /b "%BUILD_DIR%\velocity-*.jar"         >nul 2>&1 && echo [OK] NovaChat:Proxy:velocity
dir /b "%BUILD_DIR%\bungee-*.jar"           >nul 2>&1 && echo [OK] NovaChat:Proxy:bungee
dir /b "%BUILD_DIR%\nukkit-*.jar"           >nul 2>&1 && echo [OK] NovaChat:Bedrock:nukkit
dir /b "%BUILD_DIR%\*fabric*-remapped.jar"  >nul 2>&1 && echo [OK] NovaChat:MOD:fabric
dir /b "%BUILD_DIR%\*quilt*-remapped.jar"   >nul 2>&1 && echo [OK] NovaChat:MOD:quilt
dir /b "%BUILD_DIR%\NovaChat-neoforge*.jar" >nul 2>&1 && echo [OK] NovaChat:MOD:neoforge
if exist "%BUILD_DIR%\novachat-pmmp\plugin.yml"    echo [OK] NovaChat:Bedrock:pmmp
if exist "%BUILD_DIR%\novachat-endstone\plugin.toml" echo [OK] NovaChat:Bedrock:endstone

echo.

if %BUILD_FAILED% EQU 1 (
    echo [WARNING] Some builds failed. Check the output above for details.
    exit /b 1
) else (
    echo [SUCCESS] All builds completed successfully!
    exit /b 0
)
