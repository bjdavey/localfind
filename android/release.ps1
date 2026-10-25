<#
.SYNOPSIS
    Builds a signed release App Bundle (.aab) and uploads it to a Google Play Console track.

.DESCRIPTION
    1. Optionally bumps versionCode (+1) / sets versionName in app/build.gradle.kts.
    2. Builds the signed bundle with `gradlew bundleRelease` (signing from android/keystore.properties).
    3. Uploads the .aab through the Google Play Developer API (edits: insert -> bundles.upload -> tracks.update -> commit).
       Pure PowerShell 7, no extra tools: the service account JWT is signed with .NET RSA.

    One-time setup:
      a) Upload key: on the first run the script asks for a path/alias/password and generates the keystore and
         android/keystore.properties itself (both gitignored). Back them up! Format, if you prefer to write it by hand:
           storeFile=../localfind-upload.jks   (relative to android/)
           storePassword=...
           keyAlias=...
           keyPassword=...
      b) Play API access: Play Console -> Setup -> API access -> create/link a service account, grant it
         "Release to testing tracks" (and "Release to production" for production), download its JSON key and save it
         as android/play-service-account.json (gitignored) or pass -ServiceAccountJson.
      c) The app must already exist in Play Console and have had its first release uploaded manually once.
         A never-published app only accepts draft releases.

.PARAMETER ServiceAccountJson
    Path of the Play service account key. Default: android/play-service-account.json.

.NOTES
    Everything else is asked interactively (Enter accepts the default): release type (internal / closed / open /
    production), upload or build-only, draft or not, version name, versionCode bump, release notes.

.EXAMPLE
    .\release.ps1
#>

param(
    [string]$ServiceAccountJson = (Join-Path $PSScriptRoot "play-service-account.json")
)

$ErrorActionPreference = "Stop"
$PackageName = "com.localfind"
$GradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
$Aab = Join-Path $PSScriptRoot "app\build\outputs\bundle\release\app-release.aab"

# ---- Questions ----------------------------------------------------------------------------------------------
# Everything is asked interactively; Enter accepts the [default].
function Ask($prompt, $default) {
    $a = Read-Host "$prompt [$default]"
    if ($a) { $a } else { $default }
}

$currentName = [regex]::Match((Get-Content $GradleFile -Raw), 'versionName = "([^"]*)"').Groups[1].Value
$Track = switch (Ask "Release type: 1) test (internal)  2) closed test  3) open test  4) production" "1") {
    "1" { "internal" } "2" { "alpha" } "3" { "beta" } "4" { "production" } default { throw "Pick 1-4." }
}
$DryRun = (Ask "Upload to Google Play? (n = only build the bundle)" "y") -notmatch '^[yY]'
$Draft = (-not $DryRun) -and ((Ask "Create as draft (finish the rollout in Play Console; needed while the app is unpublished)?" "n") -match '^[yY]')
$VersionName = Ask "Version name" $currentName
$NoBump = (Ask "Increment versionCode? (n = reuse current, e.g. retry after a failed upload)" "y") -notmatch '^[yY]'
$ReleaseNotes = if ($DryRun) { "" } else { Read-Host "Release notes (en-US, max 500 chars, Enter for none)" }
$SkipBuild = $false

# ---- Preconditions (fail before the slow build) -------------------------------------------------------------
$KeystoreProps = Join-Path $PSScriptRoot "keystore.properties"

