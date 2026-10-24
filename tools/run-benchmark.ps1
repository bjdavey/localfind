<#
.SYNOPSIS
    Automated CPU, GPU, and NPU Video Indexing Benchmark Runner for LocalFind.

.DESCRIPTION
    1. Detects connected ADB devices and prompts user to choose a target device.
    2. Prompts user to enter a video file path (defaulting to .local\test_files\A_Perfect_Planet_-_Down_To_Earth_-_S1-E18.webm).
    3. Invokes android/deploy-bundle.ps1 to build and deploy split APKs + LiteRT models via bundletool.
    4. Pushes the chosen test video to the device (/data/local/tmp/).
    5. Builds and installs the androidTest APK (CpuGpuNpuBenchmarkTest).
    6. Executes the benchmark suite (am instrument) measuring CPU vs GPU vs NPU throughput, latency, and thermals.
    7. Extracts the benchmark markdown report and saves it to .local/benchmarks/ as a dated markdown file (and appends to benchmark_summary.md).

.PARAMETER DeviceId
    ADB serial ID of the target device. If omitted, an interactive menu is displayed.

.PARAMETER VideoPath
    Full or relative path to the video to benchmark.
    Defaults to: .local\test_files\A_Perfect_Planet_-_Down_To_Earth_-_S1-E18.webm

.PARAMETER SkipDeploy
    Skip calling deploy-bundle.ps1 if the app and models are already deployed.

.PARAMETER SkipBuild
    Pass -SkipBuild to deploy-bundle.ps1 to reuse existing bundles and APKs.

.PARAMETER Release
    Deploy release bundle instead of debug.

.EXAMPLE
    .\tools\run-benchmark.ps1
    .\tools\run-benchmark.ps1 -VideoPath ".local\test_files\Girls_Door.mp4" -SkipDeploy
    .\tools\run-benchmark.ps1 -DeviceId "emulator-5554" -VideoPath "C:\Videos\sample.mp4"
#>

