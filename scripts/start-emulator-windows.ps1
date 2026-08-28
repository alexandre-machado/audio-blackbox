<#
.SYNOPSIS
  Boots the project's AVD on Windows and exposes its adb server to WSL.

.DESCRIPTION
  The Windows half of the emulator workflow: this machine's WSL install has no emulator
  package, no system images and no /dev/kvm, so the emulator runs on Windows (where it gets
  WHPX acceleration) while the Gradle build and the instrumented tests keep running inside WSL
  against it.

  This script only sets up and boots. It deliberately does NOT run the tests -- those are driven
  from WSL by scripts/ci/run-instrumented-tier.sh, which pulls the screenshots off the device.

  The AVD definition is read from scripts/ci/avd.env, the same single source of truth that
  .github/workflows/ci.yml and scripts/run-instrumented-tests.sh use, so a locally captured
  screenshot comes off the same API level, target, arch and device profile that CI renders.
  Do not hardcode those values here -- edit avd.env if they need to change.

  Idempotent: an already-installed system image and an already-created AVD are left alone.

.NOTES
  One-time prerequisite this script cannot do for itself:
    Android Studio > Settings > Languages & Frameworks > Android SDK > "SDK Tools" tab
      > check "Android SDK Command-line Tools (latest)" > Apply
  It is a GUI checkbox because sdkmanager -- the tool that would install it -- ships inside the
  very package that is missing. The script fails loudly with this instruction rather than trying
  to bootstrap it by downloading a zip from the internet.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\start-emulator-windows.ps1
#>

[CmdletBinding()]
param(
    # Boot without a visible window. Off by default: seeing the emulator is the point when the
    # captures being produced are store screenshots a human has to approve.
    [switch]$Headless,

    # Wipe the AVD's user data before booting. Use when a previous run left the app in a state
    # that would contaminate a screenshot (a stale dialog, a half-filled buffer).
    [switch]$WipeData
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Message) Write-Host "[start-emulator] $Message" }

# Runs a scriptblock with the working directory forced to a LOCAL Windows path.
#
# This exists because sdkmanager and avdmanager are .bat files, and cmd.exe refuses to start
# when the current directory is a UNC path -- which it is whenever this script is invoked from
# the repo living inside WSL (\\wsl.localhost\...). The failure is early and cryptic ("UNC paths
# are not supported"), and it is not the SDK's fault, so the fix belongs here rather than in a
# "cd to a local drive first" instruction the reader has to remember.
function Use-LocalWorkingDirectory {
    param([string]$Directory, [scriptblock]$Action)
    Push-Location -LiteralPath $Directory
    # Native tools write progress, deprecation notices and download chatter to stderr. With the
    # script-level $ErrorActionPreference='Stop', PowerShell promotes ANY stderr line from a
    # native command into a terminating NativeCommandError -- so a harmless
    # "sdkmanager is deprecated" warning would abort the run. Success/failure here is decided by
    # $LASTEXITCODE at each call site, which is the honest signal, not by whether the tool
    # happened to print something.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Action } finally {
        $ErrorActionPreference = $previousPreference
        Pop-Location
    }
}

# Prints to stderr and exits non-zero without PowerShell's exception decoration. Write-Error
# would wrap a plain instruction in a CategoryInfo/FullyQualifiedErrorId stack trace, which
# buries the one line the reader actually needs to act on.
function Fail {
    param([string]$Message)
    [Console]::Error.WriteLine("[start-emulator] ERROR: $Message")
    exit 1
}

# --- Locate the repo and the SDK -------------------------------------------------------------
$RepoRoot = Split-Path -Parent $PSScriptRoot
$AvdEnv   = Join-Path $RepoRoot 'scripts\ci\avd.env'

if (-not (Test-Path $AvdEnv)) {
    Fail "Cannot find scripts/ci/avd.env (looked in '$AvdEnv'). Run this from inside the repo."
}

$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $Sdk)) {
    Fail "Android SDK not found at '$Sdk'. Set ANDROID_SDK_ROOT or install the SDK via Android Studio."
}

