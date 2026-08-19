# Running on a physical device from WSL

Goal: build inside WSL and run on a physical Samsung Galaxy S25, installing as
little as possible on root Windows.

Every claim below is marked **[verified]** (observed on this machine, 2026-08-19,
WSL2 on Windows build 10.0.26200, `wsl --version` 2.9.4.0) or **[unverified]**
(from official docs only, not observed here). Steps that require physically
tapping the phone are marked **[needs developer]** — an agent cannot do them.

## Connectivity approach: wireless debugging, zero Windows installs

**Chosen approach: adb wireless debugging over the LAN, run entirely from
inside WSL. Nothing needs to be installed on root Windows.**

### Why this works on this machine [verified]

This machine's `.wslconfig` already has `networkingMode=Mirrored` (Windows 11,
build 26200, which is 22H2+, so mirrored mode is available). Per Microsoft's
WSL networking docs, mirrored mode's stated benefit is "Connect to WSL
directly from your local area network (LAN)" — WSL's network interfaces
mirror the host's, instead of sitting behind WSL's own NAT.

Observed on this machine:
- `ip addr` inside WSL shows `eth2` with `192.168.0.106/24` — a real address
  on the host's LAN subnet, not a NAT-only `172.x` WSL address.
- `ping 192.168.0.1` (the LAN gateway) from inside WSL succeeds.

Because of mirrored networking, `adb` running inside WSL makes outbound TCP
connections (`adb pair`, `adb connect`) directly onto the LAN using the host's
real interface. This is an *outbound* connection initiated by WSL, so no
inbound Windows Firewall rule or `usbipd`/port-proxy setup is needed — the
phone sees a normal LAN client connecting to it, the same as it would see
Windows itself.

**If your machine uses the default NAT networking mode instead** (check
`networkingMode` in `%UserProfile%\.wslconfig`; absence of the key or
`networkingMode=NAT` means NAT), this approach is [unverified] to work as-is:
NAT mode does not by default expose WSL's outbound traffic as coming from the
host's LAN identity in all router/AP configurations, and inbound-initiated
flows definitely don't reach WSL without a port proxy. In practice `adb
connect` is still an outbound WSL→phone connection either way, so it likely
works under NAT too as long as the phone and Windows host are both on a
routable LAN and the AP doesn't do client isolation — but this was not tested
on NAT mode here. If it doesn't work, fall back to Approach 2 below.

### What is installed where [verified]

- Inside WSL only, under `~/android-sdk-tools/` (not committed to the repo,
  not on Windows):
  - Eclipse Temurin JDK 17 (tarball from `api.adoptium.net`)
  - Android `cmdline-tools` (`commandlinetools-linux-15859902_latest.zip`
    from `dl.google.com`)
  - `platform-tools` (adb 1.0.41, packaged as `platform-tools_r37.0.1`),
    `platforms;android-36`, `build-tools;36.0.0`, installed via `sdkmanager`
- **Nothing installed on root Windows.** No `usbipd-win`, no Windows adb, no
  Android Studio anywhere.

Point `local.properties` (gitignored, not committed) at your SDK root, e.g.:

```properties
sdk.dir=/home/<you>/android-sdk-tools/sdk
```

## One-time setup

### 1. SDK inside WSL [verified — done on this machine]

```bash
# JDK 17
mkdir -p ~/android-sdk-tools && cd ~/android-sdk-tools
curl -fsSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk.tar.gz && rm jdk.tar.gz   # extracts to jdk-17.x.y+z/

# Android cmdline-tools (check developer.android.com/studio for the current
# version number if this URL 404s)
mkdir -p sdk/cmdline-tools
curl -fsSL -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
unzip -q cmdline-tools.zip && mv cmdline-tools sdk/cmdline-tools/latest && rm cmdline-tools.zip

export JAVA_HOME=~/android-sdk-tools/jdk-17.0.20+8   # match your extracted dir
export ANDROID_SDK_ROOT=~/android-sdk-tools/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

yes | sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses
sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

**[verified, 2026-08-19, first real build]** The app's `compileSdk` is `37`,
one ahead of what step above installs. `./gradlew assembleDebug` fails at
resource processing until you also install the SDK 37 platform and matching
build-tools. Note the package IDs are `platforms;android-37.0` and
`build-tools;37.0.0`, not the plain `platforms;android-37` you'd guess by
analogy with the SDK 36 packages — `sdkmanager --list` is the source of
truth if a future `compileSdk` bump 404s again:

```bash
sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  "platforms;android-37.0" "build-tools;37.0.0"
```

Add the `export` lines (with your real paths) to `~/.bashrc`/`~/.zshrc` so
every shell picks up `adb`, `java`, etc.

Create `local.properties` at the repo root (gitignored):

```bash
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
```

`adb version` and `adb devices` after this step **[verified]**:

```
Android Debug Bridge version 1.0.41
Version 37.0.1-15733141
Installed as /home/<you>/android-sdk-tools/sdk/platform-tools/adb
...
List of devices attached
```

(empty device list — expected, nothing paired yet.)

### 2. Enable developer mode and wireless debugging on the S25 [needs developer]

