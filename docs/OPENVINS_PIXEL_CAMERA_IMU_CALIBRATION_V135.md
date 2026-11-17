# RF Lab v1.3.5 – Audit der Kamera-, IMU- und Bildausrichtungsgeometrie

## Status und eigentliche Ursache

**Kalibrierung ist nicht nachgewiesen.** Eine initialisierte OpenVINS-
Trajektorie oder die erfolgreiche Android-Kompilierung sind keine
Kalibrierzertifikate. Auch das Online-VIO-Extrinsik-/Zeitoffset-Ergebnis
darf ohne unabhängige Beobachtbarkeit und Referenzmessung nicht als
metrisch richtig bezeichnet werden.

Die Analyse des gepinnten nativen Projekts und RF-Lab-Codes zeigte:

1. OpenVINS Mobile lieferte einen YAML-Datensatz eines anderen Geräts
   (fx~478 Pixel, 640x480, T_cam_imu und +18,8ms Zeitverschiebung).
   Pixel 10 Pro XL überschreibt nur nährungsweise Kameraparameter aus
   Camera2, die Start-Zeitverschiebung wird pauschal auf 0 gesetzt.
   Falls Android keine Lens-Pose-Daten liefert, kann eine alte, falsche
   Extrinsik verbleiben. Solche Messungen dürfen nicht automatisch zu
   einer 3D-Karte fusioniert werden.
2. Camera2 verwendete CONTINUOUS_VIDEO Autofokus. Optische/
   digitale Stabilisierung sollte aus sein, wurde aber nicht anhand der
   tatsächlichen CaptureResult-Daten geprüft. ZOOM und
   Distortion-Correction wurden ebenfalls nicht auditierbar fixiert.
3. Der native OpenVINS-Filter hat `calib_cam_intrinsics`,
   `calib_cam_extrinsics` und `calib_cam_timeoffset` aktiviert;
   die 3D-Nachverarbeitung nutzte bislang die **Kamera-Seed-YAML**,
   nicht die nachoptimierten OpenVINS-Parameter zur jeweiligen Pose.
4. In der nativen OpenVINS-Android-Brücke wurde für chroma
   `pixelStride==2` pauschal NV21 angenommen. Android
   `YUV_420_888` lässt U/V-Plane-Speicherlayout und Row-Stride offen.
   Dadurch konnte es zu Rot-/Blau-Verwechslung und verändertem
   Graubild für Feature Tracking kommen. Die ältere
   `pixelStride==1`-Umwandlung hatte außerdem eine fehlerhafte ROI
   außerhalb des reservierten I420-Puffers.
5. Der native `ImageReader` liefert Rohsensorpixel in der festen
   Kamerakoordinatenbasis. Die Android-Activity ist auf Querformat
   fixiert; die physische Drehung des Handys um 90° ändert die
   Sensorgeometrie, **nicht** das gespeicherte Pixelkoordinatensystem.
   DA3-Small nimmt hingegen ein Hochformatbild (504×896). Es wurde
   *jedes* breite Foto starr um +90° gedreht. Damit war DA3 bei
   bestimmten physischen Telefonorientierungen um 90°/180° verdreht.
6. Frühere Builds erzeugten aus unvalidierten Kamera-/IMU-Seedwerten
   kolorierte 3D-Wolken, obwohl Map-to-World-Korrektheit
   nicht ausreichend geprüft war.

## Korrekturen und neue Quality Gates

- Native Camera2 YUV-Konvertierung: für beide Chroma-`pixelStride`-Modi
  Y, U und V getrennt unter Beachtung der tatsächlichen `rowStride`
  und `pixelStride` lesen und zu korrekt gepacktem I420 konvertieren.
  Bildreihenfolge und Zeitstempel bleiben unverändert.
- In der gepinnten nativen Camera2-Pipeline: Autofokus deaktivieren und
  Fokus für jede Aufnahmeserie festsetzen; OIS/EIS ausschalten,
  Distortion-Correction OFF anfordern, Zoom 1x.
