# RF Lab Tracking · Pixel-Testversion 1.3.10-qa.1

Dieser Zweig sichert die signierte ARM64-Testversion aus Commit `96ef3b92dc28668616b131fe834476f85a2770e5`. Für die normale Installation steht die fertige APK im zugehörigen Chat bereit. Aus dem Git-Paket und der vorhandenen Pixel-Test-APK 1.3.9-qa.1 lässt sich dieselbe Datei auf einem Rechner vollständig offline wiederherstellen.

1. Den Zweig als ZIP herunterladen und entpacken. Python 3 und xdelta3 werden für die Wiederherstellung benötigt.
2. Im entpackten Ordner ausführen:

   ```sh
   python3 testpaket/wiederherstellen.py /pfad/RF-Lab-v1.3.9-qa.1-Pixel-arm64.apk
   ```

3. Die erzeugte Datei `RF-Lab-v1.3.10-qa.1-Tracking-Pixel-arm64.apk` auf das Pixel übertragen und öffnen. Die App erscheint als **RF Lab Tracking** mit der Paket-ID `dev.rflab.scanner.trackingtest`.

Das Skript prüft die SHA-256-Prüfsummen von Basis und Ergebnis. Die geprüfte APK hat SHA-256 `aa8046766ab3909eabd9fc6272cf59900a799df6fd77d522016a101715f6d829`. Für die ZIP-Wiederherstellung wurden dieselben Bytes erneut erzeugt und geprüft.