These steps require tapping the physical phone; they were not and could not
be performed by this agent. On the Galaxy S25 (One UI, current as of Android
16 / One UI 8):

1. **Settings → About phone → Software information → tap "Build number" 7
   times** to unlock Developer options (you'll see a countdown toast and a
   PIN/pattern prompt).
2. **Settings → Developer options → Wireless debugging** → toggle on. Accept
   the "Allow wireless debugging on this network?" dialog.
3. Ensure the phone is on the **same Wi-Fi network/subnet** as the Windows
   host (same `192.168.0.0/24` here, not a guest network with client
   isolation — client isolation on the AP would block this even though
   routing itself is fine).
4. Tap **Wireless debugging → Pair device with pairing code**. Note the IP,
   port, and 6-digit code shown — this pairing port is different from, and
   only used once for, the initial pairing.

### 3. Pair and connect from WSL [needs developer input, then verifiable]

With the code/IP/port from step 2.4:

```bash
adb pair <ip>:<pairing_port>       # enter the 6-digit code when prompted
adb connect <ip>:<connect_port>    # connect_port shown on the main
                                    # "Wireless debugging" screen, NOT the
                                    # pairing port
adb devices                        # should list the phone as "device"
```

**[verified, 2026-08-19]** Completed once by the developer (one-time
phone-side taps: enable Developer options, toggle Wireless debugging, pair
with the 6-digit code). After that, `adb devices` lists the S25 as `device`
and stays that way across the whole session — this is the actual acceptance
bar for this issue and is now met.

`adb devices -l` legitimately lists the **same physical phone twice** once
paired: once by its live `ip:port` transport, once by its mDNS service name
(`adb-<serial>-xxxx._adb-tls-connect._tcp`). Both lines report the same
`product:`/`model:`/`device:` fields, just different `transport_id`s. This
is normal — don't treat it as "two devices attached".

## Per-session reconnect

**[verified, 2026-08-19]** Wireless debugging's connect port changes after
the phone reboots or reconnects to Wi-Fi. Pairing itself is persistent
across reboots — you do **not** need to read a new pairing code off the
phone each session. What you need is the current connect port, and mDNS
discovery gets it without touching the phone at all:

```bash
adb mdns services
# List of discovered mdns services
# adb-<serial>-xxxx  _adb-tls-connect._tcp  <phone-ip>:<current-port>
adb connect <phone-ip>:<current-port>
adb devices          # confirm `device` state
```

If `adb mdns services` prints nothing, mDNS discovery itself has dropped
(e.g. Wireless debugging got toggled off, or the phone left the LAN); only
then do you need to go to the phone's **Developer options → Wireless
debugging** screen and read the IP:port directly, or re-pair from scratch
if "Forget" / "Revoke adb debugging authorizations" was tapped.

## Running the app

```bash
./scripts/run-on-device.sh
```

This builds the debug APK via `./gradlew installDebug`, installs it on the
one connected device, and launches its main activity via `monkey -p
<applicationId> -c android.intent.category.LAUNCHER`. It fails loudly if:
- `adb` can't be found (checks `$ADB_BIN`, then `$PATH`, then
  `sdk.dir` in `local.properties`),
- `$JAVA_HOME` is set but `$JAVA_HOME/bin/java` doesn't exist or isn't
  executable, or neither `$JAVA_HOME` nor `java` on `$PATH` is available,
- no device ends up in `device` state,
- the `applicationId` parsed out of the build file doesn't match the
  Android package-name charset (defense in depth before it's passed to
  `adb shell`, since `adb shell` re-joins argv for the remote shell).

Device resolution handles the messy reality of wireless debugging rather
than aborting on it:
- A stale `offline`/`unauthorized`/`no permissions (...)` entry (left
  behind by, say, a phone reboot that changed the connect port, with the
  old transport still listed) does **not** abort the run as long as at
  least one device is in `device` state — it's skipped with a `WARNING` on
  stderr naming the stale transport and its state. Only if **no** device
  ends up ready does the run fail, and then the error names the actual
  blocking state per transport (`unauthorized` → accept the prompt on the
  phone; `offline` → reconnect) instead of a generic message.
- Among the ready (`device`-state) transports, physical identity is
  resolved by hardware serial (`adb shell getprop ro.serialno`, falling
  back to `ro.boot.serialno`), not by `product:`/`model:`/`device:` fields
  — two different phones of the same model report identical
  product/model/device strings, so only the actual serial can tell them
  apart. Transports that resolve to the same serial (e.g. a phone's live
  `ip:port` connection and its mDNS service name, both listed at once) are
  collapsed to one target; transports with genuinely different serials
  abort with "Multiple distinct devices found" and ask you to set
  `ANDROID_SERIAL`. If a serial can't be read at all -- or reads back as
  empty/whitespace-only, or contains characters outside the expected
  serial charset (letters, digits, `-`, `_`, `.`) -- the run aborts rather
  than guessing it matches another entry.

### Dry-running device resolution without a phone

Set `RUN_ON_DEVICE_DRY_RUN=1` and the script stops right after picking a
target device, before the build/install/launch step:

