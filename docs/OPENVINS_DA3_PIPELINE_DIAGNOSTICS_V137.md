# RF Lab v1.3.7: überprüfbare OpenVINS / DA3 / RGB-3D Pipeline

## Messdatenbasis: zwei Pixel-10-Pro-XL-Aufnahmen vom 18. November 2026

Die Beurteilung beruht auf den vom Tester exportierten **originalen** OpenVINS-ZIPs,
nicht allein auf dem UI-Status "Native initialized: true".

| Messung | Morgens 07:24 | Abends 22:03 |
|---|---:|---:|
| Native VIO-Posen | 462 | 303 |
| VIO-Zeitraum | 15,98 s | 20,14 s |
| RGB-Fotos | 32 | 40 |
| RGB-Fotos mit VIO-Pose innerhalb 180 ms | 18 | 21 |
| Native Pfadlänge (unvalidierte Einheiten) | ca. 1,0 | ca. 87,2 |
| Netto-Verschiebung | ca. 0,02 | **ca. 75,6** |
| Maximale VIO-Geschwindigkeit | ca. 0,9 Einheiten/s | **ca. 21,3 Einheiten/s** |
| IMU-Aufzeichnungsrate | ca. 401 Hz | ca. 401 Hz |
| Kamerastream | Camera2 640 × 480 | Camera2 640 × 480 |
| Lens pose reference | PRIMARY_CAMERA | PRIMARY_CAMERA |

**Interpretation:** Die zweite native VIO-Trajektorie ist für Indoor-Raumkartierung
physikalisch unplausibel. Die erste hat kleine Translationen und teilweise
wenig Parallaxe. Die Belichtung in der zweiten Aufnahme fällt stark ab;
ORB-Merkmalskorrespondenzen werden damit schwieriger.

**Nicht** aus den ZIPs ableitbar: kalibrierte absolute Meter, korrekte
Camera2-Kamera-IMU-Rotation/Translation, Ursachenanteil einer möglichen
VIO-Verarbeitungspause. Der diagnostizierte Abstand zum letzten verarbeiteten
Frame kann nach einer beendeten Aufnahme normal sein: Die Diagnose muss
`recording_active_at_diagnostic` berücksichtigen.

### Warum die farbige 3D-Karte fehlte

1. OpenVINS liefert eine Pose erst nach Initialisierung. Rund die Hälfte der
   frühen RGB-Fotos hat deshalb keine synchronisierte VIO-Pose.
2. Camera2 meldet `LENS_POSE_REFERENCE=PRIMARY_CAMERA`, nicht `GYROSCOPE`.
   Die Kamera-Translation ist daher **nicht** direkt zur IMU referenziert.
   `T_cam_imu` und `timeshift_cam_imu` im ursprünglichen YAML sind
   unvalidierte Seed-Werte.
3. Selbst stabil wirkende **online** Kameraparameter sind kein Beweis korrekter
   metrischer Kalibrierung. Beide Datenreihen haben geringe Drift der
   Online-Parameter im letzten Abschnitt, die zweite VIO-Translation driftet
   trotzdem extrem.
4. Die persistente RGB-DA3-Fusion wurde zurecht gesperrt. Die alte UI zeigte
   dann nur den Kamerapfad; so konnte die real vorhandene DA3-Tiefe nicht als
   kolorierte 3D-Diagnose untersucht werden.

## Neue beobachtbare Stufen

- **Stufe A – Originalaufzeichnung:** Native Camera2 + IMU + VIO mit echten
  Zeitstempeln; RGB-Originalfotos bleiben erhalten.
- **Stufe B – Physikalische VIO-Plausibilität:** VIO-Bewegungen werden anhand
  zeitstempelbezogener Geschwindigkeit, Sprüngen und Gesamtdrift geprüft.
  Unplausible VIO sperrt die Weltgeometrie, nicht die DA3-Diagnose.
- **Stufe C – sofortige farbige DA3-Einzelbildansicht:** Nach der **ersten**
  erfolgreichen DA3-Inferenz werden die realen RGB-Pixel in einer 2.5D-
  Tiefenrelief-Punktwolke sichtbar, **unabhängig von VIO- und Extrinsikgates**.
  Z und Bildstrahlen werden zur Visualisierung normiert; dies ist explizit
  KEINE registrierte Mehrbild-Raumkarte, keine Vermessung und kein Metrikmaß.
- **Stufe D – native mehrbildgeometrische Forschungsvorschau:** Reziproke ORB-
  Korrespondenzen, echte native VIO-Ray-Triangulation, robuste DA3-
  inverse-depth scale/shift-Ausrichtung. Wenn die übliche Kameraevidenz
  ungeeignet, aber die beschränkte PRIMARY_CAMERA Forschungskontrolle
  erfolgreich ist, dürfen RGB-Punkte nur als **transient** unkalibrierte
  Mehrbild-Forschungsvorschau erscheinen.
