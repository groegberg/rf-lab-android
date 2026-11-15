# RF Lab Spatial Home v0.4.0

Android 12+, no root. This is the first functional foundation of Spatial Home, not the deployment of the entire multiversion product roadmap.

## What's actually available

- New main navigation: HOME / WOHNUNG / EINMESSEN / AUTOMATION / LAB / EXPERTEN.
- Local schematic apartment plan with named rooms and meter coordinates. Add/remove rooms; rooms cannot overlap.
- Smart lamp and plug inventory, manual positioning on the room plan. These are user-defined device aliases, not Alexa-connected devices.
- Calibration with fresh Wi-Fi scans at user-placed reference points. Multi-BSSID RSSI samples are stored locally and remain bound to one room/point.
- Android scan callbacks are timestamp-filtered; cached or repeated scans do not count as new measurements. Active scan requests default to 35 seconds, subject to Android limits.
- Room inference using RSSI fingerprint matching. Missing APs are penalized, stale measurements fail closed, ambiguous rooms are UNKNOWN.
- Ranked per-room candidate weights and explanations; weights are **not yet statistically calibrated confidence probabilities**.
- Repeated measurements: up to 35 sweeps per anchor. Median per BSSID suppresses individual RSSI outliers.
- Spatial proximity preview with an approximate 2D position and a conservative uncertainty disk only after at least 3 noncollinear-ish anchors spread in both axes of the same recognized room.
- Rules with minimum dwell time; when the model is stable, the engine logs **simulation-only** actions. No Smart Home device is switched.
- Leave-one-entire-position-out validation to expose overfitting rather than testing each anchor against itself.
- Home-map JSON export/import with limits, schema validation and rollback on corrupt input.
- Manual Wi-Fi survey CSV migration from v0.3.1; this retains the old median RSSI data, not the original sample distributions.
- LAB opens the previous Field Studio / diagnostics and a new live Magnetic Lab that records sensor XYZ/|B| and exports CSV.
- Alexa Smart Home MotionSensor offline payload adapter and tests under `integrations/alexa/`.

## Quick setup on the Pixel

1. Install APK; if Android reports conflicting signatures, **export old session JSONL/manual survey CSV first**, then uninstall the old app and install the new build. Private app data are deleted on uninstall.
2. Open WOHNUNG, create at least **two non-overlapping rooms**. Specify each room's X, Z, width, depth in meters.
3. WOHNUNG > Gerät hinzufügen creates a lamp or plug alias; tap the floorplan to move the selected device.
4. Open EINMESSEN, select a room, tap a reference position, then **Am Punkt messen**. Wait until the display confirms at least 2–4 fresh Wi-Fi sweeps. Android may only permit ~4 requested scans per two minutes while in the foreground.
5. Pause recording, physically move to the next position, tap a new anchor. Prefer **3+ spread-out anchors per room** and measure at different phone orientations.
6. Repeat for at least a second room. HOME > Ortung starten; navigate between rooms and compare the displayed room and its alternatives. This mode operates while the app is visible.
7. Open AUTOMATION, enable a test rule for a room. With stable scans and sufficient score separation, the app adds a **SIMULATION** log entry. It does not send Alexa commands.
8. In EXPERTEN export a JSON backup. Restore from the same menu if the APK must be reinstalled.

## Evaluation notes

- Room classification requires at least 3 common valid BSSIDs. There must be trained anchors in at least two rooms.
- The classifier rejects stale scans older than 90 seconds and poor/marginal matches. A percentage on the HOME screen represents a heuristic normalized match weight and should not be interpreted as calibrated posterior probability.
- The device-proximity circle is indicative, bounded below by a 1.5 m radius, and not an accuracy guarantee. Wall boundaries dominate any proximity decision.
- Held-out anchor validation is meaningful only if at least two different locations exist in each trained room. Data from the held-out coordinate are excluded from its training references.
- Default scan frequency can be throttled by the platform. Wi-Fi in the background, a locked phone or a sleeping app cannot reliably support second-by-second room transitions.
- A phone left on a table tracks the **phone**, not a walking person.

## Alexa status

Live Alexa integration is **NOT enabled**. A legally and technically valid Smart Home add-on needs an Alexa Developer account, a deployed cloud endpoint, OAuth 2.0 account linking, Alexa Authorization AcceptGrant, per-user LWA event tokens, and verification that de-DE virtual MotionSensor events can trigger the desired routines. See `integrations/alexa/README.md`.

Amazon expects at least 30 seconds between DETECTED and NOT_DETECTED changes. Unknown smartphone location must not be equated with an absent person.

## Research modules and future milestones

- v0.5: larger ground-truth datasets, model calibration and more robust room classifier.
- v0.6: opt-in background feasibility experiments (foreground service + notification), battery/scan monitoring and possibly auxiliary BLE beacon hardware.
- v0.7: Alexa Developer sandbox, secure cloud bridge, per-user account linking and voluntary sensor publication.
- v0.8: ARCore device-placement assistant, smart lamp visual identification and advanced adaptive measurement planning.
- v0.9+: acoustic echo recording, magnetically registered 3D maps, multimodal change detection and custom external SDR/radar adapters.

## Privacy and safety

No INTERNET permission is requested in the current APK. The Wi-Fi radio map, devices, locations, and simulation records remain in the app's private local storage. All routines are disabled unless explicitly configured for **simulation**. Smart plugs and other physical appliances are never switched by this release.
