<#
.SYNOPSIS
    Automated Play Feature Delivery & Play AI Pack APK deployment script using bundletool.
    Simulates Google Play Store install-time dynamic delivery for LiteRT NPU hardware acceleration
    and automatically downloads, stages, and verifies the matching on-device LiteRT-LM model.

.DESCRIPTION
    1. Downloads standalone bundletool.jar (if not already cached in android/.tools/).
    2. Identifies connected target device via ADB (or accepts -DeviceId) and detects primary ABI.
    3. Queries device SoC (ro.soc.manufacturer & ro.soc.model) to determine conditional Device Group and target model file.
    4. Builds the Android App Bundle (.aab) via Gradle for the target ABI (rebuilding if a cached AAB lacks the target ABI).
    5. Extracts split APKs with bundletool targeting the specific Device Group.
    6. Installs base and matching NPU runtime splits (HTP/v73/v75/v79/v81 or MediaTek/Tensor) onto the device.
    7. Verifies and stages the matching LiteRT-LM model to /data/local/tmp/ (downloading from Hugging Face if needed).
    8. Launches com.localfind/.MainActivity and verifies that the embedding model loads successfully.

.PARAMETER DeviceId
    Optional ADB serial / transport ID of the target device. Auto-detected if omitted.

.PARAMETER Release
    Build and install release bundle instead of debug.

.PARAMETER SkipBuild
    Skip Gradle bundle task and reuse existing .aab file (unless its ABI does not match the target device).

.PARAMETER SkipModelPush
    Skip checking, downloading, and pushing the LiteRT-LM model to the device.

.EXAMPLE
    .\deploy-bundle.ps1
    .\deploy-bundle.ps1 -DeviceId "emulator-5554"
    .\deploy-bundle.ps1 -Release
    .\deploy-bundle.ps1 -SkipModelPush
#>

param(
    [string]$DeviceId = "",
    [switch]$Release,
    [switch]$SkipBuild,
    [switch]$SkipModelPush
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
    Write-Host "[1/8] Downloading bundletool v$BundletoolVersion..." -ForegroundColor Yellow
    $Url = "https://github.com/google/bundletool/releases/download/$BundletoolVersion/bundletool-all-$BundletoolVersion.jar"
    Invoke-WebRequest -Uri $Url -OutFile $BundletoolJar -UseBasicParsing
    Write-Host "      Bundletool downloaded to $BundletoolJar" -ForegroundColor Green
} else {
    Write-Host "[1/8] bundletool found at $BundletoolJar" -ForegroundColor Green
}

# 2. Select Device
Write-Host "[2/8] Detecting ADB devices..." -ForegroundColor Yellow
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

$DeviceAbi = "$(adb -s $DeviceId shell getprop ro.product.cpu.abi)".Trim()
if ([string]::IsNullOrWhiteSpace($DeviceAbi)) {
    $DeviceAbi = "arm64-v8a"
}

Write-Host "      Target device: $DeviceId (ABI: $DeviceAbi)" -ForegroundColor Green

$IsEmulatorTarget = ("$(adb -s $DeviceId shell getprop ro.kernel.qemu)".Trim() -eq "1") -or
    ("$(adb -s $DeviceId shell getprop ro.hardware)".Trim() -match "goldfish|ranchu")
if ($IsEmulatorTarget) {
    Write-Host "      Emulator target detected: forcing the persisted accelerator preference to CPU." -ForegroundColor Yellow
}

# 3. Query Device SoC and match Device Group & LiteRT Model
Write-Host "[3/8] Querying device SoC for Play Feature Delivery & Model targeting..." -ForegroundColor Yellow
$SocMfr = "$(adb -s $DeviceId shell getprop ro.soc.manufacturer)".Trim()
$SocModel = "$(adb -s $DeviceId shell getprop ro.soc.model)".Trim()
$Platform = "$(adb -s $DeviceId shell getprop ro.board.platform)".Trim()
$Board = "$(adb -s $DeviceId shell getprop ro.product.board)".Trim()
$Hardware = "$(adb -s $DeviceId shell getprop ro.hardware)".Trim()