$SdkManager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$AvdManager = Join-Path $Sdk 'cmdline-tools\latest\bin\avdmanager.bat'
$Emulator   = Join-Path $Sdk 'emulator\emulator.exe'
$Adb        = Join-Path $Sdk 'platform-tools\adb.exe'

if (-not (Test-Path $SdkManager)) {
    Fail @"
Command-line tools are not installed, so sdkmanager/avdmanager are unavailable.

Install them once, via the GUI (sdkmanager cannot install itself):
  Android Studio > Settings > Languages & Frameworks > Android SDK
    > "SDK Tools" tab > check "Android SDK Command-line Tools (latest)" > Apply

Then re-run this script.
"@
}
foreach ($tool in @(@{P=$AvdManager; N='avdmanager'}, @{P=$Emulator; N='emulator.exe'}, @{P=$Adb; N='adb.exe'})) {
    if (-not (Test-Path $tool.P)) { Fail "$($tool.N) not found at '$($tool.P)'." }
}

# --- Read the AVD definition (shared with CI) ------------------------------------------------
# avd.env is heavily commented for humans; take only the bare KEY=VALUE lines, the same way
# .github/workflows/ci.yml does when feeding it into $GITHUB_ENV.
$Avd = @{}
Get-Content $AvdEnv | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $key, $value = $_ -split '=', 2
    $Avd[$key] = $value
}
foreach ($required in @('API_LEVEL', 'TARGET', 'ARCH', 'PROFILE', 'AVD_NAME')) {
    if (-not $Avd.ContainsKey($required)) { Fail "avd.env is missing $required." }
}

$SystemImage = "system-images;android-$($Avd.API_LEVEL);$($Avd.TARGET);$($Avd.ARCH)"
$AvdName     = $Avd.AVD_NAME

# A package spec is ONE argument that happens to contain semicolons -- but sdkmanager/avdmanager
# are .bat files, so the argument travels through cmd.exe, where ';' is an argument separator.
# Unquoted, "system-images;android-30;google_apis;x86_64" arrives as four packages and the tool
# reports "Package system-images not found. Package android-30 not found." and then exits 0,
# having installed nothing. The embedded quotes survive the cmd.exe hop and keep it whole.
$SystemImageArg = '"' + $SystemImage + '"'

Write-Step "AVD definition from avd.env: $AvdName ($SystemImage, profile '$($Avd.PROFILE)')"

# --- Ensure the system image is installed ----------------------------------------------------
# Detected on disk rather than by asking sdkmanager: recent SDK releases print
# "The SDK Manager CLI tool (sdkmanager) is deprecated. Android CLI will be used instead." and
# delegate elsewhere, so its --list_installed output format is not something to depend on. The
# directory layout for an installed system image is stable and has been for years.
$SystemImagePath = Join-Path $Sdk "system-images\android-$($Avd.API_LEVEL)\$($Avd.TARGET)\$($Avd.ARCH)"
if (Test-Path $SystemImagePath) {
    Write-Step "System image already installed."
} else {
    Write-Step "Installing $SystemImage (this is a large download on first run -- several GB, no timeout applies here)..."
    # Licenses are accepted non-interactively; the alternative is a prompt that blocks forever
    # when this is run from a non-interactive shell.
    # No --no-metrics here, deliberately: the replacement "Android CLI" advertises that flag in
    # its banner, but the deprecated sdkmanager shim this path actually invokes rejects it as an
    # unknown option and installs nothing. If this ever migrates to `android sdk`, opting out of
    # its telemetry is the consistent choice for a repo that removed analytics from the app
    # itself (#119) -- but it belongs with that migration, not bolted onto the old tool.
    Use-LocalWorkingDirectory $Sdk { 'y' | & $SdkManager --install $SystemImageArg }
    $installExit = $LASTEXITCODE

    # Verified on disk, not by exit code alone. The deprecation shim above delegates to another
    # tool, so its exit code is one indirection away from the thing actually being asserted --
    # that the image is present. If it is there, a non-zero exit was noise; if it is not, a zero
    # exit was a lie. Either way the filesystem is the oracle.
    if (-not (Test-Path $SystemImagePath)) {
        Fail "Install finished (exit $installExit) but '$SystemImagePath' does not exist -- the system image was not actually installed. Scroll up for what sdkmanager reported."
    }
    Write-Step "System image installed."
}

