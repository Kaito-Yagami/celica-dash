<#
Make the offline map for the head unit: a PMTiles extract of Great Britain from the latest
Protomaps daily build, then push it to the unit.

    powershell -ExecutionPolicy Bypass -File tools\map\get_tiles.ps1
    adb push tools\map\gb.pmtiles /sdcard/Android/data/com.celicadash.app/files/gb.pmtiles

Needs internet on the PC (about 2 GB comes down). The pmtiles CLI is fetched into tools\map on
first run. Zoom 14 is enough for a dash map at driving speeds; raise MAXZOOM for more detail at
the cost of a much bigger file.
#>
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$cli = Join-Path $here "pmtiles.exe"
$out = Join-Path $here "gb.pmtiles"
$MAXZOOM = 14
$BBOX = "-8.7,49.8,1.9,60.9"     # Great Britain, with a little sea round it

if (-not (Test-Path $cli)) {
    Write-Host "fetching the pmtiles CLI..."
    $rel = Invoke-RestMethod "https://api.github.com/repos/protomaps/go-pmtiles/releases/latest"
    $asset = $rel.assets | Where-Object { $_.name -match "Windows_x86_64.zip$" } | Select-Object -First 1
    $zip = Join-Path $here "pmtiles.zip"
    Invoke-WebRequest $asset.browser_download_url -OutFile $zip
    Expand-Archive $zip -DestinationPath $here -Force
    Remove-Item $zip
}

Write-Host "finding the latest Protomaps build..."
$builds = Invoke-RestMethod "https://build-metadata.protomaps.dev/builds.json"
$latest = ($builds | Sort-Object key -Descending | Select-Object -First 1).key
$url = "https://build.protomaps.com/$latest"
Write-Host "extracting Great Britain from $url (this takes a while)..."
& $cli extract $url $out --bbox=$BBOX --maxzoom=$MAXZOOM
Write-Host "done: $out  ($([math]::Round((Get-Item $out).Length / 1MB)) MB)"
Write-Host "now: adb push `"$out`" /sdcard/Android/data/com.celicadash.app/files/gb.pmtiles"