param(
    [string]$DeviceId = "",
    [Alias("VideoName", "Video")]
    [string]$VideoPath = "",
    [switch]$SkipDeploy,
    [switch]$SkipBuild,
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

# Determine RepoRoot and key paths
$RepoRoot = if (Test-Path (Join-Path $ScriptDir "android\deploy-bundle.ps1")) {
    $ScriptDir
} else {
    Split-Path -Parent $ScriptDir
}

$AndroidDir = Join-Path $RepoRoot "android"
$LocalDir = Join-Path $RepoRoot ".local"
$TestFilesDir = Join-Path $LocalDir "test_files"
$BenchmarksDir = Join-Path $LocalDir "benchmarks"
$DeployScript = Join-Path $AndroidDir "deploy-bundle.ps1"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " LocalFind - Hardware Benchmark Runner (CPU vs GPU vs NPU) " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Check if environment is interactive
$IsInteractive = $true
try {
    if ([Console]::IsInputRedirected) {
        $IsInteractive = $false
    }
} catch {
    $IsInteractive = $false
}

# -----------------------------------------------------------------------------
# 1. Device Selection
# -----------------------------------------------------------------------------
Write-Host "`n[1/6] Detecting connected ADB devices..." -ForegroundColor Yellow
$AdbRaw = adb devices -l
$AvailableDevices = @()

foreach ($line in $AdbRaw) {
    if ($line -match '^([^\s]+)\s+device\b(.*)$') {
        $serial = $matches[1].Trim()
        $extra = $matches[2]

        $model = ""
        if ($extra -match 'model:([^\s]+)') {
            $model = $matches[1].Trim()
        }
        $propModel = (adb -s $serial shell getprop ro.product.model 2>$null).Trim()
        if (-not [string]::IsNullOrWhiteSpace($propModel)) {
            $model = $propModel
        }

        $abi = (adb -s $serial shell getprop ro.product.cpu.abi 2>$null).Trim()
        if ([string]::IsNullOrWhiteSpace($abi)) {
            $abi = "arm64-v8a"
        }

        $qemu = (adb -s $serial shell getprop ro.kernel.qemu 2>$null).Trim()
        $hw = (adb -s $serial shell getprop ro.hardware 2>$null).Trim()
        $isEmulator = ($qemu -eq "1") -or ($hw -match "goldfish|ranchu") -or ($serial -match "^emulator-")

        $AvailableDevices += [PSCustomObject]@{
            Serial     = $serial
            Model      = if ($model) { $model } else { "Unknown Device" }
            Abi        = $abi
            IsEmulator = $isEmulator
            Type       = if ($isEmulator) { "Emulator" } else { "Physical" }
        }
    }
}

if ($AvailableDevices.Count -eq 0) {
    Write-Error "No connected ADB devices in 'device' state found. Please connect a device or start an emulator."
    exit 1
}

$SelectedDevice = $null

if (-not [string]::IsNullOrWhiteSpace($DeviceId)) {
    $Matched = $AvailableDevices | Where-Object { $_.Serial -eq $DeviceId }
    if ($Matched) {
        $SelectedDevice = $Matched
    } else {
        Write-Warning "Specified DeviceId '$DeviceId' was not detected in active devices list. Targeting directly."
        $SelectedDevice = [PSCustomObject]@{
            Serial     = $DeviceId
            Model      = "Custom Device"
            Abi        = "arm64-v8a"
            IsEmulator = ($DeviceId -match "^emulator-")
            Type       = if ($DeviceId -match "^emulator-") { "Emulator" } else { "Physical" }
        }
    }
} elseif ($AvailableDevices.Count -eq 1 -or -not $IsInteractive) {
    $SelectedDevice = $AvailableDevices[0]
    Write-Host "      Auto-selected target device: $($SelectedDevice.Serial) ($($SelectedDevice.Model))" -ForegroundColor Green
} else {
    Write-Host "`nConnected Devices:" -ForegroundColor Cyan
    for ($i = 0; $i -lt $AvailableDevices.Count; $i++) {
        $dev = $AvailableDevices[$i]
        $idx = $i + 1
        Write-Host "  [$idx] $($dev.Serial) - $($dev.Model) ($($dev.Abi), $($dev.Type))" -ForegroundColor White
    }

    $Choice = Read-Host "Select device [1-$($AvailableDevices.Count)] (default: 1)"
    $Choice = $Choice.Trim()
    if ([string]::IsNullOrWhiteSpace($Choice) -or $Choice -eq "1") {
        $SelectedDevice = $AvailableDevices[0]
    } else {
        $num = [int]$Choice
        if ($num -ge 1 -and $num -le $AvailableDevices.Count) {
            $SelectedDevice = $AvailableDevices[$num - 1]
        } else {
            Write-Warning "Invalid selection '$Choice'. Defaulting to 1."
            $SelectedDevice = $AvailableDevices[0]
        }
    }
}

Write-Host "      Target Device: $($SelectedDevice.Serial) - $($SelectedDevice.Model) ($($SelectedDevice.Abi))" -ForegroundColor Green

# -----------------------------------------------------------------------------
# 2. Test Video Path Selection
# -----------------------------------------------------------------------------
Write-Host "`n[2/6] Configuring benchmark test video..." -ForegroundColor Yellow

$DefaultVideoRelPath = ".local\test_files\A_Perfect_Planet_-_Down_To_Earth_-_S1-E18.webm"
$DefaultVideoFullPath = Join-Path $RepoRoot $DefaultVideoRelPath

$ChosenVideoInput = $VideoPath

if ([string]::IsNullOrWhiteSpace($ChosenVideoInput)) {
    if (-not $IsInteractive) {
        $ChosenVideoInput = $DefaultVideoFullPath
        Write-Host "      Non-interactive: using default video path: $DefaultVideoRelPath" -ForegroundColor Green
    } else {
        $Prompt = "Enter full video path (default: $DefaultVideoRelPath): "
        $InputText = Read-Host $Prompt
        $InputText = $InputText.Trim().Trim('"').Trim("'")

        if ([string]::IsNullOrWhiteSpace($InputText)) {
            $ChosenVideoInput = $DefaultVideoFullPath
        } else {
            $ChosenVideoInput = $InputText
        }
    }
} else {
    $ChosenVideoInput = $ChosenVideoInput.Trim().Trim('"').Trim("'")
}

# Resolve path (handles absolute paths, paths relative to working dir, repo root, or test_files)
$ResolvedVideoPath = $null
if ([System.IO.Path]::IsPathRooted($ChosenVideoInput) -and (Test-Path $ChosenVideoInput)) {
    $ResolvedVideoPath = (Resolve-Path $ChosenVideoInput).Path
} elseif (Test-Path (Join-Path (Get-Location) $ChosenVideoInput)) {
    $ResolvedVideoPath = (Resolve-Path (Join-Path (Get-Location) $ChosenVideoInput)).Path
} elseif (Test-Path (Join-Path $RepoRoot $ChosenVideoInput)) {
    $ResolvedVideoPath = (Resolve-Path (Join-Path $RepoRoot $ChosenVideoInput)).Path
} elseif (Test-Path (Join-Path $TestFilesDir $ChosenVideoInput)) {
    $ResolvedVideoPath = (Resolve-Path (Join-Path $TestFilesDir $ChosenVideoInput)).Path
}

if (-not $ResolvedVideoPath -or -not (Test-Path $ResolvedVideoPath)) {
    Write-Error "Video file not found at '$ChosenVideoInput'. Please provide a valid file path."
    exit 1
}

$SelectedVideo = Get-Item $ResolvedVideoPath
$SelectedVideoSizeMb = [math]::Round($SelectedVideo.Length / 1MB, 2)
Write-Host "      Selected Video: $($SelectedVideo.Name) ($SelectedVideoSizeMb MB)" -ForegroundColor Green
Write-Host "      Source Path:    $($SelectedVideo.FullName)" -ForegroundColor DarkCyan

# -----------------------------------------------------------------------------
# 3. Deploy App via deploy-bundle.ps1
# -----------------------------------------------------------------------------
if (-not $PSBoundParameters.ContainsKey('SkipDeploy') -and $IsInteractive) {
    Write-Host "`nDeployment options:" -ForegroundColor Cyan
    Write-Host "  [1] Deploy app bundle & models via deploy-bundle.ps1 (Full fresh deploy)" -ForegroundColor White
    Write-Host "  [2] Skip deployment (Fast, reuse currently installed app & models)" -ForegroundColor White

    $DeployChoice = Read-Host "Select deployment option [1-2, or 's' to skip] (default: 1)"
    $DeployChoice = $DeployChoice.Trim().ToLower()
    if ($DeployChoice -eq "2" -or $DeployChoice -eq "s" -or $DeployChoice -match "^(skip|y|yes)$") {
        $SkipDeploy = $true
    } else {
        $SkipDeploy = $false
    }
}

if ($SkipDeploy) {
    Write-Host "`n[3/6] Skipping deploy (SkipDeploy selected)..." -ForegroundColor DarkCyan
} else {
    Write-Host "`n[3/6] Invoking deploy-bundle.ps1 for $($SelectedDevice.Serial)..." -ForegroundColor Yellow

    if (-not (Test-Path $DeployScript)) {
        Write-Error "Deployment script not found at $DeployScript"
        exit 1
    }

    $DeployParams = @{
        DeviceId = $SelectedDevice.Serial
    }
    if ($Release) { $DeployParams["Release"] = $true }
    if ($SkipBuild) { $DeployParams["SkipBuild"] = $true }

    & $DeployScript @DeployParams
    if ($LASTEXITCODE -ne 0) {
        Write-Error "deploy-bundle.ps1 failed with exit code $LASTEXITCODE"
        exit $LASTEXITCODE
    }
    Write-Host "      App bundle & runtime splits successfully deployed!" -ForegroundColor Green
}

# -----------------------------------------------------------------------------
# 4. Push Video to Device
# -----------------------------------------------------------------------------
Write-Host "`n[4/6] Ensuring test video is present on device (/data/local/tmp)..." -ForegroundColor Yellow

$RemoteVideoPath = "/data/local/tmp/$($SelectedVideo.Name)"
$RemoteSize = 0
$SizeOutput = adb -s $SelectedDevice.Serial shell "if [ -f '$RemoteVideoPath' ]; then stat -c %s '$RemoteVideoPath' 2>/dev/null || ls -l '$RemoteVideoPath' | awk '{print `$5}'; fi"
if ($SizeOutput) {
    $RemoteSizeText = ($SizeOutput -join "").Trim()
    if ($RemoteSizeText -match "^\d+$") {
        $RemoteSize = [int64]$RemoteSizeText
    }
}

if ($RemoteSize -eq $SelectedVideo.Length) {
    Write-Host "      Video '$($SelectedVideo.Name)' is already staged on device with matching size ($SelectedVideoSizeMb MB)." -ForegroundColor Green
} else {
    Write-Host "      Pushing '$($SelectedVideo.Name)' ($SelectedVideoSizeMb MB) to $RemoteVideoPath..." -ForegroundColor Yellow
    adb -s $SelectedDevice.Serial push "$($SelectedVideo.FullName)" "$RemoteVideoPath"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to push video file to device."
        exit $LASTEXITCODE
    }
    adb -s $SelectedDevice.Serial shell "chmod 666 '$RemoteVideoPath'"
    Write-Host "      Video pushed and verified successfully!" -ForegroundColor Green
}

