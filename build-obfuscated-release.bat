@echo off
setlocal enabledelayedexpansion

set "ROOT_DIR=%~dp0"
for /f %%A in ('powershell -NoProfile -Command "(Get-Content '%ROOT_DIR%app\build.gradle.kts' | Select-String 'versionName = \"([^\"]+)\"').Matches[0].Groups[1].Value"') do (
    set "VERSION_NAME=%%~A"
)

if not defined VERSION_NAME (
    echo Could not detect versionName from app\build.gradle.kts
    exit /b 1
)

set "APK_PATH=%ROOT_DIR%app\build\outputs\apk\release\app-release.apk"
set "MAPPING_PATH=%ROOT_DIR%app\build\outputs\mapping\release\mapping.txt"
set "METADATA_PATH=%ROOT_DIR%app\build\outputs\apk\release\output-metadata.json"
set "DIST_DIR=%ROOT_DIR%dist\release-%VERSION_NAME%"

if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"

if exist "%ROOT_DIR%gradle\wrapper\gradle-wrapper.jar" (
    call "%ROOT_DIR%gradlew.bat" clean :app:assembleRelease
) else (
    gradle clean :app:assembleRelease
)
if errorlevel 1 exit /b 1

mkdir "%DIST_DIR%"
copy /y "%APK_PATH%" "%DIST_DIR%\notifyhook-v%VERSION_NAME%-obfuscated.apk" >nul
copy /y "%MAPPING_PATH%" "%DIST_DIR%\mapping.txt" >nul
copy /y "%METADATA_PATH%" "%DIST_DIR%\output-metadata.json" >nul

echo Obfuscated release created:
echo APK: %DIST_DIR%\notifyhook-v%VERSION_NAME%-obfuscated.apk
echo Mapping: %DIST_DIR%\mapping.txt
