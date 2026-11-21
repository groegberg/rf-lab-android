# RF Lab v1.3.9 — Geometrie, Kamera-IMU-Referenzen und Multi-View-Qualitätsgates

Stand: 19.11.2026. Diese Version baut auf v1.3.8 auf; sie ist ein **diagnostischer
Qualitätsrelease**, **keine** abgeschlossene metrische Pixel-Kalibrierung.

## 0. Korrigierte DA3-Interpretation

Die eingebundene `da3_small_gpu_fp16.tflite` ist die
`litert-community/Depth-Anything-3-Small-LiteRT`-Konvertierung des
`depth-anything/DA3-SMALL`-Modells. Die Ausgabesemantik wird hier als
**affine-invariante direkte relative Tiefe** (kleiner = näher) behandelt.
Vorher wurden die positiven Modellwerte an mehreren Stellen wie inverse
Tiefe behandelt; für diese Konvertierung war das eine unzulässige
Interpretation. Andere DA3-Ports können ausdrücklich **inverse Tiefe**
ausgeben; deshalb niemals zwischen Konvertierungen ohne
spezifische Kontrolle wechseln.

- Heatmap: direkte relative Tiefe ohne `1 / value`.
- 2.5D-Vorschau: normierte Originaltiefe, nicht der Kehrwert.
- Mehrbild-Fit: `Z_vio = scale * D_DA3 + shift`; robuste, positive
  affine Anpassung an **wirklich triangulierte** VIO-Kamera-Strahlen.
- Gesamt-Diagnosekarte: direkte Tiefenreihenfolge.
- Manifest pro Foto: `da3_output_kind`,
  `raw_depth_reciprocal_applied=false`.

**Gleichzeitig:** Auch der korrigierte Rohwert ist **kein Abstand in Metern**.
Der berechnete Fitted-Maßstab bleibt unvalidiert, solange die VIO-Skala,
Kamera-IMU-Extrinsik und Reprojektionen nicht unabhängig geprüft sind.

Referenzen:
- https://huggingface.co/litert-community/Depth-Anything-3-Small-LiteRT
- https://huggingface.co/depth-anything/DA3-SMALL
- https://github.com/ByteDance-Seed/Depth-Anything-3/issues/71

## 1. Echte Kamerageometrie statt künstlichem FOV

Die Einzelbild-RGB-Punktwolke nutzt **wenn verfügbar** echte Camera2-/
OpenVINS-Intrinsiken und radtan-Korrektur, rechnet die tatsächliche DA3-Rotation
(0/90/180/270°) samt mittigem Crop exakt zurück und bildet Pixel entlang der
kalibrierten optischen Strahlen auf relative Tiefe ab.
Ohne passende K bleibt nur ein ausdrücklich **nicht kalibrierter**
FOV-Fallback, und `effective_camera_intrinsics_used_for_preview=false`
erscheint im Manifest.

Wichtig: Ein aus Camera2 näherungsweise abgeleitetes K ist besser als die
vorher hartkodierte Brennweite, ersetzt aber keine Reprojektion mit
Checkerboard und keine sorgfältige pixelgenaue Distortionskontrolle.

## 2. Belichtungszeitgenaue VIO-Posen

Statt jede JPG-Aufnahme auf die **nächstgelegene** OpenVINS-Pose zu runden,
interpoliert `ExternalVioTrajectory.interpolate()` Translation und
Quaternion zwischen benachbarten VIO-Samples auf den
**Camera2-Bildzeitstempel**. Unzulässig lange Lücken (>180 ms) oder
Extrapolation >20 ms werden abgelehnt. Die online-Kamera-Parameter stammen
von der näheren nativen Zustandsschätzung.
Für jedes Foto: `pose_interpolated_to_shutter_timestamp`.

**Grenzen:** Diese Zeitinterpolation korrigiert weder ein systematisches
`timeshift_cam_imu` noch Rolling Shutter. Sie ist keine Camera-IMU-
Kalibrierung und kein Loop Closing.

## 3. Kamera-Kalibrierungsdiagnose direkt in RF Lab

**Bedienung**: OpenVINS-Aufnahme von mindestens 10–20 Bildern eines
**flach ausgedruckten Schachbretts mit 10 × 7 Feldern** (= **9 × 6 innere
Ecken**) aus unterschiedlichen Blickrichtungen, Positionen und Distanzen.
Kameraobjektiv, Zoom, Fokus, Auflösung und Stabilisierung dürfen sich
nicht ändern. Danach Foto-Galerie → **Schachbrett-Kamerakalibrierung prüfen**.

