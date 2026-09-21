# Builds the routing graph for the head unit: downloads the Great Britain OSM extract if it is
# not here, imports it with GraphHopper 1.0 (contraction hierarchies, car), and zips the result
# as gb-routing.zip for Settings > Import routing graph on the unit.
#
#   powershell -ExecutionPolicy Bypass -File tools\routing\build_graph.ps1
#
# Needs JDK 17 and ~14 GB of free RAM for the import; takes half an hour or so.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$pbf = "great-britain-latest.osm.pbf"
if (-not (Test-Path $pbf)) {
    Write-Host "Downloading $pbf from Geofabrik (about 1.8 GB)..."
    curl.exe -L -o $pbf "https://download.geofabrik.de/europe/$pbf"
}
if (Test-Path "graph\gb") { Remove-Item -Recurse -Force "graph\gb" }
if (-not $env:JAVA_HOME) { Write-Host "set JAVA_HOME to a JDK 17 first"; exit 1 }
.\gradlew.bat -q run --args="$pbf graph/gb"
if (Test-Path "gb-routing.zip") { Remove-Item "gb-routing.zip" }
Compress-Archive -Path "graph\gb\*" -DestinationPath "gb-routing.zip" -CompressionLevel Fastest
Write-Host "Made gb-routing.zip ($([math]::Round((Get-Item gb-routing.zip).Length / 1MB)) MB). Copy it to the unit and import it in Settings."
