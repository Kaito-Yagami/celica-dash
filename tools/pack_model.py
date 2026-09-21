# -*- coding: utf-8 -*-
"""
Pack the wireframe car for the app, in its two versions.

    python tools/pack_model.py --parts <dir with body.stl, wing.stl, wheel_*.stl> \
                               --raw   "<the printable's own STL>"

Writes android/app/src/main/assets/model/wing/car.{json,bin} and .../model/stock/car.{json,bin}.

The source is a printable STL of the seventh-generation Celica, reworked in Blender by the
owner: the wheels split out as their own objects, the stock lip spoiler removed and a GT wing
fitted, each object exported as its own STL in world coordinates (the `--parts` folder). That
is the "wing" car. The "stock" car is the printable itself with the same four wheels dropped
in: the printable's own wheel triangles are the ones the owner's body no longer has near the
axles, so they are found by that difference and cut out, and the lip spoiler stays.

Neither STL lives in this repository: the printable's licence is its author's, and the parts
are the owner's. The packed result is what ships.

Packing: weld duplicate vertices (an STL repeats every one three times), quantise positions to
a uint16 grid across the car's bounding box, index the triangles with uint16. All parts share
one position array; each part is a contiguous run of faces and edges, and carries its pivot -
the axle for a wheel - so the renderer can turn it on its own. car.json is the metadata (min,
span, parts); car.bin is pos, then idx, then edg, each a little-endian uint16 array.
"""
import argparse
import io
import json
import os
import struct

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APPMODEL = os.path.join(ROOT, "android", "app", "src", "main", "assets", "model")

EDGE_DEGREES = 22.0        # dihedral threshold for a line worth drawing
WHEEL_DEGREES = 32.0       # steeper for the wheels: spokes and rim read, the barrel's creases flicker

# Pivots are the wheel objects' origins in Blender (STL space: X along the car, Y across, Z up),
# put on the axle by fitting the rim. Only the wheels turn.
WHEELS = [
    {"name": "wheel_FL", "file": "wheel_FL.stl", "pivot": [54.01, 29.34, 6.85], "axis": "y", "degrees": WHEEL_DEGREES},
    {"name": "wheel_FR", "file": "wheel_FR.stl", "pivot": [54.01, 4.85, 6.85],  "axis": "y", "degrees": WHEEL_DEGREES},
    {"name": "wheel_RL", "file": "wheel_RL.stl", "pivot": [14.44, 29.16, 6.85], "axis": "y", "degrees": WHEEL_DEGREES},
    {"name": "wheel_RR", "file": "wheel_RR.stl", "pivot": [14.44, 5.03, 6.85],  "axis": "y", "degrees": WHEEL_DEGREES},
]


def triangles(path):
    """(n, 3, 3) float array of triangle corners from a binary STL."""
    with open(path, "rb") as fh:
        blob = fh.read()
    n = struct.unpack("<I", blob[80:84])[0]
    rec = np.frombuffer(blob[84:84 + n * 50], dtype=np.uint8).reshape(n, 50)
    return rec[:, 12:48].copy().view(np.float32).reshape(n, 3, 3).astype(np.float64)


def keys(t):
    """One hashable key per triangle, blind to vertex order."""
    q = np.sort(np.round(t, 4).reshape(len(t), 9), axis=1).astype(np.float32)
    return [q[i].tobytes() for i in range(len(q))]


def stock_body(raw, body):
    """The printable less its own wheels: what the owner's body dropped near the axles."""
    have = set(keys(body))
    c = raw.mean(axis=1)
    near = np.zeros(len(raw), bool)
    for w in WHEELS:
        px, py, pz = w["pivot"]
        near |= (((c[:, 0] - px) ** 2 + (c[:, 2] - pz) ** 2) < 5.6 ** 2) & (np.abs(c[:, 1] - py) < 2.2)
    drop = np.array([near[i] and k not in have for i, k in enumerate(keys(raw))])
    print("  stock body: %d of %d printable triangles are its wheels, cut" % (drop.sum(), len(raw)))
    return raw[~drop]