OpenCV erkennt Gitterpunkte und führt eine pinhole/radtan-Kalibrierung aus.
Ausgegeben werden K, k1/k2/p1/p2/k3, Anzahl erkannter Fotos und
Reprojektions-RMS in Pixeln. Mindestens 10 erkannte, ausreichend variable
Bilder und RMS ≤1,0 Pixel sind lediglich **Vorbedingungen** für einen
Kamera-Intrinsik-Kandidaten; für präzise Vermessung sind strengere
Reprojektionsresiduen und unabhängige Aufnahmen nötig.
Das Ergebnis `checkerboard_intrinsics_audit.json` wird im ZIP exportiert.
**Nichts wird automatisch in OpenVINS als gültige Kalibrierung eingesetzt.**
Physische Schachbrett-Feldgröße ist für K nicht erforderlich, wohl aber
für spätere metrische Kamera-IMU-Translation.

`openvins_imu_quality_audit.json` ergänzt im Offline-Lauf tatsächliche
IMU-Samplerate, Messlücken und stationäre Gyroskopmittelwerte. Ein
stationärer Bias-Mittelwert ersetzt **nicht** die vollständige
IMU-Rausch-/Bias-/Extrinsik-Kalibrierung.

Für eine vollständige Pixel-VIO-Kalibrierung wird weiterhin eine auf
demselben Objektiv aufgenommene synchronisierte **AprilGrid-/Kalibr-Sequenz**
mit ausreichend Rotation/Translation in allen Achsen benötigt.
Kamera-IMU-Rotation, Translation, Zeitoffset, Rauschdichten und
Rolling-Shutter-Modell müssen unabhängig geprüft werden.
Siehe https://docs.openvins.com/gs-calibration.html.

## 4. Tracking-Benchmark: physische Referenzen

Fotogalerie → **OpenVINS Referenztest · 1 m / Loop / 90°**.
Nur eine Messaufnahme verwenden, bei der Start- und Endzustand der
**initialisierten** VIO-Posen auch wirklich den physischen Endpunkten
entsprechen:
- **Bekannte Translation**: beliebige gemessene Strecke (0,1–25 m).
  Vergleiche Netto-VIO-Verschiebung mit der bekannten Strecke.
- **Geschlossene Runde**: physische Start- und Endposition identisch.
  Schließfehler / VIO-Pfadlänge; **keine** Korrektur der Posen.
- **90°-Drehung**: kontrollierte reine Handydrehung um 90°.
  Quaternion-basierte relative Drehrichtungs**größe**, nicht deren Achse,
  gegen Referenz prüfen.

Ergebnisse `vio_reference_*.json` können im privaten Session-ZIP
exportiert werden. Solche Ergebnisse sind **keine** zertifizierte
metrische Genauigkeit; insbesondere muss die Initialisierungsphase vor
der physisch gemessenen Bewegung liegen.

Die Tracking-Kandidaten ARCore / OpenVINS / ORB-SLAM3 sollten später
mit identischen Rohbildern und denselben Referenzbewegungen verglichen
werden. Es ist in dieser Version **kein zusätzlicher ORB-SLAM3-Backend**
und kein gleichzeitiges ARCore/OpenVINS-Replay implementiert.

## 5. Mehrbild-Fusion nicht mehr durch bloßes Zusammenführen

Reziproke ORB-Korrespondenzen und triangulierte Camera2-/OpenVINS-Strahlen
richten DA3 pro Keyframe an echter optischer Tiefe aus. Danach prüft
`OpenVinsMultiViewAgreement.verify()`, ob einzelne Punktbereiche von
mindestens **zwei unterschiedlichen Aufnahmen** im gemeinsamen
Weltkoordinatensystem innerhalb definierter VIO-Toleranz wiedergefunden
werden. Fehlende Überlappung sperrt die persistente Fusion; Statuswerte
stehen in `openvins_da3_map_summary.json`.

**Nicht** mit dieser konservativen Stufe verwechselt werden darf
Photogrammetrie mit Bundle Adjustment, verifizierter Kameraskala,
Visibility-/Occlusion-Konfliktlösung, Surfel-/TSDF-Konsolidierung oder
vollständige Loop Closure. Diese benötigen erst verlässliche Kameraposen
und mehr als nur ein Bildpaar.
Für die visuelle Forschung bleibt die ausdrücklich nichtmetrische
Gesamt-Punktwolke verfügbar und zeigt weiterhin approximierte Frames.

