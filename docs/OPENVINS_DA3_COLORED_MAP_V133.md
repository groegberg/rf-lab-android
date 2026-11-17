# RF Lab v1.3.3 – experimentelle farbige OpenVINS + DA3 Punktwolke

## Ablauf auf dem Pixel 10 Pro XL

1. **3D-Scan → OpenVINS → Tracking starten → Scan starten**. Zuerst
   kurz stillhalten (native Initialisierung), anschließend den Raum mit
   langsamer seitlicher Translation und Überlappung ablaufen.
2. **Scan stoppen → KI-Tiefe / Auswertung**. Die Originale und nativen
   VIO-Kameraposen werden mit identischen Camera2-Sensorzeitstempeln
   in eine RF-Lab-Fotosession importiert.
3. **Offline berechnen**. Der lokale DA3-Small-LiteRT-Lauf nimmt maximal
   24 über die gesamte Aufnahme verteilte Farbbilder. Zu jedem Bild wird
   nur eine native VIO-Pose mit höchstens 180 ms Zeitabstand akzeptiert.
4. Bei gültiger Bildkalibrierung zeigt die obere interaktive Ansicht
   die **farbige 3D-Punktwolke**. Mit **3D-Farbkarte / Kamerapfad** kann
   zurück zur separaten Kameratrajektorie umgeschaltet werden.
   Die untere Ansicht zeigt weiterhin die letzte originale DA3-Tiefenkarte.
5. Nach dem Schließen der Auswertung zeigt auch der native OpenVINS-Scanner
   die letzte gespeicherte relative Karte (beim nächsten Öffnen).
   Das ist ein eigener Namensraum, unabhängig von ARCore.

## Technischer Datenfluss

```text
OpenVINS native Camera2 RGB (timestamp_ns) ----> DA3-Small inference
        |                                               |
        +--> original JPEG width/height                  | relative inverse depth
        |                                               v
OpenVINS native timestamped p_CinG, q_GtoC (JPL) --> timestamp join
        |                                               |
        +--> native session Kalibr: K, radtan distortion |
                                                        v
                                   inverse DA3 rotate/center crop
                                              |
                                     pinhole undistortion
                                              |
                                3D camera ray * relative depth
                                              |
                                   inverse JPL q_GtoC rotation
                                              |
                             native world => RF Lab Y-up world
                                              |
                              RGB sampled at same DA3 pixel
                                              |
                            5 cm voxel accumulator (VIO units)
                                              |
                          rflab_openvins_scan_v1.json + view
```

- Die echte OpenVINS-Laufzeit liefert nach erfolgter Initialisierung
  Kameraorientierung und Translation. Der Patch verwendet OpenVINS'
  `q_cam = q_GtoC` im JPL-Format. Für camera-to-world wird
  `quat_2_Rot(q_cam).transpose()` angewendet; erst danach erfolgt
  die Y-up-Achsdrehung `(x,y,z) → (x,z,-y)`.
- Pixel werden rückwärts durch **exakt denselben Bildzuschnitt** wie in
  `Da3DepthEngine.predictJpeg` geführt, inklusive der dort implementierten
  90°-Drehung bei Querformat. Kameraparameter stammen ausschließlich aus
  der mitgespeicherten `kalibr_imucam_chain.yaml` (pinhole/radtan).
  Wenn diese fehlt oder das Sensor-Seitenverhältnis abweicht, wird keine
  falsche 3D-Karte konstruiert; die Tiefenbilder bleiben nutzbar.
- DA3 ist ein **Einzelbildmodell mit relativer inverser Tiefe**. Der
  Prototyp setzt die Median-Tiefe pro Keyframe auf **2 unvalidierte
  OpenVINS-Einheiten** und nutzt `Z=2*median_inverse/depth_inverse`.
  Diese pro Bild neu angenommene Skalierung ist ausdrücklich KEINE
  metrische Rekonstruktion und kann doppelte oder verschobene Flächen
  erzeugen. Der 5-cm-Voxel-Abstand ist genauso nur in VIO-Einheiten.
- Gespeichert werden echte aus Bilddaten erzeugte Farbpunkte, kein
  künstliches Raum-Mesh. Grenzen: max. 24 DA3-Bilder, ~12-Pixel-Sampling,
  max. 80 000 Voxel, kein Loop Closure, keine Multi-View-Verifikation.
- Die ARCore-Raumkarte bleibt getrennt; keine VIO/ARCore-Registrierung.
  Der alte Prototyp mit `unit-step`-OpenCV-VO produziert keine
  kartierten OpenVINS/DA3-Flächen.

## Vor dem nächsten Genauigkeitstest

- Auf dem Pixel in einem strukturierten Raum mit bekannten Abmessungen
  15–30 Sekunden und überlappenden Ansichten erfassen; anschließend
  `RGB-Farbpunkte`, `Keyframes`, Tiefe und Trajektorie vergleichen.
- Prüfen, ob Wandflächen aus verschiedenen Positionen annähernd
  übereinanderliegen. Zeigen sich Doppelbilder, kommt die notwendige
  **Multi-View-Tiefenskalierung/Shift-Schätzung** als Folgeschritt.
- Besonders prüfen: Kamerakalibrierung des Pixel 10 Pro XL, VIO-Skala,
  Autofokus/Crop, Optik-Verzeichnung, Bild-/Pose-Synchronität und Drift.
- `offline_*/openvins_da3_map_summary.json` enthält Quellen und
  explizite Kalibrierungs-/Genauigkeitsflags, `manifest.json` das
  Offline-Resultat. Keine Längenmessung für Smart-Home-Raumgeometrie
  oder Automatisierungen verwenden, bis die metrische Validierung
  erfolgreich ist.

## Build und Lizenz

Vollständigen `:app:testDebugUnitTest` und `:app:assembleDebug` in
GitHub Actions ausführen; native 16-KiB-ELF-/ZIP-Alignment- und
Library-Dependency-Gates müssen bestehen. Nur autorisierter interner
Test der kombinierten GPLv3-Apk, nicht öffentlich weiterverteilen.
