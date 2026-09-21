# -*- coding: utf-8 -*-
"""
Turn the Celica model into something the home page can load.

The site runs from file://, where fetch() and XHR are blocked for local files,
so the mesh cannot be a .stl or .bin sitting next to the page - it has to
arrive as a plain <script>. This packs it down and base64s it into
docs/data/model.js.

The source is tools/model/celica.blend - the printable STL with the wheels
split out as their own objects, the lip spoiler removed and a GT wing fitted,
as the car actually is. Each object is exported from Blender as its own STL
into tools/model/ (world coordinates, so they line up as-is), and this script
packs them together as PARTS, in order.

Packing: weld duplicate vertices (an STL repeats every one three times),
quantise positions to a uint16 grid across the whole car's bounding box, and
index the triangles with uint16. All parts share one position array; each
part is a contiguous run of faces and edges, and carries its pivot - the axle
for a wheel - so a viewer can turn it on its own. The top-level idx/edg are
the plain concatenation, so a viewer that knows nothing about parts still
draws the whole car.

Run:  python tools/model.py
      python tools/model.py --bin    also writes the Android app's car.json + car.bin

The Android app takes the same payload without the base64: car.json is the metadata (min, span,
parts) and car.bin is pos, then idx, then edg, each a little-endian uint16 array, back to back.
"""
import base64
import io
import json
import os
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATADIR = os.path.join(ROOT, "docs", "data")
MODELDIR = os.path.join(HERE, "model")
APPMODEL = os.path.join(ROOT, "android", "app", "src", "main", "assets", "model")

EDGE_DEGREES = 22.0        # dihedral threshold for a line worth drawing

# One entry per Blender object, in draw order. Pivots are the object origins
# in Blender (STL space: X along the car, Y across, Z up); for the wheels the
# origin was put on the axle by fitting the rim. `axis` is what the part
# turns about. Only the wheels turn.
#
# The wheels get a steeper edge threshold: a turning wheel is read by its
# spokes and rim, and the softer creases in the barrel and hub just flicker.
# They also carry a plain disc behind the spokes that draws no lines of its
# own but stops the barrel's far edge showing through as the spokes pass -
# which reads as the wheel wobbling, even though it is not.
WHEEL_DEGREES = 32.0
PARTS = [
    {"name": "body",     "file": "body.stl"},
    {"name": "wing",     "file": "wing.stl"},
    {"name": "wheel_FL", "file": "wheel_FL.stl", "pivot": [54.01, 29.34, 6.85], "axis": "y", "degrees": WHEEL_DEGREES},
    {"name": "wheel_FR", "file": "wheel_FR.stl", "pivot": [54.01, 4.85, 6.85],  "axis": "y", "degrees": WHEEL_DEGREES},
    {"name": "wheel_RL", "file": "wheel_RL.stl", "pivot": [14.44, 29.16, 6.85], "axis": "y", "degrees": WHEEL_DEGREES},
    {"name": "wheel_RR", "file": "wheel_RR.stl", "pivot": [14.44, 5.03, 6.85],  "axis": "y", "degrees": WHEEL_DEGREES},
]


def triangles(blob):
    """(n, 3, 3) float array of triangle corners from a binary STL."""
    n = struct.unpack("<I", blob[80:84])[0]
    rec = np.frombuffer(blob[84:84 + n * 50], dtype=np.uint8).reshape(n, 50)
    # each 50-byte record: 12B normal, 36B three vertices, 2B attribute
    verts = rec[:, 12:48].copy().view(np.float32).reshape(n * 3, 3)
    return verts.astype(np.float64), n


def feature_edges(pts, faces, degrees=22.0):
    """
    Edges worth drawing: the car's creases and silhouette, not every triangle.

    A full triangle wireframe of 44k faces is visual noise. What reads as a
    technical drawing - and matches the manual line art the rest of the site is
    built from - is the same thing three.js EdgesGeometry produces: keep an
    edge only where the two faces meeting along it turn by more than a
    threshold, plus any edge with just one face (a boundary or a hole).

    Precomputed here rather than in the browser so the page has nothing to
    grind through on load.
    """
    a = faces[:, [0, 1, 2]].ravel()
    b = faces[:, [1, 2, 0]].ravel()
    pairs = np.sort(np.stack([a, b], axis=1), axis=1)
    face_of = np.repeat(np.arange(len(faces)), 3)

    order = np.lexsort((pairs[:, 1], pairs[:, 0]))
    pairs, face_of = pairs[order], face_of[order]

    # face normals, unit length
    v0, v1, v2 = pts[faces[:, 0]], pts[faces[:, 1]], pts[faces[:, 2]]
    nrm = np.cross(v1 - v0, v2 - v0)
    ln = np.linalg.norm(nrm, axis=1, keepdims=True)
    nrm = nrm / np.maximum(ln, 1e-12)

    same = np.all(pairs[1:] == pairs[:-1], axis=1)
    dup = np.flatnonzero(same)                 # first index of each shared pair
    cos_lim = np.cos(np.radians(degrees))

    dots = np.einsum("ij,ij->i", nrm[face_of[dup]], nrm[face_of[dup + 1]])
    creased = dup[dots < cos_lim]

    # edges appearing exactly once are boundaries - always keep them
    seen_twice = np.zeros(len(pairs), bool)
    seen_twice[dup] = True
    seen_twice[dup + 1] = True
    boundary = np.flatnonzero(~seen_twice)

    keep = np.concatenate([creased, boundary])
    return np.unique(pairs[keep], axis=0)


