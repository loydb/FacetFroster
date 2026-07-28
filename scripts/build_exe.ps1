# Build frost.jar (+ optional standalone Windows exe) from this repo's src/,
# linked against Sean O'Neil's Edge Frosting Tool.
#
#   powershell -File build_exe.ps1 -Tool <path-to-edge-frosting-tool> [-OutDir out] [-NoExe]
#
# -Tool    folder containing Sean's compiled classes (has lap\ and META-INF\).
#          This is his work; you supply it separately, it is not in this repo.
# -OutDir  where to write frost.jar and the exe zip (default: repo root\out).
# -NoExe   skip the self-contained exe (just build frost.jar).
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

if (-not (Test-Path (Join-Path $Tool "lap\model\Gem.class"))) {
  throw "-Tool '$Tool' does not contain Sean's Edge Frosting Tool (expected lap\model\Gem.class)"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Build classes in a LOCAL temp dir (never a synced/cloud folder: syncing the
# many intermediate lap\ files mid-build makes `jar cfm lap` package an
# incomplete lap\ -> NoClassDefFoundError at runtime).
$build = Join-Path $env:TEMP ("frost_classes_" + [System.Diagnostics.Process]::GetCurrentProcess().Id)
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $build | Out-Null
$localJar = Join-Path $build "frost.jar"

# stage Sean's classes, then compile the Messages shadow OVER his (so the tool's
# modal Swing dialogs go to the console and never block a headless run), then
# our frosters against it.
Copy-Item (Join-Path $Tool "lap") -Destination (Join-Path $build "lap") -Recurse
javac -d $build (Join-Path $src "Messages.java");                       if ($LASTEXITCODE) { throw "Messages javac failed" }
javac -cp "$build;$Tool" (Join-Path $src "FrostCLI.java")  -d $build;   if ($LASTEXITCODE) { throw "FrostCLI javac failed" }
javac -cp "$build;$Tool" (Join-Path $src "FrostCkpt.java") -d $build 2>$null; if ($LASTEXITCODE) { throw "FrostCkpt javac failed" }

"Main-Class: FrostCLI`n" | Set-Content (Join-Path $build "manifest.txt") -NoNewline
Push-Location $build
# both frosters: `java -jar frost.jar` = FrostCLI; `java -cp frost.jar FrostCkpt` = checkpointing
jar cfm $localJar manifest.txt lap FrostCLI*.class FrostCkpt*.class
Pop-Location
if ($LASTEXITCODE) { throw "jar failed" }
$jarList = & jar tf $localJar
if (-not ($jarList -match 'lap/model/Gem.class') -or -not ($jarList -match '^FrostCLI.class') -or -not ($jarList -match '^FrostCkpt.class')) {
  throw "frost.jar missing classes (need lap/model/Gem, FrostCLI, FrostCkpt) -- re-run"
}
Copy-Item $localJar (Join-Path $OutDir "frost.jar") -Force
Write-Host "Built: $(Join-Path $OutDir 'frost.jar')  (run: java -jar frost.jar <in.gcs>)"

if (-not $NoExe) {
  $appin = Join-Path $build "appinput"; New-Item -ItemType Directory -Force -Path $appin | Out-Null
  Copy-Item $localJar (Join-Path $appin "frost.jar")
  # jpackage can intermittently emit a broken runtime ("Failed to launch JVM").
  # Build to a local temp dir, verify the exe actually launches + its bundled jar
  # has the classes, retry if not, then ship a single ZIP (a raw 120 MB app-image
  # folder copied into a cloud folder corrupts; one zip syncs cleanly).
  $exeOk = $false
  for ($try = 1; $try -le 4 -and -not $exeOk; $try++) {
    $tmp = Join-Path $env:TEMP ("frost_build_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + $try)
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    jpackage --type app-image --input $appin --main-jar frost.jar --main-class FrostCLI --win-console --name frost --dest $tmp
    if ($LASTEXITCODE) { Write-Host "jpackage try $try failed"; continue }
    $exePath = Join-Path $tmp "frost\frost.exe"
    $bundledJar = Join-Path $tmp "frost\app\frost.jar"
    $jarHasClasses = (Test-Path $bundledJar) -and ((& jar tf $bundledJar) -match 'lap/model/Gem.class')
    $probe = (& $exePath "___nofile___.gcs" 2>&1) -join "`n"   # launches -> "no such file"; broken -> "Failed to launch JVM"
    if ($jarHasClasses -and $probe -match "no such file") { $exeOk = $true; Write-Host "exe verified (try $try)" }
    else { Write-Host "exe broken (try ${try})"; Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
  }
  if (-not $exeOk) { throw "jpackage produced a broken runtime after 4 tries; use frost.jar (needs a JDK) instead" }
  $zip = Join-Path $OutDir "frost-exe-selfcontained.zip"
  Remove-Item $zip -Force -ErrorAction SilentlyContinue
  Compress-Archive -Path (Join-Path $tmp "frost") -DestinationPath $zip -CompressionLevel Optimal
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  Write-Host "Built: $zip  (extract -> frost\frost.exe; no Java needed)"
}
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