def feature_edges(pts, faces, degrees):
    """
    Edges worth drawing: the car's creases and silhouette, not every triangle. Keep an edge
    only where the two faces meeting along it turn by more than a threshold, plus any edge
    with just one face (a boundary or a hole).
    """
    a = faces[:, [0, 1, 2]].ravel()
    b = faces[:, [1, 2, 0]].ravel()
    pairs = np.sort(np.stack([a, b], axis=1), axis=1)
    face_of = np.repeat(np.arange(len(faces)), 3)
    order = np.lexsort((pairs[:, 1], pairs[:, 0]))
    pairs, face_of = pairs[order], face_of[order]
    v0, v1, v2 = pts[faces[:, 0]], pts[faces[:, 1]], pts[faces[:, 2]]
    nrm = np.cross(v1 - v0, v2 - v0)
    nrm = nrm / np.maximum(np.linalg.norm(nrm, axis=1, keepdims=True), 1e-12)
    same = np.all(pairs[1:] == pairs[:-1], axis=1)
    dup = np.flatnonzero(same)
    dots = np.einsum("ij,ij->i", nrm[face_of[dup]], nrm[face_of[dup + 1]])
    creased = dup[dots < np.cos(np.radians(degrees))]
    seen_twice = np.zeros(len(pairs), bool)
    seen_twice[dup] = True
    seen_twice[dup + 1] = True
    boundary = np.flatnonzero(~seen_twice)
    return np.unique(pairs[np.concatenate([creased, boundary])], axis=0)


def pack(parts, out_dir, source):
    """parts: [(spec, (n,3,3) triangles)] in draw order."""
    verts = np.concatenate([t.reshape(-1, 3) for _, t in parts])
    ntri = sum(len(t) for _, t in parts)
    uniq, inv = np.unique(verts, axis=0, return_inverse=True)
    if len(uniq) > 65535:
        raise SystemExit("too many vertices for uint16 indices - decimate first")
    lo, hi = uniq.min(axis=0), uniq.max(axis=0)
    span = np.maximum(hi - lo, 1e-9)
    q = np.round((uniq - lo) / span * 65535.0).astype(np.uint16)
    idx_all = inv.astype(np.int64).reshape(ntri, 3)

    parts_out, edge_runs, f0, e0 = [], [], 0, 0
    for spec, t in parts:
        n = len(t)
        edges = feature_edges(uniq, idx_all[f0:f0 + n], spec.get("degrees", EDGE_DEGREES))
        entry = {"name": spec["name"], "faceStart": f0, "faceCount": n, "edgeStart": e0, "edgeCount": int(len(edges))}
        if "pivot" in spec:
            entry["pivot"] = spec["pivot"]
            entry["axis"] = spec["axis"]
        parts_out.append(entry)
        edge_runs.append(edges)
        f0 += n
        e0 += len(edges)
    idx = idx_all.astype(np.uint16)
    edges = np.concatenate(edge_runs).astype(np.uint16)

    meta = {
        "source": source, "tris": int(ntri), "verts": int(len(uniq)), "up": "z", "lengthAxis": "x",
        "min": [round(float(v), 4) for v in lo], "span": [round(float(v), 4) for v in span],
        "edges": int(len(edges)), "edgeAngle": EDGE_DEGREES, "parts": parts_out,
        "counts": {"pos": int(q.size), "idx": int(idx.size), "edg": int(edges.size)},
    }
    os.makedirs(out_dir, exist_ok=True)
    with io.open(os.path.join(out_dir, "car.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, separators=(",", ":"))
    with open(os.path.join(out_dir, "car.bin"), "wb") as fh:
        fh.write(q.astype("<u2").tobytes())
        fh.write(idx.astype("<u2").tobytes())
        fh.write(edges.astype("<u2").tobytes())
    print("  %s: %d tris, %d verts, %d edges, car.bin %.2f MB" % (
        os.path.basename(out_dir), ntri, len(uniq), len(edges), os.path.getsize(os.path.join(out_dir, "car.bin")) / 1048576.0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parts", required=True, help="folder with body.stl, wing.stl and the four wheel STLs")
    ap.add_argument("--raw", required=True, help="the printable's own STL, wheels and lip spoiler in one mesh")
    a = ap.parse_args()

    body = triangles(os.path.join(a.parts, "body.stl"))
    wing = triangles(os.path.join(a.parts, "wing.stl"))
    wheels = [(w, triangles(os.path.join(a.parts, w["file"]))) for w in WHEELS]
    raw = triangles(a.raw)

    print("wing car")
    pack([({"name": "body"}, body), ({"name": "wing"}, wing)] + wheels, os.path.join(APPMODEL, "wing"),
         "toyota celica gen7 printable STL, reworked: wheels split out, lip spoiler off, GT wing on")
    print("stock car")
    pack([({"name": "body"}, stock_body(raw, body))] + wheels, os.path.join(APPMODEL, "stock"),
         "toyota celica gen7 printable STL, as printed, with the wheels split out")


if __name__ == "__main__":
    main()