# -----------------------------------------------------------------------------
# 5. Build and Install androidTest APK
# -----------------------------------------------------------------------------
Write-Host "`n[5/6] Building and installing benchmark test runner APK..." -ForegroundColor Yellow

$GradleCmd = if ($IsWindows -or $env:OS -match "Windows") {
    Join-Path $AndroidDir "gradlew.bat"
} else {
    Join-Path $AndroidDir "gradlew"
}

$TestApkPath = Join-Path $AndroidDir "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

Write-Host "      Compiling :app:assembleDebugAndroidTest (targetAbi=$($SelectedDevice.Abi))..." -ForegroundColor DarkCyan
& $GradleCmd -p "$AndroidDir" :app:assembleDebugAndroidTest "-PtargetAbi=$($SelectedDevice.Abi)"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Building androidTest APK failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "      Installing $TestApkPath onto device $($SelectedDevice.Serial)..." -ForegroundColor DarkCyan
adb -s $SelectedDevice.Serial install -r -t "$TestApkPath"
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to install test APK onto device."
    exit $LASTEXITCODE
}
Write-Host "      Test APK successfully installed!" -ForegroundColor Green

# -----------------------------------------------------------------------------
# 6. Execute Benchmark Suite & Collect Markdown Report
# -----------------------------------------------------------------------------
Write-Host "`n[6/6] Executing CpuGpuNpuBenchmarkTest on $($SelectedDevice.Model)..." -ForegroundColor Cyan
Write-Host "      Target Video: $($SelectedVideo.Name)" -ForegroundColor DarkCyan
Write-Host "      Running tests: Primitives Warmup -> CPU Benchmark -> GPU Benchmark -> NPU Benchmark...`n" -ForegroundColor DarkCyan

