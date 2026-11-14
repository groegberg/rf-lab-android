# Pixel 10 Pro XL ('mustang') — access to RF PHY measurements

Research state: 2026-11-14. Target: stock Android, no root first. This is an investigation, NOT proof of raw CSI/CIR access on Pixel 10 Pro XL.

## Bottom line

The RF Lab app reads public WiFi metadata (RSSI, BSSID, 802.11 information elements, MLO metadata), IMU and ARCore visual-inertial poses. Android does **not** expose conventional WiFi CSI / complex IQ through the standard app APIs.

There are **three** targeted investigations worth pursuing:

1. **UWB CIR diagnostic capability**: Android's public AOSP *HAL documentation* specifies vendor UCI TLVs `SUPPORTED_DIAGNOSTICS=0xE7`, `ENABLE_DIAGNOSTICS=0xE8`, `DIAGRAMS_FRAME_REPORTS_FIELDS=0xE9`, and CIR mask bit `0x04`, plus `ANDROID_RANGE_DIAGNOSTICS` notifications. This is a privileged HAL / firmware path, NOT an application API. Whether Pixel firmware supports this must be checked. CIR in this context normally requires an active UWB ranging session and cooperating peer. It is not a freely steerable radar receiver.
2. **WiFi driver / raw 802.11 frame path**: The Pixel 10 Pro XL device codename is `mustang`. A community analysis of Google's Quick Share 'mosey' path reports a Broadcom BCM4398 and native `wondertap`/monitor interface for raw 802.11 packet data. This is not independently confirmed for the exact current firmware, and packet sniffing does NOT return CSI or raw IQ. Nexmon's upstream CSI extractor does not list BCM4398 among its supported extractor firmware builds. Kernel module and driver version verification is the next step.
3. **Bluetooth LE Channel Sounding phase-related HCI reports**: The Bluetooth 6 HCI defines `LE CS Subevent Result` reports, and Android supports HCI snoop logs. If Pixel really supports CS *and* a CS-capable peer is present, a debugging capture might contain phase-correction or timing step data. The normal Android Ranging API exposes estimated range, not the full PHY measurements. Availability is unverified and firmware/HCI logging may redact or aggregate steps.

## Safe, read-only diagnostics (ADB; no root or firmware changes)

Use Google's official Android SDK Platform Tools and authorize USB debugging on the phone. Enter commands from PowerShell, a macOS/Linux terminal, or another ADB host:

```sh
adb devices
adb shell getprop ro.product.device
adb shell getprop ro.build.fingerprint
adb shell uname -a
adb shell pm list features
adb shell ls /sys/class/net
adb shell ls /sys/class/ieee80211
adb shell cat /proc/modules
adb shell cmd -l
adb shell dumpsys uwb
adb shell dumpsys wifi
adb shell dumpsys bluetooth_manager
```

No output, `Permission denied`, `unknown service`, or unavailable interface is a valid negative diagnostic. Do not assume the shell has privilege to access vendor HAL, chip firmware or monitor interfaces.

**Inspect and redact** MAC addresses/BSSIDs, WiFi SSIDs, serial number, build user data, keys, peer addresses, location information, etc. before sharing; full bugreports can be very sensitive. Prefer reporting a few relevant capability/status lines instead of sharing unfiltered dumps.

Follow-up evidence to look for:
- `mustang` in `ro.product.device`.
- WiFi module names `bcmdhd`, `bcm4398` and possible `wonder`/monitor interfaces.
- `android.hardware.bluetooth_le.channel_sounding` in `pm list features`. Bluetooth 6 branding alone is NOT sufficient.
- UWB adapter active/available, chip capability flags and whether the dump contains `SUPPORTED_DIAGNOSTICS`; lack of such flags in dumpsys is inconclusive.
- `cmd -l` list includes `uwb` or other radio services. Use their help/status commands only after reading documented interface; never issue unverified vendor UCI writes.

## Experimental HCI capture

Only after confirming Channel Sounding support and having a compatible, consenting CS-enabled peer:
1. Developer options -> 'Bluetooth HCI snoop log' on. Toggle Bluetooth off/on.
2. Start an ordinary CS ranging session using the public Android Ranging API.
3. Generate a local bugreport (this may contain sensitive device information), and extract BTSnoop as described in official Android documentation.
4. Inspect in Wireshark for `LE CS Subevent Result` and continued results. The existence and completeness of phase-related step information is a hypothesis, not a guaranteed capability.
5. Disable full HCI snoop logging afterward.

