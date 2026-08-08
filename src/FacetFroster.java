import lap.model.*;
import lap.math.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Edge-frosting CLI for GemCutStudio .gcs files.
 *   frost &lt;input.gcs&gt; [width|N%] [-o output.gcs]
 * width: bevel width. Bare number = model units; "N%" = percent of model
 *        width (default 1%). Output defaults to &lt;input dir&gt;/&lt;name&gt;_frosted.gcs.
 *
 * Bypasses the tool's lossy re-cut importer by loading facets verbatim, then
 * runs Sean O'Neil's cutFrostedEdges on the resulting clean manifold, with a
 * live console progress bar. Self-contained (bundles the lap.* classes).
 */
public class FacetFroster {
    static Point3D<Double> P(double x, double y, double z) { return new Point3D<Double>(x, y, z); }
    static String key(double nx, double ny, double nz, double cn) {
        return String.format("%.5f_%.5f_%.5f_%.5f", nx, ny, nz, cn);
    }
    static String keyOf(Facet f) {
        Point3D<Double> n = f.getPlane().getNormal();
        double nx = n.getX(), ny = n.getY(), nz = n.getZ();
        Point3D<Double> v0 = f.points.get(0);
        return key(nx, ny, nz, nx * v0.getX() + ny * v0.getY() + nz * v0.getZ());
    }

