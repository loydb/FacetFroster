# Recovery runner for a long FrostCkpt frost (large/dense designs, many minutes).
#
# Runs FrostCkpt (correct result + rolling-3 .gcs checkpoints every 10%). If it
# genuinely stalls (checkpoint progress stops advancing for a long time) or the
# JVM dies without producing output, it restarts FRESH (resume-continuation
# diverges because the tool's cut() carries hidden state). Renders on success if
# a viewer is supplied.
#
# Monitoring is PROGRESS-FILE based, not CPU. Two hard-won gotchas this avoids:
#   * On some setups `java` is a wrapper shim that spawns the real JVM as a
#     separate PID -- monitoring/killing the wrapper false-kills healthy runs
#     and leaves zombie workers. So we kill by image name at restart.
#   * The frost's modal-dialog freeze is prevented by the Messages shadow (which
#     lives in -BuildDir); this watchdog is only for power-loss / true hangs.
#
# Usage:
#   powershell -File frost_checkpointed.ps1 -InputGcs design.gcs [-OutputGcs out.gcs]
#             [-BuildDir build] [-Width 1%] [-Viewer path\to\gcs_viewer.py]
param(
  [Parameter(Mandatory=$true)][string]$InputGcs,
  [string]$OutputGcs,
  [string]$BuildDir,                 # must contain lap\ + the Messages shadow (build_exe.ps1 makes this)
  [string]$Width = "1%",
  [string]$Viewer,                   # optional: path to gcs_viewer.py to render on success
  [int]$StallLimit = 900             # seconds with no checkpoint-progress advance => treat as hung
)
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $BuildDir)   { $BuildDir   = Join-Path $here "build" }
if (-not $OutputGcs)  { $OutputGcs  = [System.IO.Path]::ChangeExtension($InputGcs, $null).TrimEnd('.') + "_frosted.gcs" }
$cd     = Join-Path $here "ckpt_run"
$render = [System.IO.Path]::ChangeExtension($OutputGcs, ".png")
$log    = Join-Path $here "run.log"
function Log($m){ "$((Get-Date).ToString('HH:mm:ss'))  $m" | Tee-Object -FilePath $log -Append | Out-Null }

if (-not (Test-Path (Join-Path $BuildDir "lap\menu\Messages.class"))) {
  Log "ERROR: $BuildDir lacks the Messages shadow. Run build_exe.ps1 first (it stages build\)."; exit 1
}

$success = $false
for ($attempt = 1; $attempt -le 6 -and -not $success; $attempt++) {
  Get-Process java -EA SilentlyContinue | Stop-Process -Force -EA SilentlyContinue   # clear any zombies
  Remove-Item $cd -Recurse -Force -EA SilentlyContinue; New-Item -ItemType Directory -Force $cd | Out-Null
  Remove-Item $OutputGcs -EA SilentlyContinue
  Log "attempt ${attempt}: launching FrostCkpt"
  Start-Process -FilePath "java" -ArgumentList @("-cp","$BuildDir","FrostCkpt",$InputGcs,$OutputGcs,$Width,$cd,"10") `
    -RedirectStandardOutput (Join-Path $here "run_ck.log") -RedirectStandardError (Join-Path $here "run_ck.err") -WindowStyle Hidden | Out-Null

  $lastProg = -1; $lastAdvance = Get-Date
  while ($true) {
    Start-Sleep -Seconds 30
    if (Test-Path $OutputGcs) {                                # completion = final written
      Start-Sleep -Seconds 4                                   # let it flush
      Log "attempt ${attempt}: final written"; $success = $true; break
    }
    $worker = Get-Process java -EA SilentlyContinue | Where-Object { $_.WorkingSet64 -gt 300MB }
    $prog = -1; if (Test-Path (Join-Path $cd "progress.txt")) { $prog = [int](Get-Content (Join-Path $cd "progress.txt") -Raw).Trim() }
    if ($prog -gt $lastProg) { $lastProg = $prog; $lastAdvance = Get-Date }
    if (-not $worker -and -not (Test-Path $OutputGcs)) { Log "attempt ${attempt}: worker gone, no output -> retry"; break }
    $idle = ((Get-Date) - $lastAdvance).TotalSeconds
    if ($idle -ge $StallLimit) { Log "attempt ${attempt}: no progress ${idle}s (hung) -> kill+retry"; Get-Process java -EA SilentlyContinue | Stop-Process -Force -EA SilentlyContinue; break }
  }
}
if ($success) {
  $tiers = (Select-String -Path $OutputGcs -Pattern '<tier ' -AllMatches | Measure-Object).Count
  $fr    = (Select-String -Path $OutputGcs -Pattern 'name="FR"' -AllMatches | Measure-Object).Count
  $fac   = (Select-String -Path $OutputGcs -Pattern '<facet' -AllMatches | Measure-Object).Count
  Log "DONE: $fac facets, $tiers tiers ($fr frost). -> $OutputGcs"
  if ($Viewer -and (Test-Path $Viewer)) {
    Log "rendering diagram"
    & python $Viewer $OutputGcs --save $render 2>&1 | ForEach-Object { Log "  $_" }
    Log "render: $render"
  }
} else { Log "FAILED after all attempts; latest checkpoint is in $cd" }
