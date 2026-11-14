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