# Force-stop app and clear logcat buffer
adb -s $SelectedDevice.Serial shell am force-stop com.localfind
adb -s $SelectedDevice.Serial logcat -c

$TargetDeviceLabel = "$($SelectedDevice.Model)"

$InstrumentArgs = @(
    "-s", $SelectedDevice.Serial,
    "shell", "am", "instrument", "-w", "-r",
    "-e", "class", "com.localfind.CpuGpuNpuBenchmarkTest",
    "-e", "video_name", "$($SelectedVideo.Name)",
    "-e", "target_device", "$TargetDeviceLabel",
    "com.localfind.test/androidx.test.runner.AndroidJUnitRunner"
)

$InstrumentOutput = @()
$InMarkdownBlock = $false
$MarkdownLines = @()

& adb @InstrumentArgs | ForEach-Object {
    $line = $_
    $InstrumentOutput += $line

    # Echo real-time instrumentation output
    if ($line -match "INSTRUMENTATION_STATUS: test=" -or
        $line -match "INSTRUMENTATION_STATUS_CODE:" -or
        $line -match "BENCHMARK_CPU_GPU_NPU" -or
        $line -match "Time:" -or
        $line -match "OK \(" -or
        $line -match "FAILURES!!!") {
        Write-Host "  $line" -ForegroundColor White
    }

    # Extract markdown block if present in stream
    if ($line -match "<<<BENCHMARK_MARKDOWN_START>>>") {
        $InMarkdownBlock = $true
        return
    }
    if ($line -match "<<<BENCHMARK_MARKDOWN_END>>>") {
        $InMarkdownBlock = $false
        return
    }
    if ($InMarkdownBlock) {
        $MarkdownLines += $line
    }
}

