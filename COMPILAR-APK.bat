@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
title EPHORASL - Compilar APK Debug

echo ============================================
echo  EPHORASL 7.50 - Compilar APK (base commit c6ecaf9 + mundo-texturas-1 + wire-ids)
echo  Run 37424518117 - workflow EPHORASL Android APK
echo ============================================
echo.

REM ---------- 1. Java 17 ----------
echo [1/4] Verificando Java...
java -version >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No se encontro Java en PATH.
  echo Instala Temurin 17: https://adoptium.net/temurin/releases/?version=17
  echo y vuelve a ejecutar este .bat.
  pause
  exit /b 1
)
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVAVER=%%g
echo Java detectado: %JAVAVER%
echo.

REM ---------- 2. Android SDK ----------
echo [2/4] Verificando Android SDK...
if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
  ) else if exist "C:\Android\Sdk\platform-tools\adb.exe" (
    set "ANDROID_HOME=C:\Android\Sdk"
  )
)
REM Edita esta linea si tu SDK esta en otro lado:
REM set "ANDROID_HOME=C:\Users\TU_USUARIO\AppData\Local\Android\Sdk"

if not defined ANDROID_HOME (
  echo [ERROR] No se encontro ANDROID_HOME.
  echo Edita este .bat y descomenta/ajusta la linea SET ANDROID_HOME=...
  echo O instalalo con Android Studio.
  pause
  exit /b 1
)
echo ANDROID_HOME=%ANDROID_HOME%
set "SDKMANAGER=%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat"
if not exist "%SDKMANAGER%" (
  echo Buscando sdkmanager...
  for /f "delims=" %%p in ('dir /s /b "%ANDROID_HOME%\sdkmanager.bat" 2^>nul') do set "SDKMANAGER=%%p" & goto :foundSdk
  :foundSdk
)
if not exist "%SDKMANAGER%" (
  echo [ERROR] No se encontro sdkmanager.bat en %ANDROID_HOME%
  echo Instalalo desde Android Studio ^> SDK Manager ^> SDK Tools ^> Android SDK Command-line Tools
  pause
  exit /b 1
)
echo Instalando/verificando paquetes SDK (igual que build.yml)...
call "%SDKMANAGER%" --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.0.12077973" "cmake;3.22.1"
echo.

REM ---------- 3. Gradle 8.10.2 ----------
echo [3/4] Verificando Gradle...
set "GRADLE_VER=8.10.2"
set "GRADLE_DIR=%~dp0.tools\gradle-%GRADLE_VER%"
set "GRADLE_BIN=%GRADLE_DIR%\bin\gradle.bat"
where gradle >nul 2>&1
if %errorlevel%==0 (
  echo Usando gradle del PATH:
  gradle --version | findstr /i "gradle"
  set "GRADLE_CMD=gradle"
) else if exist "%GRADLE_BIN%" (
  echo Usando gradle portable: %GRADLE_BIN%
  set "GRADLE_CMD=%GRADLE_BIN%"
) else (
  echo Descargando Gradle %GRADLE_VER% portable...
  if not exist "%~dp0.tools" mkdir "%~dp0.tools"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VER%-bin.zip' -OutFile '%~dp0.tools\gradle.zip'"
  if errorlevel 1 (
    echo [ERROR] No se pudo descargar Gradle. Revisa tu internet.
    pause
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%~dp0.tools\gradle.zip' -DestinationPath '%~dp0.tools' -Force"
  set "GRADLE_CMD=%GRADLE_BIN%"
)
echo.

REM ---------- 4. Compilar ----------
echo [4/4] Compilando APK debug...
echo Esto tarda varios minutos la primera vez.
echo.
cd /d "%~dp0"
if defined GRADLE_CMD (
  if "%GRADLE_CMD%"=="gradle" (
    gradle :app:assembleDebug --no-daemon --console=plain --stacktrace
  ) else (
    call "%GRADLE_CMD%" :app:assembleDebug --no-daemon --console=plain --stacktrace
  )
) else (
  call "%GRADLE_BIN%" :app:assembleDebug --no-daemon --console=plain --stacktrace
)
if errorlevel 1 (
  echo.
  echo [ERROR] Fallo la compilacion. Revisa los errores de arriba.
  pause
  exit /b 1
)

echo.
echo ============================================
echo  COMPILACION OK
echo ============================================
if not exist "APK-SALIDA" mkdir "APK-SALIDA"
copy /y "app\build\outputs\apk\debug\*.apk" "APK-SALIDA\" 
echo.
echo APK copiado a: %~dp0APK-SALIDA\
dir "APK-SALIDA\*.apk"
echo.
pause
