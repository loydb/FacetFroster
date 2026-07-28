# Recovery runner for a big FrostCkpt frost (e.g. the superegg, ~30 min).
#
# Runs FrostCkpt (correct result + rolling-3 .gcs checkpoints every 10%). If it
# genuinely stalls (checkpoint progress stops advancing for a long time) or the
# JVM dies without producing output, restarts FRESH (resume-continuation
# diverges because the tool's cut() carries hidden state). Renders on success.
#
# Monitoring is PROGRESS-FILE based, not CPU. Two hard-won gotchas this avoids:
#   * On this box `java` is a wrapper shim that spawns the real JVM as a
#     separate PID -- monitoring/killing the wrapper false-kills healthy runs
#     and leaves zombie workers. So we kill by image name at restart.
#   * The frost's dialog freeze is already prevented by the Messages shadow
#     (build dir below); this watchdog is only for power-loss / true hangs.
#
# Usage: edit the paths below, then:  powershell -File run_superegg.ps1
$ErrorActionPreference = "Continue"
$here   = Split-Path -Parent $MyInvocation.MyCommand.Path
$build  = Join-Path $here "build"          # must contain lap\ + the Messages shadow (build_exe.ps1 makes this)
$input  = "D:\Dropbox\faceting\superegg.gcs"
$final  = "D:\Dropbox\faceting\superegg_frosted.gcs"
$cd     = Join-Path $here "ckpt_run"
$render = Join-Path $here "superegg_frosted_render.png"
$viewer = "D:\Dropbox\faceting\gcs-viewer\gcs_viewer.py"
$width  = "1%"
$log    = Join-Path $here "run.log"
$STALL_LIMIT = 900            # seconds with no checkpoint-progress advance => treat as hung
function Log($m){ "$((Get-Date).ToString('HH:mm:ss'))  $m" | Tee-Object -FilePath $log -Append | Out-Null }

if (-not (Test-Path (Join-Path $build "lap\menu\Messages.class"))) {
  Log "ERROR: $build lacks the Messages shadow. Run build_exe.ps1 first (it stages build\)."; exit 1
}

$success = $false
for ($attempt = 1; $attempt -le 6 -and -not $success; $attempt++) {
  Get-Process java -EA SilentlyContinue | Stop-Process -Force -EA SilentlyContinue   # clear any zombies
  Remove-Item $cd -Recurse -Force -EA SilentlyContinue; New-Item -ItemType Directory -Force $cd | Out-Null
  Remove-Item $final -EA SilentlyContinue
  Log "attempt ${attempt}: launching FrostCkpt"
  Start-Process -FilePath "java" -ArgumentList @("-cp","$build","FrostCkpt",$input,$final,$width,$cd,"10") `
    -RedirectStandardOutput (Join-Path $here "run_ck.log") -RedirectStandardError (Join-Path $here "run_ck.err") -WindowStyle Hidden | Out-Null

  $lastProg = -1; $lastAdvance = Get-Date
  while ($true) {
    Start-Sleep -Seconds 30
    if (Test-Path $final) {                                   # completion = final written
      Start-Sleep -Seconds 4                                  # let it flush
      Log "attempt ${attempt}: final written"; $success = $true; break
    }
    $worker = Get-Process java -EA SilentlyContinue | Where-Object { $_.WorkingSet64 -gt 300MB }
    $prog = -1; if (Test-Path (Join-Path $cd "progress.txt")) { $prog = [int](Get-Content (Join-Path $cd "progress.txt") -Raw).Trim() }
    if ($prog -gt $lastProg) { $lastProg = $prog; $lastAdvance = Get-Date }
    if (-not $worker -and -not (Test-Path $final)) { Log "attempt ${attempt}: worker gone, no output -> retry"; break }
    $idle = ((Get-Date) - $lastAdvance).TotalSeconds
    if ($idle -ge $STALL_LIMIT) { Log "attempt ${attempt}: no progress ${idle}s (hung) -> kill+retry"; Get-Process java -EA SilentlyContinue | Stop-Process -Force -EA SilentlyContinue; break }
  }
}
if ($success) {
  Log "SUCCESS: rendering diagram"
  & python $viewer $final --save $render 2>&1 | ForEach-Object { Log "  $_" }
  $tiers = (Select-String -Path $final -Pattern '<tier ' -AllMatches | Measure-Object).Count
  $fr    = (Select-String -Path $final -Pattern 'name="FR"' -AllMatches | Measure-Object).Count
  $fac   = (Select-String -Path $final -Pattern '<facet' -AllMatches | Measure-Object).Count
  Log "DONE: $fac facets, $tiers tiers ($fr frost). Render: $render"
} else { Log "FAILED after all attempts; latest checkpoint is in $cd" }