if ([string]::IsNullOrWhiteSpace($SocModel)) {
    $SocModel = $Platform
}
Write-Host "      Detected SoC: Manufacturer='$SocMfr', Model='$SocModel', Platform='$Platform', Board='$Board'" -ForegroundColor DarkCyan

$DeviceGroup = ""
$ModelName = "embeddinggemma-2-740m.litertlm" # Universal CPU/GPU fallback for Emulators & generic devices

# Match against device_targeting_configuration.xml & SocDetector specifications
$LookupKey = "$SocMfr $SocModel $Platform $Board $Hardware".ToUpper()

if (-not $IsEmulatorTarget -and $DeviceAbi -eq "arm64-v8a") {
    if ($LookupKey -match "QUALCOMM|QTI|QCOM") {
        switch -Regex ($LookupKey) {
            "SM8550|KALAMA" {
                $DeviceGroup = "Qualcomm_SM8550"
                $ModelName = "embeddinggemma-2-740m_Qualcomm_SM8550.litertlm"
            }
            "SM8650|PINEAPPLE" {
                $DeviceGroup = "Qualcomm_SM8650"
                $ModelName = "embeddinggemma-2-740m_Qualcomm_SM8650.litertlm"
            }
            "SM8750|SUN" {
                $DeviceGroup = "Qualcomm_SM8750"
                $ModelName = "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm"
            }
            "SM8850" {
                $DeviceGroup = "Qualcomm_SM8850"
                $ModelName = "embeddinggemma-2-740m_Qualcomm_SM8850.litertlm"
            }
            default {
                $DeviceGroup = ""
                $ModelName = "embeddinggemma-2-740m.litertlm"
            }
        }
    } elseif ($LookupKey -match "MEDIATEK|MT69") {
        switch -Regex ($LookupKey) {
            "MT6991|6991" {
                $DeviceGroup = "Mediatek_MT6991"
                $ModelName = "embeddinggemma-2-740m_MediaTek_MT6991.litertlm"
            }
            "MT6993|6993" {
                $DeviceGroup = "Mediatek_MT6993"
                $ModelName = "embeddinggemma-2-740m_MediaTek_MT6993.litertlm"
            }
            default {
                $DeviceGroup = ""
                $ModelName = "embeddinggemma-2-740m.litertlm"
            }
        }
    } elseif ($LookupKey -match "GOOGLE|TENSOR") {
        switch -Regex ($LookupKey) {
            "TENSOR G5|LAGUNA|G5" {
                $DeviceGroup = "Google_Tensor_G5"
                $ModelName = "embeddinggemma-2-740m_Google_Tensor_G5.litertlm"
            }
            "TENSOR G6|G6" {
                $DeviceGroup = "Google_Tensor_G6"
                $ModelName = "embeddinggemma-2-740m_Google_Tensor_G6.litertlm"
            }
            default {
                $DeviceGroup = ""
                $ModelName = "embeddinggemma-2-740m.litertlm"
            }
        }
    }
}

if ($DeviceGroup) {
    Write-Host "      Matched Device Group: '$DeviceGroup' (NPU split APKs will be installed)" -ForegroundColor Green
    Write-Host "      Target NPU Model:     '$ModelName'" -ForegroundColor Green
} else {
    Write-Host "      No specific NPU Device Group matched (Device will receive base APK with CPU/GPU fallback)" -ForegroundColor Yellow
    Write-Host "      Target Base Model:    '$ModelName'" -ForegroundColor Yellow
}

