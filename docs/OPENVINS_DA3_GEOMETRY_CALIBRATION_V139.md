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
