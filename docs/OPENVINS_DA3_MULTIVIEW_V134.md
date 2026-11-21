# RF Lab v1.3.4 – OpenVINS/DA3 Multi-View-Tiefenabgleich

**Status:** Experimentelle on-device-Rekonstruktion. Erfolgreicher Android-Build
und echter Pixel-10-Pro-XL-Feldtest sind getrennte Gates. Keine verifizierte
metrische Raumkarte, keine Loop Closure, keine behaupteten Messgenauigkeiten.

## Unterschied zu v1.3.3

Die Version v1.3.3 setzte die DA3-Tiefenkarte **pro Einzelbild** unabhängig
auf eine willkürliche Median-Distanz. Das erzeugt zwangsläufig verschobene
Doppelflächen bei Bildern mit unterschiedlichem Tiefeninhalt.

v1.3.4 entfernt diese Heuristik. Für die Fusion sind nun **echte
Bildkorrespondenzen mit ausreichender OpenVINS-Kameratranslation** Pflicht.

1. OpenVINS liefert aus dem nativen MSCKF echte Kamera-Position und
   Quaternion pro Camera2-Hardwarezeitstempel. Die vorhandene Funktion
   `ExternalVioTrajectory.closestIndex` lässt nur ein Foto-Pose-Paar mit
   maximal **180 ms Zeitabweichung** zu.
2. DA3-Small-LiteRT erzeugt pro RGB-Keyframe eine relative inverse
   Tiefenkarte und den exakt vorverarbeiteten Modell-RGB-Input (504 × 896).
   RGB und Tiefenwerte werden aus **demselben** Modellpixel gelesen.
3. OpenCV ORB (bis 1100 Features/Frame) findet farbbasierte Merkmale.
   Benachbarte Keyframes (bis zu drei Frames weiter) werden mit
   reziprokem Hamming-KNN/Ratio-Test verglichen. Eine sichtbare Kante
   ohne passenden Merkmalsnachweis ist kein 3D-Beweis.
4. Aus dem gespeicherten native-Kamera-Kalibrierungssnapshot (pinhole/radtan),
   der **inversen** DA3-Rotation/Center-Crop-Transformation und dem nativen
   OpenVINS-Quaternion wird zu jedem Merkmal ein 3D-Kamerastrahl gebildet.
   Zwei optisch gematchte Strahlen werden mit ihren **echten**
   OpenVINS-Baselines trianguliert, wenn ausreichend Parallaxe, positive
   Tiefe und geringer Strahlabstand vorliegen.
5. Für jeden unterstützten Keyframe wird ein robuster affiner Abgleich
   `inverse_depth_vio = scale * da3_inverse + shift` mittels
   deterministischem 2-Punkt-RANSAC und Least-Squares-Inlier-Refit
   bestimmt. Es sind mindestens **16 Inlier**, eine sinnvolle Tiefen-
   Spannweite, räumliche Merkmalsabdeckung und geringe relative
   Residuen erforderlich. Keine gültige Anpassung bedeutet:
   **kein Keyframe in die 3D-Punktwolke übernehmen**.
6. Nur entsprechend abgeglichene Tiefen werden rückprojiziert und zu
   farbigen 5-cm-VIO-Voxeln fusioniert. Mindestens **zwei ausgerichtete**
   Keyframes und mindestens 150 Projektionskandidaten sind nötig.
7. Alle DA3-Ausgaben bleiben unabhängig von der 3D-Qualität erhalten.
   Ohne zuverlässigen Abgleich zeigt die Auswertung echte DA3-Tiefenkarten
   und die native Kameratrajektorie, keine erfundene Raumoberfläche.

## Daten und Diagnose

- `offline_*/manifest.json`: DA3-Tiefenkarte pro Foto, Zeitstempel,
  Zuordnung zur nativen OpenVINS-Pose und gegebenenfalls affine
  Skalierung/Offset/unterstützende Inlier.
- `offline_*/openvins_da3_map_summary.json`: verwendete Methode,
  geprüfte Paare, reziproke ORB-Matches, triangulierte Treffer,
  alignment-qualifizierte Frames, RGB-Punkte und individuelle
  Fit-Residualen und Abdeckungsquadranten.
- `rflab_openvins_scan_v1.json`: eigene farbige OpenVINS/DA3-Punktwolke.
  `rflab_room_scan_v1.json` (ARCore) bleibt unangetastet.
- `OpenVinsDa3AlignmentTest`: exakte Zweistrahl-Triangulation,
  Pure-Rotation- und Near-Parallel-Rejection, affine Skalierung unter
  Ausreißern und Ablehnung widersprüchlicher Tiefen.

## Pixel-Test

Nach Installation eines geprüften Testbuilds: strukturierten Raum bzw.
Möbelstück 20–35 s scannen. Kamera zunächst für die native VIO-
Initialisierung kurz ruhig halten, dann **langsam seitlich** mit genügend
Überlappung bewegen. Nicht nur auf der Stelle drehen.

Dann `Scan stoppen → KI-Tiefe / Auswertung → Offline berechnen`.
In der 3D-Ansicht Kamerapfad und farbige Oberflächen prüfen. Wenn keine
Karte entsteht: zunächst den diagnostischen Bericht als ZIP exportieren
und folgende Zahlen kontrollieren:
`matches_checked`, `triangulated_correspondences`,
`registered_keyframes`, `aligned_keyframe_diagnostics[*].inliers`,
`median_relative_error`.

### Grenzen

- Die VIO-Baseline ist zwar eine genuine OpenVINS-Messung, aber
  Pixel-spezifische Intrinsik/Extrinsik/Zeitverschiebung sind weiterhin
  **nicht** unabhängig validiert; Ergebnisse sind **VIO-Einheiten**,
  keine gesicherten Meterangaben.
- Sparse 2-View-Triangulation erlaubt eine affine DA3-Tiefenanpassung,
  aber noch keinen globalen Pose-Graph, Loop Closure, Dense-Photometric-
  Reprojection, Occlusion-Test, TSDF/SDF oder eine watertight Mesh.
- ORB-Matching kann auf glatten Wänden, reflektierenden oder wiederholten
  Mustern scheitern. Das ist explizit ein Qualitätsfehler, kein Anlass,
  eine virtuelle Wand zu erfinden.
- Umfang bleibt bewusst für das Smartphone begrenzt: bis 24 DA3-Bilder
  über die Aufnahmedauer verteilt, 3 benachbarte Frame-Paare je Bild,
  begrenzte RANSAC-Arbeit, 80 000 finale Voxel.
- Vor RF-/Magnet-/Smart-Home-Geräte-Koordinaten braucht es einen
  metrologischen Maßstabs- und Drift-Test sowie eine stabile persistente
  Weltkarte.

## APK und Build

- Standard-CI prüft Test-Suite, Android-APK, NDK-Libraries und 16-KiB-
  Kompatibilität.
- Historischer CI-Befund v1.3.3: alle Tests/Build-/ELF-Checks bestanden,
  **nur** der `actions/upload-artifact`-Schritt scheiterte am
  GitHub-Storage-Quota. APK deshalb nicht aus dem Run verfügbar.
- Eine separate PRIVATE GitHub prerelease kann direkt aus der erfolgreich
  gebauten Runner-APK erstellt werden. Diese darf nur für autorisierte
  interne Tests verwendet werden, solange die GPLv3-Gesamtquellen-/
  Lizenzkonformität noch nicht abschließend geklärt ist.
