# Recovery runner for a long FacetFrosterCkpt frost (large/dense designs, many minutes).
#
# Runs FacetFrosterCkpt (correct result + rolling-3 .gcs checkpoints every 10%). If it
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
#   powershell -File facetfroster_checkpointed.ps1 -InputGcs design.gcs [-OutputGcs out.gcs]
#             [-Jar out\FacetFroster.jar] [-Width 1%] [-Viewer path\to\gcs_viewer.py]
param(
  [Parameter(Mandatory=$true)][string]$InputGcs,
  [string]$OutputGcs,
  [string]$Jar,                      # built FacetFroster.jar (build_exe.ps1 makes it); has FacetFrosterCkpt + Messages shadow
  [string]$Width = "1%",
  [string]$Viewer,                   # optional: path to gcs_viewer.py to render on success
  [int]$StallLimit = 900             # seconds with no checkpoint-progress advance => treat as hung
)
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Jar)        { $Jar        = Join-Path (Split-Path -Parent $here) "out\FacetFroster.jar" }
if (-not $OutputGcs)  { $OutputGcs  = [System.IO.Path]::ChangeExtension($InputGcs, $null).TrimEnd('.') + "_frosted.gcs" }
$cd     = Join-Path $here "ckpt_run"
$render = [System.IO.Path]::ChangeExtension($OutputGcs, ".png")
$log    = Join-Path $here "run.log"
function Log($m){ "$((Get-Date).ToString('HH:mm:ss'))  $m" | Tee-Object -FilePath $log -Append | Out-Null }

# Kill ONLY the worker we launched and its child process tree (java may be a
# wrapper shim that spawns the real JVM as a child PID), never every java on the
# box.
function Stop-ProcTree($proc){
  if (-not $proc) { return }
  $id = $proc.Id
  Get-CimInstance Win32_Process -Filter "ParentProcessId=$id" -EA SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue }
  Stop-Process -Id $id -Force -EA SilentlyContinue
}

if (-not (Test-Path $Jar)) {
  Log "ERROR: FacetFroster.jar not found at '$Jar'. Run build_exe.ps1 first."; exit 1
}

$success = $false
$proc = $null
for ($attempt = 1; $attempt -le 6 -and -not $success; $attempt++) {
  Stop-ProcTree $proc   # clear the previous attempt's worker (if any), PID-scoped
  Remove-Item $cd -Recurse -Force -EA SilentlyContinue; New-Item -ItemType Directory -Force $cd | Out-Null
  Remove-Item $OutputGcs -EA SilentlyContinue
  Log "attempt ${attempt}: launching FacetFrosterCkpt"
  $proc = Start-Process -FilePath "java" -ArgumentList @("-cp","$Jar","FacetFrosterCkpt",$InputGcs,$OutputGcs,$Width,$cd,"10") `
    -RedirectStandardOutput (Join-Path $here "run_ck.log") -RedirectStandardError (Join-Path $here "run_ck.err") -WindowStyle Hidden -PassThru

  $lastProg = -1; $lastAdvance = Get-Date
  while ($true) {
    Start-Sleep -Seconds 30
    if (Test-Path $OutputGcs) {                                # completion = final written
      Start-Sleep -Seconds 4                                   # let it flush
      Log "attempt ${attempt}: final written"; $success = $true; break
    }
    # is OUR worker still alive? match by class on the command line so a java
    # wrapper shim spawning the real JVM under a different PID is still seen.
    $worker = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -EA SilentlyContinue |
              Where-Object { $_.CommandLine -like '*FacetFrosterCkpt*' }
    $prog = -1; if (Test-Path (Join-Path $cd "progress.txt")) { $prog = [int](Get-Content (Join-Path $cd "progress.txt") -Raw).Trim() }
    if ($prog -gt $lastProg) { $lastProg = $prog; $lastAdvance = Get-Date }
    if (-not $worker -and -not (Test-Path $OutputGcs)) { Log "attempt ${attempt}: worker gone, no output -> retry"; break }
    $idle = ((Get-Date) - $lastAdvance).TotalSeconds
    if ($idle -ge $StallLimit) { Log "attempt ${attempt}: no progress ${idle}s (hung) -> kill+retry"; Stop-ProcTree $proc; break }
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
