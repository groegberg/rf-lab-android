# RF Lab v1.3.6 – OpenVINS CSV-Export und 3D-Diagnose aus echter Mehrbildgeometrie

## Nachvollzogene Fehler

Die reale Pixel-10-Pro-XL-Aufnahme enthielt 32 RGB-Bilder, 462 initialisierte
OpenVINS-Kameraposen und ca. 11.686 IMU-Zeilen. Die native VIO startete erst
ca. 13,1 s nach den ersten RGB-Bildern; nur 18/32 Bilder besaßen eine
zugeordnete native Pose. Die beobachtete Kameratrajektorie hatte eine
räumliche Ausdehnung von ungefähr 0,36 VIO-Einheiten bei ca. 1 Einheit
integrierter Pfadlänge. Die RAW-Daten und private Wohnungsbilder sind NICHT
im Repository enthalten.

**ZIP-Export:** `PhotoSessionStore.export` schrieb `imu_openvins.csv`
einmal speziell und ein weiteres Mal über `imu_*.csv`. Java
`ZipOutputStream` wirft bei identischem Dateinamen einen Fehler.
Jetzt prüft `UniquePhotoZipEntries` alle Einträge auf Eindeutigkeit.
Die IMU-CSV wird genau einmal eingeschlossen; alle nativen
`external_vio_trajectory.csv`-Posen, Kalibrierdateien und Fotos bleiben
vorhanden. Regressionstest: `UniquePhotoZipEntriesTest`.

**Keyframe-Selektion:** Früher wurden 24 DA3-Bilder gleichmäßig von Anfang
bis Ende der Aufnahme gewählt – inklusive der 13 Sekunden ohne VIO-Pose.
Jetzt wählt `VioKeyframeSelection` bevorzugt alle mit einer echten
OpenVINS-Pose zeitlich assoziierbaren Bilder, max. 24 gleichmäßig entlang
dieser gültigen Spanne. Nur ohne VIO gibt es den alten DA3-Preview-Fallback.
Originalbilder und native Rohaufnahmen bleiben unangetastet.

**Unsichtbarer 3D-Pfad:** `OfflineReconstructionActivity` blendete seine
3D-Ansicht erst ein, wenn mindestens 50 RGB-Tiefenpunkte fusioniert
worden waren. Der Pixel 10 Pro XL meldete aber
`lens_pose_reference=PRIMARY_CAMERA` und
`seed=Android lens rotation, zero translation seed`: Camera2 gibt
damit keine bestätigte Kamera-IMU-Translation relativ zum Gyroskop an.
Der Vertrauens-Gate muss deshalb die persistente DA3-Fusion sperren.
Dies darf die **echte 3D-Kameratrajektorie** nicht ausblenden.
Sie wird jetzt unmittelbar in einer temporären
`RoomScanStore`-Instanz mit korrekter Skalierung dargestellt;
der 2D/3D-Wechsel ist immer verfügbar. Kein zusätzliches echtes
Tiefensignal wird behauptet.

**Separate experimentelle farbige ORB-Vorschau:** Es existieren echte
reciprocale ORB-Bildkorrespondenzen mit triangulierbaren 3D-Rays.
`OpenVinsDa3MultiView.align` gibt bei bestandenem Parallaxen-,
Cheiralitäts- und Ray-Gap-Check zusätzlich farbige
`SurveyDepth`-Positionen aus dem echten Kameraposenpaar zurück.
`OpenVinsSparsePreviewGate` lässt diese Darstellung ausschließlich
bei ansonsten stabilen Camera2-CaptureResults, geeigneter
gerätegleicher Seed-Rotation und stabiler online geschätzter
OpenVINS-Kamera-Kalibrierung für `PRIMARY_CAMERA` zu.
Die Punkte werden **nur als unkalibrierte,
in-memory 3D-Diagnose** gerendert. Sie werden NIE in die
vertrauenswürdige native DA3-Raumkarte geschrieben oder
als metrische Kartierung bezeichnet. Wenn die tatsächlichen
Bildkorrespondenzen nicht genügen, bleibt nur der 3D-Kamerapfad.

**Diagnose-ZIP:** Der zuvor gemischte Bericht verwendete bei der
Nachschau teilweise 640×360-Metadaten eines nach Aufnahmeende
neu geöffneten Kamerapreviews, während die exportierte
Kameraaufnahme tatsächlich 640×480 hatte. Der zukünftige
Exportbericht enthält die CaptureResult- und Seed-Dateien des
ausgewählten nativen Sessionordners und erklärt die Trennung.

## Zielgerichteter Test

1. Alte v1.3.5-Aufnahme möglichst vor Neuinstallation über
   `Messdaten ZIP` sichern. Der normale Galerie-ZIP-Export
   ist erst ab v1.3.6 korrigiert.
2. Nach Installation unter `Aufnahmeserie / Export öffnen` die
   bestehende native OpenVINS-ZIP über
   `Ältere OpenVINS-ZIP importieren` übernehmen.
   Dabei keine private Session durch eine neue überschreiben.
3. `Offline-Rekonstruktion` öffnen: Der 3D-Kamerapfad
   ist sofort sichtbar und auch in 2D umschaltbar.
4. `Offline berechnen` auslösen. Die DA3-Bilder sind an
   echte OpenVINS-Posen gebunden. Anschließend erscheinen,
   nur wenn echte geometrisch gültige Korrespondenzen
   existieren, zusätzlich die unkalibrierten farbigen
   ORB-Diagnosepunkte. Die Meldung muss klar
   `KEINE DA3-Raumkarte` ausweisen, solange PRIMARY_CAMERA
   und unvalidierte IMU-Translation vorliegen.
5. `ZIP sichern` in der Galerie erneut testen.
   Archiv muss `imu_openvins.csv` genau einmal enthalten.
6. Für weitere Aufnahme: erst nach nativer VIO-Initialisierung
   den Raum 40–60 s ruhig und mit ausreichend seitlicher
   Translation (größere Parallaxe) abfahren. Reines Drehen
   auf der Stelle liefert keine hinreichende Tiefenbaseline.

## Noch nicht gelöst

Volle metrische Kamera-IMU-Kalibrierung für den physischen
Pixel-Kamerasensor (mit AprilGrid/Kalibr), echte Rolling-Shutter-
Belichtungszeitkalibrierung, Optimierung von Pose und Tiefen-
Konsistenz über mehrere Ansichten, Loop Closure und dichtes
texturiertes Mesh. DA3-Small liefert relative Invers-Tiefe.
Ein plausibler sparse ORB-Test ist kein Genauigkeitsnachweis.

**STATUS:** Experimentelle Pixel-Testversion, PR DRAFT.