- **CaptureResult** (nicht nur CaptureRequest): tatsächlich
  verwendete AF/OIS/EIS/Distortion-Modi, physisches
  Kameraobjektiv, Live-Intrinsik-/Fokusänderungen, Zoom, Kamerauhr,
  Kamera-Auflösung und Anzahl der Bilder in
  `config/PIXEL_CAMERA_CAPTURE_EVIDENCE.txt` erfassen.
  Bei fehlenden/unbestätigten Daten wird **keine** 3D-Fusion gestartet.
- Messstatus bei Scan-Stopp mit in nativen Session-Snapshot und den
  RF Lab-Galerieimport übernehmen. `camera_geometry_audit.json`
  mit konkreten Ablehnungsgründen schreiben.
- Native `vio_camera_trajectory.csv`: nach jeder initialisierten
  Pose **den aktuellen** OpenVINS-Status mit
  `fx,fy,cx,cy,k1,k2,p1,p2`, JPL-`q_ItoC`, `p_IinC` und
  Kamera-IMU-Zeitoffset in Sekunden protokollieren.
- In der Offline-Kartierung pro DA3-Keyframe anhand seines
  Kamera-Zeitstempels die nächste echte VIO-Pose und deren
  **korrespondierende** aktuelle optische Kalibrierung nehmen,
  statt die Seed-Kalibrierung nach allen Filteränderungen anzuwenden.
- Schwerkraft aus nativen IMU-CSV-Ereignissen in die zugehörigen
  Kameraachsen transformieren. Richtungsabhängig 0/90/180/270 Grad
  **nur** für DA3-Bildinput auswählen. Die vollständige
  inverse Rotation/Center-Crop-Transformation zurück auf
  Kamera-Sensorpixel bezieht ORB-Rays und Farbpunkte ein.
  Kamera2 und OpenVINS erhalten weiter unveränderte Sensorbilder.
- Bei fast senkrechter Kamera, starker Drehbewegung,
  unsicherer roll-Upright-Richtung, unzureichenden Gravity-Samples
  oder zu starker DA3-Beschneidung **Keyframe nicht 3D-fusionieren**.
  DA3-Einzelbildvorschau bleibt verfügbar.
- Online-Kalibrierungsstabilität der letzten 75% des nativen
  Posepfads prüfen: Kamera-Brennweite, Abweichung von Camera2-Seed,
  zeitliche Schwankungen der Extrinsik und Zeitversatz.
  Prüfwerte: mindestens 20 Statuswerte, ≥85% Pose-Coverage,
  ≤4% Brennweitenschwankung, ≤20% Seed-Abweichung,
  ≤7° Rotationsschwankung, ≤3,5 cm VIO-Extrinsik-Verschiebung
  und ≤15ms Zeitoffsetspanne. **Heuristische Qualitätsgrenzen,
  keine Kalibrierungs-Toleranzbescheinigung.**
- Die bestehende strengere ORB-Zweistrahl-Triangulation mit
  robustem affinen DA3-Tiefenabgleich bleibt aktiv.
  Alle Qualitäts-Gates müssen für eine Karte bestanden sein.
- Vorhandene v1.3.4-Serien ohne per-frame Online-Kalibrierung und
  tatsächliche CaptureResults werden nicht nachträglich so
  behandelt, als seien sie sicher vermessen.

## Was passiert beim physischen Drehen?