# 4. Build App Bundle
$BuildType = if ($Release) { "Release" } else { "Debug" }
$BuildTypeLower = $BuildType.ToLower()
$AabPath = Join-Path $ScriptDir "app\build\outputs\bundle\$BuildTypeLower\app-$BuildTypeLower.aab"
$ApksPath = Join-Path $ScriptDir "app\build\outputs\bundle\$BuildTypeLower\app-$BuildTypeLower.apks"

function Test-AabContainsAbi([string]$BundleFile, [string]$Abi) {
    if (-not (Test-Path $BundleFile)) { return $false }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Zip = [System.IO.Compression.ZipFile]::OpenRead($BundleFile)
    try {
        $Entry = $Zip.Entries | Where-Object { $_.FullName -like "base/lib/$Abi/*" } | Select-Object -First 1
        return ($null -ne $Entry)
    } finally {
        $Zip.Dispose()
    }
}

$NeedsBuild = (-not $SkipBuild)
if ($SkipBuild) {
    if (-not (Test-Path $AabPath)) {
        Write-Host "[4/8] Existing AAB not found at $AabPath; building bundle..." -ForegroundColor Yellow
        $NeedsBuild = $true
    } elseif (-not (Test-AabContainsAbi -BundleFile $AabPath -Abi $DeviceAbi)) {
        Write-Host "[4/8] Existing AAB at $AabPath lacks native libraries for ABI '$DeviceAbi'; rebuilding bundle..." -ForegroundColor Yellow
        $NeedsBuild = $true
    } else {
        Write-Host "[4/8] Skipping build step (reusing existing '$DeviceAbi' AAB at $AabPath)..." -ForegroundColor DarkCyan
    }
}

if ($NeedsBuild) {
    Write-Host "[4/8] Building Android App Bundle (:$BuildType, targetAbi=$DeviceAbi)..." -ForegroundColor Yellow
    $Gradletask = ":app:bundle$BuildType"
    $GradleCmd = if ($IsWindows -or $env:OS -match "Windows") { ".\gradlew.bat" } else { "./gradlew" }
    
    & $GradleCmd $Gradletask "-PtargetAbi=$DeviceAbi"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle build failed with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path $AabPath)) {
    Write-Error "Bundle file not found at $AabPath"
}

# 5. Generate APKs with bundletool
Write-Host "[5/8] Generating split APKs via bundletool..." -ForegroundColor Yellow
& java -jar "$BundletoolJar" build-apks --bundle="$AabPath" --output="$ApksPath" --overwrite
if ($LASTEXITCODE -ne 0) {
    Write-Error "bundletool build-apks failed"
}

# 6. Install onto device
Write-Host "[6/8] Installing matching APK splits to device $DeviceId..." -ForegroundColor Yellow
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

