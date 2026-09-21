"""Prepares a pack of cover art for the dash, so a playlist has its covers before it is played.

    python tools/art/fetch_art.py "<playlist>.csv" ["<another>.csv" ...]

Takes an Exportify CSV straight out of Spotify (Track Name, Album Name, Artist Name(s)), or a
plain list of "Title - Artist" lines. Covers are looked up an album at a time and saved under
the name ArtLookup gives that track, so the unit finds them without asking anyone. The result is
tools/art/art.zip, which Settings > Developer > Fetch cover art pack pulls over the Wi-Fi.

Deezer answers first because it is happy to be asked quickly; Apple - the same catalogue the
unit uses when it is online - picks up what Deezer has never heard of, at the one-every-three-
seconds it asks for. Covers already fetched are left alone, so this can be run again after a
playlist grows, or after it has been stopped.

The name is the MD5 of "<plain title>|<plain artist>", where plain means: brackets dropped, a
trailing " - Remastered/Live/Radio Edit/..." dropped, the first artist only, and everything but
letters and digits turned into single spaces. ArtLookup.softKey does exactly the same, so the
two agree however the track arrives over Bluetooth.
"""
import csv
import hashlib
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
VERSIONS = r"remaster|remastered|live|version|edit|mix|mono|stereo|anniversary|deluxe|radio|single|bonus"
AGENT = {"User-Agent": "celica-dash/1.0"}
SIZE = "400x400"                                   # the dash draws the cover at about 250 px
APPLE_GAP = 3.1                                    # Apple allows about twenty lookups a minute
DEEZER_GAP = 0.25
_last = {"apple": 0.0, "deezer": 0.0}


def plain(s):
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def strip_title(t):
    t = re.sub(r"\(.*?\)", " ", t)
    t = re.sub(r"\[.*?]", " ", t)
    return re.sub(r"\s+-\s+[^-]*\b(" + VERSIONS + r")\b.*$", "", t, flags=re.I)


def first_artist(artist):
    return re.split(r";|,| & ", artist or "")[0].strip()


def soft_key(title, artist):
    """The name ArtLookup looks under. Keep this in step with ArtLookup.softKey."""
    return hashlib.md5((plain(strip_title(title)) + "|" + plain(first_artist(artist))).encode()).hexdigest()


def read_tracks(path):
    """(title, artist, album) for every line of an Exportify CSV or a plain list."""
    raw = io.open(path, encoding="utf-8-sig").read()
    out = []
    if "Track Name" in raw.splitlines()[0]:
        for row in csv.DictReader(io.StringIO(raw)):
            t = (row.get("Track Name") or "").strip()
            if t:
                out.append((t, (row.get("Artist Name(s)") or "").strip(), (row.get("Album Name") or "").strip()))
        return out
    for line in raw.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        for sep in ("\t", " | ", " — ", " - "):
            if sep in line:
                a, b = line.split(sep, 1)
                out.append((a.strip(), b.strip(), ""))
                break
        else:
            out.append((line, "", ""))
    return out


def get(url, timeout=20):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=timeout) as r:
        return r.read()


def say(line):
    """Console-safe: a track name in a script the console cannot spell is not worth a crash."""
    enc = sys.stdout.encoding or "utf-8"
    print(line.encode(enc, "replace").decode(enc, "replace"), flush=True)


def wait(who, gap):
    left = _last[who] + gap - time.time()
    if left > 0:
        time.sleep(left)
    _last[who] = time.time()


def apple(term, entity):
    wait("apple", APPLE_GAP)
    url = "https://itunes.apple.com/search?term=%s&entity=%s&limit=5" % (urllib.parse.quote(term), entity)
    for attempt in range(3):
        try:
            return json.loads(get(url).decode("utf-8", "replace")).get("results") or []
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(20 * (attempt + 1))         # asked to slow down; so slow down
            _last["apple"] = time.time()
    return []


def deezer(term, entity):
    """Deezer in Apple's shape, so one matcher serves both."""
    wait("deezer", DEEZER_GAP)
    url = "https://api.deezer.com/search/%s?q=%s&limit=5" % ("album" if entity == "album" else "track", urllib.parse.quote(term))
    try:
        raw = get(url)
    except urllib.error.HTTPError as e:
        if e.code not in (403, 429):
            raise
        time.sleep(6)
        raw = get(url)
    data = json.loads(raw.decode("utf-8", "replace")).get("data") or []
    out = []
    for d in data:
        album = d if entity == "album" else (d.get("album") or {})
        art = album.get("cover_big") or album.get("cover_medium")
        if not art:
            continue
        out.append({
            "artworkUrl100": art,
            "collectionName": album.get("title", ""),
            "trackName": d.get("title", ""),
            "_direct": True,                       # already the size we want, no rewriting
        })
    return out


def cover(title, artist, album):
    """The cover for one track: by its album where there is one, else by the song itself."""
    artist = first_artist(artist)
    tries = []
    if album:
        tries.append((" ".join(x for x in (artist, album) if x), "album", plain(album), "collectionName"))
    tries.append((" ".join(x for x in (artist, strip_title(title).strip()) if x), "song", plain(strip_title(title)), "trackName"))
    for where in (deezer, apple):
        for term, entity, want, field in tries:
            try:
                results = where(term, entity)
            except Exception as e:
                say("  %s: %s" % (term, e))
                continue
            if not results:
                continue
            best = min(results, key=lambda r: 0 if plain(r.get(field, "")) == want else 1)
            art = best.get("artworkUrl100") or ""
            if art:
                return get(art if best.get("_direct") else art.replace("100x100", SIZE), timeout=30)
    return None


def main():
    srcs = sys.argv[1:] or [os.path.join(HERE, "tracks.txt")]
    if not all(os.path.exists(s) for s in srcs):
        print(__doc__)
        return
    os.makedirs(OUT, exist_ok=True)
    tracks = []
    for s in srcs:
        tracks += read_tracks(s)
    # one lookup an album, not one a track - a record"s tracks all wear the same cover
    by_album = {}
    for title, artist, album in tracks:
        by_album.setdefault((plain(album), plain(first_artist(artist))), []).append((title, artist, album))
    say("%d tracks, %d albums" % (len(tracks), len(by_album)))

    got = 0
    missed = []
    for i, (_, group) in enumerate(by_album.items(), 1):
        keys = [soft_key(t, a) for t, a, _ in group]
        if all(os.path.exists(os.path.join(OUT, k + ".jpg")) for k in keys):
            got += len(group)
            continue
        title, artist, album = group[0]
        try:
            data = cover(title, artist, album)
        except Exception as e:                      # a hiccup on one album is not worth stopping for
            say("  %s - %s: %s" % (album or title, artist, e))
            data = None
        if not data:
            missed.append("%s - %s" % (album or title, first_artist(artist)))
        else:
            for k in keys:
                open(os.path.join(OUT, k + ".jpg"), "wb").write(data)
            got += len(group)
        if i % 25 == 0:
            say("  %d/%d albums, %d covers" % (i, len(by_album), got))

    zpath = os.path.join(HERE, "art.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:
        for name in sorted(os.listdir(OUT)):
            if name.endswith(".jpg"):
                z.write(os.path.join(OUT, name), name)
    say("%d of %d tracks have covers - wrote %s (%.1f MB)" % (got, len(tracks), zpath, os.path.getsize(zpath) / 1e6))
    if missed:
        say("%d albums not found:" % len(missed))
        for m in missed[:40]:
            say("  " + m)


if __name__ == "__main__":
    main()