## 6. Quantitative Bewertung gewölbter Flächen

Je Einzelbild berechnet `OpenVinsPlanarityAudit` einen robusten
dominanten Ebenenanteil samt normiertem Residuum. Der Bildbrowser
zeigt **Ebenenanteil X % (nur Diagnose)**, und `manifest.json`
speichert die Werte. Damit können wir Versionen an **derselben
physischen Wand** vergleichen. In einer allgemeinen Wohnraumszene
muss die dominante Ebene nicht die Wand sein; das ist
**keine** unabhängige Prüfung einer bekannten Referenzebene.

## 7. Abnahmeschritte für den Pixel 10 Pro XL

1. **Gleiche alte ZIPs** wie v1.3.8 offline auswerten. Pro Bild veränderte
   Tiefenrichtung, K-Verwendung, Planaritätsanteil und Pose-Interpolation
   vergleichen; keinesfalls alte Karten auf v1.3.9 umetikettieren.
2. Schachbrett-Kalibriersequenz bei guter Beleuchtung und fixiertem
   Objektiv aufnehmen. RMS und Kamera-/IMU-Audit sichern.
3. VIO initialisieren lassen, dann kontrollierte 90°-Drehung und
   gemessene 1-m-Bewegung aufnehmen; Referenztests auswerten.
4. Bei genügend Parallaxe mehrere Ansichten derselben Wand scannen.
   In der Offline-Map-Summary müssen sowohl Fit-Qualität als auch
   `multiview_overlap_accepted` sichtbar sein.
5. Nicht als „metrisch vermessen“ oder für RF-Gerätepositionierung
   verwenden, bevor unabhängige Referenzgeometrie und Loop Closure
   nachgewiesen wurden.

## 8. Bewusst offene Entwicklungsphasen

- P1: absolute Kamera<-IMU-Extrinsik, gemessener Zeitoffset und
  IMU-Rauschparameter auf dem Pixel validieren; Kalibr benötigt reale
  Messsequenz. Der Checkerboard-Kandidat darf den Seed nicht automatisch
  überschreiben.
- P2: unabhängiger A/B-Tracking-Benchmark mit ARCore und einem
  alternativen SLAM-System auf **demselben** Datenstrom; auch
  Rolling-Shutter-/Belichtungszeitfehler quantifizieren.
- P3: echte zeitlich konsistente **Multi-View**-DA3-Inferenz (das
  eingesetzte LiteRT-Modell ist nur Single-Image), globale
  Tiefen-Faktorisierung und differenzierte Unsicherheiten.
- P4: Pose-Graph-/Bundle-Adjustment, Wiedererkennung und
  Loop Closure, nachgeschaltete Voxel-/Surfel-Fusion und physisch
  vermessene Raumflächen. Der neue Cross-Frame-Gate ist dafür nur
  eine erste, überprüfbare Qualitätsstufe.


## 9. Original Pixel recordings: independent lighting and VIO evidence

From the **two original, privately supplied user ZIPs** captured on
2026-11-18, an independent, offline JPEG and native-trajectory pass found:

| Parameter | 07:24 | 22:03 |
| --- | ---: | ---: |
| Native initialized VIO poses | 462 | 303 |
| Typical VIO output period | 33.3 ms (~30 Hz) | 66.7 ms (~15 Hz) |
| RGB JPEG with native pose ±180 ms | 18 / 32 | 21 / 40 |
| Median grayscale of VIO-associated RGB (0–255) | 127.5 | **6.0** |
| Median OpenCV ORB features, max 1000 | 1000 | **148** |
| Net native VIO displacement, unvalidated units | 0.024 | **75.631** |
| P90 VIO speed, unvalidated units/s | 0.097 | **8.77** |

The 22:03 recording is almost completely dark over the tracking interval
and contains very few usable features, independently of unresolved
Camera2/gyro calibration. The 15 Hz output does NOT on its own prove that
AE exposure reduced the sensor FPS: native worker, CPU load or dropped
frames can also contribute. That causal distinction requires **real
CaptureResult** exposure duration and frame-duration evidence.

### Additional fixes after initial v1.3.9 branch implementation

