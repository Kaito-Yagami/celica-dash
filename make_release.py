# -*- coding: utf-8 -*-
"""
Builds the release zip: CelicaDash-Setup-v<version>.zip in dist/.

    python make_release.py            builds the APK and the Java tool, gathers the lot, zips it
    python make_release.py --no-build reuses what is built

What goes in: RUN ME (Windows and Mac), README.html, LICENSES/, the signed APK under app/,
setup/ (the helper, the wizard, the region index, dash-import.jar, and any extras such as the
Theatre films), boot/ (the splash maker and its fonts). What stays out: the sources, the
tools that make the pack, everything under setup/tools and setup/payload.

The release key must be named in android/keystore.properties; without it the APK is signed
with the debug key and the script says so, since nobody could update over such a build.
"""
import io
import os
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(HERE, "dist")
GRADLEW = os.path.join(".", "gradlew.bat" if os.name == "nt" else "gradlew")


def run(args, cwd):
    print("$ " + " ".join(args) + "   (in " + os.path.relpath(cwd, HERE) + ")")
    p = subprocess.run([os.path.join(cwd, args[0])] + args[1:], cwd=cwd)
    if p.returncode != 0:
        sys.exit("failed (%d): %s" % (p.returncode, " ".join(args)))


def version():
    s = io.open(os.path.join(HERE, "android", "app", "build.gradle.kts"), encoding="utf-8").read()
    return re.search(r'versionName = "([^"]+)"', s).group(1)


def copy(src, dst):
    if os.path.isdir(src):
        shutil.copytree(src, dst, dirs_exist_ok=True)
    else:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)


def main():
    v = version()
    build = "--no-build" not in sys.argv
    if build:
        if not os.path.exists(os.path.join(HERE, "android", "keystore.properties")):
            print("!! android/keystore.properties is missing: the APK will carry the debug key, and nobody could update over it")
        run([GRADLEW, "assembleRelease", "-q"], os.path.join(HERE, "android"))
        run([GRADLEW, "fatJar", "-q"], os.path.join(HERE, "tools", "routing"))
    apk = os.path.join(HERE, "android", "app", "build", "outputs", "apk", "release", "app-release.apk")
    jar = os.path.join(HERE, "setup", "bin", "dash-import.jar")
    for p in (apk, jar):
        if not os.path.exists(p):
            sys.exit("missing " + p)

    name = "CelicaDash-Setup-v" + v
    out = os.path.join(DIST, name)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    copy(os.path.join(HERE, "RUN ME.cmd"), os.path.join(out, "RUN ME.cmd"))
    copy(os.path.join(HERE, "RUN ME.command"), os.path.join(out, "RUN ME.command"))
    copy(os.path.join(HERE, "README.html"), os.path.join(out, "README.html"))
    copy(os.path.join(HERE, "LICENSES"), os.path.join(out, "LICENSES"))
    copy(apk, os.path.join(out, "app", "celicadash-%s.apk" % v))
    for f in ("helper.py", "regions.json", "make_regions.py"):
        copy(os.path.join(HERE, "setup", f), os.path.join(out, "setup", f))
    copy(os.path.join(HERE, "setup", "wizard"), os.path.join(out, "setup", "wizard"))
    copy(jar, os.path.join(out, "setup", "bin", "dash-import.jar"))
    extras = os.path.join(HERE, "setup", "extras")
    if os.path.isdir(extras):
        copy(extras, os.path.join(out, "setup", "extras"))
    for d in ("tools", "payload"):
        os.makedirs(os.path.join(out, "setup", d), exist_ok=True)
        io.open(os.path.join(out, "setup", d, "README.txt"), "w").write("RUN ME fills this folder.\n")
    copy(os.path.join(HERE, "boot", "make_boot.py"), os.path.join(out, "boot", "make_boot.py"))
    copy(os.path.join(HERE, "boot", "fonts"), os.path.join(out, "boot", "fonts"))

    zpath = os.path.join(DIST, name + ".zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, _, files in os.walk(out):
            for fn in files:
                p = os.path.join(dirpath, fn)
                z.write(p, os.path.relpath(p, DIST))
    print("\n%s  -  %.1f MB" % (zpath, os.path.getsize(zpath) / 1e6))
    for dirpath, _, files in os.walk(out):
        for fn in files:
            p = os.path.join(dirpath, fn)
            if os.path.getsize(p) > 1e6:
                print("  %-50s %6.1f MB" % (os.path.relpath(p, out), os.path.getsize(p) / 1e6))


if __name__ == "__main__":
    main()
