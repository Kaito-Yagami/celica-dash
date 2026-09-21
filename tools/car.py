"""The car over Wi-Fi, through the head unit's own adb - which runs as root, on port 9876, with
no authorisation prompt (ro.secure=0, ro.adb.secure=0, SELinux permissive: the ROM shipped that
way; see handoff/boot.md). The unit must be on the house Wi-Fi.

    python tools/car.py install         build the release APK, install it, compile it to native, relaunch
    python tools/car.py shot [name]     a screenshot of the whole screen, map and all -> tools/car/<name>.png
    python tools/car.py log [pattern]   the unit's log (all buffers), optionally filtered
    python tools/car.py boot            reboot, wait for it, then print the boot timings that matter
    python tools/car.py sh <command>    a root shell command
    python tools/car.py songs [dir]     copy a folder of music onto the unit's storage for its own player

Why release, and why compile: the debug APK is "debuggable", which Android refuses to compile past
"quicken" and runs with JNI checking on - the dash took nine seconds to its first frame and the 3D
car stuttered while the JIT caught up. The release build (signed with the same debug key, so it
installs straight over) compiled with `cmd package compile -m speed` starts in a second.

The dash's own fetch-an-update path (Settings > Advanced) and tools/remote.py still work; this
is the quicker way in now that there is adb.
"""
import os
import shutil
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# the SDK's adb (ANDROID_SDK_ROOT or ANDROID_HOME), else whatever adb is on the PATH
_SDK = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or ""
ADB = (os.path.join(_SDK, "platform-tools", "adb" + (".exe" if os.name == "nt" else "")) if _SDK else "") or shutil.which("adb") or "adb"
# the unit, as ip:port - QF units answer as root on 9876 - in CELICA_UNIT
UNIT = os.environ.get("CELICA_UNIT") or sys.exit("set CELICA_UNIT to the unit's address, e.g. CELICA_UNIT=192.168.1.30:9876")
PKG = "com.celicadash.app"
APK = os.path.join(ROOT, "android", "app", "build", "outputs", "apk", "release", "app-release.apk")
SHOTS = os.path.join(ROOT, "tools", "car")