```bash
RUN_ON_DEVICE_DRY_RUN=1 ./scripts/run-on-device.sh
```

It exits 0 in this mode but prints an explicit `STOPPED EARLY` line on
stderr saying nothing was built, installed, or launched -- a dry run can
never be mistaken for a completed deploy. This exists so the device-
selection logic (stale-transport handling, same-device-vs-different-device
disambiguation) can be exercised against a stub `adb` without a paired
phone; it runs after all safety/validation checks, so it cannot be used to
skip any of them.

**[verified, 2026-08-19, first real run against physical hardware]** Ran
end-to-end against the paired S25 with a clean shell (no `adb`/`java` on
`$PATH`, relying purely on `local.properties`/`$JAVA_HOME` discovery):

```
[run-on-device] Using adb: /home/<you>/android-sdk-tools/sdk/platform-tools/adb
[run-on-device] Note: device listed under multiple transports (<ip>:<port> adb-<serial>._adb-tls-connect._tcp); using <ip>:<port>.
[run-on-device] Target device: <ip>:<port>
[run-on-device] Building and installing debug APK (./gradlew installDebug)...
...
Installed on 1 device.
BUILD SUCCESSFUL
[run-on-device] Launching cc.machado.audioblackbox on <ip>:<port>...
[run-on-device] Done. cc.machado.audioblackbox is installed and launched on <ip>:<port>.
```

Confirmed the app was actually running (not just "launched" per monkey's
exit code) via `adb -s <serial> shell pidof cc.machado.audioblackbox`
returning a live PID, and a `logcat -d --pid=<pid>` slice showing
`MainActivity` composing and drawing its first frame.

The Gradle project skeleton (issue #1 / PR #8) is merged into `main`, so
`gradlew` and `app/build.gradle.kts` are present and `./gradlew installDebug`
has a real app to build and install. The no-device failure path **is**
verified [verified] — running the script with no phone attached produces:

```
[run-on-device] Using adb: /home/<you>/android-sdk-tools/sdk/platform-tools/adb
[run-on-device] ERROR: No device attached. Reconnect via wireless debugging (adb connect <ip>:<port>) or plug in USB, then re-run. See docs/development/running-on-device.md.
```
and a non-zero exit code.

## Troubleshooting (failure modes actually hit)

- **`adb devices` prints nothing under "List of devices attached"`** — no
  pairing has happened yet, or the phone dropped off Wi-Fi. [verified] this
  is the exact state observed on this machine before phone-side setup.
- **`unauthorized`** — the phone is showing (or already dismissed) an "Allow
  debugging?" dialog; unlock the phone and accept it, or if the dialog was
  missed, `adb disconnect` and reconnect to get a fresh prompt.
- **`offline`** — usually a stale connect port from a previous session
  (rebooting the phone or the router changes it); `adb disconnect <old>` then
  `adb connect <ip>:<new-port>` from the current Wireless debugging screen.
- **AP client isolation** — some Wi-Fi networks (guest networks, some
  corporate/public APs) block device-to-device traffic even though both are
  "on the network"; `adb connect` will time out. Put the phone on the same
  home LAN as the Windows host, or use Approach 2/3 below. [unverified
  root-cause diagnosis for this specific network — general documented
  failure mode].

## Fallback approaches

### Approach 2: `usbipd-win` (USB passthrough) — requires a Windows install

If wireless debugging is blocked (AP isolation, phone and PC on different
subnets, corporate network policy), attach the phone over USB into WSL using
[`usbipd-win`](https://github.com/dorssel/usbipd-win):

```powershell
winget install usbipd
usbipd list
usbipd bind --busid <busid>
usbipd attach --wsl --busid <busid>
```

Then `adb devices` inside WSL should show the USB-attached phone. **Trade-off:**
this requires a Windows-side install (`usbipd-win`, via `winget`) plus
re-running `usbipd attach` after every replug/reboot, and a physical USB
cable — it's the standard, well-documented path but violates the "nothing on
Windows" preference. [unverified — not exercised in this session, no need
arose since wireless debugging's network prerequisites were confirmed met].

### Approach 3: adb server on Windows, client in WSL — requires a Windows install

Install platform-tools on Windows and run `adb.exe` as the server (`adb.exe
start-server`, bound to `127.0.0.1:5037`), then point WSL's `adb` client at it
over TCP (`ADB_SERVER_SOCKET=tcp:<windows-ip>:5037` or, under mirrored
networking, `tcp:127.0.0.1:5037` since mirrored mode aliases `localhost`
between Windows and WSL). **Trade-off:** requires installing Android
platform-tools on Windows; only worth it if `usbipd` device passthrough is
also unavailable (e.g. locked-down USB policy) but a Windows-side adb is
allowed. [unverified — not exercised, listed as documented fallback only].

## Summary: what's on root Windows and why

**Nothing**, for the approach actually used (wireless debugging + mirrored
WSL networking, both already present on this machine). The two fallbacks
above each need one Windows-side install (`usbipd-win` or Windows
platform-tools) and are only worth reaching for if wireless debugging is
network-blocked; neither was necessary here since LAN connectivity from WSL
was directly confirmed.