- `OpenVinsRgbCaptureQuality` decodes a low-resolution representation
  of the **actual original Camera2 RGB JPEG** before AI normalization.
  It records grayscale median, near-black fraction and gradient
  contrast for every photo in the offline manifest. Underexposed/
  structureless photos remain available in the individual DA3 browser
  but cannot contribute unreliable ORB multiview alignment. The browser
  shows the real RGB brightness and its eligibility.
- The pinned native `CaptureResult` diagnostic now reports actual
  exposure times, ISO values, sensor frame duration and a rolling
  90-frame count of frames with duration >50 ms. This avoids assuming
  that a requested camera FPS was achieved. The live OpenVINS diagnostic
  shows a **possible** low-light slowdown warning when recent frames
  repeatedly fall below 20 Hz.
- The offline map summary counts RGB photos rejected from multiview
  by low light or insufficient structure.

**Practical requirement:** Run future camera/VIO tests with sufficiently
bright, evenly illuminated walls and furniture, without switching lenses
or letting focus/OIS change. The second nighttime recording is NOT a
fair standalone test of OpenVINS's calibrated-room potential.

## 10. Build without GitHub-hosted Actions

GitHub runs in private repository `groegberg/rf-lab-android`
were blocked pre-start on 2026-11-19. Both 24.04 and 22.04 runner labels
returned `runner_id=0`, empty steps and no logs. This frequently
indicates GitHub Actions billing/budget entitlement or a platform
provisioning issue; exact account-specific reason requires the owner's
job annotation and billing settings.

Tracked separately: [Actions runner blocker #11](https://github.com/groegberg/rf-lab-android/issues/11).

**Local fallback (Linux or WSL2 with Android SDK, JDK17, Gradle 8.13):**

```bash
git checkout feature/embedded-openvins-v1.3
export ANDROID_HOME="$HOME/Android/Sdk"
bash tools/build_private_android_local.sh
```

The script runs pinned native OpenVINS bootstrap, rebuilds Android 16 KiB
Boost, runs Kotlin tests and Alexa message contract, assembles the APK,
validates native ELF dependencies, 16 KiB ELF and APK alignment and writes
a local-only private APK with source commit/SHA256 in
`private-build/`. It **does not publish** or upload anything.
The GitHub v1.3.9 release remains **unverified** until a real runner
build or equivalent local test completes.

## 11. Added per-frame geometry A/B: relative DA3 versus ORB aligned

v1.3.9 now preserves **two genuinely different 3D representations** for
every photo that passes sparse multi-view triangulation:

- `relative_0000.rgbxyz`: per-photo relative DA3-Small depth from the
  actual Camera2-radtan optical rays, normalized **only for display**.
  There is no common scale or known physical Z.
- `aligned_0000.rgbxyz`: `Z_VIO = scale × D_DA3 + shift` using
  the per-photo RANSAC fit to actual 3D ORB two-view triangulations,
  projected using the interpolated camera pose into the common
  **unvalidated** native VIO world coordinate system.

For each successful fit the individual aligned point cloud is written to
the private `offline_*` folder and linked in `manifest.json` with
`aligned_rgb_cloud_file`, point count, RANSAC inliers, affine scale and
shift. If a photo lacks usable ORB correspondences, **no aligned file is
fabricated**. The composite `vio_world_diagnostic.rgbxyz` still
distinguishes the matched real geometry from individually normalized
visualization fallback.

The in-app **Bilder + Weltkarte** browser now offers two buttons below
the tabs:

- **DA3 relativ**: the original relative single-image cloud.
- **ORB-angepasst**: the actual multi-view-depth-aligned point cloud
  for the selected image when there was a valid fit.

These modes have different coordinate frames, so their apparent screen
rotation/offset need not be identical. Compare the **shape/flatness**
and the measured `Ebene DA3 X% / ORB Y%` planar support rather than
screen positions. Per-photo aligned geometry has a **better justified
shape/depth mapping** but is still NOT measured in metres.

The checkerboard intrinsic calibration also now requires *view diversity*
before it accepts its result as a usable candidate: at least 12
detected patterns, 3 image regions, changing apparent board size
and at least two oblique/perspective views. RMS alone is insufficient:
repeated photos of the same target pose are a degenerate calibration.
Tilt can be detected by corner-axis skew and opposing-edge
perspective foreshortening.

**Status:** All code is committed on `feature/embedded-openvins-v1.3`.
As of this update GitHub Actions continues failing before a runner is
assigned. The Android APK remains unbuilt, and the unit tests have **not**
been run on the Android CI or on the Pixel.