## Privileged UWB investigation if capabilities suggest CIR support

Only if ADB/standard APIs identify a plausible route, use *another test device* or OEM developer/engineering build to research vendor HAL/UCI interface. Root alone does not make a firmware feature exist and introduces data wipe, reduced security, firmware compatibility and boot risks. Prefer official developer kit with documented CIR export if handset vendor path is closed.

## Source references

- Official Pixel 10 Pro XL codename: https://source.android.com/docs/setup/reference/build-numbers
- AOSP UWB diagnostics TLVs incl CIR: https://source.android.com/docs/core/connect/uwb-hal-interface
- AOSP UWB UCI packet definitions: https://android.googlesource.com/platform/external/uwb/+/refs/heads/master/src/rust/uwb_uci_packets/uci_packets.pdl
- AOSP Android Ranging API: https://developer.android.com/develop/connectivity/ranging
- Community `wondertap` analysis (unverified for current device/firmware): https://github.com/thelok1s/mosey-extended
- Official Broadcom patch framework: https://github.com/seemoo-lab/nexmon
- Nexmon CSI supported chips: https://github.com/seemoo-lab/nexmon_csi
- AOSP Bluetooth HCI log guide: https://source.android.com/docs/core/connect/bluetooth/verifying_debugging
- Bluetooth Core HCI specification: https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-62/out/en/host-controller-interface/host-controller-interface-functional-specification.html

## Important scientific distinction

Raw 802.11 monitor mode frames = MAC-layer packets, not radio I/Q or per-subcarrier CSI. UWB FiRa CIR diagnostics are sampled/channel-related data from a ranging link when exposed; do not equate them with passive radar images. Bluetooth PBR phase correction terms relate to two active peers, not necessarily to arbitrary reflective object imaging.

## 2026-11-14 follow-up: what root can and cannot unlock

**Root is a permission escalation, not a firmware feature switch.** It can provide shell access to driver ioctls, some device nodes, logging, tracing and vendor HAL files, but cannot create unimplemented IQ or CSI export functionality.

### (A) Broadcom Wi-Fi driver: actual low-level CSI debug structures

AOSP publishes a BCM4398-specific Broadcom driver source tree with `include/ftm_ioctl.h` and `include/bcmerror.h`. The related Broadcom FTM header (publicly inspectable in the BCM4383 family tree) defines:

- `WL_FTM_EVENT_CSI_DUMP = 22`, explicitly described as a raw CSI dump for debugging;
- `WL_FTM_TLV_ID_CSI_DUMP = 1037`, `wl_ftm_csi_dump_t` with variable-length sample payload;
- `CSI_DUMP_FLAG_RX_CSI_DATA`, `CSI_DUMP_FLAG_PASSIVE_STA`;
- `WL_FTM_TLV_ID_TIMESTAMP_DUMP_V1 = 1041`, and four timestamp fields for FTM sessions;
- a CSI ranging parameter structure and related 802.11az debug events.

The BCM4398-specific `bcmerror.h` also contains `WL_FTM_E_NO_CSI_DATA` and `WL_FTM_E_PHY_CSI_FATAL_ERR`. This points to a relevant potential driver/firmware pathway. However, **the CSI definitions have not yet been checked directly in the precise BCM4398 revision shipped on the user's November 2026 Android 17 build**, and **no live CSI output has been verified**. Some header features may be compiled out, diagnostic-only, or require negotiated 802.11az/FTM ranging with a cooperating peer. An unsupported AP cannot be turned into an FTM responder by rooting only the phone.

The `wondertap` native monitor mechanism reported for Pixel 10/BCM4398 is intended for `mosey`/Quick Share 802.11 action frames. With root, it may be possible to observe or control the monitor interface. **It is not a CSI dump and does not inherently include PHY-level amplitude/phase or arbitrary Wi-Fi traffic.**

Research order after root:
1. Record firmware/driver version and check stock kernel header matching the exact device build;
2. Identify available driver ioctls, tracepoints and debug event IDs without making firmware modifications;
3. Determine whether `proxd`/FTM CSI TLVs are really supported, and whether a licensed consenting FTM peer is required;
4. Only then prototype a constrained read-only capture and validate that payload is complex CSI rather than diagnostic counters.

