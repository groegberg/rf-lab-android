# RF Lab v1.3.1 — OpenVINS als integrierte Live-Posequelle

**Status:** Entwicklungs-/Pixel-Test, keine vermessene 3D-Geometrie. Native
OpenVINS Mobile GPLv3, gepinnt auf `f0c465695c67c0eac786f4edf83a68109a6a58a9`,
bleibt über das reproduzierbare Bootstrap eingebettet.

## Was sich gegenüber v1.3.0 ändert

- `3D-SCAN → Tracking: OpenVINS → Tracking starten` öffnet
  `dev.rflab.scanner.OpenVinsScanActivity`, die echte native
  `com.openvins.android.MainActivity` **erweitert**. Es gibt exakt einen
  Camera2-/IMU-Datenstrom und denselben OpenVINS-MSCKF-Zustand.
- Die native, nur nach Initialisierung verfügbare Kamerapose wird im
  bestehenden RF-Lab-Scan-Renderer live als türkise Trajektorie gezeichnet.
  Orange markiert die letzte native KameraPOSITION. Die 3D-Ansicht lässt sich
  drehen und zoomen. Die Positionsanzeige enthält auch native Quaternionen.
- `Scan starten` schaltet native MSCKF-VIO **und** seine zeitstempelbasierte
  RGB-/IMU-/Pose-Aufnahme ein; `Scan stoppen` schließt die Dateien und
  stoppt die Verarbeitung, bevor die Kamera verlassen wird.
- Der Status unterscheidet ausdrücklich `initialisiert noch`,
  `native VIO-Pose` und `keine Pose`. Ein 2-D-Feature-Overlay
  zählt nicht als funktionierendes VIO.
- `KI-Tiefe / Auswertung` importiert die interne Native-Aufnahme direkt in
  eine RF-Lab-Fotosession und öffnet die vorhandene Offline-Auswertung:
  echte native VIO-Trajektorie plus DA3-Small-LiteRT-Tiefenkarten.
  Danach `Offline berechnen` antippen.
- In der RF-Lab-Sitzungsprotokollierung (`rflab_*.jsonl`) stehen
  `spatial_optical_scan`, `vio_camera_pose` und
  `spatial_optical_scan_end` für VIO-Vergleiche.
- Die native Pose-Trajektorie wird zusätzlich als
  `rflab_openvins_scan_v1.json` gesichert und nach Rückkehr in
  `3D-SCAN` angezeigt. **ARCore-Daten** bleiben in der bisherigen
  `rflab_room_scan_v1.json`, ohne Koordinatenmischung.

## Koordinaten und Zeit

Native OpenVINS: im Normalfall X/Y horizontal, Z nach oben.
Für die bestehende RF-Lab-Y-up-Anzeige gilt die starre rechtshändige
Achsendrehung `(x, y, z) → (x, z, −y)`. Diese Abbildung ist **keine**
Ausrichtung auf den ARCore-Ursprung und keine metrische Kalibrierung.
Der angezeigte Quaternionwert ist **nativ** und wird nicht als
kalibrierte ARCore-Kameraorientierung ausgegeben.

Echte Native-CSV-Daten behalten Camera2/IMU-Hardwarezeitstempel.
Im Live-RF-Lab-JSONL wird der **Zeitpunkt des JNI-Callbacks**
(`elapsedRealtimeNanos`) protokolliert, nicht fälschlich als nativer
Kamerabelichtungszeitpunkt ausgegeben.

## Pixel-Testfall

1. Vorhandene RF-Lab-Aufnahmen sichern; Debug-Signaturen können zwischen
   GitHub-Actions-Builds wechseln und eine Neuinstallation erfordern.
2. OpenVINS als Tracking wählen und den integrierten Scanner starten.
3. Zunächst `Initialisierung` erwarten, dann mit langsamer
   Seitwärtsbewegung strukturierte Flächen erfassen. Die Statuszeile muss
   *wirklich* `Native VIO-Pose` melden, der türkise Pfad wachsen.
4. Den Weg durch den Raum möglichst als Runde ablaufen, danach zur
   Ausgangsposition zurückkehren. Drift und Brüche notieren.
5. Scan stoppen; `KI-Tiefe / Auswertung` und danach
   `Offline berechnen` ausführen. Prüfen: RGB-Fotos, echte native
   VIO-Posen, Bild-/Pose-Zuordnung, DA3-Tiefenkarte.
6. Vergleichen: alten ARCore-Scan getrennt starten. Die beiden
   Trajektorien NICHT gemeinsam in eine Raumkarte einsetzen.

### Was noch nicht gelöst ist

- Noch keine validierte Pixel-10-Pro-XL-Kamera/IMU-Extrinsik, Zeitsynchron-
  feinkalibrierung, metrische Skala oder quantitative Driftmessung.
- OpenVINS-MSCKF ist **VIO**, kein Loop-Closure-/Relokalisierungs-SLAM.
- DA3 liefert relative **monokulare** Tiefe. Die Live-Karte zeigt darum
  den nativen Kamerapfad, nicht erfundene Voxel/Mesh-Oberflächen. Die
  Verbindung der Offline-DA3-Depth-Maps mit OpenVINS-Posen zu einer
  geprüften farbigen 3D-Karte ist ein gesonderter Folgeschritt.
- Kein gleichzeitiges Öffnen von ARCore und Camera2; Android würde die
  Kameraressourcen miteinander konkurrieren lassen.

## Build/Distribution

`python3 tools/bootstrap_embedded_openvins.py` wendet
`tools/patch_embedded_openvins_scan.py` nach den bestehenden Research-
Patches auf die gepinnte GPLv3-Quelle an. NDK r28c, Rebuild der benötigten
Boost-1.72-Bibliotheken, 16-KiB-PT_LOAD-/ZIP-Alignment und
`DT_NEEDED`-Checks bleiben verpflichtende GitHub-Actions-Gates.
Eine kombinierte RF-Lab-/OpenVINS-APK ist ein GPLv3-kombiniertes Werk:
Nur private interne Tests; externe Verteilung erst nach Quellcode-,
Lizenz- und Drittanbieterprüfungen.
