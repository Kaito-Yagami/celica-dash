# -*- coding: utf-8 -*-
"""
The setup helper behind RUN ME: a small web server on the PC.

    python setup/helper.py            (RUN ME.cmd does this and opens the browser)

Two faces on one port, 8787:
  - the wizard, for the browser on this PC only (http://localhost:8787/): the questions, the
    long jobs with their progress, the uploads; everything under /api is refused from any
    other machine;
  - the payload, for the head unit on the same Wi-Fi: GET /hello says what is ready and
    carries the owner's answers; GET /payload/<file> serves it, with ranges so a dropped
    Wi-Fi resumes rather than restarts.
A UDP beacon on 8788, once a second, tells the unit where the helper is.

Everything the jobs need - the pmtiles extractor, a Java runtime, adb - is fetched into
setup/tools/ the first time it is wanted, with a line in the log saying so; the only thing
needed up front is Python 3. Downloads go through curl where there is one, since a Python's
own certificate bundle can be stale, and through urllib otherwise.
"""
import io
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.0"
# 8787 unless told otherwise (--port, or CELICADASH_PORT); the beacon tells the unit which
PORT = int(os.environ.get("CELICADASH_PORT") or (sys.argv[sys.argv.index("--port") + 1] if "--port" in sys.argv else 8787))
BEACON_PORT = 8788
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
WIZARD = os.path.join(HERE, "wizard")
PAYLOAD = os.path.join(HERE, "payload")
TOOLS = os.path.join(HERE, "tools")
BIN = os.path.join(HERE, "bin")
LOGS = os.path.join(PAYLOAD, "logs")
MUSIC_IN = os.path.join(PAYLOAD, "music_in")
# the remote queue: tools/remote.py drops a command here, the unit (with its Advanced switch
# on) picks it up on its next poll and posts the answer back
REMOTE_Q = os.path.join(PAYLOAD, "remote", "queue")
REMOTE_R = os.path.join(PAYLOAD, "remote", "results")
UPLOADS = os.path.join(PAYLOAD, "uploads")
ANSWERS = os.path.join(PAYLOAD, "answers.json")
for d in (PAYLOAD, TOOLS, LOGS, MUSIC_IN, REMOTE_Q, REMOTE_Q + "-emu", REMOTE_R, UPLOADS):
    os.makedirs(d, exist_ok=True)

IS_WIN = platform.system() == "Windows"
IS_MAC = platform.system() == "Darwin"
EXE = ".exe" if IS_WIN else ""
AUDIO = {".mp3", ".m4a", ".flac", ".ogg", ".wav", ".aac"}
MAXZOOM = 14
PROTOMAPS_BUILDS = "https://build-metadata.protomaps.dev/builds.json"
PROTOMAPS_BUILD = "https://build.protomaps.com/"
PMTILES_RELEASES = "https://api.github.com/repos/protomaps/go-pmtiles/releases/latest"
PLATFORM_TOOLS = {"Windows": "windows", "Darwin": "darwin", "Linux": "linux"}
ADOPTIUM = "https://api.adoptium.net/v3/binary/latest/17/ga/{os}/{arch}/jre/hotspot/normal/eclipse?project=jdk"

lock = threading.Lock()


# ---- small things ---------------------------------------------------------------------------

def say(*a):
    try:
        print(*a, flush=True)
    except Exception:
        print(str(a).encode("ascii", "replace").decode(), flush=True)


def read_json(path, default=None):
    try:
        with io.open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return default


def write_json(path, obj):
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def lan_ips():
    """This PC's addresses on its networks, the likeliest first."""
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    # the one a packet to the outside would leave by goes first
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        first = s.getsockname()[0]
        s.close()
        if first in ips:
            ips.remove(first)
        ips.insert(0, first)
    except Exception:
        pass
    return ips


def free_ram_gb():
    try:
        if IS_WIN:
            import ctypes
            class MS(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong), ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong), ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong), ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
            m = MS(); m.dwLength = ctypes.sizeof(MS)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
            return m.ullTotalPhys / 1e9, m.ullAvailPhys / 1e9
        if IS_MAC:
            total = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"]).strip())
            return total / 1e9, total / 1e9 * 0.6
        with open("/proc/meminfo") as fh:
            d = dict(l.split(":") for l in fh if ":" in l)
        return int(d["MemTotal"].split()[0]) / 1e6, int(d["MemAvailable"].split()[0]) / 1e6
    except Exception:
        return 0.0, 0.0


def free_disk_gb(path=PAYLOAD):
    try:
        return shutil.disk_usage(path).free / 1e9
    except Exception:
        return 0.0


def fmt_bytes(n):
    return "%.1f GB" % (n / 1e9) if n >= 1e9 else "%.0f MB" % (n / 1e6) if n >= 1e6 else "%.0f KB" % (n / 1e3)


# ---- downloads ------------------------------------------------------------------------------