- **Stufe E – persistente Raumkarte:** Nur bei ausreichend zuverlässiger
  Kamera/IMU-/Online-Kalibrierung, plausibler nativer Bewegung,
  mindestens zwei konsistenten DA3-Keyframes und ausreichend Punkten.
  Weiterhin keine verifizierte metrische Genauigkeit und kein Loop Closure.

Die Benutzeroberfläche unterscheidet **Einzelbild-RGB-Tiefe**,
**Mehrbild-Forschungsvorschau** und **persistente Farbkarte**.
Die Datei `openvins_da3_map_summary.json` enthält jetzt die genauen
Ausfallhäufigkeiten je geometrischem Gate; `manifest.json` nennt für jedes
Foto den Gategrund und die Zahl der Bildstrahlen.

`openvins_vio_motion_audit.json` schreibt P90-/Maximalgeschwindigkeit,
Netto-Verschiebung, Pfadlänge und den Grund einer Sperre.

## Beim Drehen des Smartphones

- Kamera2-Rohpixel und die nativen Kameraintrinsiken bleiben im **Sensorkoordinatensystem**.
  Die UI-Ausrichtung verändert dieses Rohbild nicht.
- OpenVINS erhält weiter das Rohbild und IMU-Samples; idealerweise verfolgt
  es die physische 6DoF-Kamerapose auch bei 90° Roll-/Pitch-Wechsel.
- Der DA3-Eingang wird pro Foto aus der gemessenen IMU-Schwerkraft und
  der zeitgleichen Kamera<-IMU-Orientierung nach 0/90/180/270 Grad gedreht.
  Die DA3-Crop-Geometrie wird bei der Rückprojektion explizit invertiert.
- Schnelle Rotation, Schwerkraft parallel zur optischen Achse, Bildunschärfe
  oder große Schräglagen machen eine eindeutige DA3-Aufrichtung unzuverlässig.
  Solche Bilder bleiben als relative Tiefe sichtbar, werden jedoch nicht
  ungeprüft für Weltgeometrie verwendet. Reine Drehung erzeugt kaum Parallaxe
  und hilft nicht, absolute Tiefe zu triangulieren.

## Noch notwendige Kalibrierung / unabhängige Validierung

Ein korrektes `T_cam_imu`, Zeitoffset, Intrinsiken/Verzeichnung und
Rolling-Shutter-Fehler lassen sich NICHT allein aus `initialized=true`
oder aus den beiden Aufnahmen zertifizieren. Für einen echten Metriktest:

1. Dieselbe physische Camera2-Linse fest einstellen, Sensorauflösung und
   unveränderte AF/OIS/EIS-Modi dokumentieren.
2. Für eine Offline-Kalibrierung strukturierte Bilder eines geeigneten
   Checkerboards/AprilGrids zusammen mit den originalen 400-Hz-IMU-Samples
   unter **vielseitiger Bewegung** und gleichmäßiger Beleuchtung aufnehmen.
3. Per Kalibr (oder gleichwertigem Werkzeug) (T_{cam\leftarrow imu}),
   Kamera/IMU-Zeitoffset sowie Kamera-/IMU-Modelle schätzen, Parameter
   unabhängig über Reprojektion und weitere Sequenzen validieren.
4. Danach wiederholte 90°-Drehungen und eine bekannte 1- bis 3-m-
   Translation messen. Trajektorie und Punktwolke auf Wiederholgenauigkeit,
   lokale Abstände und Drift prüfen.
5. Erst nach dieser unabhängigen Kontrolle Metriklabels und automatische
   Raumgrenzen zulassen. Für langfristige Karten ist zusätzlich echte
   Wiedererkennung/Loop Closure nötig.

## Erwartung für Pixel-Test

Beim Öffnen einer importierten oder gerade aufgenommenen Session:
`Offline berechnen` startet die pipeline. Schon nach dem ersten DA3-Bild
muss eine farbige, drehbare 2.5D-Punktwolke statt einer leeren 3D-Fläche
erscheinen. Ein späterer echter Mehrbild-Abgleich ersetzt sie nur bei
ausreichender Evidenz. Bei der driftenden zweiten Aufnahme bleibt der
**Mehrbild-Speicher absichtlich gesperrt** und zeigt als Grund "VIO-DRIFT".
Die beiden ursprünglichen ZIPs sind unverändert und bleiben als
Referenzdatensatz für weitere Regressionsprüfungen erhalten.
