# Build frost.exe — a standalone Windows console app that edge-frosts a .gcs.
#   Usage after build:  frost.exe <input.gcs> [width|N%] [-o out.gcs]
#   Output default:     <input dir>\<name>_frosted.gcs
# Requires JDK 25+ (javac, jar, jpackage) on PATH. Produces a self-contained
# app-image (bundled JRE) so the target PC needs no Java installed.

# Continue (not Stop): javac/jpackage write harmless warnings to stderr which,
# under Stop, abort the build as NativeCommandError. Real failures are caught by
# the explicit $LASTEXITCODE checks after each native command.
$ErrorActionPreference = "Continue"
$here  = Split-Path -Parent $MyInvocation.MyCommand.Path      # ...\edge-frosting-tool\Scripts
$tool  = Split-Path -Parent $here                             # ...\edge-frosting-tool (has lap\ + META-INF\)
# CRITICAL: build in a LOCAL temp dir, NOT inside Dropbox. Compiling/jarring into
# a synced Dropbox folder lets sync interfere with the intermediate lap\ classes,
# so `jar cfm lap` intermittently packages an INCOMPLETE lap\ -> the exe dies with
# NoClassDefFoundError. Build locally, verify, then copy the finished jar in.
$build = Join-Path $env:TEMP ("frost_classes_" + [System.Diagnostics.Process]::GetCurrentProcess().Id)
Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $build | Out-Null
$localJar = Join-Path $build "frost.jar"

# 1. stage tool classes FIRST (dest empty -> no nesting)
Copy-Item (Join-Path $tool "lap") -Destination (Join-Path $build "lap") -Recurse

# 2. compile the Messages shadow (no-op console) OVER the tool's, then FrostCLI +
#    FrostCkpt against it (they read Messages.lastMessage). The shadow neutralizes
#    the tool's modal Swing dialogs that would block a headless run.
javac -d $build (Join-Path $here "Messages.java")
if ($LASTEXITCODE) { throw "Messages javac failed" }
javac -cp "$build;$tool" (Join-Path $here "FrostCLI.java") -d $build
if ($LASTEXITCODE) { throw "FrostCLI javac failed" }
javac -cp "$build;$tool" (Join-Path $here "FrostCkpt.java") -d $build 2>$null
if ($LASTEXITCODE) { throw "FrostCkpt javac failed" }
"Main-Class: FrostCLI`n" | Set-Content (Join-Path $build "manifest.txt") -NoNewline
Push-Location $build
# include BOTH frosters: `java -jar frost.jar` = FrostCLI; `java -cp frost.jar FrostCkpt` = checkpointing froster
jar cfm $localJar manifest.txt lap FrostCLI*.class FrostCkpt*.class
Pop-Location
if ($LASTEXITCODE) { throw "jar failed" }
# verify the LOCAL jar contains the classes before it goes anywhere near Dropbox
$jarList = & jar tf $localJar
if (-not ($jarList -match 'lap/model/Gem.class') -or -not ($jarList -match '^FrostCLI.class') -or -not ($jarList -match '^FrostCkpt.class')) {
  throw "frost.jar missing classes (need lap/model/Gem, FrostCLI, FrostCkpt) -- flaky build, re-run"
}
# copy the verified jar into Dropbox, then re-verify the synced copy (sync can corrupt it)
Copy-Item $localJar (Join-Path $here "frost.jar") -Force
for ($j = 0; $j -lt 10; $j++) {
  Start-Sleep -Seconds 2
  $dl = & jar tf (Join-Path $here "frost.jar") 2>$null
  if (($dl -match 'lap/model/Gem.class') -and ($dl -match '^FrostCkpt.class')) { break }
  if ($j -eq 9) { throw "Dropbox frost.jar failed verification after sync" }
}
Write-Host "frost.jar verified (local build + Dropbox copy)"

# 3. jpackage into a standalone console exe (bundles a JRE)
$appin = Join-Path $build "appinput"
New-Item -ItemType Directory -Force -Path $appin | Out-Null
Copy-Item $localJar (Join-Path $appin "frost.jar")   # the verified LOCAL jar, not the syncing Dropbox one
# IMPORTANT: build the 120 MB app-image OUTSIDE Dropbox. Writing that many files
# into a syncing Dropbox folder corrupts the runtime (produces "Failed to launch
# JVM"). We build to a local temp dir, then ship a single ZIP into Scripts --
# Dropbox syncs one blob reliably; the user extracts it to get a clean exe.
# jpackage on this box intermittently produces a broken runtime ("Failed to
# launch JVM"). Build + verify the exe actually launches; retry if not.
$exeOk = $false
for ($try = 1; $try -le 4 -and -not $exeOk; $try++) {
  $tmp = Join-Path $env:TEMP ("frost_build_" + [System.Diagnostics.Process]::GetCurrentProcess().Id + "_" + $try)
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  jpackage --type app-image --input $appin --main-jar frost.jar --main-class FrostCLI `
           --win-console --name frost --dest $tmp
  if ($LASTEXITCODE) { Write-Host "jpackage try $try failed (exit)"; continue }
  $exePath = Join-Path $tmp "frost\frost.exe"
  $bundledJar = Join-Path $tmp "frost\app\frost.jar"
  $jarHasClasses = (Test-Path $bundledJar) -and ((& jar tf $bundledJar) -match 'lap/model/Gem.class')
  $probe = (& $exePath "___nofile___.gcs" 2>&1) -join "`n"   # launches -> "no such file"; broken runtime -> "Failed to launch JVM"
  if ($jarHasClasses -and $probe -match "no such file") { $exeOk = $true; Write-Host "exe verified (try $try)" }
  else { Write-Host "exe broken (try ${try}): jarClasses=$jarHasClasses probe=$($probe -split "`n" | Select-Object -First 1)"; Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}
if (-not $exeOk) { throw "jpackage produced a broken runtime after 4 tries; use frost.cmd + frost.jar instead" }
$zip = Join-Path $here "frost-exe-selfcontained.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $tmp "frost") -DestinationPath $zip -CompressionLevel Optimal
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue   # local temp class build

Write-Host "Built + verified: frost.jar (Dropbox) and $([System.IO.Path]::GetFileName($zip))"
Write-Host "  Run: frost.cmd <in.gcs>   (needs JDK)   OR   extract the zip -> frost\frost.exe (no Java)"
