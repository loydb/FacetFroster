# FacetFroster

A headless, scriptable command-line tool for adding **edge frosting** to
GemCutStudio `.gcs` faceting designs — including dense, high–facet-count models
(curved supereggs, spheres, freeform solids) that the interactive tool cannot
load. It adds a live progress bar, batch/automation support, checkpointing for
long runs, and a corrected `.gcs` exporter, and it runs with no GUI.

![Before/after: a smooth 1442-facet superegg vs. the same design with every facet edge frosted](docs/superegg_before_after.png)

---

## Credit — Sean O'Neil's Edge Frosting Tool

**FacetFroster is a wrapper. All of the actual edge-frosting geometry is the
work of Sean O'Neil, author of the _Edge Frosting Tool_.** The frosting
algorithm, the gem/facet/plane geometry kernel, and every bevel this program
produces come from his tool — FacetFroster only adds command-line
orchestration, I/O, and packaging around it.

Specifically, FacetFroster calls into Sean O'Neil's classes for 100% of the
geometry:

- `lap.model.Gem.cutFrostedEdges(...)` — the frosting algorithm itself
- `lap.model.Gem.getEdgeParameters(...)` — per-edge bevel parameters
- `lap.model.Gem.cut(...)` — the geometry cut that carves each bevel
- `lap.model.Gem.getEdgesFromFacets()`, `Facet`, `Edge`, `Cut`, `Tier`,
  `lap.math.Plane`, `lap.math.Point3D`, `lap.math.ProgressValue`

Please credit **Sean O'Neil's Edge Frosting Tool** in any use of FacetFroster.

**Get Sean's Edge Frosting Tool** (his official release, a runnable jar that also
includes his scaled-PDF feature):
<https://www.mediafire.com/file/ood63rjcv8ixorv/edge-frosting-tool-plus-scaled-pdf-v7.jar/file>

> **The Edge Frosting Tool itself is NOT redistributed in this repository.**
> Download it from Sean (link above) and build FacetFroster against it (see
> [Building](#building)). See [Licensing](#licensing) below.

---

## Why this exists

Sean's Edge Frosting Tool is a Swing desktop application whose `.gcs` importer
reconstructs a gem by **replaying each tier as a cut** from a starting blank.
That works for typical hand-cut faceting designs, but it collapses on dense,
curved models: a 60-fold "superegg" with 1442 explicit facets reconstructs to
~38 facets and then crashes.

FacetFroster bypasses the lossy importer: it parses the `.gcs` facets
**verbatim** into Sean's `Gem` model, lets his `getEdgesFromFacets()` build a
clean 2-facets-per-edge manifold, then runs his frosting algorithm on that. It
also:

- **auto-tunes** the vertex-weld tolerance to the smallest value that yields a
  closed manifold,
- shows a **live console progress bar** (polls Sean's `ProgressValue`),
- writes a **corrected exporter** — frosted bevels are distributed into proper
  per-angle/depth tiers (each an independent GemCutStudio cut with a sane gear
  index list) rather than lumped into a single tier that GemCutStudio
  mis-reads,
- supports **checkpointing** (`FacetFrosterCkpt`) for very long runs — a rolling
  window of `.gcs` snapshots every N%,
- routes the tool's modal warning dialogs to the console so it never blocks a
  headless run (`Messages` shadow).

## Building

Requires a JDK (developed against JDK 25 — needs `javac`, `jar`, and, for the
exe, `jpackage`) and Sean O'Neil's Edge Frosting Tool jar (download link in the
[credit section](#credit--sean-oneils-edge-frosting-tool)). Point the build at
his jar (an already-extracted folder works too):

```powershell
# from the repo root — builds out\FacetFroster.jar (+ a self-contained exe zip):
powershell -File scripts\build_exe.ps1 -Tool <edge-frosting-tool-plus-scaled-pdf-v7.jar>
powershell -File scripts\build_exe.ps1 -Tool <...v7.jar> -NoExe   # jar only, no exe
```

Or compile by hand (compile the `Messages` shadow first so the frosters link
against the console version, not the tool's dialog version — `<tool>` is Sean's
jar or its extracted folder):

```
javac -cp "<tool>" -d build src\Messages.java
javac -cp "build;<tool>" -d build src\FacetFroster.java src\FacetFrosterCkpt.java
```

The built jar/exe **embed Sean's classes** — only distribute them if his license
permits it (see [Licensing](#licensing)).

## Usage

```
java -jar out\FacetFroster.jar <input.gcs> [width | N%] [-o out.gcs]
```

(or run `FacetFroster\FacetFroster.exe` from the extracted self-contained zip —
no Java needed).

- Output defaults to `<input dir>\<name>_frosted.gcs`.
- `width`: a bare number = model units; `N%` = percent of model width (default 1%).
- Thin-girdle designs: a 1% bevel can overlap into spiky geometry — use a
  smaller width (e.g. `0.3%`).

Checkpointed (large/dense designs, many minutes) — a rolling window of 3 `.gcs`
snapshots every `pct`%, so a crash/power-loss doesn't lose the whole run:

```
java -cp out\FacetFroster.jar FacetFrosterCkpt <in.gcs> <out.gcs> <width|N%> <ckptDir> [pct]
```

`scripts\facetfroster_checkpointed.ps1` wraps that with auto-restart on a true hang.

## Licensing

- **FacetFroster's own code** (`src/`, `scripts/`) — see [LICENSE](LICENSE).
- **Sean O'Neil's Edge Frosting Tool** — his own work, under his own terms, and
  **not included here**. Obtain it from Sean and comply with his license.
  Redistributing a built jar/exe that embeds his classes requires his
  permission.

## Files

| File | What it is |
|------|-----------|
| `src/FacetFroster.java` | one-shot froster: verbatim load + auto-tune + progress bar + corrected exporter |
| `src/FacetFrosterCkpt.java` | checkpointing froster (rolling-N `.gcs` snapshots) for long runs |
| `src/Messages.java` | console shadow of the tool's `lap.menu.Messages` (no blocking dialogs) |
| `scripts/build_exe.ps1` | build + verify `FacetFroster.jar` and a self-contained exe zip (`-Tool <path>`) |
| `scripts/facetfroster_checkpointed.ps1` | parameterized recovery runner (rolling checkpoints + auto-restart on hang) |
