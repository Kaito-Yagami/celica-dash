"""Builds the dash's search file - every place and stop in Great Britain - from the OSM extract.

    python tools/map/make_search.py [tools/routing/great-britain-latest.osm.pbf] [tools/map/search.idx]

The map tiles are drawn from a thinned basemap: it keeps a couple of petrol stations per town and
throws the rest away, so it is no use as a list of places. The routing extract next door is the
whole of OpenStreetMap for Great Britain, so the search file and the map's own icons are built
from that instead - the corner shop, the gym and the supermarket forecourt all included.

    "CSCH" 2  kindCount  [len,name] x kindCount
    count (u32)  lat0 e6 (i32)  step e6 (i32)  bands (u16)  bandStart (u32) x bands
    then, in latitude order: lat e6 (i32)  lon e6 (i32)  kind (u8)  nameLen (u8)  name (utf-8)

Everything is little-endian. The unit scans those bytes, so there is no index to keep in step -
the file is the index. A search reads it all; the icons on the map only read the bands the
screen covers, which is why the entries are in latitude order.
"""
import os
import struct
import sys
import time
from collections import Counter

import osmium

# the tag values worth keeping, by the tag they hang off. The value doubles as the kind.
BY_TAG = {
    "amenity": {
        "fuel", "charging_station", "parking", "restaurant", "cafe", "fast_food", "pub", "bar",
        "ice_cream", "pharmacy", "hospital", "clinic", "doctors", "dentist", "veterinary",
        "police", "fire_station", "post_office", "bank", "atm", "bureau_de_change", "library",
        "townhall", "toilets", "drinking_water", "cinema", "theatre", "nightclub", "casino",
        "marketplace", "car_wash", "car_rental", "taxi", "bus_station", "ferry_terminal",
        "community_centre", "place_of_worship", "school", "college", "university", "kindergarten",
        "food_court", "biergarten",
    },
    "shop": {
        "supermarket", "convenience", "bakery", "butcher", "greengrocer", "mall", "department_store",
        "hardware", "doityourself", "garden_centre", "furniture", "electronics", "clothes", "shoes",
        "books", "florist", "hairdresser", "laundry", "optician", "jewelry", "gift", "toys", "pet",
        "car", "car_parts", "car_repair", "tyres", "bicycle", "alcohol", "beauty", "chemist",
        "mobile_phone", "sports", "stationery", "variety_store", "travel_agency", "deli", "farm",
        "hifi", "computer", "outdoor", "fishing", "music", "charity", "newsagent", "kiosk",
    },
    "leisure": {
        "fitness_centre", "sports_centre", "swimming_pool", "golf_course", "stadium", "park",
        "garden", "playground", "nature_reserve", "marina", "water_park", "ice_rink", "bowling_alley",
        "dog_park", "miniature_golf", "horse_riding",
    },
    "tourism": {
        "hotel", "motel", "guest_house", "hostel", "camp_site", "caravan_site", "attraction",
        "museum", "gallery", "zoo", "theme_park", "viewpoint", "picnic_site", "information",
        "artwork", "aquarium",
    },
    "historic": {"castle", "monument", "ruins", "memorial", "archaeological_site"},
    "healthcare": {"centre", "physiotherapist", "hospice"},
    "railway": {"station", "halt", "tram_stop"},
    "aeroway": {"aerodrome", "terminal"},
    "highway": {"services", "rest_area"},
    "man_made": {"lighthouse", "pier"},
    "place": {"city", "town", "village", "hamlet", "suburb", "borough", "quarter", "neighbourhood", "locality", "island"},
}
# a few values read better under another name
RENAME = {("healthcare", "centre"): "clinic", ("aeroway", "terminal"): "airport", ("aeroway", "aerodrome"): "airport"}
# these are worth an icon even with nobody's name on them
NAMELESS = {"fuel", "charging_station", "toilets", "services", "viewpoint", "picnic_site"}
PLACE_KINDS = ["city", "town", "village", "hamlet", "suburb", "borough", "quarter", "neighbourhood", "locality", "island"]
TAGS = tuple(BY_TAG.keys())


def kind_of(tags):
    """The one kind this object counts as, or None when it is not a place or a stop."""
    for tag in TAGS:
        v = tags.get(tag)
        if v is not None and v in BY_TAG[tag]:
            return RENAME.get((tag, v), v)
    return None


def name_of(tags):
    for k in ("name", "name:en", "brand", "operator"):
        v = tags.get(k)
        if v:
            return v.strip()
    return ""


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(os.path.dirname(here))
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "tools", "routing", "great-britain-latest.osm.pbf")
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "search.idx")
    kinds = []
    kind_no = {}
    seen = {}
    counts = Counter()
    t0 = time.time()
    done = 0

    fp = osmium.FileProcessor(src).with_locations("flex_mem").with_filter(osmium.filter.KeyFilter(*TAGS))
    for o in fp:
        done += 1
        if done % 2_000_000 == 0:
            print(f"  {done // 1000}k tagged objects, {len(seen)} entries, {time.time() - t0:.0f}s", flush=True)
        tags = o.tags
        kind = kind_of(tags)
        if kind is None:
            continue
        name = name_of(tags)
        if not name and kind not in NAMELESS:
            continue
        if o.is_node():
            lat, lon = o.location.lat, o.location.lon
        elif o.is_way():
            n = 0
            lat = lon = 0.0
            try:
                for nd in o.nodes:
                    if nd.location.valid():
                        lat += nd.location.lat
                        lon += nd.location.lon
                        n += 1
            except (osmium.InvalidLocationError, RuntimeError):
                continue
            if n == 0:
                continue
            lat /= n
            lon /= n
        else:
            continue                                   # relations are few and always mapped twice
        if kind not in kind_no:
            kind_no[kind] = len(kinds)
            kinds.append(kind)
        key = (kind_no[kind], name.lower(), round(lat, 4), round(lon, 4))
        if key in seen:
            continue
        seen[key] = (name[:90] or kind.replace("_", " ").title(), kind_no[kind], lat, lon)
        counts[kind] += 1

    rows = sorted(seen.values(), key=lambda r: r[2])
    print(f"{done} tagged objects, {len(rows)} entries in {time.time() - t0:.0f}s")
    print("  " + "  ".join(f"{k}:{n}" for k, n in counts.most_common(14)))
    # one band of latitude every 0.05 degrees, so the map can read only the strip it shows
    lat0, step = 49_000_000, 50_000
    bands = (62_000_000 - lat0) // step
    body = bytearray()
    starts = [0] * bands
    filled = -1
    for name, kind, lat, lon in rows:
        e6 = int(round(lat * 1e6))
        b = min(bands - 1, max(0, (e6 - lat0) // step))
        while filled < b:
            filled += 1
            starts[filled] = len(body)
        nb = name.encode("utf-8")[:255]
        body += struct.pack("<iiBB", e6, int(round(lon * 1e6)), kind, len(nb)) + nb
    while filled < bands - 1:
        filled += 1
        starts[filled] = len(body)
    with open(out, "wb") as w:
        w.write(b"CSCH" + bytes([2, len(kinds)]))
        for k in kinds:
            kb = k.encode()
            w.write(bytes([len(kb)]) + kb)
        w.write(struct.pack("<IiiH", len(rows), lat0, step, bands))
        w.write(struct.pack(f"<{bands}I", *starts))
        w.write(body)
    print(f"wrote {out} - {os.path.getsize(out) / 1e6:.1f} MB, {len(kinds)} kinds, {bands} bands")


if __name__ == "__main__":
    main()
