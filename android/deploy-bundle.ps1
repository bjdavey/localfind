<#
.SYNOPSIS
    Automated Play Feature Delivery & Play AI Pack APK deployment script using bundletool.
    Simulates Google Play Store install-time dynamic delivery for LiteRT NPU hardware acceleration
    and automatically downloads, stages, and verifies the matching on-device LiteRT-LM model.

.DESCRIPTION
    1. Downloads standalone bundletool.jar (if not already cached in android/.tools/).
    2. Identifies connected target devices via ADB (interactive menu, -DeviceId, or -All).
    3. Queries device SoC (ro.soc.manufacturer & ro.soc.model) to determine conditional Device Group and target model file.
    4. Builds the Android App Bundle (.aab) via Gradle for the target ABI (reusing if cached AAB matches).
    5. Extracts split APKs with bundletool targeting the specific Device Group.
    6. Installs base and matching NPU runtime splits (HTP/v73/v75/v79/v81 or MediaTek/Tensor) onto the device.
    7. Verifies and stages the matching LiteRT-LM model to /data/local/tmp/ (downloading from Hugging Face if needed).
    8. Launches com.localfind/.MainActivity and verifies that the embedding model loads successfully.

.PARAMETER DeviceId
    Optional ADB serial / transport ID of the target device, or 'all' to target all connected devices.
    If omitted and -All is not specified, an interactive menu is displayed.

.PARAMETER All
    Deploy to all connected ADB devices in 'device' state.

.PARAMETER Release
    Build and install release bundle instead of debug.

.PARAMETER SkipBuild
    Skip Gradle bundle task and reuse existing .aab file (unless its ABI does not match the target device).

.PARAMETER SkipModelPush
    Skip checking, downloading, and pushing the LiteRT-LM model to the device.

.EXAMPLE
    .\deploy-bundle.ps1
    .\deploy-bundle.ps1 -DeviceId "emulator-5554"
    .\deploy-bundle.ps1 -All
    .\deploy-bundle.ps1 -Release
    .\deploy-bundle.ps1 -SkipModelPush
#>

