#!/usr/bin/env python3
"""Analyze 16 KiB PT_LOAD ELF alignment for all bundled ARM64 .so files.

Android 15+ may run on 16 KiB-page devices. A build that *compiles* is not
necessarily compatible with such devices if any prebuilt JNI library uses
4 KiB PT_LOAD alignment. This script reports diagnostics, not a false pass.
"""
import struct
import sys
import zipfile

MIN_PAGE=16*1024

def alignment(raw: bytes):
    if raw[:4] != b"\x7fELF" or raw[4] != 2 or raw[5] not in (1,2):
        return None
    endian="<" if raw[5] == 1 else ">"
    if len(raw)<64:return None
    phoff=struct.unpack_from(endian+"Q",raw,32)[0]
    phentsize=struct.unpack_from(endian+"H",raw,54)[0]
    phnum=struct.unpack_from(endian+"H",raw,56)[0]
    aligns=[]
    for i in range(phnum):
        off=phoff+i*phentsize
        if off+56>len(raw):return None
        if struct.unpack_from(endian+"I",raw,off)[0]==1:
            aligns.append(struct.unpack_from(endian+"Q",raw,off+48)[0])
    return min(aligns) if aligns else None

def main(apk):
    old=[]
    with zipfile.ZipFile(apk) as z:
        names=sorted(x for x in z.namelist() if
                     x.startswith("lib/arm64-v8a/") and x.endswith(".so"))
        if not names:
            raise SystemExit("No ARM64 .so files in APK")
        for name in names:
            value=alignment(z.read(name))
            label="unknown" if value is None else f"{value//1024} KiB"
            print(f"{name}: PT_LOAD alignment {label}")
            if value is None or value<MIN_PAGE:old.append(name)
    if old:
        print(f"::warning::{len(old)} native libraries lack verified 16KiB ELF alignment.")
        print("Cannot claim 16KiB device compatibility. Rebuild/replace these "
              "third-party binaries before deployment to a 16KiB-page Pixel.")
    else:print("All ARM64 PT_LOAD segments report >= 16 KiB alignment.")
    return 0

if __name__=="__main__":
    sys.exit(main(sys.argv[1]))