| Bewegung | Korrektes Modell | Restrisiko |
|---|---|---|
| Bildschirmwechsel/Android UI | Kein Einfluss auf OpenVINS, Activity ist fix | Gescannte Bilder müssen IMU-upright für DA3 gedreht werden |
| Drehen von Quer- in Hochformat | Starr gekoppelte Kamera/IMU drehen gemeinsam; Rotation gehört in q_GtoC | Falsche oder zeitlich verzögerte Extrinsik erzeugt 3D-Doppelflächen |
| Smartphone 90° um optische Achse rollen | q_GtoC rotiert optische Strahlen; DA3 erhält IMU-upright | Schwerkraftmessung bei schneller Drehung/Bewegung unzuverlässig |
| Pitch/Yaw auf andere Wand | q und p definieren neue Sicht; keine geänderte Kalibrierung bei fester Linse | Wenig Parallaxe bei reinem Drehen; kein Tiefenmaßstab |
| Kamera fast senkrecht auf Boden/Decke | Schwerkraft bestimmt keine stabile „oben“-Richtung im Bild | 3D-Fusion vorerst gesperrt statt zufälliger DA3-Rotation |
| Auf der Stelle drehen | OpenVINS kann Orientierung verfolgen | Keine triangulierbare Tiefenbaseline, deshalb keine Multi-View-Tiefe |
| Fokus/OIS/physisches Objektiv ändern | Kamera-K muss passend aktualisiert werden | Scan sperrt solche Fälle, keine automatische Übernahme |

## Testprotokoll Pixel 10 Pro XL

Zunächst nicht hektisch durch alle Räume laufen. Lieber mehrere kurze
(20–35 s) Sessions mit **gleichem fest strukturierten Möbelstück**
und sichtbaren Kanten aufnehmen:

1. Statisch 5 Sekunden ruhig halten. Danach seitlich 30–60 cm
   mit natürlicher Struktur und überlappenden Bildern bewegen.
   Kalibrierwerte, native Initialisierung und synchronisierte Posen prüfen.
2. Gerät langsam um die **optische Achse rollen**:
   0°, 90°, 180°, 270° mit kurzen Pausen. IMU-upright-Winkel
   in `manifest.json` und die DA3-Tiefenvorschau prüfen.
3. Nur auf der Stelle yaw/pitch drehen: Es **darf keine**
   triangulierte metrisch wirkende Oberfläche hinzuerfunden werden.
4. Nicht gleiche Session mit Autofokus/Zoom oder wechselnder
   physischer Kamera mischen; bei jedem unerwarteten Wechsel muss der
   CaptureResult-Gate den Scan für 3D ablehnen.
5. Referenzobjekt mit bekannten Abmessungen mehrfach von
   verschiedenen Richtungen aufnehmen und die rekonstruierte
   Trennung/Drift erst nach erfolgreicher stabiler Kalibrierung messen.

Relevante Ausgaben unter `offline_*/`:
`camera_geometry_audit.json`,
`openvins_online_calibration_audit.json`,
`openvins_capture_diagnostics.json`,
`manifest.json`,
`openvins_da3_map_summary.json`.

## Noch ausstehend für echte metrische Kalibrierung

**OpenVINS/Android-Instrumentierung allein kann keine rigide
Kamera-IMU-Extrinsik und keinen systematischen Roll-Shutter-Fehler
metrisch zertifizieren.** Notwendig sind:

- Unverarbeitete, hardwarezeitgestempelte YUV-Bilder + IMU-CSV
  mit ausreichend hoher Rate und identischer Kameralinsenwahl.
- Kamerakalibrierung der genutzten Auflösung und Fokusstellung
  mit AprilGrid/Checkerboard, dann IMU-Rauschparameter durch
  Allan-Varianz (stationäre Daten) und extrinsische sowie zeitliche
  Kamera-IMU-Kalibrierung in **Kalibr** oder äquivalentem
  gemeinsamen nichtlinearen Batch-Verfahren.
- Überprüfen von reprojection RMS, IMU-prediction residuals,
  Zeitoffset-Unsicherheit, 6DoF-Observierbarkeit,
  wiederholter Trajektorienschleife und bekannten
  Abmessungen/Loop-Closure-Fehlern.
- In späterer Version: kontinuierliche VIO-Kamerapose-
  Interpolation zum exakten Belichtungszeitpunkt,
  Rolling-Shutter-Modell, Loop Closure und anschließende
  Tiefen- und Mesh-Optimierung statt nur sparse voxel cloud.

Diese Version ist ein **Diagnose-/Korrekturversuch**,
nicht „fertig hochgenaues SLAM“. Das PR bleibt DRAFT,
bis die Hardware-Messdaten die Geometrie bestätigen.
