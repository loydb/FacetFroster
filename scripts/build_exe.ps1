# Build FacetFroster.jar (+ optional standalone Windows exe) from this repo's
# src/, linked against Sean O'Neil's Edge Frosting Tool.
#
#   powershell -File build_exe.ps1 -Tool <path-to-edge-frosting-tool> [-OutDir out] [-NoExe]
#
# -Tool    Sean's Edge Frosting Tool: either his distributed .jar, or a folder
#          containing his compiled classes (lap\). His work; you supply it
#          separately (download link in the README), it is not in this repo.
# -OutDir  where to write FacetFroster.jar and the exe zip (default: repo root\out).
# -NoExe   skip the self-contained exe (just build FacetFroster.jar).
#
# Requires a JDK (developed against JDK 25): javac, jar, and (for the exe)
# jpackage on PATH. The exe bundles a JRE so the target PC needs no Java.
#
# NOTE: the resulting jar/exe EMBED Sean's classes. Only redistribute them if
# his license permits it (see the repo README / LICENSE).
param(
  [Parameter(Mandatory=$true)][string]$Tool,
  [string]$OutDir,
  [switch]$NoExe
)
$ErrorActionPreference = "Continue"   # javac/jpackage warn to stderr; real failures caught via $LASTEXITCODE
$here = Split-Path -Parent $MyInvocation.MyCommand.Path       # ...\scripts
$src  = Join-Path (Split-Path -Parent $here) "src"
if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $here) "out" }

# Sean distributes his tool as a .jar; also accept an already-extracted folder.
if ((Test-Path $Tool) -and ($Tool -like '*.jar')) {
  $toolDir = Join-Path $env:TEMP ("facetfroster_tool_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + [System.IO.Path]::GetRandomFileName())
  if (Test-Path $toolDir) { throw "temp dir already exists: $toolDir" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $Tool).Path, $toolDir)  # a jar is a zip
  $Tool = $toolDir
}
if (-not (Test-Path (Join-Path $Tool "lap\model\Gem.class"))) {
  throw "-Tool '$Tool' is not Sean's Edge Frosting Tool (expected his .jar, or a folder with lap\model\Gem.class)"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Build classes in a LOCAL temp dir (never a synced/cloud folder: syncing the
# many intermediate lap\ files mid-build makes `jar cfm lap` package an
# incomplete lap\ -> NoClassDefFoundError at runtime).
$build = Join-Path $env:TEMP ("facetfroster_classes_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + [System.IO.Path]::GetRandomFileName())
if (Test-Path $build) { throw "temp dir already exists: $build" }
New-Item -ItemType Directory -Force -Path $build | Out-Null
$localJar = Join-Path $build "FacetFroster.jar"

# stage Sean's classes, then compile the Messages shadow OVER his (so the tool's
# modal Swing dialogs go to the console and never block a headless run), then
# our frosters against it.
Copy-Item (Join-Path $Tool "lap") -Destination (Join-Path $build "lap") -Recurse
javac -d $build (Join-Path $src "Messages.java");                          if ($LASTEXITCODE) { throw "Messages javac failed" }
javac -cp "$build;$Tool" (Join-Path $src "FacetFroster.java")     -d $build;   if ($LASTEXITCODE) { throw "FacetFroster javac failed" }
javac -cp "$build;$Tool" (Join-Path $src "FacetFrosterCkpt.java") -d $build 2>$null; if ($LASTEXITCODE) { throw "FacetFrosterCkpt javac failed" }

"Main-Class: FacetFroster`n" | Set-Content (Join-Path $build "manifest.txt") -NoNewline
Push-Location $build
# both frosters: `java -jar FacetFroster.jar` = FacetFroster (one-shot);
#                `java -cp FacetFroster.jar FacetFrosterCkpt` = checkpointing
jar cfm $localJar manifest.txt lap FacetFroster*.class FacetFrosterCkpt*.class
Pop-Location
if ($LASTEXITCODE) { throw "jar failed" }
$jarList = & jar tf $localJar
if (-not ($jarList -match 'lap/model/Gem.class') -or -not ($jarList -match '^FacetFroster.class') -or -not ($jarList -match '^FacetFrosterCkpt.class')) {
  throw "FacetFroster.jar missing classes (need lap/model/Gem, FacetFroster, FacetFrosterCkpt) -- re-run"
}
Copy-Item $localJar (Join-Path $OutDir "FacetFroster.jar") -Force
Write-Host "Built: $(Join-Path $OutDir 'FacetFroster.jar')  (run: java -jar FacetFroster.jar <in.gcs>)"

if (-not $NoExe) {
  $appin = Join-Path $build "appinput"; New-Item -ItemType Directory -Force -Path $appin | Out-Null
  Copy-Item $localJar (Join-Path $appin "FacetFroster.jar")
  # jpackage can intermittently emit a broken runtime ("Failed to launch JVM").
  # Build to a local temp dir, verify the exe actually launches + its bundled jar
  # has the classes, retry if not, then ship a single ZIP (a raw 120 MB app-image
  # folder copied into a cloud folder corrupts; one zip syncs cleanly).
  $exeOk = $false
  for ($try = 1; $try -le 4 -and -not $exeOk; $try++) {
    $tmp = Join-Path $env:TEMP ("facetfroster_build_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + $try + "_" + [System.IO.Path]::GetRandomFileName())
    if (Test-Path $tmp) { throw "temp dir already exists: $tmp" }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    jpackage --type app-image --input $appin --main-jar FacetFroster.jar --main-class FacetFroster --win-console --name FacetFroster --dest $tmp
    if ($LASTEXITCODE) { Write-Host "jpackage try $try failed"; continue }
    $exePath = Join-Path $tmp "FacetFroster\FacetFroster.exe"
    $bundledJar = Join-Path $tmp "FacetFroster\app\FacetFroster.jar"
    $jarHasClasses = (Test-Path $bundledJar) -and ((& jar tf $bundledJar) -match 'lap/model/Gem.class')
    $probe = (& $exePath "___nofile___.gcs" 2>&1) -join "`n"   # launches -> "no such file"; broken -> "Failed to launch JVM"
    if ($jarHasClasses -and $probe -match "no such file") { $exeOk = $true; Write-Host "exe verified (try $try)" }
    else { Write-Host "exe broken (try ${try})"; Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
  }
  if (-not $exeOk) { throw "jpackage produced a broken runtime after 4 tries; use FacetFroster.jar (needs a JDK) instead" }
  $zip = Join-Path $OutDir "FacetFroster-windows-exe.zip"
  Remove-Item $zip -Force -ErrorAction SilentlyContinue
  Compress-Archive -Path (Join-Path $tmp "FacetFroster") -DestinationPath $zip -CompressionLevel Optimal
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  Write-Host "Built: $zip  (extract -> FacetFroster\FacetFroster.exe; no Java needed)"
}
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
