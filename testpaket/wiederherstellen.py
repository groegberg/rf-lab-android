"""Die verifizierte Test-APK aus der bisherigen Pixel-APK wiederherstellen."""
import argparse
from hashlib import sha256
from pathlib import Path
from shutil import copyfileobj, which
from subprocess import run
from tempfile import TemporaryDirectory

BASE_SHA = "b1c91d1d7f9105a24e295bde8915b4246721cc889a0742dd8f410e5c8d1ad85f"
APK_SHA = "aa8046766ab3909eabd9fc6272cf59900a799df6fd77d522016a101715f6d829"
NAME = "RF-Lab-v1.3.10-qa.1-Tracking-Pixel-arm64.apk"


def digest(path):
    result = sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bisherige_apk", type=Path)
    args = parser.parse_args()
    if digest(args.bisherige_apk) != BASE_SHA:
        parser.error("Als Basis wird RF-Lab-v1.3.9-qa.1-Pixel-arm64.apk benötigt.")
    decoder = which("xdelta3")
    if not decoder:
        parser.error("xdelta3 muss installiert und im Suchpfad verfügbar sein.")
    target = Path.cwd() / NAME
    if target.exists():
        if digest(target) == APK_SHA:
            print(target)
            return
        parser.error("Im Ausgabeordner liegt bereits eine andere gleichnamige Datei.")
    with TemporaryDirectory(dir=target.parent) as scratch:
        delta = Path(scratch) / "paket.vcdiff"
        parts = sorted(Path(__file__).resolve().parent.glob("Pixel.vcdiff.*"))
        with delta.open("wb") as output:
            for part in parts:
                with part.open("rb") as segment:
                    copyfileobj(segment, output)
        apk = Path(scratch) / NAME
        run([decoder, "-d", "-s", args.bisherige_apk, delta, apk], check=True)
        if digest(apk) != APK_SHA:
            raise SystemExit("Prüfsumme der Ausgabe stimmt nicht.")
        apk.rename(target)
    print(target)


if __name__ == "__main__":
    main()
