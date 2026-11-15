# RF Lab Spatial Home v0.5.0 — Guided 3D Setup

This update replaces the technical dashboard as startup UI with a beginner-oriented five-stage workflow. It does not remove any legacy survey or diagnostics tools.

## 1. Kamerascanner
- Open the first stage and tap **Kamera & 3D-Scan öffnen**.
- Allow the camera, point it at well-lit textured walls and furniture, slowly move while ARCore establishes TRACKING.
- The screen shows the real ARCore live camera preview and an accumulating **3D optical point cloud** with a cyan camera trajectory.
- Point colors are an **artificial height scale**, not true RGB textures or a reconstructed triangle mesh.
- Rotate cloud with one finger; pinch to zoom. Save when at least 50 real depth points and 3 valid tracking poses have been captured.
- Scan data are saved locally in a single optical ARCore coordinate frame. The old Field Studio JSONL recorder also logs optical poses/depth frames.
- Do not attempt to merge two separate ARCore sessions: they have different coordinate origins. A new scan replaces the previous scan only after confirmation and is blocked while mapped rooms are present.

## 2. Räume direkt im Scan festlegen
- Open **RÄUME**, examine the colored top-down point cloud.
- Tap **two opposite corners** to define a rectangle. Name it, then repeat for the next room.
- Multiple rooms can be marked against the **same saved scan**. Coordinates use a fixed translation from that scan's AR world frame into positive floorplan coordinates.
- Room rectangles must not overlap. The first version does not yet infer walls or arbitrary room polygons automatically.
- A manual floorplan editor remains accessible if ARCore tracking is unavailable in darkness.

## 3. Physikalische Raumprofile einmessen
- **RF:** Take user-anchored, fresh Wi-Fi RSSI fingerprint scans in at least two rooms. There is no invented metric location from RSSI.
- **Magnetic:** Select a room, record real X/Y/Z Android magnetometer values, optionally save a median and magnitude variation as an experimental room-level signature; CSV is also available.
- **Acoustic:** Select a room, use an **audible low-amplitude 1.3–6.5 kHz chirp** (requires microphone permission), compute a matched-filter echo profile, save 64 relative temporal bins to that room and optionally export CSV.
- Acoustic data are *not* calibrated absolute depth; Android audio latency, speaker/mic cross-talk and signal processing can produce artefacts. Avoid measuring near ears or at high media volume.
- Magnetic XYZ values are in the **device frame** and depend on phone orientation. They are not automatically transformed into a 3D volume.

## 4. Geräte verorten
- Add lamps, plugs, or **Access Point / Router** entries to the floorplan and tap to place them.
- For a mapped AP, optionally select a **BSSID actually measured** during WLAN calibration. One physical AP or mesh node can expose several BSSIDs, and the hardware is not located automatically.
- Alexa devices remain user-defined aliases; no automatic cloud enumeration or device control in this release.

## 5. Automatisierung planen
- Open the rules editor; simulate stable room transitions and proposed lighting actions.
- No live Alexa commands. The Alexa Smart Home MotionSensor message adapter is under integrations/alexa, awaiting OAuth credentials, a deployed bridge and verified de-DE routine support.

## Backup and installation
- On the **3D-SCAN** or **AUTOMATION** step, use **Wohnung sichern / Komplettes Backup** to export a single JSON file containing the spatial home, sparse optical 3D cloud and saved magnetic/acoustic profiles.
- A matching **Backup laden** option restores those files with schema validation and rollback on failure.
- This full backup does **not** include legacy JSONL sessions or actual microphone PCM files; export historical experiments separately.
- If installing over v0.4.0 fails due to CI debug key mismatch, export the old **Spatial Home JSON** and any manual survey CSV before uninstalling. Uninstall deletes private app data.
- Version v0.4.0 did not yet save an optical 3D scan or room-level acoustic profiles; a v0.4.0 home JSON can still be restored using the previous **EXPERTEN** home backup importer.

## Test on Pixel 10 Pro XL
1. In good light, start a new 3D scan; wait for ARCore tracking and confirm nonzero **ARCore-Posen** and **3D-Punkte**.
2. Rotate the cloud and save; app should open **RÄUME**.
3. Define two rooms in the scan, check their dimensions and that both are visible.
4. Choose RF calibration in the wizard. Record several fresh Wi-Fi scans and at least three distributed reference points per room.
5. Return to MESSEN and record a short magnetic room profile, then optionally one audible chirp test; compare the profiles to another room.
6. Place a router, select a real measured BSSID and position a lamp and plug.
7. Configure a simulation rule under AUTOMATION and verify no real devices are switched.
8. Export a **Gesamtes Backup**, restore it and check that rooms, optical scan and profiles remain visible.

### Known limitations
- Actual Android hardware runtime has not been tested through CI. Compilation and unit tests alone do not guarantee working ARCore/depth, microphone/audio routing, or permission UX.
- Optical Depth is sparse and can leave incomplete walls, poor edges and holes; highly reflective/transparent or textureless surfaces are challenging.
- Room tagging is by user-drawn rectangles on the X/Z projection, not automatic semantic indoor segmentation or RGB photogrammetry.
- Sequential optical scans cannot currently be merged. A dedicated spatial registration step will be required later.
- There is no measured millimetre-accurate pose and no radio-frequency imaging from a phone alone.
- In normal localization mode Android Wi-Fi scans remain throttled, and the app does not bypass background restrictions.
- Automation is explicitly simulator-only.
