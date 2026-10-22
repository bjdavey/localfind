<#
.SYNOPSIS
    Automated Play Feature Delivery & Play AI Pack APK deployment script using bundletool.
    Simulates Google Play Store install-time dynamic delivery for LiteRT NPU hardware acceleration.

.DESCRIPTION
    1. Downloads standalone bundletool.jar (if not already cached in android/.tools/).
    2. Identifies connected target device via ADB (or accepts -DeviceId).
    3. Queries device SoC (ro.soc.manufacturer & ro.soc.model) to determine conditional Device Group.
    4. Builds the Android App Bundle (.aab) via Gradle.
    5. Extracts split APKs with bundletool targeting the specific Device Group.
    6. Installs base and matching NPU runtime splits (HTP/v65/v66/v68/v69/v73/v75) onto the device.
    7. Launches com.localfind/.MainActivity.

.PARAMETER DeviceId
    Optional ADB serial / transport ID of the target device. Auto-detected if omitted.

.PARAMETER Release
    Build and install release bundle instead of debug.

.PARAMETER SkipBuild
    Skip Gradle bundle task and reuse existing .aab file.

.EXAMPLE
    .\deploy-bundle.ps1
    .\deploy-bundle.ps1 -DeviceId "emulator-5554"
    .\deploy-bundle.ps1 -Release
#>

param(
    [string]$DeviceId = "",
    [switch]$Release,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $ScriptDir

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " LocalFind - Bundletool Automated NPU Deployment Helper   " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Ensure bundletool.jar exists
$ToolsDir = Join-Path $ScriptDir ".tools"
if (-not (Test-Path $ToolsDir)) {
    New-Item -ItemType Directory -Path $ToolsDir | Out-Null
}
$BundletoolVersion = "1.18.3"
$BundletoolJar = Join-Path $ToolsDir "bundletool.jar"

if (-not (Test-Path $BundletoolJar)) {
    Write-Host "[1/6] Downloading bundletool v$BundletoolVersion..." -ForegroundColor Yellow
    $Url = "https://github.com/google/bundletool/releases/download/$BundletoolVersion/bundletool-all-$BundletoolVersion.jar"
    Invoke-WebRequest -Uri $Url -OutFile $BundletoolJar -UseBasicParsing
    Write-Host "      Bundletool downloaded to $BundletoolJar" -ForegroundColor Green
} else {
    Write-Host "[1/6] bundletool found at $BundletoolJar" -ForegroundColor Green
}

# 2. Select Device
Write-Host "[2/6] Detecting ADB devices..." -ForegroundColor Yellow
$AdbOutput = adb devices | Where-Object { $_ -match "\t(device)$" }
$Devices = @()
foreach ($line in $AdbOutput) {
    $parts = $line -split "\t"
    if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
        $Devices += $parts[0].Trim()
    }
}

if ($Devices.Count -eq 0) {
    Write-Error "No connected ADB devices in 'device' state found. Please connect a device or launch an emulator."
}

if ([string]::IsNullOrWhiteSpace($DeviceId)) {
    if ($Devices.Count -eq 1) {
        $DeviceId = $Devices[0]
    } else {
        # Prefer physical device (non-emulator) if present
        $PhysicalDevice = $Devices | Where-Object { $_ -notmatch "^emulator-" } | Select-Object -First 1
        if ($PhysicalDevice) {
            $DeviceId = $PhysicalDevice
        } else {
            $DeviceId = $Devices[0]
        }
    }
}

Write-Host "      Target device: $DeviceId" -ForegroundColor Green

# 3. Query Device SoC and match Device Group
Write-Host "[3/6] Querying device SoC for Play Feature Delivery targeting..." -ForegroundColor Yellow
$SocMfr = (adb -s $DeviceId shell getprop ro.soc.manufacturer).Trim()
$SocModel = (adb -s $DeviceId shell getprop ro.soc.model).Trim()
if ([string]::IsNullOrWhiteSpace($SocModel)) {
    $SocModel = (adb -s $DeviceId shell getprop ro.board.platform).Trim()
}
Write-Host "      Detected SoC: Manufacturer='$SocMfr', Model='$SocModel'" -ForegroundColor DarkCyan

