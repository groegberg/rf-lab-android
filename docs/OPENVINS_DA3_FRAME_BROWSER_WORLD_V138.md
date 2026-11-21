# RF Lab v1.3.8 – Alle DA3-Aufnahmen und gemeinsame OpenVINS-RGB-D-Punktwolke

## Was die Version tatsächlich tut

Die erste sichtbare Punktwolke in v1.3.7 stammte **nur aus einem JPEG**.
Frühere Tiefenbilder existierten zwar in der Offline-Auswertung als U16- und
JPG-Dateien, aber es gab weder eine durchsuchbare 3D-Ansicht pro Aufnahme
noch eine praktikable Gesamtansicht ohne streng freigegebene IMU-Kalibrierung.

v1.3.8 trennt die **Messdaten**, die **Diagnose** und eine später verlässlich
kalibrierte **Raumkarte**:

| Ansicht | Daten | Bedeutung |
| --- | --- | --- |
| Einzelbild 1/N | Original-Camera2-RGB, reales DA3-Small-Ergebnis, pro Bild gespeicherte RGB-XYZ-Punkte | Rotierbares relatives 2.5D-Tiefenrelief **ohne metrische Entfernung** |
| Gesamtkarte | Jedes DA3-RGB-Bild mit passendem, echtem OpenVINS-Kamerazeitstempel, jeweilige Kamerapose/Rotation, Camera2-Intrinsik + Bildrotations-/Crop-Rückrechnung | **Experimentelle**, nichtmetrische Weltpunktwolke aller **zugeordneten** Bilder; Tracking und Registrierung sind nicht verifiziert |
| Geometrisch angepasst | Reziproke ORB-Matches, triangulierte OpenVINS-Strahlen, robuste affine DA3-Tiefenanpassung | Nur Frames, welche die Qualitätskriterien erfüllen |
| Approximativ | Alle übrigen zugeordneten Frames; je Foto robust normierte DA3-Tiefenwerte, fest definierter Visualisierungsbereich | Notlösung für Sichtbarkeit und Debugging: **nicht** als korrekt deckungsgleiche Raumoberflächen interpretieren |
| Dauerhafte Raumkarte | Nur die streng freigegebene mehrbildgeometrische Fusion | Weiterhin gesperrt, wenn Pixel-Kamera-IMU-Extrinsik oder VIO-Bewegung ungeeignet ist |

### Bewusst keine erfundenen Messwerte

Das Weltbild nutzt echte OpenVINS-Positionen und -Kameraquaternionen,
aber der angenommene Tiefenmaßstab für nicht angeglichene DA3-Bilder ist
**ein festes Anzeigeintervall**, KEIN gemessener Meter. Fehlende Kamera-
IMU-Extrinsik, fehlende echte Tiefenskala, lange VIO-Drift oder fehlende
Loop-Closure können zu doppelten bzw. versetzten Wänden führen.
Der Modus heißt deshalb **Gesamtkarte · OpenVINS (Diagnose)** und wird
nicht als fertiger Raumscan oder Grundlage für Raumgrenzen gespeichert.

Bei der Pixel-10-Pro-XL-Aufnahme vom 18.11.2026, 22:03, zeigt das
Diagnosetool weiterhin die unplausible ca. 75-m-VIO-Verschiebung an.
Der Gesamtmodus kann die fehlerhafte Überlagerung visualisieren, die
geprüfte Raumkarte bleibt ausdrücklich gesperrt.

## Bedienung

1. RF Lab öffnen → vorhandene Foto-/OpenVINS-Session auswählen oder ZIP importieren.
2. **Offline berechnen**. Alle gespeicherten RGB-Bilder werden jetzt sequenziell
   durch DA3 verarbeitet; auch die Bilder **vor** der VIO-Initialisierung
   bleiben als Einzelbildvorschau zugänglich.
3. Nach Abschluss öffnet sich automatisch der **RGB-D-Browser**.
4. **Gesamtkarte · OpenVINS** zeigt alle zeitlich zugeordneten Bilder
   samt Kameratrajektorie. Status: Anzahl Fotos mit Pose, geometrisch
   angepasste/approximative Bilder, Gesamtzahl RGB-Punkte, Drift-Warnung.
5. **Einzelbilder** wählen: Mit **Vorheriges/Nächstes** oder dem
   **Schieberegler** zu Bild 1/N … N/N. Original-RGB, DA3-Farb-Tiefenkarte
   und drehbare kamerafarbige 3D-Punkte werden direkt aus dem gespeicherten
   Offline-Ergebnis geladen – ohne erneute Modellinferenz.
6. In der Offline-Ergebnisansicht kannst du mit **Bilder + Weltkarte** auch
   später dieselbe Auswertung erneut öffnen.

## Dateien im privaten Offline-Ordner

- `relative_0000.rgbxyz`, `relative_0001.rgbxyz`, …:
  reale RGB-Pixel und bildlokale relative XYZ-Punkte mit validiertem binären
  Dateiformat `RFC1` (nichtmetrisch).
- `depth_0000.u16` + `depth_0000.jpg`, …:
  rohe relative DA3-Ausgabe und visualisierter Tiefenverlauf.
- `vio_world_diagnostic.rgbxyz`: alle aus echten VIO-Posen projizierten
  Farbpunkte, **ausdrücklich nur Debug-Daten**.
- `manifest.json`: Originalfoto, Zeitstempel, zugeordneter VIO-Poseindex,
  Bildrotationswinkel, individuelle 3D-Cachedatei, Geometrie-Gate und
  Beteiligung an der diagnostischen Weltpunktwolke.
- `openvins_da3_map_summary.json`: erfasste Bilder, geometrisch angepasste
  vs. approximative Weltframes und Punktzahl.
- `openvins_vio_motion_audit.json`: VIO-Bewegung und Drift-Kennzahlen.

Wenn ein Foto keine echte VIO-Kamerapose innerhalb von 180 ms aufweist,
ist es **einzeln sichtbar**, gehört aber **nicht zur OpenVINS-Gesamtkarte**.
Eine fehlende Pose wird niemals interpoliert oder durch eine erfundene
Trajektorie ersetzt.

### Was als Nächstes verbessert werden muss

- Pixel-spezifisches T_cam_imu und Kamera-/IMU-Zeitoffset offline mit
  Kalibriergitter, reprojektionsbasierten Residuen und unabhängigen
  Testsequenzen validieren.
- Die Kamerapose vom Bildzeitstempel genauer interpolieren (nur bei
  ausreichend dichtem, gültigem VIO-Verlauf), statt maximal 180 ms
  auf die nächste Pose zu runden.
- Mehrbild-Tiefenausrichtung über viele Ansichten, konsistente visuelle
  Korrespondenzen und Voxel-Konfliktprüfung ausbauen. Die aktuelle
  approximative Tiefenskala allein reicht ausdrücklich **nicht** für
  eine genau ausgerichtete Gesamtraumkarte.
- Eine tatsächliche SLAM-Korrektur/Loop-Closure und unabhängige
  Abstandsvalidierung ergänzen.
