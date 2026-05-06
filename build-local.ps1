# Lokaler Build der Kiosk-MDA APK
# Aufruf in normalem PowerShell:
#   cd C:\temp\projects\kiosk-mda
#   .\build-local.ps1

$ErrorActionPreference = "Stop"

# JDK 17 aus Android Studio
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\hozan.shaker\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Host "Java: " -NoNewline
& "$env:JAVA_HOME\bin\java.exe" -version
Write-Host "Android SDK: $env:ANDROID_HOME"

Set-Location "$PSScriptRoot\android"

# local.properties anlegen falls fehlt
$localProps = "$PSScriptRoot\android\local.properties"
if (-not (Test-Path $localProps)) {
    "sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Out-File -FilePath $localProps -Encoding ASCII
}

Write-Host ""
Write-Host "==> Building Debug APK..."
& .\gradlew.bat :app:assembleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

$apk = "$PSScriptRoot\android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    $dest = "$PSScriptRoot\..\..\kiosk-mda-debug.apk"
    Copy-Item $apk $dest -Force
    Write-Host ""
    Write-Host "==> Fertig!" -ForegroundColor Green
    Write-Host "    APK: $dest"
    Write-Host "    Größe: $((Get-Item $dest).Length / 1MB) MB"
    Write-Host ""
    Write-Host "Installation:"
    Write-Host "    adb install -r `"$dest`""
}