    static String key4(double nx, double ny, double nz, double cn) {
        return String.format("%.4f_%.4f_%.4f_%.4f", nx, ny, nz, cn);
    }
    /** check key: facet's getNormal + first-vertex plane distance (matched against
     *  the original-plane set built in buildGem with the same 1e-4 rounding). */
    static String checkKey4(Facet f) {
        Point3D<Double> n = f.getPlane().getNormal();
        double nx = n.getX(), ny = n.getY(), nz = n.getZ();
        Point3D<Double> v0 = f.points.get(0);
        return key4(nx, ny, nz, nx * v0.getX() + ny * v0.getY() + nz * v0.getZ());
    }
    /** how many distinct original planes still have a surviving facet */
    static int survOrig(Gem g, java.util.Set<String> orig) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Facet f : g.facets) {
            if (f.points.size() < 3) continue;
            String k = checkKey4(f);
            if (orig.contains(k)) seen.add(k);
        }
        return seen.size();
    }
    static String block(String text, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*/>").matcher(text);
        if (m.find()) return m.group();
        m = Pattern.compile("<" + tag + "\\b.*?</" + tag + ">", Pattern.DOTALL).matcher(text);
        return m.find() ? m.group() : "";
    }

    /** Hardened XML parse (XXE-safe): forbids DOCTYPE, external entities, DTDs
     *  and external schemas. Use in place of the default DocumentBuilder. */
    static Document parseSecure(File f) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        try { dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (IllegalArgumentException ignore) {}
        try { dbf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); } catch (IllegalArgumentException ignore) {}
        return dbf.newDocumentBuilder().parse(f);
    }

    /** XML-attribute-escape an input-derived string value (null-safe). Escape
     *  '&' FIRST so the other replacements are not double-escaped. */
    static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: frost <input.gcs> [width|N%] [-o output.gcs] [--fractional] [--girdle]");
            System.err.println("  --fractional   allow fractional cutting indices (default: snap to whole gear indices)");
            System.err.println("  --girdle       also frost girdle-adjacent edges (default: girdle left unfrosted)");
            System.exit(2);
        }
        String in = null, out = null, widthArg = null;
        // By default, snap frosted facets to whole gear index positions so they
        // are actually cuttable (a facet at index 11.5 is hard to set on the
        // machine). --fractional turns snapping off for maximum edge coverage.
        boolean roundIndices = true;
        // Girdle edges are excluded by default (the girdle is normally left
        // unfrosted, and a bevel pivoting off a short vertical girdle facet
        // consumes it at any width -- same reason Sean's tool puts girdle
        // frosting behind its own checkbox). --girdle opts in.
        boolean frostGirdle = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-o") || a.equals("--out")) { out = args[++i]; }
            else if (a.equals("--fractional") || a.equals("-f")) { roundIndices = false; }
            else if (a.equals("--girdle") || a.equals("-g")) { frostGirdle = true; }
            else if (in == null) in = a;
            else if (widthArg == null) widthArg = a;
        }
        File inFile = new File(in);
        if (!inFile.exists()) { System.err.println("no such file: " + in); System.exit(2); }
        if (out == null) {
            String name = inFile.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File dir = inFile.getAbsoluteFile().getParentFile();
            out = new File(dir, base + "_frosted.gcs").getPath();
        }
        String otext = new String(Files.readAllBytes(inFile.toPath()), "UTF-8");

        PrintStream real = System.out;
        PrintStream nul = new PrintStream(OutputStream.nullOutputStream());

        System.out.println("Loading " + inFile.getName() + " ...");
        Document doc = parseSecure(inFile);

        // ---- parse raw facets (normal + tier + vertex list) once ----
        List<double[]> fnorm = new ArrayList<>();     // {nx,ny,nz}
        List<String> ftier = new ArrayList<>();
        List<double[][]> fverts = new ArrayList<>();   // per-facet vertex arrays
        Map<String, String> tierAngle = new LinkedHashMap<>();  // original tier name -> angle
        Map<String, String> tierDepth = new LinkedHashMap<>();
        Map<String, String> tierInstr = new LinkedHashMap<>();
        double minx = 1e18, maxx = -1e18, miny = 1e18, maxy = -1e18;
        NodeList tiers = doc.getElementsByTagName("tier");
        for (int ti = 0; ti < tiers.getLength(); ti++) {
            Element te = (Element) tiers.item(ti);
            String tname = te.getAttribute("name");
            if (tname.isEmpty()) tname = "MAIN";
            tierAngle.putIfAbsent(tname, te.getAttribute("angle"));
            tierDepth.putIfAbsent(tname, te.getAttribute("depth"));
            tierInstr.putIfAbsent(tname, te.getAttribute("instructions"));
            NodeList tf = te.getElementsByTagName("facet");
            for (int i = 0; i < tf.getLength(); i++) {
                Element fe = (Element) tf.item(i);
                double nx = Double.parseDouble(fe.getAttribute("nx"));
                double ny = Double.parseDouble(fe.getAttribute("ny"));
                double nz = Double.parseDouble(fe.getAttribute("nz"));
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-12) nl = 1;
                NodeList vs = fe.getElementsByTagName("vertex");
                double[][] vv = new double[vs.getLength()][3];
                for (int j = 0; j < vs.getLength(); j++) {
                    Element ve = (Element) vs.item(j);
                    double x = Double.parseDouble(ve.getAttribute("x"));
                    double y = Double.parseDouble(ve.getAttribute("y"));
                    double z = Double.parseDouble(ve.getAttribute("z"));
                    vv[j][0] = x; vv[j][1] = y; vv[j][2] = z;
                    minx = Math.min(minx, x); maxx = Math.max(maxx, x);
                    miny = Math.min(miny, y); maxy = Math.max(maxy, y);
                }
                fnorm.add(new double[]{nx / nl, ny / nl, nz / nl});
                ftier.add(tname.isEmpty() ? "MAIN" : tname);
                fverts.add(vv);
            }
        }

        // ---- auto-tune point-weld tolerance: smallest that yields a closed
        //      manifold (every edge shared by exactly 2 facets) ----
        double[] cands = {1e-6, 3e-6, 1e-5, 3e-5, 1e-4, 3e-4, 1e-3};
        Gem gem = null;
        Map<String, String> planeTier = null;
        java.util.Set<String> origKeys = null;
        double chosenTol = cands[cands.length - 1];
        int bestBad = Integer.MAX_VALUE;
        Gem bestGem = null; Map<String, String> bestTier = null; java.util.Set<String> bestOrig = null; double bestTol = chosenTol;
        System.setOut(nul);
        for (double tol : cands) {
            Object[] r = buildGem(fnorm, ftier, fverts, tol);
            Gem gg = (Gem) r[0];
            @SuppressWarnings("unchecked") Map<String, String> pt = (Map<String, String>) r[1];
            @SuppressWarnings("unchecked") java.util.Set<String> ok = (java.util.Set<String>) r[2];
            int bad = 0;
            for (Edge e : gg.edges) if (e.facets.size() != 2) bad++;
            if (bad < bestBad) { bestBad = bad; bestGem = gg; bestTier = pt; bestOrig = ok; bestTol = tol; }
            if (bad == 0) { gem = gg; planeTier = pt; origKeys = ok; chosenTol = tol; break; }
        }
        int nonManifold = 0;
        if (gem == null) { gem = bestGem; planeTier = bestTier; origKeys = bestOrig; chosenTol = bestTol; nonManifold = bestBad; }
        Gem.maxError = chosenTol;
        System.setOut(real);
        System.out.printf("Weld tolerance %.0e -> %d edges, %d non-manifold%n",
                chosenTol, gem.edges.size(), nonManifold);
        if (nonManifold > 0) {
            System.err.println("ERROR: this design is not a closed manifold (" + nonManifold
                    + " edges touch only one facet). Edge frosting needs two facets per edge,");
            System.err.println("so it cannot be frosted. The model likely has coincident, missing,");
            System.err.println("or non-planar facets. (Frosting works on ~98% of designs.)");
            System.exit(1);
        }

        // Use the design's own index gear so whole-index rounding snaps to
        // positions the design is actually cut on (not the tool's default 96).
        // Passed explicitly to getEdgeParameters/cut below, so no metadata on the
        // gem is needed.
        int designGear = 96;
        NodeList idxN = doc.getElementsByTagName("index");
        if (idxN.getLength() > 0) {
            try { designGear = Integer.parseInt(((Element) idxN.item(0)).getAttribute("gear")); }
            catch (NumberFormatException e) { }
        }

        double modelW = Math.max(maxx - minx, maxy - miny);
        double thickness;
        if (widthArg == null) thickness = 0.01 * modelW;
        else if (widthArg.trim().endsWith("%"))
            thickness = Double.parseDouble(widthArg.trim().replace("%", "")) / 100.0 * modelW;
        else thickness = Double.parseDouble(widthArg.trim());
        System.out.printf("Model: %d facets, %d edges, width %.3f  ->  bevel width %.4f%n",
                gem.facets.size(), gem.edges.size(), modelW, thickness);
        System.out.println(roundIndices
                ? "Index mode: whole indices on the design's " + designGear + " gear (cuttable). Use --fractional to allow fractional indices."
                : "Index mode: fractional indices allowed.");

        final ProgressValue pv = new ProgressValue();
        final boolean[] done = {false};
        Thread bar = new Thread(() -> {
            String last = "";
            while (!done[0]) {
                int pct = pv.getProgress();
                String st = pv.getStatus();
                String line = renderBar(pct, st == null ? "" : st);
                if (!line.equals(last)) { real.print("\r" + line); real.flush(); last = line; }
                try { Thread.sleep(80); } catch (InterruptedException e) { break; }
            }
        });
        bar.setDaemon(true);
        bar.start();
        // The tool's modal warning dialogs are neutralized by shadowing
        // lap.menu.Messages (routes them to the console), so no dialog can block.
        // Silence its console output too for the duration of the loop: trial cuts
        // that get reverted fire "facets overwritten" warnings that don't apply
        // to the final result (our own summary reports what actually happened).
        lap.menu.Messages.lastMessage = null;
        PrintStream realErr = System.err;
        System.setOut(nul);
        System.setErr(nul);
        Gem g;
        int skippedEdges = 0, narrowedEdges = 0, girdleEdges = 0, tiltedEdges = 0;
        try {
            // Drive the frosting edge-by-edge (validated identical to the tool's
            // own cutFrostedEdges) with the guarantee that NO original facet is
            // ever destroyed: each bevel is cut on a copy and kept only if every
            // original facet survives. If the full-width band would consume a
            // small facet, the edge falls back to a narrower band (1/2, 1/4,
            // 1/8, 1/16 width), then to a steeper-tilted band (see below); only
            // an edge where every variant would destroy a neighbor -- which no
            // tested design has -- is left unfrosted, and that is reported.
            // All bevel parameters are computed upfront from the original
            // topology (the tool's own two-phase approach).
            final double[] LEVELS = {1.0, 0.5, 0.25, 0.125, 0.0625};
            java.util.List<java.util.List<double[]>> paramsL = new java.util.ArrayList<>();
            for (double lv : LEVELS) {
                java.util.List<double[]> ps = new java.util.ArrayList<>();
                for (Edge e : gem.edges) {
                    Double[] p = gem.getEdgeParameters(e, thickness * lv, roundIndices, designGear);
                    ps.add(new double[]{p[0], p[1], p[2], p[3], p[4], p[5]});
                }
                paramsL.add(ps);
            }
            // girdle exclusion uses Sean's own isGirdle() predicate, per edge
            boolean[] girdleEdge = new boolean[gem.edges.size()];
            for (int i = 0; i < gem.edges.size(); i++) {
                Edge e = gem.edges.get(i);
                girdleEdge[i] = e.facets.get(0).isGirdle() || e.facets.get(1).isGirdle();
            }
            // pre-compute per-edge data for the tilt-bias fallback (see below)
            int nE = gem.edges.size();
            double[][] edgeNa = new double[nE][], edgeNb = new double[nE][], edgeMid = new double[nE][];
            for (int i = 0; i < nE; i++) {
                Edge e = gem.edges.get(i);
                Point3D<Double> na = e.facets.get(0).getPlane().getNormal();
                Point3D<Double> nb = e.facets.get(1).getPlane().getNormal();
                edgeNa[i] = new double[]{na.getX(), na.getY(), na.getZ()};
                edgeNb[i] = new double[]{nb.getX(), nb.getY(), nb.getZ()};
                edgeMid[i] = new double[]{
                    (e.getPoint1().getX() + e.getPoint2().getX()) / 2,
                    (e.getPoint1().getY() + e.getPoint2().getY()) / 2,
                    (e.getPoint1().getZ() + e.getPoint2().getZ()) / 2};
            }
            g = gem;
            int have = survOrig(g, origKeys);
            int N = nE;
            for (int i = 0; i < N; i++) {
                if (!frostGirdle && girdleEdge[i]) { girdleEdges++; continue; }
                boolean cutOk = false;
                // 1) Sean's mitered bevel plane, narrowing the band if needed
                for (int L = 0; L < LEVELS.length && !cutOk; L++) {
                    double[] p = paramsL.get(L).get(i);
                    Plane plane = new Plane(P(p[3], p[4], p[5]), p[2]);
                    Gem snap = new Gem(g);
                    g = g.cut(designGear, p[2], p[0] * 180.0 / Math.PI, p[1] * designGear / (2 * Math.PI), plane, 1, false);
                    g.update();
                    int now = survOrig(g, origKeys);
                    if (now < have) { g = snap; }               // still eats a facet -> try narrower
                    else { have = now; cutOk = true; if (L > 0) narrowedEdges++; }
                }
                // 2) tilt-bias fallback: a near-horizontal miter plane (shallow
                //    crown/table edges) extends across the stone and clips the far
                //    girdle at ANY width. Rotating the bevel plane toward the
                //    steeper facet keeps it clear of the far side while still
                //    laying a frost band on the edge (asymmetric: wider on the
                //    shallow facet).
                if (!cutOk) {
                    double[] a = edgeNa[i], b = edgeNb[i], m = edgeMid[i];
                    double[] sh = Math.abs(a[2]) >= Math.abs(b[2]) ? a : b;   // shallower (normal closer to +-z)
                    double[] st = (sh == a) ? b : a;                          // steeper
                    outer:
                    for (double t : new double[]{0.75, 0.9}) {
                        for (double lv : new double[]{1.0, 0.5, 0.25}) {
                            double nx = (1 - t) * sh[0] + t * st[0];
                            double ny = (1 - t) * sh[1] + t * st[1];
                            double nz = (1 - t) * sh[2] + t * st[2];
                            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                            if (nl < 1e-9) continue;
                            nx /= nl; ny /= nl; nz /= nl;
                            double az = Math.atan2(ny, nx);
                            if (az < 0) az += 2 * Math.PI;
                            if (roundIndices)   // snap the biased plane to a whole gear index too
                                az = Math.round(az * designGear / (2 * Math.PI)) * 2 * Math.PI / designGear;
                            double polar = Math.atan2(Math.hypot(nx, ny), nz);
                            double cn = (nx * m[0] + ny * m[1] + nz * m[2]) - (thickness * lv) / 2;
                            Plane plane = new Plane(P(nx, ny, nz), cn);
                            Gem snap = new Gem(g);
                            g = g.cut(designGear, cn, polar * 180.0 / Math.PI, az * designGear / (2 * Math.PI), plane, 1, false);
                            g.update();
                            int now = survOrig(g, origKeys);
                            if (now < have) { g = snap; }
                            else { have = now; cutOk = true; tiltedEdges++; break outer; }
                        }
                    }
                }
                if (!cutOk) skippedEdges++;
                pv.setProgress("Cutting edges...", 5 + (int) (90L * (i + 1) / N));
            }
            g.mergeTiers();
            g.update();
        } catch (Throwable t) {
            done[0] = true;
            System.setOut(real);
            System.setErr(realErr);
            System.err.println("\nERROR: frosting failed on this design (" + t.getClass().getSimpleName()
                    + "). Its geometry may be degenerate; try a smaller width.");
            System.exit(1);
            return;
        }
        System.setOut(real);
        System.setErr(realErr);
        done[0] = true;
        try { bar.join(300); } catch (InterruptedException e) { }
        real.print("\r" + renderBar(100, "Done") + "\n");

        // ---- group facets into GCS tiers ----
        // Original (polished) facets keep their source tier (name + real angle).
        // Frosted bevel facets are grouped by their bevel-cut angle (the tool
        // computed a proper cutting angle per bevel), one tier per distinct
        // angle -- NOT all lumped into a single 90-degree tier.
        // A GCS tier is one cut = (angle, depth). Original facets keep their
        // source tier; frosted bevels are bucketed by (angle, depth) so same-
        // angle bevels at different depths become separate tiers, matching the
        // structure the tool's own exporter produces.
        Map<String, List<Facet>> byTier = new LinkedHashMap<>();
        Map<String, Double> bucketAngle = new HashMap<>();
        Map<String, String> bucketName = new HashMap<>();
        Map<String, Boolean> bucketFrost = new HashMap<>();
        Map<String, Double> bucketDepth = new HashMap<>();   // FR tier depth (plane distance)
        int keep = 0, frost = 0;
        for (Facet f : g.facets) {
            if (f.points.size() < 3) continue;
            String tier = planeTier.get(keyOf(f));
            boolean isF = (tier == null) || f.isFrosted();
            String bucket;
            if (!isF) {
                bucket = "O:" + tier;
                bucketName.put(bucket, tier);
                bucketFrost.put(bucket, false);
                String ang = tierAngle.get(tier);
                if (ang != null && !ang.isEmpty()) try { bucketAngle.put(bucket, Double.parseDouble(ang)); } catch (Exception e) {}
                keep++;
            } else {
                double a;
                Cut c = f.getCut();
                if (c != null) a = c.getAbsoluteAngle();
                else { double nz = f.getPlane().getNormal().getZ(); a = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, nz)))); }
                double cn = cnOf(f);
                bucket = "F:" + Math.round(a * 100) + ":" + Math.round(cn * 10000);
                bucketName.put(bucket, "FR");
                bucketFrost.put(bucket, true);
                bucketAngle.put(bucket, a);
                bucketDepth.put(bucket, cn);
                frost++;
            }
            byTier.computeIfAbsent(bucket, k -> new ArrayList<>()).add(f);
        }
        // order: original tiers (file order), then frosted tiers by angle then depth
        List<String> order = new ArrayList<>();
        LinkedHashSet<String> seenNames = new LinkedHashSet<>();
        Matcher tm = Pattern.compile("<tier\\b[^>]*\\bname=\"([^\"]*)\"").matcher(otext);
        while (tm.find()) seenNames.add(tm.group(1).isEmpty() ? "MAIN" : tm.group(1));
        for (String nm : seenNames) if (byTier.containsKey("O:" + nm)) order.add("O:" + nm);
        for (String k : byTier.keySet()) if (k.startsWith("O:") && !order.contains(k)) order.add(k);
        List<String> fr = new ArrayList<>();
        for (String k : byTier.keySet()) if (k.startsWith("F:")) fr.add(k);
        fr.sort(Comparator.<String>comparingDouble(k -> bucketAngle.getOrDefault(k, 0.0))
                .thenComparingDouble(k -> bucketDepth.getOrDefault(k, 0.0)));
        order.addAll(fr);

        StringBuilder sb = new StringBuilder();
        sb.append("<GemCutStudio version=\"1000\">\n");
        String ib = block(otext, "index");
        if (!ib.isEmpty()) sb.append("\t").append(ib).append("\n");
        int frTierCount = 0;
        for (String t : order) {
            boolean isFr = bucketFrost.getOrDefault(t, false);
            if (isFr) frTierCount++;
            double ang = bucketAngle.getOrDefault(t, 0.0);
            String name = bucketName.getOrDefault(t, "FR");
            String depth = isFr ? trim(bucketDepth.getOrDefault(t, 1.0))
                                : dflt(tierDepth.get(name), "1");
            String instr = isFr ? "Frost edges" : orEmpty(tierInstr.get(name));
            sb.append(String.format("\t<tier angle=\"%s\" depth=\"%s\" name=\"%s\" instructions=\"%s\" visible=\"true\" guide=\"false\">%n",
                    trim(ang), xmlAttr(depth), xmlAttr(name), xmlAttr(instr)));
            for (Facet f : byTier.get(t)) {
                Point3D<Double> n = f.getPlane().getNormal();
                double ia = Math.toDegrees(Math.atan2(n.getY(), n.getX()));   // azimuth = gear index angle
                sb.append(String.format("\t\t<facet nx=\"%s\" ny=\"%s\" nz=\"%s\" index_angle=\"%s\"%s>%n",
                        n.getX(), n.getY(), n.getZ(), trim(ia), isFr ? " frosting=\"0.5\"" : ""));
                for (Point3D<Double> p : f.points)
                    sb.append(String.format("\t\t\t<vertex x=\"%s\" y=\"%s\" z=\"%s\"/>%n", p.getX(), p.getY(), p.getZ()));
                sb.append("\t\t</facet>\n");
            }
            sb.append("\t</tier>\n");
        }
        String rb = block(otext, "render");
        if (!rb.isEmpty()) sb.append("\t").append(rb).append("\n");
        String nf = block(otext, "info");
        if (!nf.isEmpty()) sb.append("\t").append(nf).append("\n");
        sb.append("</GemCutStudio>\n");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) { w.write(sb.toString()); }

        System.out.printf("Frosted %d of %d edges -> %d polished + %d frosted facets in %d frost tiers%n",
                gem.edges.size() - skippedEdges - girdleEdges, gem.edges.size(), keep, frost, frTierCount);
        if (girdleEdges > 0)
            System.out.println(girdleEdges + " girdle-adjacent edge(s) left unfrosted "
                    + "(the norm for a cut stone; pass --girdle to frost them too).");
        if (narrowedEdges > 0)
            System.out.println(narrowedEdges + " edge(s) used a narrower band so the frosting "
                    + "wouldn't consume a small facet.");
        if (tiltedEdges > 0)
            System.out.println(tiltedEdges + " shallow edge(s) used a steeper-tilted band "
                    + "(a flat miter there would cut into the far side of the stone).");
        if (skippedEdges > 0)
            System.out.println("Skipped " + skippedEdges + " edge(s) that cannot be frosted "
                    + "without consuming another facet.");
        System.out.println("Wrote " + out);
    }

    /** plane distance of a facet: normal . v0 (a point on the plane) */
    static double cnOf(Facet f) {
        Point3D<Double> n = f.getPlane().getNormal();
        Point3D<Double> v0 = f.points.get(0);
        return n.getX() * v0.getX() + n.getY() * v0.getY() + n.getZ() * v0.getZ();
    }
    static String dflt(String s, String d) { return (s == null || s.isEmpty()) ? d : s; }
    static String orEmpty(String s) { return s == null ? "" : s; }

    /** format an angle without trailing zeros (e.g. 90.0 -> "90", 67.116 -> "67.116") */
    static String trim(double a) {
        if (a == Math.rint(a)) return Long.toString((long) a);
        return new java.math.BigDecimal(a).setScale(4, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** Build a Gem from parsed facets at weld tolerance tol; vertices added
     *  directly (no plane-rejection), points welded via gem.indexOfPoint.
     *  Returns {Gem, Map<planeKey,tierName>}. */
    static Object[] buildGem(List<double[]> fnorm, List<String> ftier,
                             List<double[][]> fverts, double tol) {
        Gem.maxError = tol;
        Gem gem = new Gem();
        // new Gem() is NOT empty: it is a unit-cube blank (6 facets at +-1, 8
        // points). Left in place, those phantom facets superimpose on the design
        // (coinciding with girdle/table planes on stones that span +-1), corrupt
        // the edge pairing, and get "obliterated" by legitimate bevels. Clear it.
        gem.facets.clear();
        gem.points.clear();
        gem.edges.clear();
        Map<String, String> planeTier = new HashMap<>();
        java.util.Set<String> origKeys = new java.util.HashSet<>();
        for (int i = 0; i < fnorm.size(); i++) {
            double[] n = fnorm.get(i);
            double nx = n[0], ny = n[1], nz = n[2];
            Facet fc = new Facet(gem);
            double[][] vv = fverts.get(i);
            List<Point3D<Double>> pts = new ArrayList<>();
            double cn = 0;
            for (double[] v : vv) {
                Point3D<Double> pt = P(v[0], v[1], v[2]);
                int idx = gem.indexOfPoint(pt);
                if (idx >= 0) pt = gem.points.get(idx); else gem.points.add(pt);
                pts.add(pt);
                cn += nx * pt.getX() + ny * pt.getY() + nz * pt.getZ();  // deduped point, not raw
            }
            cn /= pts.size();
            fc.setPlane(new Plane(P(nx, ny, nz), cn));
            for (Point3D<Double> pt : pts) fc.points.add(pt);   // direct add
            gem.facets.add(fc);
            planeTier.put(key(nx, ny, nz, cn), ftier.get(i));
            origKeys.add(key4(nx, ny, nz, cn));   // orig-plane key (file normal, avg cn, 1e-4)
        }
        gem.getEdgesFromFacets();
        return new Object[]{gem, planeTier, origKeys};
    }

    static String renderBar(int pct, String status) {
        pct = Math.max(0, Math.min(100, pct));
        int w = 30, fill = pct * w / 100;
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < w; i++) b.append(i < fill ? '#' : (i == fill ? '>' : ' '));
        b.append(String.format("] %3d%%  %-18s", pct, status.length() > 18 ? status.substring(0, 18) : status));
        return b.toString();
    }
}
