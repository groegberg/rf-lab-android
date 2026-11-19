# RF Lab · Tracking und Photogrammetrie im Sitzversuch

**Entscheidung vom 19. November 2026.** DA3 bleibt als lokale Tiefenvorschau pro Foto erhalten. OpenVINS ist auf dem getesteten Pixel noch nicht zur Raumvermessung freigegeben. Eine unabhängige Rekonstruktion aus überlappenden Fotos eignet sich bereits als Vergleich für den Kamerapfad. Die [Technikbewertung und Roadmap](https://github.com/groegberg/rf-lab-android/blob/main/docs/RF_LAB_TECHNIKBEWERTUNG_UND_ROADMAP.md) beschreibt die übergeordneten Abnahmekriterien.

## Was der Sitzversuch zeigt

Der Nutzer beschreibt einen etwa 60 cm langen Kreisbogen mit rund 100° im Sitzen. Die Strecke ist eine Schätzung. Der Export stammt aus RF Lab 1.3.9-qa.1, Build 25. Der erste native Pose-Zeitstempel dient als Nullpunkt für die VIO-Zeiten.

| Auswertung | Ergebnis | Bedeutung |
| --- | --- | --- |
| OpenVINS | 806 Posen über 26,89 s, 344,64 unkalibrierte Wegeinheiten, 274,38 Einheiten Abstand zwischen erster und letzter Pose | Die native Positionsschätzung ist für diesen Versuch unbrauchbar. Nach 3 s betrug der Abstand 0,62, nach 8 s bereits 9,86 Einheiten. |
| Aufnahme | 36 RGB-Bilder, etwa 0,90 s zwischen den Fotos. IMU-Median 401 Hz, größte Zeitlücke 2,55 ms. Alle 36 Fotos bestanden den Helligkeits- und Strukturtest der App. | Fehlende IMU-Samples oder durchgehend dunkle Bilder erklären diesen Verlauf nicht. |
| Drehung | Bei benachbarten Fotos lagen relative Bilddrehung und integrierte Gyrodrehung im Median 0,43° auseinander. Verglichen wurden die zeitlich überlappenden Intervalle mit den Kameraachsen aus der Aufnahme. | Ein grober Achsentausch ist in diesem Scan unwahrscheinlich. Die Kamera-IMU-Translation und der genaue Zeitversatz bleiben offen. |
| Unabhängige Photogrammetrie | 36 von 36 Bildern in einem zusammenhängenden Modell, 3.060 farbige Punkte, rund 0,50 Pixel mittlerer Rückprojektionsfehler. Die größte Kameradrehung zum ersten Bild betrug rund 99°. Danach schwenkte die Kamera zurück. | Die Bilder tragen räumliche Information. Der Fehler beschreibt die Übereinstimmung der Bildpunkte. Aus ihm lassen sich weder Zentimeterfehler noch die Länge des Handbogens ableiten. |
| Wiederholbarkeit | In einem weiteren Lauf mit anderem RANSAC-Start enthielt das größte Teilmodell 32 von 36 Fotos. | Registrierung, Anzahl der Teilmodelle sowie fehlende Fotos gehören in die Auswertung jedes neuen Raums. |

Das im Git hinterlegte Vergleichsskript hat die Aufnahme in einem weiteren Test mit festgelegten Startwerten erneut registriert. Der zweite Start erreichte 36 von 36 Kameraposen, 3.098 Punkte und 98,78°. Die bereitgestellte 3D-Ansicht zeigt den separat ausgewerteten Lauf mit 3.060 Punkten.

Die Photogrammetrie lief als CPU-Referenz außerhalb des Smartphones. Die angegebenen 60 cm und 100° gingen nicht als Vorgaben in die Rekonstruktion ein. Zuordnung und Bündelausgleich nutzten RGB-Fotos und einen Kamerastartwert. Das Ergebnis hat eine freie Längenskala und bildet vor allem gut wiedererkennbare Punkte ab. Der Export enthält RGB-Fotos mit etwa 1,1 Hz. Für einen vollständigen erneuten Lauf des nativen Filters auf seinem ursprünglichen Kamerastream mit rund 30 Hz fehlen die Zwischenbilder.

## Änderungen in der Pixel-Testversion 1.3.10

1. **Initialisierung.** Der statische native Filter verwendet den mittleren Gyrowert eines Zeitfensters als Bias. Jetzt prüft er zuerst Nettodrehung, quadratischen Mittelwert der Winkelgeschwindigkeit und Zeitlücken. Im Modus mit stationärem Start müssen beide Hälften des Fensters ruhig sein. Im historischen Export bestand kein einziges der 123 geprüften Einsekundenfenster vor der ersten Pose diese Prüfung. Diese Beobachtung stützt einen fehlerhaften Start als Beitrag zur Drift. Ihr Anteil gegenüber fehlenden visuellen Korrekturen lässt sich aus dem alten Export nicht bestimmen.
2. **Live-Pfad.** Die Anzeige liest Position, Orientierung und Kamera-Zeitstempel gemeinsam aus dem zuletzt abgeschlossenen Filterupdate. Die App erkennt Sprünge und länger anhaltende unplausible Geschwindigkeit. Sie kennzeichnet den Pfad als verworfen und zeichnet Bilder und IMU weiter auf. Auf den 806 exportierten Posen löst die Prüfung nach 10,27 s aus. Bewegungen unter den groben Schwellen können weiterhin falsche Maße liefern.
3. **DA3 und vorhandene Scans.** Bei verworfenen Posen zeigt die Auswertung DA3-Einzelbilder. Sie erstellt daraus keine Weltkarte. Dies gilt beim Öffnen auch für archivierte Auswertungen mit negativem Bewegungsbefund. Eine native Aufnahme mit leeren Pose-Daten und ausreichenden RGB-Fotos lässt sich als Bildserie übernehmen.
4. **Diagnose.** Der native CSV-Export enthält pro Pose die geschätzten Gyro- und Beschleunigungssensor-Biaswerte, Geschwindigkeit, die Zahl der im MSCKF verbliebenen Merkmalsbahnen und die Zahl der SLAM-Landmarken im Filter. So lässt sich beim nächsten Ausfall erkennen, ob der Filter visuelle Stützung hatte.

## Nächste Tests und weitere Entwicklung

| Schritt | Durchführung und Entscheidung |
| --- | --- |
| Pixel-Kurztest | Kamera auf ein strukturiertes Motiv richten. Zum Start das Gerät ruhig abstützen, bis die erste VIO-Pose erscheint. Dann die Kamera an zwei markierten Positionen vorbei bewegen, am Umkehrpunkt kurz halten und zum Start zurückkehren. Dreimal wiederholen. Für den Maßvergleich die gerade Distanz zwischen den Markierungen messen. Die Länge eines Bogens und der direkte Endpunktabstand sind verschiedene Größen. |
| Aufnahme und Live-Karte | ARCore und OpenVINS auf derselben markierten Route vergleichen. Trackingverluste, Endpunktabweichung und Bildunterstützung pro Abschnitt auswerten. Für einen exakten Offline-VIO-Vergleich müssen künftig alle tatsächlich verarbeiteten Graubilder mit ihren Zeitstempeln gespeichert werden. |
| Offline auf dem Smartphone | Zunächst eine begrenzte Zahl überlappender Schlüsselbilder auswählen, Merkmale zuordnen, Kameraposen und Raumpunkte gemeinsam optimieren. Komponenten mit wenig Parallaxe gesondert kennzeichnen. Speicherbedarf, Laufzeit und Temperatur am Pixel messen. DA3 kann anschließend zwischen gut gestützten Raumpunkten Flächen ergänzen. |
| Maßhaltigkeit | Den Maßstab an einer unabhängigen Referenz verankern. Weitere, bei der Optimierung unbenutzte Strecken prüfen. Pilotziel der Roadmap sind höchstens 5 cm Fehler im Median und 10 cm beim 90. Perzentil über mindestens 30 Innenmaße von 2–6 m in mindestens fünf Räumen. |
| Funk und Smart Home | Funkmessungen über Kamera-Zeitstempel an bestätigte Kartenpositionen binden. Erst nach stabiler Wiedererkennung von Räumen Gerätepositionen und raumbezogene Automationen bewerten. |

### Photogrammetrischen Vergleich wiederholen

Der Vergleichslauf benötigt Python und PyCOLMAP 4.2.1. Das Zielverzeichnis muss neu sein. Jede Registrierung protokolliert ihre Teilmodelle. Die CSV enthält die Kamerazentren in derselben relativen Skala wie die PLY-Datei.

```sh
python tools/benchmark_openvins_sfm.py fotositzung.zip vergleich
```

Die native Ruheprüfung lässt sich auf dem Entwicklungsrechner unabhängig vom Android-SDK prüfen.

```sh
c++ -std=c++17 research/openvins/tests/steady_gyro_test.cpp -o /tmp/rflab-steady-test
/tmp/rflab-steady-test
```

Methodendokumentation · [OpenVINS-Sensorkalibrierung](https://docs.openvins.com/gs-calibration.html) · [COLMAP-Rekonstruktion](https://colmap.github.io/tutorial.html)
