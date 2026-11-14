# RF Lab research plan

1. Inventory public Wi-Fi/UWB/sensor capabilities and log measurements reproducibly.
2. Validate WLAN RSSI and available Wi-Fi RTT against known distances (RTT requires compatible APs).
3. Add ARCore 6DoF localization while keeping camera-derived geometry separate from RF-derived geometry.
4. Investigate vendor-approved CSI/IQ paths; normal Android APIs do not expose coherent RF phase.
5. Create synthetic multiband radio propagation benchmark; test sparse, multi-view, multistatic and material inverse problems.
6. Validate RF-derived properties with ground truth. Never label optical geometry as RF reconstruction.

Do not assume Wi-Fi network scan readings or UWB peer-ranging distance are radar reflections. No background uploads. CSV/JSONL may expose local Wi-Fi identifiers.