def adb(*args, timeout=600, capture=True):
    p = subprocess.run([ADB, "-s", UNIT, *args], capture_output=capture, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def sh(cmd, timeout=600):
    return adb("shell", cmd, timeout=timeout)[1].strip()


def connect(wait=0):
    deadline = time.time() + wait
    while True:
        try:
            subprocess.run([ADB, "connect", UNIT], capture_output=True, text=True, timeout=8)
            if "uid=0" in sh("id", timeout=8):
                return True
        except subprocess.TimeoutExpired:
            pass
        if time.time() > deadline:
            return False
        time.sleep(4)


def need():
    if not connect():
        sys.exit(f"no root shell at {UNIT}: is the unit on and on the house Wi-Fi?")


def install():
    android = os.path.join(ROOT, "android")
    gradlew = os.path.join(android, "gradlew.bat" if os.name == "nt" else "gradlew")
    g = subprocess.run([gradlew, "assembleRelease", "-q"], cwd=android, capture_output=True, text=True)
    if g.returncode != 0:
        print(g.stdout[-3000:], g.stderr[-3000:]); sys.exit("gradle failed")
    need()
    print("installing", os.path.getsize(APK) // (1024 * 1024), "MB")
    rc, out = adb("install", "-r", APK)
    print(out.strip().splitlines()[-1])
    if rc != 0:
        sys.exit(1)
    # the dash draws its CarPlay curtain over zlink, a window over other apps: an appop only
    # root can grant here, kept across updates, gone with an uninstall - so it is set every time
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    print("overlay:", sh(f"appops get {PKG} SYSTEM_ALERT_WINDOW").splitlines()[0])
    # and the songs under /sdcard/Music, for the dash's own player: a runtime permission
    sh(f"pm grant {PKG} android.permission.READ_EXTERNAL_STORAGE")
    # and out of doze's reach: behind CarPlay for an hour the dash is a background app, and its
    # links (dongle, phone GPS) must keep running; the launcher is not exempt by itself
    sh(f"dumpsys deviceidle whitelist +{PKG} >/dev/null")
    print("storage:", "granted" if "READ_EXTERNAL_STORAGE: granted=true" in sh(f"dumpsys package {PKG}") else "NOT granted")
    t = time.time()
    print("compiling to native", end=" ", flush=True)
    print(sh(f"cmd package compile -m speed -f {PKG}"), f"({int(time.time() - t)}s)")
    print(re.sub(r"\s+", " ", sh(f"dumpsys package {PKG} | grep -A3 'Dexopt state' | grep arm64")))
    sh(f"am force-stop {PKG}")       # it is the launcher: the system brings it straight back
    time.sleep(6)
    print(sh("logcat -d -b all -v time | grep -E 'Displayed com.celica' | grep -v adbd | tail -1"))


def shot(name=None):
    need()
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, (name or time.strftime("%H%M%S")) + ".png")
    p = subprocess.run([ADB, "-s", UNIT, "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
    open(path, "wb").write(p.stdout)
    print(path)


def log(pattern=None):
    need()
    out = sh("logcat -d -b all -v time", timeout=120)
    if pattern:
        out = "\n".join(l for l in out.splitlines() if re.search(pattern, l, re.I))
    say(out)


def boot():
    """Reboot and report what the eye cannot time: when the animation went, when the dash drew."""
    need()
    sh("setprop persist.logd.size 8M")
    adb("reboot")
    t0 = time.time()
    print("rebooting", flush=True)
    time.sleep(30)
    if not connect(wait=150):
        sys.exit("did not come back")
    print(f"back after {int(time.time() - t0)}s; waiting for the dash", flush=True)
    time.sleep(20)
    out = sh("logcat -d -b all -v time | grep -E 'BootAnimationShownTiming|FallbackHome|Start proc.*com.celica|Displayed com.celica|framework_boot_completed'")
    print(out)
    if "FallbackHome" in out:
        print("\nFallbackHome ran: the dash was not taken as the locked-boot home; check directBootAware")


def say(text):
    """Print through whatever the console can show; a track name is not worth a crash."""
    enc = sys.stdout.encoding or "utf-8"
    print(text.encode(enc, "replace").decode(enc, "replace"), flush=True)


def songs(src=None):
    """Music onto the unit's own storage. The unit's media scanner picks it up for the vendor's
    player; the dash then follows that player's session. Skips files already there by size."""
    src = src or os.path.join(os.path.expanduser("~"), "Downloads", "potify downloader", "songs")
    files = [f for f in os.listdir(src) if f.lower().endswith((".mp3", ".m4a", ".flac", ".ogg"))]
    if not files:
        sys.exit(f"nothing to copy in {src}")
    need()
    dst = "/sdcard/Music/Celica"
    sh(f"mkdir -p {dst}")
    have = {}
    for line in sh(f"ls -l {dst}").splitlines():
        m = re.match(r"\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+(.+)$", line)
        if m:
            have[m.group(2)] = int(m.group(1))
    done = 0
    todo = []
    for f in sorted(files):
        clean = re.sub(r"\.\{ext\}", "", f)              # a downloader's unfilled template, if any
        if have.get(clean) != os.path.getsize(os.path.join(src, f)):
            todo.append((f, clean))
    print(f"{len(files)} tracks, {len(todo)} to copy, {sum(os.path.getsize(os.path.join(src, f)) for f, _ in todo) // (1024 * 1024)} MB", flush=True)
    for i, (f, clean) in enumerate(todo, 1):
        rc, out = adb("push", os.path.join(src, f), f"{dst}/{clean}", timeout=300)
        done += rc == 0
        if rc != 0 or i % 25 == 0 or i == len(todo):
            say(f"[{i}/{len(todo)}] {clean}" + ("" if rc == 0 else "  FAILED"))
    # the unit's own player lists what its media scanner has seen; ask for a pass over the folder
    sh(f"find {dst} -type f -name '*.mp3' | head -2000 | while read f; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://$f\" >/dev/null 2>&1; done")
    print(f"{done} copied, {len(files) - len(todo)} were already there, {len(todo) - done} failed; {dst}")


def main():
    if len(sys.argv) < 2:
        print(__doc__); return
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "install": install()
    elif cmd == "shot": shot(*args[:1])
    elif cmd == "log": log(*args[:1])
    elif cmd == "boot": boot()
    elif cmd == "sh": need(); print(sh(" ".join(args)))
    elif cmd == "songs": songs(*args[:1])
    else: print(__doc__)


if __name__ == "__main__":
    main()