# First run: ask where to put the upload keystore, generate it with keytool and write keystore.properties.
function New-UploadKeystore {
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue)?.Source
    if (-not $keytool) {
        # Fall back to the JDK bundled with Android Studio / JAVA_HOME
        $keytool = @($env:JAVA_HOME, "$env:ProgramFiles\Android\Android Studio\jbr") |
            Where-Object { $_ } | ForEach-Object { Join-Path $_ "bin\keytool.exe" } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
    }
    if (-not $keytool) { throw "keytool not found: install a JDK or set JAVA_HOME." }

    Write-Host "No keystore.properties yet: creating a new upload keystore." -ForegroundColor Yellow
    $folder = (Read-Host "Folder for the keystore (e.g. E:\keys)").Trim('"')
    if (-not $folder) { throw "No folder given." }
    $path = [IO.Path]::GetFullPath((Join-Path $folder "localfind-upload.jks"))
    if (Test-Path $path) { throw "$path already exists: not overwriting. Pick another path or write keystore.properties yourself." }
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null

    $alias = Read-Host "Key alias [upload]"
    if (-not $alias) { $alias = "upload" }
    $plain = {
        param($prompt)
        $s = Read-Host $prompt -AsSecureString
        [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
    }
    $pass = & $plain "Keystore password (min 6 chars; also used as key password)"
    if ($pass.Length -lt 6) { throw "Password must be at least 6 characters." }

    & $keytool -genkeypair -v -keystore $path -alias $alias -storepass $pass -keypass $pass `
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=LocalFind"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed." }

    # Forward slashes: .properties files treat backslashes as escapes
    @("storeFile=$($path.Replace('\', '/'))", "storePassword=$pass", "keyAlias=$alias", "keyPassword=$pass") |
        Set-Content $KeystoreProps
    Write-Host "Created $path and $KeystoreProps" -ForegroundColor Green
    Write-Host "BACK UP the keystore and password now (a password manager is fine). Enroll in Play App Signing so a lost upload key can be reset." -ForegroundColor Yellow
}

if (-not $SkipBuild -and -not (Test-Path $KeystoreProps)) { New-UploadKeystore }
if (-not $DryRun -and -not (Test-Path $ServiceAccountJson)) {
    throw "Service account key not found: $ServiceAccountJson. See the setup notes in release.ps1."
}
if ($ReleaseNotes.Length -gt 500) { throw "Release notes are limited to 500 characters." }

if ($Track -eq "production" -and -not $DryRun) {
    $answer = Read-Host "Upload to PRODUCTION$(if ($Draft) { ' (draft)' })? Type 'production' to confirm"
    if ($answer -ne "production") { throw "Cancelled." }
}

# ---- Build --------------------------------------------------------------------------------------------------
if (-not $SkipBuild) {
    # The AI pack asset folders are empty in git (*.litertlm is gitignored), so a bundle built without this step
    # has no models at all (~64 MB). Copy each device group's model from .local/models (fetched by deploy-bundle.ps1)
    # into the pack, build, then remove the copies again (they are not needed for debug/deploy builds).
    # Folder name -> source file; group_other holds the universal CPU/GPU model.
    $modelDir = Join-Path (Split-Path -Parent $PSScriptRoot) ".local\models"
    $packDir = Join-Path $PSScriptRoot "ai_pack\embedding_model\src\main\assets"
    $groups = [ordered]@{
        "Qualcomm_SM8550" = "embeddinggemma-2-740m_Qualcomm_SM8550.litertlm"
        "Qualcomm_SM8650" = "embeddinggemma-2-740m_Qualcomm_SM8650.litertlm"
        "Qualcomm_SM8750" = "embeddinggemma-2-740m_Qualcomm_SM8750.litertlm"
        "Qualcomm_SM8850" = "embeddinggemma-2-740m_Qualcomm_SM8850.litertlm"
        "Mediatek_MT6991" = "embeddinggemma-2-740m_MediaTek_MT6991.litertlm"
        "Mediatek_MT6993" = "embeddinggemma-2-740m_MediaTek_MT6993.litertlm"
        "Google_Tensor_G5" = "embeddinggemma-2-740m_Google_Tensor_G5.litertlm"
        "Google_Tensor_G6" = "embeddinggemma-2-740m_Google_Tensor_G6.litertlm"
        "other"           = "embeddinggemma-2-740m.litertlm"
    }
    # Every group must have a plausible model (a truncated download is far below 100 MB): devices of a group without
    # one would install an app that has no model. Missing ones are downloaded from Hugging Face (same source as
    # deploy-bundle.ps1) into a .partial file first, so an interrupted download never looks complete.
    $isMissing = { param($g) $f = Join-Path $modelDir $groups[$g]; -not (Test-Path $f) -or (Get-Item $f).Length -lt 100MB }
    New-Item -ItemType Directory -Force $modelDir | Out-Null
    foreach ($g in @($groups.Keys | Where-Object { & $isMissing $_ })) {
        $file = Join-Path $modelDir $groups[$g]
        $url = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/$($groups[$g])"
        Write-Host "Downloading $g model: $url" -ForegroundColor Yellow
        & curl.exe -L --fail --progress-bar -o "$file.partial" $url
        if ($LASTEXITCODE -eq 0) { Move-Item "$file.partial" $file -Force }
        else { Remove-Item "$file.partial" -Force -ErrorAction SilentlyContinue }
    }
    $missing = @($groups.Keys | Where-Object { & $isMissing $_ })
    if ($missing) {
        $list = ($missing | ForEach-Object { "  $_ -> $($groups[$_])" }) -join "`n"
        throw "Models missing or incomplete in ${modelDir}:`n$list"
    }
    # The vendor NPU runtime libraries are gitignored too; without them the runtime splits are empty shells (the
    # MediaTek v8/v9 modules were 18 KB). Fetch whatever is missing (the scripts are idempotent), like deploy-bundle.ps1.
    $jni = "src\main\jni\arm64-v8a"
    $rt = Join-Path $PSScriptRoot "litert_npu_runtime_libraries"
    $runtimeLibs = @(
        @{ Script = "fetch_qualcomm_library.sh"; File = "qualcomm_runtime_v73\$jni\libQnnHtpV73Stub.so" }
        @{ Script = "fetch_qualcomm_library.sh"; File = "qualcomm_runtime_v75\$jni\libQnnHtpV75Stub.so" }
        @{ Script = "fetch_qualcomm_library.sh"; File = "qualcomm_runtime_v79\$jni\libQnnHtpV79Stub.so" }
        @{ Script = "fetch_qualcomm_library.sh"; File = "qualcomm_runtime_v81\$jni\libQnnHtpV81Stub.so" }
        @{ Script = "fetch_mediatek_library.sh"; File = "mediatek_runtime_v8\$jni\libneuronusdk_adapter.mtk.so" }
        @{ Script = "fetch_mediatek_library.sh"; File = "mediatek_runtime_v9\$jni\libneuronusdk_adapter.so" }
    )
    # Git for Windows' bash: plain 'bash' may resolve to the WSL launcher in System32.
    $gitBash = Join-Path (Split-Path (Split-Path (Get-Command git).Source)) "bin\bash.exe"
    if (-not (Test-Path $gitBash)) { $gitBash = "bash" }
    foreach ($script in ($runtimeLibs | Where-Object { -not (Test-Path (Join-Path $rt $_.File)) } | ForEach-Object { $_.Script } | Select-Object -Unique)) {
        Write-Host "NPU runtime libraries missing; running $script (large download)..." -ForegroundColor Yellow
        & $gitBash (Join-Path $rt $script)
        if ($LASTEXITCODE -ne 0) { throw "$script failed with exit code $LASTEXITCODE" }
    }
    $stillMissing = $runtimeLibs | Where-Object { -not (Test-Path (Join-Path $rt $_.File)) }
    if ($stillMissing) { throw "Runtime libraries still missing after fetch: $($stillMissing.File -join ', ')" }

    # Version bump only after all checks passed, so a failed check does not burn a versionCode.
    $gradle = Get-Content $GradleFile -Raw
    $code = [int][regex]::Match($gradle, 'versionCode = (\d+)').Groups[1].Value
    if (-not $NoBump) {
        $code++
        $gradle = $gradle -replace 'versionCode = \d+', "versionCode = $code"
    }
    if ($VersionName) { $gradle = $gradle -replace 'versionName = "[^"]*"', "versionName = `"$VersionName`"" }
    Set-Content $GradleFile $gradle -NoNewline
    Write-Host "Releasing $PackageName versionCode $code to '$Track'" -ForegroundColor Cyan

    # The on-demand recovery pack (:ai_pack:universal_model, fetched by "Download compatible model" when the NPU
    # model cannot run) must hold the universal model in releases; deploy-bundle.ps1 keeps it empty by default.
    $recoveryDest = Join-Path $PSScriptRoot "ai_pack\universal_model\src\main\assets\model\embeddinggemma-2-740m.litertlm"
    $staged = @()
    Push-Location $PSScriptRoot
    try {
        foreach ($g in $groups.Keys) {
            $dest = Join-Path $packDir "model#group_$g\embeddinggemma-2-740m.litertlm"
            Write-Host "Staging $g model..."
            Copy-Item (Join-Path $modelDir $groups[$g]) $dest -Force
            $staged += $dest
        }
        Write-Host "Staging recovery (universal) model..."
        Copy-Item (Join-Path $modelDir $groups["other"]) $recoveryDest -Force
        $staged += $recoveryDest
        & .\gradlew.bat bundleRelease
        if ($LASTEXITCODE -ne 0) { throw "Gradle bundleRelease failed." }
    } finally {
        Pop-Location
        $staged | Remove-Item -Force -ErrorAction SilentlyContinue
    }

    # Verify the built bundle really contains every model and runtime library (entries are matched by their
    # asset-pack / module path, and must not be empty).
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Aab)
    try {
        $entries = $zip.Entries | Where-Object { $_.Length -gt 0 } | ForEach-Object { $_.FullName }
    } finally { $zip.Dispose() }
    $absent = @()
    foreach ($g in $groups.Keys) {
        if (-not ($entries -contains "embedding_model/assets/model#group_$g/embeddinggemma-2-740m.litertlm")) { $absent += "model $g" }
    }
    if (-not ($entries -contains "universal_model/assets/model/embeddinggemma-2-740m.litertlm")) { $absent += "recovery model" }
    foreach ($l in $runtimeLibs) {
        $module = $l.File.Split('\')[0]
        $name = Split-Path $l.File -Leaf
        if (-not ($entries -contains "$module/lib/arm64-v8a/$name")) { $absent += "$module/$name" }
    }
    if ($absent) { throw "Bundle is incomplete, missing: $($absent -join ', ')" }
    Write-Host "Bundle check OK: all $($groups.Count) models and $($runtimeLibs.Count) runtime libraries present." -ForegroundColor Green
}
if (-not (Test-Path $Aab)) { throw "Bundle not found: $Aab" }
Write-Host ("Bundle: {0} ({1:N1} MB)" -f $Aab, ((Get-Item $Aab).Length / 1MB))
if ($DryRun) { Write-Host "Dry run: not uploading." -ForegroundColor Yellow; return }

# ---- Google auth: service account JWT (RS256) -> OAuth access token -----------------------------------------
function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

$sa = Get-Content $ServiceAccountJson -Raw | ConvertFrom-Json
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"RS256","typ":"JWT"}'))
$claims = @{
    iss   = $sa.client_email
    scope = "https://www.googleapis.com/auth/androidpublisher"
    aud   = $sa.token_uri
    iat   = $now
    exp   = $now + 3600
} | ConvertTo-Json -Compress
$unsigned = "$header." + (ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($claims)))
$rsa = [Security.Cryptography.RSA]::Create()
$rsa.ImportFromPem($sa.private_key)
$sig = $rsa.SignData([Text.Encoding]::UTF8.GetBytes($unsigned),
    [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
$token = (Invoke-RestMethod -Method Post -Uri $sa.token_uri -Body @{
        grant_type = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        assertion  = "$unsigned." + (ConvertTo-Base64Url $sig)
    }).access_token
$auth = @{ Authorization = "Bearer $token" }

# ---- Play edit: upload bundle, assign to track, commit ------------------------------------------------------
# All changes happen inside one "edit" and only become visible on commit; a failure before that leaves Play untouched.
$api = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PackageName"
$edit = Invoke-RestMethod -Method Post -Uri "$api/edits" -Headers $auth -Body "{}" -ContentType "application/json"

Write-Host "Uploading bundle..."
$upload = Invoke-RestMethod -Method Post -Headers $auth -ContentType "application/octet-stream" -InFile $Aab `
    -Uri "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PackageName/edits/$($edit.id)/bundles?uploadType=media"
Write-Host "Uploaded as versionCode $($upload.versionCode)"

$release = @{
    status       = if ($Draft) { "draft" } else { "completed" }
    versionCodes = @("$($upload.versionCode)")
}
if ($ReleaseNotes) { $release.releaseNotes = @(@{ language = "en-US"; text = $ReleaseNotes }) }
$trackBody = @{ track = $Track; releases = @($release) } | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Put -Uri "$api/edits/$($edit.id)/tracks/$Track" -Headers $auth `
    -Body $trackBody -ContentType "application/json" | Out-Null

Invoke-RestMethod -Method Post -Uri "$api/edits/$($edit.id):commit" -Headers $auth | Out-Null
Write-Host "Done: versionCode $($upload.versionCode) is on the '$Track' track ($($release.status))." -ForegroundColor Green
