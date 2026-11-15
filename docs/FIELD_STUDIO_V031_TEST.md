# RF Lab Field Studio v0.3.1 — Morgen-Testplan

## Überblick

Ziel: robuste Messungen auf Standard-Android-Geräten, auch ohne ARCore und ohne Licht.

### Neu

1. Kamera-freier Nachtmodus (Reiter NACHT): 2D-Raumrechteck mit vom Nutzer angegebenen Maßen. Ein Tap setzt einen Messpunkt; seine Position ist manuell, kein ARCore-Tracking und kein IMU-Dead-Reckoning.
2. Pro Standort werden nur bei aktivierter Aufnahme echte WLAN-Link- und frische Scan-RSSI-Werte erfasst. Die App prüft Scanzeitstempel und verwirft Scans, die vor dem Aufnahmestart des aktuellen Punktes entstanden sind.
3. Die Anzeige berechnet pro BSSID und Position den Median sowie Minimum, Maximum und Anzahl der Messwerte. Eine 2D-Interpolation erscheint nur bei mindestens drei räumlich verteilten Punkten, begrenzt auf die Nähe zu gemessenen Positionen.
4. ARCore-Fehlermeldung INSUFFICIENT_LIGHT: ungefähr neun Sekunden andauernder Trackingausfall führt zum Umschalten in den Nachtmodus, ohne die aktive WLAN-Session abzubrechen. Manuelles Umschalten ist in KARTE ebenfalls möglich.
5. Import älterer RF Lab JSONL-Dateien über die Android-Dokumentenauswahl, mit einfacher Schemaerkennung und Größenlimit 80 MB. Die Aufnahmen erscheinen im lokalen Archiv.
6. CSV-Export der mit ARCore-Posen verknüpften WLAN-Werte und separater CSV-Export der manuell kartierten WLAN-Positionen.
7. Übersicht über frische/gecachte Scans und Zeit seit letzter WLAN-Antwort auf der Funkfeldkarte.

### Empfohlener erster Test

- Öffne NACHT. Wähle Zimmerbreite und -tiefe in Metern, beispielsweise 6 × 5 m.
- Starte den Kamerafreien Modus. Stelle dich an eine bekannte Position, tippe den Ort P1 im Grundriss und warte 15–20 Sekunden.
- Tippe auf Aufnahme pausieren. Gehe zum nächsten Ort. Erzeuge P2 und wiederhole mit P3 und P4, möglichst weit verteilt.
- Stoppe die Messfahrt. Wähle im Nachtmodus einen einzelnen BSSID zur RSSI-Heatmap und exportiere die manuelle CSV.
- Wiederhole bei Tageslicht eine ARCore-Messfahrt. Prüfe Funkfeldkarte, echte Trackingpunkte und optischen Raumscan.
- Importiere eine frühere JSONL-Datei und vergleiche im Bereich MESH die sichtbaren Access Points.
- Lege unter INDOOR zwei bis drei benannte WLAN-Fingerprint-Orte an und teste deren Erkennung.

### Messintegrität

ARCore-Raumkoordinaten und manuell eingegebene Grundrisskoordinaten sind separate Koordinatensysteme. Sie werden nicht ohne Registrierung zusammengeführt. Eine 2D-Heatmap zeigt lokale RSSI-Interpolation, keine RF-Phasenbildgebung und keine versteckten Objekte.

### Kompatibilität

Kein Root, kein Exploit, keine Internetberechtigung. Android 12 oder neuer; ARCore Depth und Kamera sind optionale Features. WLAN-Signalstärke erfordert die üblichen Android-Berechtigungen und aktiviertem Standortdienst; bei Android kann die Scan-Drosselung die angeforderte Frequenz einschränken.

### Update-Hinweis

GitHub-Actions-Debug-APKs können unterschiedliche Debug-Signaturschlüssel verwenden. Beim Wechsel von Version 0.3.0 auf 0.3.1 kann deshalb ein In-place-Update fehlschlagen. Bitte ältere JSONL und relevante CSVs vorher exportieren; eine Deinstallation löscht Android-App-Daten, inklusive privater Indoor-Referenzorte. Für dauerhaft nahtlose Updates sollte später ein stabiler geheimer Release-Signaturschlüssel eingerichtet werden.

### Export-Arten

- JSONL: vollständige Session einschließlich WLAN, Pose und manueller Messereignisse.
- Räumliches CSV: Zeitpunkt, BSSID/SSID, RSSI, Frequenz und falls vorhanden ARCore-Position (mit Run-ID).
- Nachtmodus-CSV: manuelle Koordinaten, BSSID, Frequenz, Median-, Minimal- und Maximal-RSSI, Stichprobenzahl.

