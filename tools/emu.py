"""The emulator standing in for the head unit, for seeing a change without going outside.

    python tools/emu.py boot            start the celica AVD (1024x600, the unit's shape)
    python tools/emu.py update          build, install, restart the dash, and take a picture
    python tools/emu.py shot [name]     just take a picture, into tools/emu/
    python tools/emu.py go <screen>     home | drive | map | media | phone | garage | manual
    python tools/emu.py where <lat> <lon> [speed_mph] [bearing]   put the car somewhere
    python tools/emu.py drive           walk the car along a short loop, for the map and Drive

The emulator has no Bluetooth of any use, so the dongle, the vendor media and the phone's GPS
over BLE are all dead there; everything else - the look, the layouts, the map, the search, the
routing, the covers - is the same build the car runs.
"""
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "tools", "emu")
SDK = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or sys.exit("set ANDROID_SDK_ROOT to the Android SDK folder")
PKG = "com.celicadash.app"


def sh(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def adb(*args):
    return sh(["adb", *args])


def boot():
    if "emulator-" in adb("devices").stdout:
        print("already running")
        return
    subprocess.Popen([SDK + "/emulator/emulator.exe", "-avd", "celica", "-no-boot-anim", "-gpu", "swiftshader_indirect"])
    print("booting…")
    adb("wait-for-device")
    while adb("shell", "getprop", "sys.boot_completed").stdout.strip() != "1":
        time.sleep(5)
    print("up")


def update():
    g = sh(["./gradlew", "assembleDebug", "-q"], cwd=os.path.join(ROOT, "android"), shell=True)
    if g.returncode != 0:
        print(g.stdout[-3000:] or g.stderr[-3000:])
        return
    apk = os.path.join(ROOT, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    print(adb("install", "-r", "-t", apk).stdout.strip().splitlines()[-1])
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(6)
    shot()


def shot(name=None):
    os.makedirs(SHOTS, exist_ok=True)
    name = (name or time.strftime("%H%M%S")) + ".png"
    path = os.path.join(SHOTS, name)
    p = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True)
    open(path, "wb").write(p.stdout)
    print(path)


def go(screen):
    sh([sys.executable, os.path.join(ROOT, "tools", "remote.py"), "--emu", "go", screen])


def where(lat, lon, mph=0.0, bearing=0.0):
    """The emulator takes a position over its console; speed and heading come from the movement."""
    adb("emu", "geo", "fix", str(lon), str(lat))


def drive():
    """A slow lap of a few streets, so the map has something to follow and the trail to draw."""
    lat, lon = 55.7644, -4.1769
    for i in range(240):
        where(lat + 0.00035 * i % 0.02, lon + 0.00055 * (i % 30))
        time.sleep(1)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd, args = sys.argv[1], sys.argv[2:]
    {
        "boot": boot, "update": update, "shot": shot, "go": go, "where": where, "drive": drive,
    }[cmd](*args)


if __name__ == "__main__":
    main()
