# Routing graph for the dash

The app routes on the unit itself, with no signal, over a GraphHopper graph of Great Britain.
This folder makes that graph on the PC.

```
powershell -ExecutionPolicy Bypass -File tools\routing\build_graph.ps1
```

downloads the Geofabrik extract, imports it (GraphHopper 1.0, car profile, contraction
hierarchies) and writes `gb-routing.zip`. Copy the zip to the unit - `adb push` to
`/sdcard/Download/` or a USB stick - then ⚙ › *Import routing graph* and pick it.

GraphHopper **1.0** on purpose: it is the last release whose routing runs on Android. Later
versions compile their weightings with Janino, which emits JVM bytecode the Android runtime
cannot load. The app depends on the same version so the files match; do not bump one without
the other.

The graph is refreshed by running the script again (delete the `.osm.pbf` first for new data).