# 7. Model Staging Check and Push
if (-not $SkipModelPush) {
    Write-Host "[7/8] Ensuring LiteRT-LM model ($ModelName) is present on device..." -ForegroundColor Yellow
    
    # Check if target model is already staged in /data/local/tmp on the device and non-empty
    $DeviceCheckOutput = @(adb -s $DeviceId shell "if [ -f /data/local/tmp/$ModelName ] && [ -s /data/local/tmp/$ModelName ]; then echo EXISTS; fi")
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Could not check for the model on device '$DeviceId'."
    }
    $DeviceCheck = ($DeviceCheckOutput -join "`n").Trim()
    
    if ($DeviceCheck -eq "EXISTS") {
        Write-Host "      Model '$ModelName' is already present on device (/data/local/tmp)." -ForegroundColor Green
    } else {
        $RepoRoot = Split-Path -Parent $ScriptDir
        $LocalModelsDir = Join-Path $RepoRoot ".local\models"
        if (-not (Test-Path $LocalModelsDir)) {
            New-Item -ItemType Directory -Path $LocalModelsDir | Out-Null
        }
        $CachedModelFile = Join-Path $LocalModelsDir $ModelName

        # If model is not in local cache, download from Hugging Face
        if (-not (Test-Path $CachedModelFile) -or (Get-Item $CachedModelFile).Length -eq 0) {
            Write-Host "      Model not found in local cache. Downloading '$ModelName' from Hugging Face..." -ForegroundColor Yellow
            $HfUrl = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/$ModelName"
            $DownloadOk = $false

            if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
                Write-Host "      Downloading via curl: $HfUrl" -ForegroundColor DarkCyan
                & curl.exe -L --fail --progress-bar -o "$CachedModelFile" "$HfUrl"
                if ($LASTEXITCODE -eq 0 -and (Test-Path $CachedModelFile) -and (Get-Item $CachedModelFile).Length -gt 0) {
                    $DownloadOk = $true
                } else {
                    if (Test-Path $CachedModelFile) { Remove-Item $CachedModelFile -Force }
                }
            }

            if (-not $DownloadOk) {
                try {
                    Write-Host "      Downloading via Invoke-WebRequest: $HfUrl" -ForegroundColor DarkCyan
                    Invoke-WebRequest -Uri $HfUrl -OutFile $CachedModelFile -UseBasicParsing
                    if ((Test-Path $CachedModelFile) -and (Get-Item $CachedModelFile).Length -gt 0) {
                        $DownloadOk = $true
                    }
                } catch {
                    if (Test-Path $CachedModelFile) { Remove-Item $CachedModelFile -Force }
                    Write-Warning "Could not download $ModelName ($HfUrl): $_"
                }
            }

            # If NPU model failed to download, attempt fallback to universal base model
            if (-not $DownloadOk -and $ModelName -ne "embeddinggemma-2-740m.litertlm") {
                Write-Host "      Attempting fallback to universal model 'embeddinggemma-2-740m.litertlm'..." -ForegroundColor Yellow
                $FallbackModel = "embeddinggemma-2-740m.litertlm"
                $CachedFallback = Join-Path $LocalModelsDir $FallbackModel
                if (-not (Test-Path $CachedFallback) -or (Get-Item $CachedFallback).Length -eq 0) {
                    $FallbackUrl = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/$FallbackModel"
                    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
                        & curl.exe -L --fail --progress-bar -o "$CachedFallback" "$FallbackUrl"
                    }
                }
                if ((Test-Path $CachedFallback) -and (Get-Item $CachedFallback).Length -gt 0) {
                    $ModelName = $FallbackModel
                    $CachedModelFile = $CachedFallback
                }
            }
        }

        # Push to device if cached model file is available
        if ((Test-Path $CachedModelFile) -and (Get-Item $CachedModelFile).Length -gt 0) {
            Write-Host "      Pushing '$ModelName' to device (/data/local/tmp/)..." -ForegroundColor Yellow
            adb -s $DeviceId push "$CachedModelFile" "/data/local/tmp/$ModelName"
            if ($LASTEXITCODE -eq 0) {
                Write-Host "      Model successfully pushed to /data/local/tmp/$ModelName" -ForegroundColor Green
            } else {
                Write-Warning "adb push failed with exit code $LASTEXITCODE"
            }
        } else {
            Write-Warning "Model file could not be obtained. The app will search for existing local files or Play AI Pack on startup."
        }
    }
} else {
    Write-Host "[7/8] Skipping model staging (-SkipModelPush specified)..." -ForegroundColor DarkCyan
}

# Stop any running instance so startup logs and preferences are cleanly applied
adb -s $DeviceId shell am force-stop com.localfind

