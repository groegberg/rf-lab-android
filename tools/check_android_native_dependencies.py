#!/usr/bin/env python3
"""Require Android-loadable names for native OpenVINS/Boost shared dependencies.

ELF DT_NEEDED is authoritative; a successfully linked desktop/NDK binary
can fail Android dlopen when Boost's versioned .so SONAME is absent from APK.
"""
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

NEEDED = re.compile(r"\(NEEDED\).*\[([^\]]+)\]")

def main(apk):
    sdk=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise SystemExit("ANDROID_HOME is required")
    readelf=pathlib.Path(sdk)/"ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    if not readelf.is_file():
        raise SystemExit("Pinned NDK llvm-readelf unavailable: "+str(readelf))
    with zipfile.ZipFile(apk) as archive:
        all_names={
            pathlib.PurePosixPath(name).name
            for name in archive.namelist()
            if name.startswith("lib/arm64-v8a/") and name.endswith(".so")
        }
        examined=sorted(name for name in all_names
                        if name=="libnative-lib.so" or name.startswith("libboost_"))
        if "libnative-lib.so" not in examined:
            raise SystemExit("Native OpenVINS engine missing in APK")
        errors=[]
        with tempfile.TemporaryDirectory() as td:
            for name in examined:
                actual="lib/arm64-v8a/"+name
                path=pathlib.Path(td)/name
                path.write_bytes(archive.read(actual))
                result=subprocess.run([str(readelf),"-d",str(path)],
                    check=True,stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,text=True)
                needs=NEEDED.findall(result.stdout)
                print(name+" requires "+", ".join(needs))
                for dep in needs:
                    if (dep.startswith("libboost_") or
                        dep in ("libopencv_java4.so","libc++_shared.so")) and dep not in all_names:
                        errors.append(name+" requires absent "+dep)
    if errors:
        for error in errors:print("::error::"+error)
        raise SystemExit("Unresolvable native OpenVINS dynamic dependencies")
    print("All APK-internal native OpenVINS dependencies are present")

if __name__=="__main__":
    if len(sys.argv)!=2:raise SystemExit("Usage: check_android_native_dependencies.py file.apk")
    main(sys.argv[1])