$TestSuccess = ($LASTEXITCODE -eq 0) -and (-not ($InstrumentOutput -match "FAILURES!!!"))

# Retrieve markdown report from device storage
$ExtractedMarkdown = ""

if ($MarkdownLines.Count -gt 5) {
    $ExtractedMarkdown = $MarkdownLines -join "`n"
} else {
    Write-Host "Fetching benchmark report directly from app internal storage..." -ForegroundColor DarkCyan
    $ReportCacheOutput = adb -s $SelectedDevice.Serial shell "run-as com.localfind sh -c 'if [ -f cache/benchmark_report.md ]; then cat cache/benchmark_report.md; fi'"
    if ($ReportCacheOutput -and ($ReportCacheOutput -join "`n").Contains("# LocalFind Hardware Acceleration Benchmark")) {
        $ExtractedMarkdown = $ReportCacheOutput -join "`n"
    } else {
        $TmpReport = adb -s $SelectedDevice.Serial shell "if [ -f /data/local/tmp/benchmark_report.md ]; then cat /data/local/tmp/benchmark_report.md; fi"
        if ($TmpReport -and ($TmpReport -join "`n").Contains("# LocalFind Hardware Acceleration Benchmark")) {
            $ExtractedMarkdown = $TmpReport -join "`n"
        }
    }
}

# If still missing, extract from logcat
if ([string]::IsNullOrWhiteSpace($ExtractedMarkdown)) {
    $Logs = @(adb -s $SelectedDevice.Serial logcat -d -s BENCHMARK_CPU_GPU_NPU:I)
    $LogText = $Logs -join "`n"
    if ($LogText -match "<<<BENCHMARK_MARKDOWN_START>>>([\s\S]*?)<<<BENCHMARK_MARKDOWN_END>>>") {
        $ExtractedMarkdown = $matches[1].Trim()
    }
}

# Save individual Markdown report and append to cumulative benchmark_summary.md
if (-not (Test-Path $BenchmarksDir)) {
    New-Item -ItemType Directory -Path $BenchmarksDir | Out-Null
}

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$SafeDevName = $SelectedDevice.Model -replace '[^\w\-]', '_'
$SafeVidName = [System.IO.Path]::GetFileNameWithoutExtension($SelectedVideo.Name) -replace '[^\w\-]', '_'

$OutputFileName = "benchmark_${SafeDevName}_${SafeVidName}_${Timestamp}.md"
$OutputPath = Join-Path $BenchmarksDir $OutputFileName
$SummaryPath = Join-Path $BenchmarksDir "benchmark_summary.md"

# Remove obsolete benchmark_latest.md if present
Remove-Item (Join-Path $BenchmarksDir "benchmark_latest.md") -Force -ErrorAction SilentlyContinue