def main():
    # read every part, remembering how many triangles each one contributes
    blobs = []
    for part in PARTS:
        path = os.path.join(MODELDIR, part["file"])
        if not os.path.exists(path):
            sys.exit("missing %s - export it from tools/model/celica.blend" % path)
        with open(path, "rb") as fh:
            v, n = triangles(fh.read())
        blobs.append((part, v, n))
        print("  %-9s %6d triangles" % (part["name"], n))

    verts = np.concatenate([v for _, v, _ in blobs])
    ntri = sum(n for _, _, n in blobs)

    # weld across the whole car so the shared position array stays small
    uniq, inv = np.unique(verts, axis=0, return_inverse=True)
    print("  %d unique vertices after welding" % len(uniq))
    if len(uniq) > 65535:
        sys.exit("too many vertices for uint16 indices - decimate first")

    lo = uniq.min(axis=0)
    hi = uniq.max(axis=0)
    span = np.maximum(hi - lo, 1e-9)

    # quantise to a uint16 grid; at this size a step is well under a tenth of
    # a millimetre on the real car, so nothing visible is lost
    q = np.round((uniq - lo) / span * 65535.0).astype(np.uint16)
    idx_all = inv.astype(np.int64).reshape(ntri, 3)

    # Feature edges per part, not across the whole car: a wheel's silhouette
    # is its own, and the line where it was cut from the arch is a boundary,
    # which is exactly what should be drawn there.
    parts_out, edge_runs, f0, e0 = [], [], 0, 0
    for part, _, n in blobs:
        edges = feature_edges(uniq, idx_all[f0:f0 + n], part.get("degrees", EDGE_DEGREES))
        entry = {"name": part["name"],
                 "faceStart": f0, "faceCount": n,
                 "edgeStart": e0, "edgeCount": int(len(edges))}
        if "pivot" in part:
            entry["pivot"] = part["pivot"]
            entry["axis"] = part["axis"]
        parts_out.append(entry)
        edge_runs.append(edges)
        f0 += n
        e0 += len(edges)
    idx = idx_all.astype(np.uint16)
    edges = np.concatenate(edge_runs).astype(np.uint16)
    print("  %d feature edges at %.0f degrees" % (len(edges), EDGE_DEGREES))

    # The STL is Z-up and X-along-the-length. three.js is Y-up, so the page
    # rotates it; the axis order is recorded here rather than hard-coded there.
    payload = {
        "source": "toyota celica gen7 printable STL, reworked in tools/model/celica.blend",
        "tris": int(ntri),
        "verts": int(len(uniq)),
        "up": "z",
        "lengthAxis": "x",
        "min": [round(float(v), 4) for v in lo],
        "span": [round(float(v), 4) for v in span],
        "edges": int(len(edges)),
        "edgeAngle": EDGE_DEGREES,
        "parts": parts_out,
        "pos": base64.b64encode(q.tobytes()).decode("ascii"),
        "idx": base64.b64encode(idx.tobytes()).decode("ascii"),
        "edg": base64.b64encode(edges.tobytes()).decode("ascii"),
    }

    os.makedirs(DATADIR, exist_ok=True)
    out = os.path.join(DATADIR, "model.js")
    with io.open(out, "w", encoding="utf-8") as fh:
        fh.write("window.CELICA=window.CELICA||{};\nwindow.CELICA.model=")
        json.dump(payload, fh, separators=(",", ":"))
        fh.write(";\n")

    print("  positions %s KB, faces %s KB, edges %s KB"
          % (len(payload["pos"]) // 1024, len(payload["idx"]) // 1024,
             len(payload["edg"]) // 1024))
    print("model.js  %.2f MB" % (os.path.getsize(out) / 1048576.0))

    if "--bin" in sys.argv:
        os.makedirs(APPMODEL, exist_ok=True)
        meta = {k: v for k, v in payload.items() if k not in ("pos", "idx", "edg")}
        meta["counts"] = {"pos": int(q.size), "idx": int(idx.size), "edg": int(edges.size)}
        with io.open(os.path.join(APPMODEL, "car.json"), "w", encoding="utf-8") as fh:
            json.dump(meta, fh, separators=(",", ":"))
        with open(os.path.join(APPMODEL, "car.bin"), "wb") as fh:
            fh.write(q.astype("<u2").tobytes())
            fh.write(idx.astype("<u2").tobytes())
            fh.write(edges.astype("<u2").tobytes())
        print("car.bin   %.2f MB" % (os.path.getsize(os.path.join(APPMODEL, "car.bin")) / 1048576.0))


if __name__ == "__main__":
    main()