# Do not let a persisted NPU/GPU selection route an emulator into an unavailable native delegate.
if ($IsEmulatorTarget -and -not $Release) {
    $PrefsTemp = Join-Path $env:TEMP "localfind_settings.xml"
    $PrefsDir = "shared_prefs"
    $PrefsPath = "$PrefsDir/localfind_settings.xml"
    Remove-Item $PrefsTemp -Force -ErrorAction SilentlyContinue
    $PrefsExists = "$(adb -s $DeviceId shell "run-as com.localfind sh -c 'if [ -f $PrefsPath ]; then echo YES; fi'")".Trim()
    if ($PrefsExists -eq "YES") {
        adb -s $DeviceId exec-out run-as com.localfind cat $PrefsPath > $PrefsTemp
    }
    if (-not (Test-Path $PrefsTemp) -or (Get-Item $PrefsTemp).Length -eq 0) {
        @'
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map />
'@ | Set-Content -Path $PrefsTemp -Encoding utf8
    }
    try {
        [xml]$PrefsXml = Get-Content -Raw -Path $PrefsTemp
        $Accelerator = $PrefsXml.map.string | Where-Object { $_.name -eq "hardware_accelerator" }
        if ($null -eq $Accelerator) {
            $Accelerator = $PrefsXml.CreateElement("string")
            $Accelerator.SetAttribute("name", "hardware_accelerator")
            [void]$PrefsXml.DocumentElement.AppendChild($Accelerator)
        }
        $Accelerator.InnerText = "CPU"
        $PrefsXml.Save($PrefsTemp)
        adb -s $DeviceId push $PrefsTemp "/data/local/tmp/localfind_settings.xml" | Out-Null
        adb -s $DeviceId shell "run-as com.localfind mkdir -p $PrefsDir && run-as com.localfind cp /data/local/tmp/localfind_settings.xml $PrefsPath && rm -f /data/local/tmp/localfind_settings.xml"
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Could not apply the emulator CPU accelerator preference."
        }
    } finally {
        Remove-Item $PrefsTemp -Force -ErrorAction SilentlyContinue
    }
}

# 8. Launch App and Verify Model Load
Write-Host "[8/8] Launching com.localfind and verifying model is loaded..." -ForegroundColor Cyan
adb -s $DeviceId logcat -c
adb -s $DeviceId shell am start -n com.localfind/.MainActivity | Out-Null

$TimeoutSeconds = 45
$Elapsed = 0
$ModelLoaded = $false
$LoadError = ""

while ($Elapsed -lt $TimeoutSeconds) {
    Start-Sleep -Seconds 1
    $Elapsed++
    $Logs = @(adb -s $DeviceId logcat -d -s SocDetector:I ModelFiles:I Embedder:I EngineManager:I AndroidRuntime:E DEBUG:F)
    $LogsText = $Logs -join "`n"

    if ($LogsText -match "Embedding model successfully loaded\.") {
        $ModelLoaded = $true
        foreach ($line in $Logs) {
            if ($line -match "SocDetector:|ModelFiles:|Embedder:|EngineManager:") {
                Write-Host "      $($line.Trim())" -ForegroundColor Green
            }
        }
        break
    }

    if ($LogsText -match "Failed to initialize Embedder|Embedding model not found|FATAL EXCEPTION|Fatal signal") {
        $LoadError = ($Logs | Where-Object { $_ -match "SocDetector:|ModelFiles:|Embedder:|EngineManager:|FATAL EXCEPTION|Fatal signal" }) -join "`n"
        break
    }
}

if (-not $ModelLoaded) {
    if ($SkipModelPush -and $LoadError -match "Embedding model not found") {
        Write-Warning "App launched, but model is not present on device (-SkipModelPush was specified)."
    } elseif ($LoadError) {
        Write-Error "Model failed to load on ${DeviceId}:`n$LoadError"
    } else {
        Write-Error "Timed out after ${TimeoutSeconds}s waiting for embedding model to load on $DeviceId."
    }
}

Write-Host "`nDeployment and model verification completed successfully!" -ForegroundColor Green
Write-Host "To monitor inference logs in realtime, run:" -ForegroundColor DarkGray
Write-Host "adb -s $DeviceId logcat -c; adb -s $DeviceId logcat -s UniversalEmbedder:D LocalFind:D SocDetector:D ModelFiles:D`n" -ForegroundColor DarkGray