if (-not [string]::IsNullOrWhiteSpace($ExtractedMarkdown)) {
    [System.IO.File]::WriteAllText($OutputPath, $ExtractedMarkdown, [System.Text.Encoding]::UTF8)

    # Parse key data points for the cumulative summary table
    $RunTimestamp = if ($ExtractedMarkdown -match '\|\s*\*\*Timestamp\*\*\s*\|\s*([^\|]+)\|') { $matches[1].Trim() } else { (Get-Date -Format "yyyy-MM-dd HH:mm:ss") }
    $RunDevice = if ($ExtractedMarkdown -match '\|\s*\*\*Target Device\*\*\s*\|\s*([^\|]+)\|') { $matches[1].Trim() } else { $SelectedDevice.Model }
    $RunSoC = if ($ExtractedMarkdown -match '\|\s*\*\*Chipset \(SoC\)\*\*\s*\|\s*([^\|]+)\|') { $matches[1].Trim() } else { "Unknown" }
    $RunVideo = $SelectedVideo.Name
    $RunSize = "$SelectedVideoSizeMb MB"

    $CpuCell = if ($ExtractedMarkdown -match '\|\s*\*\*CPU\*\*\s*\|[^\|]+\|[^\|]+\|\s*([^\|]+)\|\s*(\d+)\s*\|[^\|]+\|\s*([^\|]+)\|') { "$($matches[1].Trim()) ($($matches[3].Trim()))" } else { "N/A" }
    $GpuCell = if ($ExtractedMarkdown -match '\|\s*\*\*GPU\*\*\s*\|[^\|]+\|[^\|]+\|\s*([^\|]+)\|\s*(\d+)\s*\|[^\|]+\|\s*([^\|]+)\|') { "$($matches[1].Trim()) ($($matches[3].Trim()))" } else { "N/A" }
    $NpuCell = if ($ExtractedMarkdown -match '\|\s*\*\*NPU\*\*\s*\|[^\|]+\|[^\|]+\|\s*([^\|]+)\|\s*(\d+)\s*\|[^\|]+\|\s*([^\|]+)\|') { "$($matches[1].Trim()) ($($matches[3].Trim()))" } else { "N/A" }

    $GpuSpeedup = if ($ExtractedMarkdown -match 'GPU vs CPU Speedup\*\*:\s*\*\*([^\*]+)\*\*') { $matches[1].Trim() } else { "" }
    $NpuSpeedup = if ($ExtractedMarkdown -match 'NPU vs CPU Speedup\*\*:\s*\*\*([^\*]+)\*\*') { $matches[1].Trim() } else { "" }
    $SpeedupParts = @()
    if ($GpuSpeedup) { $SpeedupParts += "GPU: $GpuSpeedup" }
    if ($NpuSpeedup) { $SpeedupParts += "NPU: $NpuSpeedup" }
    $SpeedupCell = if ($SpeedupParts.Count -gt 0) { $SpeedupParts -join " | " } else { "N/A" }

    # Ensure summary file exists with header
    if (-not (Test-Path $SummaryPath)) {
        $HeaderContent = @"
# LocalFind Hardware Acceleration Benchmark Summary

Cumulative benchmark results across devices, chipsets, and video files.

| Timestamp | Device | SoC | Video | Size | CPU | GPU | NPU | Speedup (vs CPU) | Report |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |

"@
        [System.IO.File]::WriteAllText($SummaryPath, $HeaderContent, [System.Text.Encoding]::UTF8)
    }

    $SummaryRow = "| $RunTimestamp | $RunDevice | $RunSoC | `$RunVideo` | $RunSize | $CpuCell | $GpuCell | $NpuCell | $SpeedupCell | [$OutputFileName](./$OutputFileName) |`r`n"
    [System.IO.File]::AppendAllText($SummaryPath, $SummaryRow, [System.Text.Encoding]::UTF8)

    Write-Host "`n==========================================================" -ForegroundColor Green
    Write-Host " Benchmark Completed Successfully!                         " -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "Report saved to:" -ForegroundColor Cyan
    Write-Host "  -> $OutputPath" -ForegroundColor White
    Write-Host "  -> Appended to cumulative summary: $SummaryPath" -ForegroundColor White
    Write-Host "==========================================================" -ForegroundColor Green

    Write-Host "`nSummary Preview:" -ForegroundColor Cyan
    Write-Host $ExtractedMarkdown -ForegroundColor Gray
} else {
    Write-Warning "Could not extract structured markdown report from device."
    $RawReport = Join-Path $BenchmarksDir "benchmark_raw_${SafeDevName}_${SafeVidName}_${Timestamp}.log"
    $InstrumentOutput | Set-Content -Path $RawReport -Encoding utf8
    Write-Host "Raw test output saved to: $RawReport" -ForegroundColor Yellow
}

if (-not $TestSuccess) {
    Write-Error "Benchmark tests reported failures or aborted. Check logs above."
    exit 1
}
