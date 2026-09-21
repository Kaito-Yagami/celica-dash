# -*- coding: utf-8 -*-
"""
Snapshot Geofabrik's region index into setup/regions.json for the RUN ME wizard.

    python setup/make_regions.py            (needs the network once; curl or urllib)

Every region Geofabrik cuts an OSM extract for - continents, countries, and the states and
counties of the big ones - with its bounding box (from the index's geometry), the extract's
URL, and a guess at the units and voice its roads are driven in. The wizard's region picker
reads this; the bbox drives the map tiles, the search index's latitude bands and where the
map opens; the URL drives the routing graph.
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "regions.json")
INDEX = "https://download.geofabrik.de/index-v1.json"

# countries that drive in miles; everything else gets kilometres. US also gets Fahrenheit.
MPH = {"great-britain", "united-kingdom", "ireland", "us", "isle-of-man", "liberia", "myanmar"}
VOICE = {
    "germany": "de-DE", "austria": "de-DE", "switzerland": "de-CH", "france": "fr-FR", "belgium": "nl-BE", "netherlands": "nl-NL",
    "spain": "es-ES", "portugal": "pt-PT", "italy": "it-IT", "poland": "pl-PL", "czech-republic": "cs-CZ", "sweden": "sv-SE",
    "norway": "nb-NO", "denmark": "da-DK", "finland": "fi-FI", "japan": "ja-JP", "brazil": "pt-BR", "mexico": "es-MX",
    "us": "en-US", "canada": "en-CA", "australia": "en-AU", "new-zealand": "en-NZ", "ireland": "en-IE", "great-britain": "en-GB", "united-kingdom": "en-GB",
}


def fetch(url):
    """curl first - it trusts the OS's certificates, which a stale Python bundle may not."""
    curl = shutil.which("curl") or shutil.which("curl.exe")
    if curl:
        tmp = tempfile.mktemp(suffix=".json")
        if subprocess.run([curl, "-sL", "-o", tmp, url]).returncode == 0:
            with io.open(tmp, encoding="utf-8") as fh:
                return json.load(fh)
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def bbox(geom):
    lo_x = lo_y = 1e9
    hi_x = hi_y = -1e9
    polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
    for poly in polys:
        for ring in poly:
            for x, y in ring:
                lo_x, hi_x = min(lo_x, x), max(hi_x, x)
                lo_y, hi_y = min(lo_y, y), max(hi_y, y)
    return [round(lo_x, 3), round(lo_y, 3), round(hi_x, 3), round(hi_y, 3)]


def main():
    d = fetch(INDEX)
    feats = d["features"]
    by_id = {f["properties"]["id"]: f for f in feats}

    def country_of(pid):
        """The country this region is in, walking up to the child of a continent."""
        f = by_id.get(pid)
        while f is not None:
            parent = f["properties"].get("parent")
            if parent is None or by_id.get(parent, {}).get("properties", {}).get("parent") is None:
                return f["properties"]["id"]
            f = by_id.get(parent)
        return pid

    out = []
    for f in feats:
        p = f["properties"]
        pbf = p.get("urls", {}).get("pbf")
        if not pbf:
            continue
        country = country_of(p["id"])
        parent = p.get("parent")
        depth = 0
        q = f
        while q is not None and q["properties"].get("parent") is not None:
            depth += 1
            q = by_id.get(q["properties"]["parent"])
        b = bbox(f["geometry"])
        name = p["name"]
        if name == p["id"] or "/" in name:
            # Geofabrik names some sub-regions by their path - "us/rhode-island" - so read it
            name = " ".join(w.capitalize() if w not in ("of", "and", "the") else w for w in p["id"].rsplit("/", 1)[-1].replace("-", " ").split())
        out.append({
            "id": p["id"], "name": name, "parent": parent, "depth": depth, "country": country,
            "pbf": pbf, "bbox": b,
            # a box that wraps the antimeridian (Alaska's islands, Russia) is no use for a tiles extract
            "wide": (b[2] - b[0]) > 300,
            "units": "us" if country == "us" else ("uk" if country in MPH else "metric"),
            "locale": VOICE.get(country, "en-US" if country == "us" else "en-GB"),
        })
    out.sort(key=lambda r: (r["depth"], r["name"]))
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"source": INDEX, "regions": out}, fh, ensure_ascii=False, separators=(",", ":"))
    print("%d regions -> %s (%.0f KB)" % (len(out), OUT, os.path.getsize(OUT) / 1024))


if __name__ == "__main__":
    main()