$DeviceGroup = ""
# Match against device_targeting_configuration.xml
if ($SocMfr -match "(?i)qualcomm|qti") {
    switch -Regex ($SocModel.ToUpper()) {
        "SM8550" { $DeviceGroup = "Qualcomm_SM8550" }
        "SM8650" { $DeviceGroup = "Qualcomm_SM8650" }
        "SM8750" { $DeviceGroup = "Qualcomm_SM8750" }
        "SM8850" { $DeviceGroup = "Qualcomm_SM8850" }
        default  { $DeviceGroup = "" }
    }
} elseif ($SocMfr -match "(?i)mediatek") {
    switch -Regex ($SocModel.ToUpper()) {
        "MT6991" { $DeviceGroup = "Mediatek_MT6991" }
        "MT6993" { $DeviceGroup = "Mediatek_MT6993" }
        default  { $DeviceGroup = "" }
    }
} elseif ($SocMfr -match "(?i)google") {
    switch -Regex ($SocModel) {
        "Tensor G5" { $DeviceGroup = "Google_Tensor_G5" }
        "Tensor G6" { $DeviceGroup = "Google_Tensor_G6" }
        default     { $DeviceGroup = "" }
    }
}

if ($DeviceGroup) {
    Write-Host "      Matched Device Group: '$DeviceGroup' (NPU split APKs will be installed)" -ForegroundColor Green
} else {
    Write-Host "      No specific NPU Device Group matched (Device will receive base APK with CPU/GPU Smart fallback)" -ForegroundColor Yellow
}

# 4. Build App Bundle
$BuildType = if ($Release) { "Release" } else { "Debug" }
$BuildTypeLower = $BuildType.ToLower()
$AabPath = Join-Path $ScriptDir "app\build\outputs\bundle\$BuildTypeLower\app-$BuildTypeLower.aab"
$ApksPath = Join-Path $ScriptDir "app\build\outputs\bundle\$BuildTypeLower\app-$BuildTypeLower.apks"

if (-not $SkipBuild) {
    Write-Host "[4/6] Building Android App Bundle (:$BuildType)..." -ForegroundColor Yellow
    $Gradletask = ":app:bundle$BuildType"
    $GradleCmd = if ($IsWindows -or $env:OS -match "Windows") { ".\gradlew.bat" } else { "./gradlew" }
    
    & $GradleCmd $Gradletask
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle build failed with exit code $LASTEXITCODE"
    }
} else {
    Write-Host "[4/6] Skipping build step (reusing existing AAB at $AabPath)..." -ForegroundColor DarkCyan
}

if (-not (Test-Path $AabPath)) {
    Write-Error "Bundle file not found at $AabPath"
}

# 5. Generate APKs with bundletool
Write-Host "[5/6] Generating split APKs via bundletool..." -ForegroundColor Yellow
& java -jar "$BundletoolJar" build-apks --bundle="$AabPath" --output="$ApksPath" --overwrite
if ($LASTEXITCODE -ne 0) {
    Write-Error "bundletool build-apks failed"
}

# 6. Install onto device
Write-Host "[6/6] Installing matching APK splits to device $DeviceId..." -ForegroundColor Yellow
$InstallArgs = @(
    "-jar", "$BundletoolJar",
    "install-apks",
    "--apks=$ApksPath",
    "--device-id=$DeviceId",
    "--allow-downgrade"
)

if ($DeviceGroup) {
    $InstallArgs += "--device-groups=$DeviceGroup"
}

& java $InstallArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "Installation failed (exit code $LASTEXITCODE). Checking if existing incompatible debug signature is present..." -ForegroundColor Yellow
    Write-Host "Attempting clean reinstall: uninstalling com.localfind..." -ForegroundColor Yellow
    adb -s $DeviceId uninstall com.localfind
    Write-Host "Retrying install-apks..." -ForegroundColor Yellow
    & java $InstallArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "bundletool install-apks failed after reinstall attempt."
    }
}

Write-Host "Split APKs successfully installed!" -ForegroundColor Green

# 7. Launch App
Write-Host "Launching com.localfind..." -ForegroundColor Cyan
adb -s $DeviceId shell am start -n com.localfind/.MainActivity

Write-Host "`nDeployment completed successfully!" -ForegroundColor Green
Write-Host "To monitor NPU initialization logs in realtime, run:" -ForegroundColor DarkGray
Write-Host "adb -s $DeviceId logcat -c; adb -s $DeviceId logcat -s UniversalEmbedder:D LocalFind:D SocDetector:D`n" -ForegroundColor DarkGray