# --- Ensure the AVD exists -------------------------------------------------------------------
$existingAvds = Use-LocalWorkingDirectory $Sdk { & $Emulator -list-avds 2>$null }
if ($existingAvds -contains $AvdName) {
    Write-Step "AVD '$AvdName' already exists."
} else {
    Write-Step "Creating AVD '$AvdName'..."
    Use-LocalWorkingDirectory $Sdk { 'no' | & $AvdManager create avd --name $AvdName --package $SystemImageArg --device $Avd.PROFILE --force }
    if ($LASTEXITCODE -ne 0) { Fail "avdmanager failed to create '$AvdName' (exit $LASTEXITCODE)." }
}

# --- Boot ------------------------------------------------------------------------------------
# -no-snapshot-save matches CI's emulator-options: every boot starts from the same state, so a
# screenshot can't inherit leftovers from a previous session.
$emulatorArgs = @('-avd', $AvdName, '-no-snapshot-save', '-no-boot-anim', '-camera-back', 'none')
if ($Headless) { $emulatorArgs += @('-no-window', '-gpu', 'swiftshader_indirect') }
if ($WipeData) { $emulatorArgs += '-wipe-data' }

Write-Step "Booting emulator: $($emulatorArgs -join ' ')"
# -WorkingDirectory for the same UNC reason as Use-LocalWorkingDirectory above: the emulator
# spawns helper processes that inherit the working directory.
Start-Process -FilePath $Emulator -ArgumentList $emulatorArgs -WorkingDirectory $Sdk -WindowStyle Normal

# --- Wait for boot ---------------------------------------------------------------------------
# The adb server is started here on Windows, deliberately: WSL will attach to THIS server rather
# than spawning a competing one. Both sides ship adb 37.0.1 (verified), so there is no
# version-mismatch war -- if you ever upgrade one side, upgrade the other too or they will
# repeatedly kill each other's server.
Write-Step "Starting adb server and waiting for the device to finish booting..."
Use-LocalWorkingDirectory $Sdk { & $Adb start-server | Out-Null }

$deadlineSeconds = 300
$elapsed = 0
while ($true) {
    $booted = Use-LocalWorkingDirectory $Sdk { & $Adb shell getprop sys.boot_completed 2>$null }
    if ("$booted".Trim() -eq '1') { break }
    if ($elapsed -ge $deadlineSeconds) {
        Fail "Emulator did not report sys.boot_completed=1 within ${deadlineSeconds}s. Check the emulator window for an error rather than assuming it is merely slow."
    }
    Start-Sleep -Seconds 5
    $elapsed += 5
}

$serial = Use-LocalWorkingDirectory $Sdk { & $Adb devices } |
    Select-String -Pattern '^emulator-\d+' |
    ForEach-Object { ($_ -split '\s+')[0] } |
    Select-Object -First 1
Write-Step "Booted. Serial: $serial"

# --- Handoff to WSL --------------------------------------------------------------------------
Write-Host ''
Write-Host '─────────────────────────────────────────────────────────────────────'
Write-Host ' Emulator is up. Leave this window open, then from WSL:'
Write-Host ''
Write-Host '   export ADB_SERVER_SOCKET=tcp:127.0.0.1:5037'
Write-Host '   adb devices        # should list' $serial
Write-Host ''
Write-Host ' That points WSL at this Windows adb server instead of starting its own.'
Write-Host ' It works because .wslconfig has networkingMode=Mirrored, so WSL and Windows'
Write-Host ' share localhost (see docs/development/running-on-device.md).'
Write-Host '─────────────────────────────────────────────────────────────────────'