param(
    [string]$DeviceId = "",
    [switch]$All,
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

# 2. Select Device(s)
Write-Host "[2/8] Detecting ADB devices..." -ForegroundColor Yellow
$AdbOutput = adb devices -l
$AvailableDevices = @()

foreach ($line in $AdbOutput) {
    if ($line -match '^([^\s]+)\s+device\b(.*)$') {
        $serial = $matches[1].Trim()
        $extra = $matches[2]

        # Extract model from adb devices -l, fallback to getprop ro.product.model
        $model = ""
        if ($extra -match 'model:([^\s]+)') {
            $model = $matches[1].Trim()
        }
        $propModel = (adb -s $serial shell getprop ro.product.model 2>$null).Trim()
        if (-not [string]::IsNullOrWhiteSpace($propModel)) {
            $model = $propModel
        }

        # Query device ABI
        $abi = (adb -s $serial shell getprop ro.product.cpu.abi 2>$null).Trim()
        if ([string]::IsNullOrWhiteSpace($abi)) {
            $abi = "arm64-v8a"
        }

        # Query whether this is an emulator
        $qemu = (adb -s $serial shell getprop ro.kernel.qemu 2>$null).Trim()
        $hw = (adb -s $serial shell getprop ro.hardware 2>$null).Trim()
        $isEmulator = ($qemu -eq "1") -or ($hw -match "goldfish|ranchu") -or ($serial -match "^emulator-")
        $typeStr = if ($isEmulator) { "Emulator" } else { "Physical" }

        $AvailableDevices += [PSCustomObject]@{
            Serial     = $serial
            Model      = if ($model) { $model } else { "Unknown" }
            Abi        = $abi
            IsEmulator = $isEmulator
            Type       = $typeStr
        }
    }
}

if ($AvailableDevices.Count -eq 0) {
    Write-Error "No connected ADB devices in 'device' state found. Please connect a device or launch an emulator."
    exit 1
}

# Determine target devices (interactive menu if not explicitly specified via CLI)
$TargetDevices = @()

if ($All -or ($DeviceId -ieq "all")) {
    $TargetDevices = @($AvailableDevices)
} elseif (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
    $Matched = $AvailableDevices | Where-Object { $_.Serial -eq $DeviceId }
    if ($Matched) {
        $TargetDevices = @($Matched)
    } else {
        Write-Warning "Specified DeviceId '$DeviceId' was not detected in active devices list. Attempting to target it directly."
        $TargetDevices = @([PSCustomObject]@{
            Serial     = $DeviceId
            Model      = "Custom Device"
            Abi        = "arm64-v8a"
            IsEmulator = ($DeviceId -match "^emulator-")
            Type       = if ($DeviceId -match "^emulator-") { "Emulator" } else { "Physical" }
        })
    }
} else {
    Write-Host "`nConnected ADB devices:" -ForegroundColor Cyan
    for ($i = 0; $i -lt $AvailableDevices.Count; $i++) {
        $dev = $AvailableDevices[$i]
        $num = $i + 1
        Write-Host "  [$num] $($dev.Serial) - $($dev.Model) ($($dev.Abi), $($dev.Type))" -ForegroundColor White
    }
    Write-Host "  [a] All devices ($($AvailableDevices.Count))`n" -ForegroundColor White

    $IsInteractive = $true
    try {
        if ([Console]::IsInputRedirected) {
            $IsInteractive = $false
        }
    } catch {
        $IsInteractive = $false
    }

    if (-not $IsInteractive) {
        Write-Host "Non-interactive environment detected; choosing default (first device: $($AvailableDevices[0].Serial))." -ForegroundColor Yellow
        $TargetDevices = @($AvailableDevices[0])
    } else {
        $PromptText = if ($AvailableDevices.Count -eq 1) {
            "Select device [1, 'a' for all] (default: 1): "
        } else {
            "Select device(s) [1-$($AvailableDevices.Count), 'a' for all] (default: 1): "
        }
        $Choice = Read-Host $PromptText
        $Choice = $Choice.Trim()

        if ([string]::IsNullOrWhiteSpace($Choice) -or $Choice -eq "1") {
            $TargetDevices = @($AvailableDevices[0])
        } elseif ($Choice -match "^(a|all|\*)$") {
            $TargetDevices = @($AvailableDevices)
        } else {
            # Support multiple comma/space separated numbers (e.g. "1, 2" or "2")
            $SelectedIndices = $Choice -split "[, ]+" | Where-Object { $_ -match "^\d+$" }
            foreach ($idx in $SelectedIndices) {
                $num = [int]$idx
                if ($num -ge 1 -and $num -le $AvailableDevices.Count) {
                    $TargetDevices += $AvailableDevices[$num - 1]
                }
            }
            if ($TargetDevices.Count -eq 0) {
                Write-Warning "Invalid choice '$Choice'. Defaulting to first device: $($AvailableDevices[0].Serial)"
                $TargetDevices = @($AvailableDevices[0])
            }
        }
    }
}

# Sort devices by ABI so devices sharing the same ABI are deployed consecutively,
# allowing reuse of the compiled AAB and split APKs without redundant rebuilding.
$TargetDevices = @($TargetDevices | Sort-Object -Property Abi)

# Paths for App Bundle & APKs
$BuildType = if ($Release) { "Release" } else { "Debug" }
$BuildTypeLower = $BuildType.ToLower()
$AabPath = Join-Path $ScriptDir "app\build\outputs\bundle\$BuildTypeLower\app-$BuildTypeLower.aab"
$ApksPath = Join-Path $ScriptDir "app\build\outputs\bundle\$BuildTypeLower\app-$BuildTypeLower.apks"

function Test-AabContainsAbi([string]$BundleFile, [string]$Abi) {
    if (-not (Test-Path $BundleFile)) { return $false }
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $Zip = [System.IO.Compression.ZipFile]::OpenRead($BundleFile)
    try {
        $Entry = $Zip.Entries | Where-Object { $_.FullName -like "base/lib/$Abi/*" } | Select-Object -First 1
        return ($null -ne $Entry)
    } finally {
        $Zip.Dispose()
    }
}

$LastBuiltAbi = ""
$LastApksAbi = ""
$Results = @()
$CurrentDevIndex = 0
$TotalDevs = $TargetDevices.Count

foreach ($Target in $TargetDevices) {
    $CurrentDevIndex++
    $DeviceId = $Target.Serial
    $DeviceAbi = $Target.Abi
    $IsEmulatorTarget = $Target.IsEmulator

    Write-Host "`n==========================================================" -ForegroundColor Cyan
    Write-Host ">>> Deploying target [$CurrentDevIndex/$TotalDevs]: $DeviceId ($($Target.Model))" -ForegroundColor Cyan
    Write-Host "==========================================================" -ForegroundColor Cyan
    Write-Host "      Target device: $DeviceId (ABI: $DeviceAbi)" -ForegroundColor Green

    if ($IsEmulatorTarget) {
        Write-Host "      Emulator target detected: forcing the persisted accelerator preference to CPU." -ForegroundColor Yellow
    }

    try {
        # 3. Query Device SoC and match Device Group & LiteRT Model
        Write-Host "[3/8] Querying device SoC for Play Feature Delivery & Model targeting..." -ForegroundColor Yellow
        $SocMfr = "$(adb -s $DeviceId shell getprop ro.soc.manufacturer 2>$null)".Trim()
        $SocModel = "$(adb -s $DeviceId shell getprop ro.soc.model 2>$null)".Trim()
        $Platform = "$(adb -s $DeviceId shell getprop ro.board.platform 2>$null)".Trim()
        $Board = "$(adb -s $DeviceId shell getprop ro.product.board 2>$null)".Trim()
        $Hardware = "$(adb -s $DeviceId shell getprop ro.hardware 2>$null)".Trim()

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

        # The Qualcomm HTP runtime libraries are git-ignored; without them the NPU split APKs are empty and
        # the app silently falls back to CPU/GPU. Fetch them (once) before building the bundle.
        if ($DeviceGroup -match "^Qualcomm_SM(\d+)") {
            $HtpVersion = @{ "8550" = "73"; "8650" = "75"; "8750" = "79"; "8850" = "81" }[$Matches[1]]
            $HtpStub = Join-Path $ScriptDir "litert_npu_runtime_libraries\qualcomm_runtime_v$HtpVersion\src\main\jni\arm64-v8a\libQnnHtpV${HtpVersion}Stub.so"
            if (-not (Test-Path $HtpStub)) {
                Write-Host "      Qualcomm NPU runtime libraries missing; running fetch_qualcomm_library.sh (~2.4 GB download)..." -ForegroundColor Yellow
                # Use Git for Windows' bash: plain 'bash' may resolve to the WSL launcher in System32.
                $GitBash = Join-Path (Split-Path (Split-Path (Get-Command git).Source)) "bin\bash.exe"
                if (-not (Test-Path $GitBash)) { $GitBash = "bash" }
                & $GitBash (Join-Path $ScriptDir "litert_npu_runtime_libraries/fetch_qualcomm_library.sh")
                if ($LASTEXITCODE -ne 0) {
                    throw "fetch_qualcomm_library.sh failed with exit code $LASTEXITCODE"
                }
            }
        }

        # Same for the MediaTek NeuroPilot adapter libraries (the fetch script is idempotent).
        if ($DeviceGroup -match "^Mediatek_") {
            $MtkLib = Join-Path $ScriptDir "litert_npu_runtime_libraries\mediatek_runtime_v8\src\main\jni\arm64-v8a\libneuronusdk_adapter.mtk.so"
            if (-not (Test-Path $MtkLib)) {
                Write-Host "      MediaTek NPU runtime libraries missing; running fetch_mediatek_library.sh..." -ForegroundColor Yellow
                $GitBash = Join-Path (Split-Path (Split-Path (Get-Command git).Source)) "bin\bash.exe"
                if (-not (Test-Path $GitBash)) { $GitBash = "bash" }
                & $GitBash (Join-Path $ScriptDir "litert_npu_runtime_libraries/fetch_mediatek_library.sh")
                if ($LASTEXITCODE -ne 0) {
                    throw "fetch_mediatek_library.sh failed with exit code $LASTEXITCODE"
                }
            }
        }

        # 4. Build App Bundle
        $NeedsBuild = $false
        if ($LastBuiltAbi -eq $DeviceAbi -and (Test-Path $AabPath) -and (Test-AabContainsAbi -BundleFile $AabPath -Abi $DeviceAbi)) {
            Write-Host "[4/8] Reusing AAB built earlier in this session for ABI '$DeviceAbi'..." -ForegroundColor DarkCyan
        } elseif ($SkipBuild) {
            if (-not (Test-Path $AabPath)) {
                Write-Host "[4/8] Existing AAB not found at $AabPath; building bundle..." -ForegroundColor Yellow
                $NeedsBuild = $true
            } elseif (-not (Test-AabContainsAbi -BundleFile $AabPath -Abi $DeviceAbi)) {
                Write-Host "[4/8] Existing AAB at $AabPath lacks native libraries for ABI '$DeviceAbi'; rebuilding bundle..." -ForegroundColor Yellow
                $NeedsBuild = $true
            } else {
                Write-Host "[4/8] Skipping build step (reusing existing '$DeviceAbi' AAB at $AabPath)..." -ForegroundColor DarkCyan
            }
        } else {
            $NeedsBuild = $true
        }

        if ($NeedsBuild) {
            Write-Host "[4/8] Building Android App Bundle (:$BuildType, targetAbi=$DeviceAbi)..." -ForegroundColor Yellow
            $Gradletask = ":app:bundle$BuildType"
            $GradleCmd = if ($IsWindows -or $env:OS -match "Windows") { ".\gradlew.bat" } else { "./gradlew" }

            & $GradleCmd $Gradletask "-PtargetAbi=$DeviceAbi"
            if ($LASTEXITCODE -ne 0) {
                throw "Gradle build failed with exit code $LASTEXITCODE"
            }
            $LastBuiltAbi = $DeviceAbi
        }

        if (-not (Test-Path $AabPath)) {
            throw "Bundle file not found at $AabPath"
        }

        # 5. Generate APKs with bundletool
        if ($LastApksAbi -eq $DeviceAbi -and (Test-Path $ApksPath) -and (-not $NeedsBuild)) {
            Write-Host "[5/8] Reusing split APKs generated earlier in this session for ABI '$DeviceAbi'..." -ForegroundColor DarkCyan
        } else {
            Write-Host "[5/8] Generating split APKs via bundletool..." -ForegroundColor Yellow
            $BuildApksArgs = @(
                "-jar", "$BundletoolJar",
                "build-apks",
                "--bundle=$AabPath",
                "--output=$ApksPath",
                "--overwrite"
            )

            # For debug builds, sign with the standard Android debug keystore so instrumentation tests
            # and debuggers (which share ~/.android/debug.keystore) match the installed package signature.
            $CandidateKeystores = @(
                "$env:ANDROID_USER_HOME\.android\debug.keystore",
                "E:\packages\.android\debug.keystore",
                "$env:USERPROFILE\.android\debug.keystore",
                "$env:HOME\.android\debug.keystore"
            )
            $DebugKeystore = $CandidateKeystores | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path $_) } | Select-Object -First 1
            if (-not $Release -and $DebugKeystore) {
                Write-Host "      Signing split APKs with debug keystore: $DebugKeystore" -ForegroundColor DarkCyan
                $BuildApksArgs += @(
                    "--ks=$DebugKeystore",
                    "--ks-pass=pass:android",
                    "--ks-key-alias=androiddebugkey",
                    "--key-pass=pass:android"
                )
            }

            & java $BuildApksArgs
            if ($LASTEXITCODE -ne 0) {
                throw "bundletool build-apks failed"
            }
            $LastApksAbi = $DeviceAbi
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
                throw "bundletool install-apks failed after reinstall attempt."
            }
        }

        Write-Host "Split APKs successfully installed!" -ForegroundColor Green

        # 7. Model Staging Check and Push
        if (-not $SkipModelPush) {
            Write-Host "[7/8] Ensuring LiteRT-LM model ($ModelName) is present on device..." -ForegroundColor Yellow

            $DeviceCheckOutput = @(adb -s $DeviceId shell "if [ -f /data/local/tmp/$ModelName ] && [ -s /data/local/tmp/$ModelName ]; then echo EXISTS; fi")
            if ($LASTEXITCODE -ne 0) {
                throw "Could not check for the model on device '$DeviceId'."
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

        # Force CPU preference on emulators to avoid routing into unavailable native delegates
        if ($IsEmulatorTarget -and -not $Release) {
            $PrefsTemp = Join-Path $env:TEMP "localfind_settings_$DeviceId.xml"
            $PrefsDir = "shared_prefs"
            $PrefsPath = "$PrefsDir/localfind_settings.xml"
            Remove-Item $PrefsTemp -Force -ErrorAction SilentlyContinue
            $PrefsExists = "$(adb -s $DeviceId shell "run-as com.localfind sh -c 'if [ -f $PrefsPath ]; then echo YES; fi'" 2>$null)".Trim()
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
                    throw "Could not apply the emulator CPU accelerator preference."
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
                $Results += [PSCustomObject]@{ DeviceId = $DeviceId; Success = $true; Message = "Launched (model check skipped)" }
            } elseif ($LoadError) {
                throw "Model failed to load on ${DeviceId}:`n$LoadError"
            } else {
                throw "Timed out after ${TimeoutSeconds}s waiting for embedding model to load on $DeviceId."
            }
        } else {
            $Results += [PSCustomObject]@{ DeviceId = $DeviceId; Success = $true; Message = "Split APKs installed & model verified" }
        }

    } catch {
        $ErrMsg = $_.Exception.Message
        Write-Host "Deployment error on ${DeviceId}: $ErrMsg" -ForegroundColor Red
        $Results += [PSCustomObject]@{ DeviceId = $DeviceId; Success = $false; Message = $ErrMsg }
    }
}

# Summary Report
Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host " Deployment Summary ($($Results.Where({$_.Success}).Count)/$TotalDevs succeeded)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
$AnyFailed = $false
foreach ($res in $Results) {
    if ($res.Success) {
        Write-Host "  [PASS] $($res.DeviceId) - $($res.Message)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $($res.DeviceId) - $($res.Message)" -ForegroundColor Red
        $AnyFailed = $true
    }
}
Write-Host "==========================================================" -ForegroundColor Cyan

if ($AnyFailed) {
    exit 1
}

Write-Host "`nAll deployments and model verifications completed successfully!" -ForegroundColor Green
Write-Host "To monitor inference logs in realtime, run:" -ForegroundColor DarkGray
Write-Host "adb logcat -s UniversalEmbedder:D LocalFind:D SocDetector:D ModelFiles:D`n" -ForegroundColor DarkGray