Official source references:
- BCM4398 AOSP driver source: https://android.googlesource.com/kernel/google-modules/wlan/bcmdhd/bcm4398/+/refs/tags/android-16.0.0_r0.0
- Broadcom FTM example header: https://android.googlesource.com/kernel/google-modules/wlan/bcmdhd/bcm4383/+/3b1a081640892e16ac658f66a0b0d026735922fe/include/ftm_ioctl.h
- BCM4398 CSI-related error codes: https://android.googlesource.com/kernel/google-modules/wlan/bcmdhd/bcm4398/+/refs/tags/android-16-beta-2_r0.4/include/bcmerror.h

### (B) UWB CIR: Android HAL and UCI packet structure

The Android HAL documents `SUPPORTED_DIAGNOSTICS=0xE7` (UWB vendor capability), `ENABLE_DIAGNOSTICS=0xE8` (session config), `DIAGRAMS_FRAME_REPORTS_FIELDS=0xE9` (session config), with the CIR reporting bit `0x04`. `ANDROID_RANGE_DIAGNOSTICS` is available only if supported. The AOSP Rust UCI packet definition contains `CirValue` including first path / peak path data and a variable `sample_window` byte array. This is *not* proof that Pixel 10 firmware exposes CIR or that the raw values are calibrated for imaging.

Root would help inspect HAL capabilities, implementation-specific diagnostics, and UCI message transport. A UWB-compatible peer and active ranging session may be required. SELinux and HAL service exclusivity remain obstacles even for root. Do **not** send untested raw UCI control commands to the radio as a first experiment.

Official sources:
- https://source.android.com/docs/core/connect/uwb-hal-interface
- https://android.googlesource.com/platform/external/uwb/+/f6b8908a080cc359e8a0a11ada390c20fde674bd/src/rust/uwb_uci_packets/uci_packets.pdl

### (C) Bluetooth Channel Sounding: HCI without root first

The Bluetooth SIG documents `LE CS Subevent Result` and `LE CS Subevent Result Continue`, including step-specific measurements such as phase correction and timing. Android Bluetooth HCI snoop logs can be created via Developer Options and a bugreport on production devices. Phase-related step data should only be expected if the phone **and its counterpart** actually support and operate Bluetooth Channel Sounding. Root may make the snoop file easier to access; it is not necessarily required.

- https://www.bluetooth.com/channel-sounding-tech-overview/
- https://source.android.com/docs/core/connect/bluetooth/verifying_debugging

### (D) Root boot/update risks and prudent decision

Magisk's official method requires an unlocked bootloader and a matching patched firmware image; Android GKI devices generally use `init_boot`. Unlocking wipes data. Google's Pixel factory image documentation specifically warns that the May 2026 Pixel 10 bootloader update raised an anti-rollback index, forbidding downgrade to earlier vulnerable builds. Do **not** flash an older Pixel 10 image or someone else's modified boot image. An incorrect flash can cause a bootloop or require a full restore; a rooted/unlocked device can lose some app security/integrity functions. Rooting for RF Lab is **not recommended until non-rooted capability checks have been examined**.

- https://github.com/topjohnwu/Magisk/blob/master/docs/install.md
- https://developers.google.com/android/images

### Decision matrix

| Payload | Likelihood of reaching data with root | Additional blocker |
| --- | --- | --- |
| Raw/monitor 802.11 frames | plausible on BCM4398 / wondertap | interface, privileges, support for desired frame types |
| CSI (complex per-subcarrier) | plausible research lead, unconfirmed | running firmware debug FTM/CSI event, potentially compatible 802.11az peer |
| FTM four-way timestamps / RTT debug | plausible, unconfirmed | firmware implementation and compatible FTM responder |
| UWB CIR | official framework data format, device support unconfirmed | diagnostic capability, UWB peer, privileged HAL |
| Bluetooth CS phase terms | possible WITHOUT root | actual CS hardware capability and peer |
| continuous baseband I/Q | no verified stock Pixel extractor | custom firmware or external SDR |

**Never equate "public struct exists" with "data works on this Pixel."** The first experiment should be read-only inventory on a stock device (driver/firmware modules; UWB/Bluetooth capabilities) before evaluating whether to dedicate a research Pixel to root.