def http_json(url):
    """A small JSON document: curl if there is one (it trusts the OS's certificates), else urllib."""
    curl = shutil.which("curl") or shutil.which("curl.exe")
    if curl:
        p = subprocess.run([curl, "-sL", "-H", "User-Agent: celicadash-setup", url], capture_output=True)
        if p.returncode == 0 and p.stdout.strip():
            return json.loads(p.stdout.decode("utf-8"))
    req = urllib.request.Request(url, headers={"User-Agent": "celicadash-setup"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def head_size(url):
    curl = shutil.which("curl") or shutil.which("curl.exe")
    try:
        if curl:
            p = subprocess.run([curl, "-sIL", url], capture_output=True, text=True, timeout=60)
            sizes = re.findall(r"(?im)^content-length:\s*(\d+)", p.stdout)
            return int(sizes[-1]) if sizes else -1
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "celicadash-setup"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return int(r.headers.get("Content-Length") or -1)
    except Exception:
        return -1


DL_LOCKS = {}


def download(url, dest, job=None, label=None):
    """A big file to disk with progress on the job; resumes what is already there. Two jobs
    wanting the same file (the routing and the search both want the extract) take turns."""
    with lock:
        dl = DL_LOCKS.setdefault(dest, threading.Lock())
    with dl:
        return _download(url, dest, job, label)


def _download(url, dest, job, label):
    label = label or os.path.basename(dest)
    part = dest + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    total = head_size(url)
    if os.path.exists(dest) and total > 0 and os.path.getsize(dest) == total:
        job_log(job, "%s is already here (%s)" % (label, fmt_bytes(total)))
        return dest
    job_log(job, "downloading %s%s" % (label, " - " + fmt_bytes(total) if total > 0 else ""))
    curl = shutil.which("curl") or shutil.which("curl.exe")
    if curl:
        # curl resumes with -C -, and its own progress goes to a log we do not read; the file's size is the progress
        p = subprocess.Popen([curl, "-sL", "-C", "-", "-o", part, "--retry", "5", url])
        while p.poll() is None:
            time.sleep(1)
            if total > 0 and os.path.exists(part):
                job_progress(job, os.path.getsize(part) / total, "%s of %s" % (fmt_bytes(os.path.getsize(part)), fmt_bytes(total)))
        if p.returncode != 0:
            raise RuntimeError("curl failed (%d) fetching %s" % (p.returncode, url))
    else:
        req = urllib.request.Request(url, headers={"User-Agent": "celicadash-setup", "Range": "bytes=%d-" % have})
        with urllib.request.urlopen(req, timeout=120) as r, open(part, "ab" if have else "wb") as out:
            done = have
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                if total > 0:
                    job_progress(job, done / total, "%s of %s" % (fmt_bytes(done), fmt_bytes(total)))
    os.replace(part, dest)
    return dest


def unzip(path, into):
    with zipfile.ZipFile(path) as z:
        z.extractall(into)


def untar(path, into):
    import tarfile
    with tarfile.open(path) as t:
        t.extractall(into)


# ---- tools on demand ------------------------------------------------------------------------

def find_exe(names, roots):
    for r in roots:
        for dirpath, _, files in os.walk(r):
            for n in names:
                if n in files:
                    return os.path.join(dirpath, n)
    return None


def tool_pmtiles(job):
    exe = find_exe(["pmtiles" + EXE], [TOOLS]) or shutil.which("pmtiles")
    if exe:
        return exe
    job_log(job, "fetching the pmtiles extractor (go-pmtiles, BSD licence)")
    rel = http_json(PMTILES_RELEASES)
    want = {"Windows": "Windows_x86_64.zip", "Darwin": "Darwin_arm64.zip" if platform.machine() == "arm64" else "Darwin_x86_64.zip", "Linux": "Linux_x86_64.tar.gz"}[platform.system()]
    asset = next(a for a in rel["assets"] if a["name"].endswith(want))
    f = download(asset["browser_download_url"], os.path.join(TOOLS, asset["name"]), job, "pmtiles")
    into = os.path.join(TOOLS, "pmtiles")
    os.makedirs(into, exist_ok=True)
    (unzip if f.endswith(".zip") else untar)(f, into)
    exe = find_exe(["pmtiles" + EXE], [into])
    if not IS_WIN:
        os.chmod(exe, 0o755)
    return exe


def java_version(exe):
    try:
        p = subprocess.run([exe, "-version"], capture_output=True, text=True, timeout=20)
        m = re.search(r'version "(\d+)', p.stderr + p.stdout)
        return int(m.group(1)) if m else 0
    except Exception:
        return 0


def tool_java(job):
    exe = find_exe(["java" + EXE], [TOOLS])
    if exe and java_version(exe) >= 17:
        return exe
    sys_java = shutil.which("java")
    if sys_java and java_version(sys_java) >= 17:
        return sys_java
    job_log(job, "fetching a Java runtime (Temurin 17, about 45 MB) - the routing tool runs on it")
    os_name = {"Windows": "windows", "Darwin": "mac", "Linux": "linux"}[platform.system()]
    arch = "aarch64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
    url = ADOPTIUM.format(os=os_name, arch=arch)
    f = download(url, os.path.join(TOOLS, "jre17." + ("zip" if IS_WIN else "tar.gz")), job, "Java runtime")
    into = os.path.join(TOOLS, "jre")
    os.makedirs(into, exist_ok=True)
    (unzip if f.endswith(".zip") else untar)(f, into)
    exe = find_exe(["java" + EXE], [into])
    if not IS_WIN:
        os.chmod(exe, 0o755)
    return exe


def tool_adb(job=None):
    exe = find_exe(["adb" + EXE], [TOOLS]) or shutil.which("adb")
    if exe:
        return exe
    job_log(job, "fetching Android platform-tools (adb) from Google - by using it you accept their SDK terms")
    url = "https://dl.google.com/android/repository/platform-tools-latest-%s.zip" % PLATFORM_TOOLS[platform.system()]
    f = download(url, os.path.join(TOOLS, "platform-tools.zip"), job, "platform-tools")
    unzip(f, TOOLS)
    exe = find_exe(["adb" + EXE], [TOOLS])
    if not IS_WIN:
        os.chmod(exe, 0o755)
    return exe


def tool_jar():
    for p in (os.path.join(BIN, "dash-import.jar"), os.path.join(TOOLS, "dash-import.jar")):
        if os.path.exists(p):
            return p
    raise RuntimeError("setup/bin/dash-import.jar is missing - it ships in the zip; on a checkout, run tools/routing/gradlew fatJar")


# ---- jobs -----------------------------------------------------------------------------------

JOBS = {}          # name -> dict(status, progress, message, log, started, ended, error)
JOB_PROCS = {}


def job_get(name):
    with lock:
        return JOBS.setdefault(name, {"status": "idle", "progress": None, "message": "", "log": os.path.join(LOGS, name + ".log"), "started": 0, "ended": 0, "error": None})


def job_log(job, line):
    say("  " + line)
    if job is None:
        return
    with io.open(job["log"], "a", encoding="utf-8") as fh:
        fh.write(time.strftime("%H:%M:%S ") + line + "\n")
    with lock:
        job["message"] = line


def job_progress(job, f, message=None):
    if job is None:
        return
    with lock:
        job["progress"] = max(0.0, min(0.999, f))
        if message:
            job["message"] = message


def run_job(name, fn):
    job = job_get(name)
    with lock:
        if job["status"] == "running":
            return False
        job.update(status="running", progress=None, message="starting", started=time.time(), ended=0, error=None)
    try:
        os.remove(job["log"])
    except OSError:
        pass

    def body():
        try:
            fn(job)
            with lock:
                job.update(status="done", progress=1.0, ended=time.time())
            job_log(job, "done")
        except Exception as e:
            with lock:
                job.update(status="failed", error=str(e), ended=time.time())
            job_log(job, "FAILED: " + str(e))
        finally:
            JOB_PROCS.pop(name, None)
            write_manifest()

    threading.Thread(target=body, name="job-" + name, daemon=True).start()
    return True


def run_cmd(job, args, cwd=None, progress_re=None, progress_of=None, env=None):
    """An external tool, its output into the job's log, killed if the job is stopped."""
    job_log(job, "$ " + " ".join(os.path.basename(a) if i == 0 else a for i, a in enumerate(args)))
    p = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", env=env)
    JOB_PROCS[job_name_of(job)] = p
    with io.open(job["log"], "a", encoding="utf-8") as fh:
        for line in p.stdout:
            line = line.rstrip("\n")
            fh.write(line + "\n")
            fh.flush()
            if progress_re:
                m = re.search(progress_re, line)
                if m:
                    job_progress(job, progress_of(m), line.strip()[:120])
            elif line.strip():
                with lock:
                    job["message"] = line.strip()[:120]
    p.wait()
    if p.returncode != 0:
        raise RuntimeError("%s exited %d - see the log" % (os.path.basename(args[0]), p.returncode))


def job_name_of(job):
    for k, v in JOBS.items():
        if v is job:
            return k
    return "?"


def stop_job(name):
    p = JOB_PROCS.get(name)
    if p:
        p.kill()
    job = job_get(name)
    with lock:
        if job["status"] == "running":
            job.update(status="failed", error="stopped", ended=time.time())


# ---- the answers, the region, the manifest ----------------------------------------------------

REGIONS = read_json(os.path.join(HERE, "regions.json"), {"regions": []})["regions"]
BY_ID = {r["id"]: r for r in REGIONS}


def answers():
    return read_json(ANSWERS, {}) or {}


def region():
    a = answers()
    r = BY_ID.get((a.get("region") or {}).get("id"))
    return r


def setup_json(a):
    """What the unit reads: the owner's answers in the dash's own shape."""
    r = BY_ID.get((a.get("region") or {}).get("id"))
    car = a.get("car") or {}
    look = a.get("look") or {}
    phone = a.get("phone") or {}
    out = {"version": VERSION, "car": {k: v for k, v in car.items() if v not in (None, "")}, "look": {k: v for k, v in look.items() if v not in (None, "")},
           "phone": {k: v for k, v in phone.items() if v not in (None, "")}}
    reg = dict((a.get("region") or {}))
    if r:
        b = r["bbox"]
        reg.update(name=r["name"], lat=round((b[1] + b[3]) / 2, 4), lon=round((b[0] + b[2]) / 2, 4), zoom=zoom_for(b))
        reg.setdefault("units", r["units"])
        reg.setdefault("locale", r["locale"])
    out["region"] = {k: v for k, v in reg.items() if k != "id" and v not in (None, "")}
    return out


def zoom_for(b):
    span = max(b[2] - b[0], (b[3] - b[1]) * 1.6)
    return round(max(3.0, min(9.0, 8.5 - 1.9 * max(0.1, span) ** 0.5 + 0.9)), 1)


def write_manifest():
    """payload.json: what the unit can fetch, from what is actually in the folder."""
    a = answers()
    items = []

    def add(kind, fn, label, **extra):
        p = os.path.join(PAYLOAD, fn)
        if os.path.exists(p) and os.path.getsize(p) > 0:
            d = {"kind": kind, "path": fn, "bytes": os.path.getsize(p), "label": label}
            d.update(extra)
            items.append(d)

    write_json(os.path.join(PAYLOAD, "setup.json"), setup_json(a))
    add("setup", "setup.json", "Your answers from this wizard")
    add("apk", "celicadash.apk", "The dash app (already installed if you opened it from here)")
    r = region()
    add("tiles", "map.pmtiles", "Offline map" + (" - " + r["name"] if r else ""))
    add("routing", "routing.zip", "Turn-by-turn routing" + (" - " + r["name"] if r else ""))
    add("search", "search.idx", "Places for the search and map icons" + (" - " + r["name"] if r else ""))
    m = read_json(os.path.join(PAYLOAD, "music.json"), {})
    add("music", "music.zip", "Your music - %d songs" % m.get("count", 0), folder=m.get("folder", "Dash"), count=m.get("count", 0))
    add("faces", "faces.zip", "Backdrop films for the Theatre face")
    add("art", "art.zip", "Cover art for your songs")
    write_json(os.path.join(PAYLOAD, "payload.json"), {"version": VERSION, "items": items})
    return items


# ---- the jobs themselves ---------------------------------------------------------------------

def bbox_of_answers():
    r = region()
    if not r:
        raise RuntimeError("pick a region first")
    if r.get("wide"):
        raise RuntimeError("%s wraps round the world; pick a state or sub-region instead" % r["name"])
    return r


def job_tiles(job):
    r = bbox_of_answers()
    b = r["bbox"]
    exe = tool_pmtiles(job)
    job_log(job, "asking Protomaps which basemap build is newest")
    builds = http_json(PROTOMAPS_BUILDS)
    latest = sorted(builds, key=lambda x: x["key"])[-1]["key"]
    out = os.path.join(PAYLOAD, "map.pmtiles")
    tmp = out + ".part"
    job_log(job, "extracting %s from %s - this is the long one; a country is a gigabyte or more" % (r["name"], latest))
    run_cmd(job, [exe, "extract", PROTOMAPS_BUILD + latest, tmp, "--bbox=%s,%s,%s,%s" % tuple(b), "--maxzoom=%d" % MAXZOOM],
            progress_re=r"(\d+(?:\.\d+)?)%", progress_of=lambda m: float(m.group(1)) / 100)
    os.replace(tmp, out)
    job_log(job, "map.pmtiles - " + fmt_bytes(os.path.getsize(out)))


def slug(region_id):
    """A file name from a region id: Geofabrik's carry slashes (us/rhode-island)."""
    return re.sub(r"[^A-Za-z0-9._-]+", "-", region_id)


def job_pbf(job):
    r = bbox_of_answers()
    dest = os.path.join(TOOLS, slug(r["id"]) + "-latest.osm.pbf")
    download(r["pbf"], dest, job, r["name"] + " extract")
    return dest


def job_routing(job):
    r = bbox_of_answers()
    pbf = job_pbf(job)
    size_gb = os.path.getsize(pbf) / 1e9
    total, avail = free_ram_gb()
    need = max(2.0, size_gb * 6)
    if total and need > total * 0.85:
        raise RuntimeError("%s is %.1f GB of map data and the routing import wants about %.0f GB of memory; this PC has %.0f GB. Pick a smaller region (a state or county) for routing." % (r["name"], size_gb, need, total))
    java = tool_java(job)
    jar = tool_jar()
    graph = os.path.join(TOOLS, "graph-" + slug(r["id"]))
    shutil.rmtree(graph, ignore_errors=True)
    heap = "-Xmx%dm" % int(min(need, total * 0.8 if total else need) * 1000)
    job_log(job, "building the routing graph with %s of heap - a country takes half an hour, a county a minute" % heap[4:])
    run_cmd(job, [java, heap, "-XX:+UseParallelGC", "-jar", jar, "route", pbf, graph],
            progress_re=r"(\d+)%", progress_of=lambda m: float(m.group(1)) / 100)
    out = os.path.join(PAYLOAD, "routing.zip")
    job_log(job, "packing the graph")
    with zipfile.ZipFile(out + ".part", "w", zipfile.ZIP_STORED) as z:
        for fn in sorted(os.listdir(graph)):
            z.write(os.path.join(graph, fn), fn)
    os.replace(out + ".part", out)
    job_log(job, "routing.zip - " + fmt_bytes(os.path.getsize(out)))


def job_search(job):
    r = bbox_of_answers()
    pbf = job_pbf(job)
    java = tool_java(job)
    jar = tool_jar()
    out = os.path.join(PAYLOAD, "search.idx")
    b = r["bbox"]
    job_log(job, "listing every place and stop in %s" % r["name"])
    run_cmd(job, [java, "-Xmx3g", "-jar", jar, "search", pbf, out + ".part", str(b[1]), str(b[3])])
    os.replace(out + ".part", out)
    job_log(job, "search.idx - " + fmt_bytes(os.path.getsize(out)))


def job_music(job):
    """Everything uploaded (files, or zips of files) into one stored zip, flat."""
    a = answers()
    folder = re.sub(r"[^A-Za-z0-9 _-]", "", (a.get("music") or {}).get("folder") or "Dash").strip() or "Dash"
    names = set()
    out = os.path.join(PAYLOAD, "music.zip")
    n = 0
    with zipfile.ZipFile(out + ".part", "w", zipfile.ZIP_STORED) as z:
        for dirpath, _, files in os.walk(MUSIC_IN):
            for fn in sorted(files):
                p = os.path.join(dirpath, fn)
                ext = os.path.splitext(fn)[1].lower()
                if ext == ".zip":
                    try:
                        with zipfile.ZipFile(p) as inner:
                            for info in inner.infolist():
                                base = os.path.basename(info.filename)
                                if info.is_dir() or os.path.splitext(base)[1].lower() not in AUDIO or base in names:
                                    continue
                                z.writestr(base, inner.read(info))
                                names.add(base); n += 1
                                job_progress(job, min(0.99, n / 2000.0), "%d songs so far" % n)
                    except zipfile.BadZipFile:
                        job_log(job, "skipping %s - not a zip" % fn)
                elif ext in AUDIO and fn not in names:
                    z.write(p, fn)
                    names.add(fn); n += 1
                    job_progress(job, min(0.99, n / 2000.0), "%d songs so far" % n)
    if n == 0:
        os.remove(out + ".part")
        raise RuntimeError("no songs found - drop MP3s (or a zip of them) on the music page first")
    os.replace(out + ".part", out)
    write_json(os.path.join(PAYLOAD, "music.json"), {"folder": folder, "count": n})
    job_log(job, "music.zip - %d songs, %s, into Music/%s on the unit" % (n, fmt_bytes(os.path.getsize(out)), folder))


def find_apk():
    cands = []
    for d in (os.path.join(ROOT, "app"), os.path.join(ROOT, "android", "app", "build", "outputs", "apk", "release")):
        if os.path.isdir(d):
            cands += [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".apk")]
    cands.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return cands[0] if cands else None


def job_apk(job):
    src = find_apk()
    if not src:
        raise RuntimeError("no APK found - app/celicadash-*.apk should be in the zip")
    shutil.copyfile(src, os.path.join(PAYLOAD, "celicadash.apk"))
    job_log(job, "celicadash.apk - " + fmt_bytes(os.path.getsize(src)) + " from " + os.path.basename(src))


def job_extras(job):
    """The owner's backdrop films and cover art, if the zip carries them."""
    for fn in ("faces.zip", "art.zip"):
        src = os.path.join(HERE, "extras", fn)
        if os.path.exists(src):
            shutil.copyfile(src, os.path.join(PAYLOAD, fn))
            job_log(job, fn + " - " + fmt_bytes(os.path.getsize(src)))


def job_boot(job):
    """The QF units' boot splash and animation, staged as the vendor's importer wants them."""
    a = answers()
    name = (a.get("boot") or {}).get("name") or "DRIVER"
    py = sys.executable
    venv = os.path.join(TOOLS, "venv")
    vpy = os.path.join(venv, "Scripts" if IS_WIN else "bin", "python" + EXE)
    if not os.path.exists(vpy):
        job_log(job, "making a small Python environment for the image tools")
        run_cmd(job, [py, "-m", "venv", venv])
    run_cmd(job, [vpy, "-m", "pip", "install", "-q", "pillow"])
    out = os.path.join(PAYLOAD, "boot")
    shutil.rmtree(out, ignore_errors=True)
    car, look = a.get("car") or {}, a.get("look") or {}
    line = "  ·  ".join(x for x in (car.get("code"), car.get("sub")) if x)
    run_cmd(job, [vpy, os.path.join(ROOT, "boot", "make_boot.py"), "--name", name, "--wordmark", car.get("name") or "CELICA *T*-SPORT",
                  "--line", line, "--accent", look.get("accent") or "D8433C", "--out", out])
    job_log(job, "boot/QF ready - copy the QF folder to a FAT32 USB stick, plug it into the unit, and say yes")


JOB_FNS = {"tiles": job_tiles, "routing": job_routing, "search": job_search, "music": job_music, "apk": job_apk, "extras": job_extras, "boot": job_boot}


# ---- adb, when there is one -----------------------------------------------------------------

def adb(*args, timeout=60):
    exe = tool_adb()
    p = subprocess.run([exe, *args], capture_output=True, text=True, timeout=timeout)
    return (p.stdout + p.stderr).strip()


UNIT = {"unit": None}


def adb_connect(ip):
    """Tries the two ports these units answer on; the vendor's 9876 (root) first."""
    ip = ip.strip()
    for port in ("9876", "5555"):
        out = adb("connect", "%s:%s" % (ip, port), timeout=15)
        if "connected" in out and "cannot" not in out:
            serial = "%s:%s" % (ip, port)
            who = adb("-s", serial, "shell", "id", timeout=15)
            model = adb("-s", serial, "shell", "getprop", "ro.product.model", timeout=15)
            qf = adb("-s", serial, "shell", "getprop", "ro.qf.platform", timeout=15)
            UNIT["unit"] = {"serial": serial, "root": "uid=0" in who, "model": model, "qf": bool(qf), "android": adb("-s", serial, "shell", "getprop", "ro.build.version.release", timeout=15)}
            return UNIT["unit"]
    UNIT["unit"] = None
    return None


def job_boot_deploy(job):
    """
    The QF folder onto the unit over the network. Three ways, tried in order of what the unit
    offers: root (the owner's own path - the logo straight onto its partition, found by name and
    backed up first; the animation into /great), the vendor's importer fed by a virtual USB disk
    (plain adb, no root; the importer does the privileged writing), or, failing both, the stick.
    """
    unit = UNIT["unit"]
    if not unit:
        raise RuntimeError("connect to the unit on the Install page first (its Wi-Fi address, then Connect)")
    qf = os.path.join(PAYLOAD, "boot", "QF")
    if not os.path.exists(os.path.join(qf, "bootanimation.zip")):
        raise RuntimeError("make the QF folder first")
    if not unit.get("qf"):
        raise RuntimeError("%s is not a QF-firmware unit; its boot screen cannot be changed this way" % unit.get("model"))
    serial = unit["serial"]
    sh = lambda cmd, t=120: adb("-s", serial, "shell", cmd, timeout=t)
    logo, anim = os.path.join(qf, "logo.bmp"), os.path.join(qf, "bootanimation.zip")
    backup = os.path.join(PAYLOAD, "boot", "backup")
    os.makedirs(backup, exist_ok=True)

    if unit.get("root"):
        job_log(job, "root: the direct way, as on the unit this was written for")
        # the logo partition, by its name - never by a number, which differs between boards
        part = ""
        for probe in ("ls -l /dev/block/by-name/logo 2>/dev/null", "ls -l /dev/block/platform/*/by-name/logo 2>/dev/null", "ls -l /dev/block/platform/*/*/by-name/logo 2>/dev/null"):
            out = sh(probe, 30)
            m = re.search(r"->\s*(/dev/block/\S+)", out)
            if m:
                part = m.group(1); break
        if part:
            size = sh("blockdev --getsize64 %s" % part, 30).strip()
            job_log(job, "logo partition %s (%s bytes) - keeping a copy first" % (part, size))
            sh("dd if=%s of=/sdcard/logo-partition-backup.bin bs=1M" % part, 300)
            adb("-s", serial, "pull", "/sdcard/logo-partition-backup.bin", os.path.join(backup, "logo-partition-backup.bin"), timeout=300)
            job_log(job, "backup saved: %s" % os.path.join(backup, "logo-partition-backup.bin"))
            if size.isdigit() and int(size) < os.path.getsize(logo):
                job_log(job, "the partition is smaller than logo.bmp - skipping the splash, the animation still goes on")
            else:
                adb("-s", serial, "push", logo, "/sdcard/logo.bmp", timeout=120)
                sh("dd if=/sdcard/logo.bmp of=%s bs=1M conv=fsync; rm /sdcard/logo.bmp" % part, 300)
                job_log(job, "splash written")
        else:
            job_log(job, "no partition named 'logo' on this board - the splash is skipped; the animation still goes on")
        great = sh("ls -ld /great 2>/dev/null", 30)
        if "/great" not in great:
            raise RuntimeError("no /great folder on this unit - not the ROM this path was written for; try the stick")
        old = sh("ls -l /great/bootanimation.zip 2>/dev/null", 30)
        if "bootanimation" in old:
            adb("-s", serial, "pull", "/great/bootanimation.zip", os.path.join(backup, "bootanimation-before.zip"), timeout=300)
        adb("-s", serial, "push", anim, "/sdcard/bootanimation.zip", timeout=300)
        sh("cp /sdcard/bootanimation.zip /great/bootanimation.zip && chown system:system /great/bootanimation.zip && chmod 644 /great/bootanimation.zip && rm /sdcard/bootanimation.zip", 120)
        job_log(job, "animation in place - it shows on the next boot. Undo: delete /great/bootanimation.zip (stock returns), dd the backup back onto %s" % (part or "the logo partition"))
        return

    job_log(job, "no root: a virtual USB disk for the vendor's own importer")
    stage = os.path.join(PAYLOAD, "boot", "stage", "QF")
    shutil.rmtree(os.path.dirname(stage), ignore_errors=True)
    os.makedirs(stage)
    open(os.path.join(stage, "import_auto.cfg"), "w").close()
    # the importer remembers what it took by MD5; a new stamp in the BMP's reserved bytes makes it new
    data = bytearray(open(logo, "rb").read())
    import struct
    struct.pack_into("<I", data, 6, int(time.time()) & 0xFFFFFFFF)
    open(os.path.join(stage, "logo.bmp"), "wb").write(data)
    shutil.copy2(anim, os.path.join(stage, "bootanimation.zip"))
    sh("sm set-virtual-disk true", 30)
    disk = None
    for _ in range(20):
        time.sleep(1)
        m = re.search(r"(disk:\d+,\d+)", sh("sm list-disks", 30))
        if m:
            disk = m.group(1); break
    if not disk:
        raise RuntimeError("the unit would not make a virtual disk - use the USB stick")
    sh("sm partition %s public" % disk, 60)
    vol = uuid = None
    for _ in range(30):
        time.sleep(1)
        m = re.search(r"(public:\d+,\d+)\s+mounted\s+(\S+)", sh("sm list-volumes public", 30))
        if m:
            vol, uuid = m.group(1), m.group(2); break
    if not vol:
        raise RuntimeError("the virtual disk did not mount - use the USB stick")
    adb("-s", serial, "push", stage, "/storage/%s" % uuid, timeout=600)
    job_log(job, "pushed to /storage/%s/QF; re-mounting so the importer notices" % uuid)
    sh("sm unmount %s" % vol, 60)
    time.sleep(2)
    sh("sm mount %s" % vol, 60)
    job_log(job, "watch the unit: 'importing', 'finished', then it reboots itself. Afterwards: sm set-virtual-disk false (the wizard's Tidy up button)")


def job_boot_cleanup(job):
    unit = UNIT["unit"]
    if not unit:
        raise RuntimeError("no unit connected")
    adb("-s", unit["serial"], "shell", "sm set-virtual-disk false; sm forget all", timeout=60)
    job_log(job, "virtual disk removed")


# ---- the server -----------------------------------------------------------------------------

def beacon():
    """Once a second: CELICADASH-HELPER <ip>:8787 to everyone on the network."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    while True:
        for ip in lan_ips()[:1] or [""]:
            msg = ("CELICADASH-HELPER %s:%d" % (ip, PORT)).encode()
            for target in ("255.255.255.255", subnet_broadcast(ip)):
                if target:
                    try:
                        s.sendto(msg, (target, BEACON_PORT))
                    except OSError:
                        pass
        time.sleep(1)


def subnet_broadcast(ip):
    parts = ip.split(".")
    return ".".join(parts[:3] + ["255"]) if len(parts) == 4 else ""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        if "/api/status" in (a[0] if a else "") or "/hello" in (a[0] if a else ""):
            return
        say("%s %s" % (self.client_address[0], fmt % a))

    def local(self):
        return self.client_address[0] in ("127.0.0.1", "::1")

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path, ranges=False):
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        ctype = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8", ".png": "image/png",
                 ".svg": "image/svg+xml", ".apk": "application/vnd.android.package-archive", ".zip": "application/zip"}.get(os.path.splitext(path)[1].lower(), "application/octet-stream")
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if ranges and rng and rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            start = int(a) if a else max(0, size - int(b))
            end = int(b) if b and a else size - 1
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        else:
            self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with open(path, "rb") as fh:
            fh.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = fh.read(min(1 << 20, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    return
                left -= len(chunk)

    def body_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode("utf-8", "replace")) if n else {}

    # ---- GET ----
    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        path, q = u.path, urllib.parse.parse_qs(u.query)
        if path == "/hello":
            items = write_manifest()
            return self.send_json({"name": platform.node() or "the PC", "version": VERSION, "ip": (lan_ips() or [""])[0], "items": items, "setup": setup_json(answers())})
        if path.startswith("/payload/"):
            fn = os.path.basename(urllib.parse.unquote(path[len("/payload/"):]))
            if fn == "payload.json":
                write_manifest()
            return self.send_file(os.path.join(PAYLOAD, fn), ranges=True)
        if path == "/cmd":
            # the unit asking for work: the oldest queued command, or nothing
            qdir = REMOTE_Q + ("-emu" if (q.get("who") or [""])[0] == "emu" else "")
            names = sorted(f for f in os.listdir(qdir) if f.isdigit())
            if not names:
                self.send_response(204); self.send_header("Content-Length", "0"); self.end_headers(); return
            fn = os.path.join(qdir, names[0])
            body = open(fn, "rb").read()
            os.remove(fn)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("X-Command-Id", names[0])
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/" or path == "/index.html":
            return self.send_file(os.path.join(WIZARD, "index.html"))
        if path == "/guide":
            return self.send_file(os.path.join(ROOT, "README.html"))
        if path.startswith("/wizard/"):
            return self.send_file(os.path.join(WIZARD, os.path.basename(path)))
        if path.startswith("/api/"):
            if not self.local():
                return self.send_json({"error": "the wizard is for the PC it runs on"}, 403)
            if path == "/api/state":
                return self.send_json(self.state())
            if path == "/api/regions":
                return self.send_json({"regions": REGIONS})
            if path.startswith("/api/log/"):
                job = job_get(path.rsplit("/", 1)[1])
                try:
                    with io.open(job["log"], encoding="utf-8") as fh:
                        lines = fh.read().splitlines()[-60:]
                except OSError:
                    lines = []
                return self.send_json({"lines": lines})
            if path == "/api/adb/scan":
                ip = (q.get("ip") or [""])[0]
                try:
                    return self.send_json({"unit": adb_connect(ip)} if ip else {"unit": None})
                except Exception as e:
                    return self.send_json({"unit": None, "error": str(e)})
            if path == "/api/region/size":
                r = BY_ID.get((q.get("id") or [""])[0])
                return self.send_json({"pbfBytes": head_size(r["pbf"]) if r else -1})
        self.send_error(404)

    def state(self):
        a = answers()
        r = region()
        total, avail = free_ram_gb()
        with lock:
            jobs = {k: {kk: vv for kk, vv in v.items() if kk != "log"} for k, v in JOBS.items()}
        music = []
        for dirpath, _, files in os.walk(MUSIC_IN):
            music += [f for f in files]
        return {
            "version": VERSION, "ips": lan_ips(), "port": PORT, "answers": a, "region": r,
            "ramGb": round(total, 1), "diskFreeGb": round(free_disk_gb(), 1), "jobs": jobs,
            "payload": write_manifest(), "musicIn": len(music), "apk": os.path.basename(find_apk() or ""),
            "hasJar": bool(find_exe(["dash-import.jar"], [BIN, TOOLS])), "hasBootTools": os.path.exists(os.path.join(ROOT, "boot", "make_boot.py")),
            "unit": UNIT["unit"],
            "platform": platform.system(),
        }

    # ---- POST / PUT ----
    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        path, q = u.path, urllib.parse.parse_qs(u.query)
        # from the unit: a command's answer, or a file it was asked to send
        if path.startswith("/result/") or path.startswith("/upload/"):
            name = os.path.basename(urllib.parse.unquote(path.rsplit("/", 1)[1]))
            dest = os.path.join(REMOTE_R, name + ".txt") if path.startswith("/result/") else os.path.join(UPLOADS, name)
            n = int(self.headers.get("Content-Length") or 0)
            with open(dest, "wb") as out:
                left = n
                while left > 0:
                    chunk = self.rfile.read(min(1 << 20, left))
                    if not chunk:
                        break
                    out.write(chunk); left -= len(chunk)
            return self.send_json({"ok": True})
        if not path.startswith("/api/") or not self.local():
            return self.send_json({"error": "no"}, 403)
        if path == "/api/answers":
            a = answers()
            a.update(self.body_json())
            write_json(ANSWERS, a)
            write_manifest()
            return self.send_json({"ok": True, "setup": setup_json(a)})
        if path.startswith("/api/run/"):
            name = path.rsplit("/", 1)[1]
            fn = JOB_FNS.get(name) or {"boot_deploy": job_boot_deploy, "boot_cleanup": job_boot_cleanup}.get(name)
            if not fn:
                return self.send_json({"error": "no such job"}, 404)
            return self.send_json({"started": run_job(name, fn)})
        if path.startswith("/api/stop/"):
            stop_job(path.rsplit("/", 1)[1])
            return self.send_json({"ok": True})
        if path == "/api/music/clear":
            shutil.rmtree(MUSIC_IN, ignore_errors=True)
            os.makedirs(MUSIC_IN, exist_ok=True)
            for fn in ("music.zip", "music.json"):
                try:
                    os.remove(os.path.join(PAYLOAD, fn))
                except OSError:
                    pass
            return self.send_json({"ok": True})
        if path == "/api/adb/install":
            b = self.body_json()
            try:
                serial = b["serial"]
                apk = os.path.join(PAYLOAD, "celicadash.apk")
                if not os.path.exists(apk):
                    job_apk(None)
                out = adb("-s", serial, "install", "-r", apk, timeout=600)
                if "Success" not in out:
                    return self.send_json({"ok": False, "out": out})
                pkg = "com.celicadash.app"
                extra = ""
                if b.get("root"):
                    extra = adb("-s", serial, "shell", "appops set %s SYSTEM_ALERT_WINDOW allow; pm grant %s android.permission.READ_EXTERNAL_STORAGE; dumpsys deviceidle whitelist +%s; cmd package compile -m speed -f %s; echo done" % (pkg, pkg, pkg, pkg), timeout=300)
                adb("-s", serial, "shell", "monkey -p %s -c android.intent.category.LAUNCHER 1" % pkg, timeout=30)
                return self.send_json({"ok": True, "out": out, "extra": extra})
            except Exception as e:
                return self.send_json({"ok": False, "out": str(e)})
        if path == "/api/open":
            # the README, in the browser
            return self.send_json({"ok": True})
        self.send_json({"error": "unknown"}, 404)

    def do_PUT(self):
        """A music file or zip, raw, straight to disk - no multipart, no memory."""
        u = urllib.parse.urlparse(self.path)
        if u.path != "/api/upload" or not self.local():
            return self.send_json({"error": "no"}, 403)
        name = os.path.basename(urllib.parse.unquote((urllib.parse.parse_qs(u.query).get("name") or ["file"])[0]))
        ext = os.path.splitext(name)[1].lower()
        if ext not in AUDIO and ext != ".zip":
            n = int(self.headers.get("Content-Length") or 0)
            self.rfile.read(n)
            return self.send_json({"ok": False, "skipped": name})
        n = int(self.headers.get("Content-Length") or 0)
        dest = os.path.join(MUSIC_IN, name)
        with open(dest, "wb") as out:
            left = n
            while left > 0:
                chunk = self.rfile.read(min(1 << 20, left))
                if not chunk:
                    break
                out.write(chunk)
                left -= len(chunk)
        return self.send_json({"ok": True, "name": name, "bytes": n})


class Server(ThreadingHTTPServer):
    # on Windows SO_REUSEADDR lets a second server bind a port another is already on, and the
    # two then share the connections - so a busy port must fail loudly, not quietly
    allow_reuse_address = False
    daemon_threads = True


def bind():
    """8787 if it is free, else the next free one from 8790; the beacon carries whichever it is."""
    global PORT
    for port in [PORT] + list(range(8790, 8800)):
        try:
            srv = Server(("0.0.0.0", port), Handler)
        except OSError:
            say("  port %d is taken (another program is using it) - trying the next" % port)
            continue
        PORT = port
        return srv
    raise SystemExit("no free port between 8787 and 8799 - close whatever is using them and run again")


def main():
    say("Celica Dash setup helper %s" % VERSION)
    srv = bind()
    say("  wizard:  http://localhost:%d/" % PORT)
    for ip in lan_ips():
        say("  unit:    %s:%d   (the address to type on the unit if it does not find this PC by itself)" % (ip, PORT))
    write_manifest()
    threading.Thread(target=beacon, name="beacon", daemon=True).start()
    if "--no-browser" not in sys.argv:
        import webbrowser
        threading.Timer(0.8, lambda: webbrowser.open("http://localhost:%d/" % PORT)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        say("bye")


if __name__ == "__main__":
    main()
