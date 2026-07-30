import lap.model.*;
import lap.math.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Checkpointing edge-froster for GemCutStudio .gcs.
 *   FacetFrosterCkpt <input.gcs> <output.gcs> <width|N%> <ckptDir> [pct]
 * Drives the tool's frosting loop by hand (validated identical to
 * cutFrostedEdges) so it can save a rolling window of 3 checkpoints every
 * `pct`% (default 10) and RESUME after a crash/freeze. If ckptDir already has
 * checkpoints for this input, it resumes from the newest one.
 */
public class FacetFrosterCkpt {
    static final int ROLL = 3;
    static Point3D<Double> P(double x,double y,double z){ return new Point3D<Double>(x,y,z); }
    static double clamp(double v){ return Math.max(-1, Math.min(1, v)); }

    // ---- reference maps from the ORIGINAL file (for final tier structure) ----
    static Map<String,String> planeTier = new HashMap<>();
    static Map<String,String> tierAngle = new LinkedHashMap<>();
    static Map<String,String> tierDepth = new LinkedHashMap<>();
    static Map<String,String> tierInstr = new LinkedHashMap<>();
    static List<String> tierOrder = new ArrayList<>();

    static String key(double nx,double ny,double nz,double cn){ return String.format("%.5f_%.5f_%.5f_%.5f",nx,ny,nz,cn); }
    static double cnOf(Facet f){ Point3D<Double> n=f.getPlane().getNormal(); Point3D<Double> v=f.points.get(0);
        return n.getX()*v.getX()+n.getY()*v.getY()+n.getZ()*v.getZ(); }
    static String keyOf(Facet f){ Point3D<Double> n=f.getPlane().getNormal(); return key(n.getX(),n.getY(),n.getZ(),cnOf(f)); }

    /** parse a .gcs into a welded Gem (all facets verbatim). Also records the
     *  reference tier maps when isOriginal. */
    static Gem build(String path, double tol, boolean isOriginal) throws Exception {
        Gem.maxError = tol;
        Document doc = parseSecure(new File(path));
        Gem gem = new Gem();
        NodeList tiers = doc.getElementsByTagName("tier");
        for (int ti=0; ti<tiers.getLength(); ti++){
            Element te=(Element)tiers.item(ti);
            String tn=te.getAttribute("name"); if(tn.isEmpty()) tn="MAIN";
            if(isOriginal){ if(!tierAngle.containsKey(tn)){ tierAngle.put(tn,te.getAttribute("angle"));
                tierDepth.put(tn,te.getAttribute("depth")); tierInstr.put(tn,te.getAttribute("instructions")); tierOrder.add(tn);} }
            NodeList tf=te.getElementsByTagName("facet");
            for(int i=0;i<tf.getLength();i++){
                Element fe=(Element)tf.item(i);
                double nx=Double.parseDouble(fe.getAttribute("nx")),ny=Double.parseDouble(fe.getAttribute("ny")),nz=Double.parseDouble(fe.getAttribute("nz"));
                double nl=Math.sqrt(nx*nx+ny*ny+nz*nz); if(nl<1e-12)nl=1; nx/=nl;ny/=nl;nz/=nl;
                Facet fc=new Facet(gem); NodeList vs=fe.getElementsByTagName("vertex"); List<Point3D<Double>> pts=new ArrayList<>(); double cn=0;
                for(int j=0;j<vs.getLength();j++){Element ve=(Element)vs.item(j);
                    Point3D<Double> pt=P(Double.parseDouble(ve.getAttribute("x")),Double.parseDouble(ve.getAttribute("y")),Double.parseDouble(ve.getAttribute("z")));
                    int idx=gem.indexOfPoint(pt); if(idx>=0) pt=gem.points.get(idx); else gem.points.add(pt); pts.add(pt); cn+=nx*pt.getX()+ny*pt.getY()+nz*pt.getZ();}
                cn/=pts.size(); fc.setPlane(new Plane(P(nx,ny,nz),cn)); for(Point3D<Double> pt:pts) fc.points.add(pt); gem.facets.add(fc);
                if(isOriginal) planeTier.put(key(nx,ny,nz,cn), tn);
            }
        }
        gem.getEdgesFromFacets();
        return gem;
    }

    static double autoTune(String path) throws Exception {
        double[] cands={1e-6,3e-6,1e-5,3e-5,1e-4,3e-4,1e-3};
        double best=cands[cands.length-1]; int bestBad=Integer.MAX_VALUE;
        for(double t:cands){ Gem g=build(path,t,false); int bad=0; for(Edge e:g.edges) if(e.facets.size()!=2) bad++;
            if(bad<bestBad){bestBad=bad;best=t;} if(bad==0) return t; }
        return best;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: FacetFrosterCkpt <input.gcs> <output.gcs> <width|N%> <ckptDir> [pct]");
            System.exit(2);
        }
        String in=args[0], out=args[1], widthArg=args[2], dir=args[3];
        int pct = args.length>4 ? Integer.parseInt(args[4]) : 10;
        new File(dir).mkdirs();
        PrintStream real=System.out; PrintStream nul=new PrintStream(OutputStream.nullOutputStream());

        // reference maps + model bounds/width (parse original once, plain)
        double tol;
        File progress=new File(dir,"progress.txt");
        File paramsF=new File(dir,"params.txt");
        boolean resume = progress.exists() && paramsF.exists() && latestCkpt(dir)!=null;

        System.out.println(resume ? "Resuming from checkpoint..." : "Starting fresh...");
        System.setOut(nul);
        // always parse original for reference maps + tol
        String meta = paramsF.exists()? null : null;
        tol = autoTune(in);
        build(in, tol, true);                 // populate reference maps (discard gem)
        // width -> thickness (from original bounds)
        double minx=1e18,maxx=-1e18,miny=1e18,maxy=-1e18;
        for(String k:planeTier.keySet()){}     // maps don't carry coords; recompute bounds from file
        double[] bb=bounds(in); double modelW=Math.max(bb[1]-bb[0], bb[3]-bb[2]);
        double thickness = widthArg.trim().endsWith("%") ? Double.parseDouble(widthArg.replace("%",""))/100.0*modelW
                          : Double.parseDouble(widthArg.trim());
        System.setOut(real);

        Gem g; List<double[]> params; int startIdx;
        int gear = 96;
        if(!resume){
            g = build(in, tol, false);
            gear = g.getMetadata()!=null ? g.getMetadata().gear : 96;
            System.setOut(nul);
            params = new ArrayList<>();
            for(Edge e:g.edges){ Double[] p=g.getEdgeParameters(e, thickness, false, gear);
                params.add(new double[]{p[0],p[1],p[2],p[3],p[4],p[5]}); }
            System.setOut(real);
            saveParams(paramsF, params, gear, tol);
            startIdx=0;
            System.out.printf("Model width %.3f, bevel %.4f, %d edges, weld %.0e%n", modelW, thickness, params.size(), tol);
        } else {
            try {
                Object[] pr = loadParams(paramsF); params=(List<double[]>)pr[0]; gear=(int)pr[1]; double ptol=(double)pr[2];
                startIdx = Integer.parseInt(new String(Files.readAllBytes(progress.toPath())).trim()) + 1;
                if (startIdx < 0 || startIdx > params.size())
                    throw new IndexOutOfBoundsException("startIdx "+startIdx+" out of range 0.."+params.size());
                // resume from the exact index-based state (coord-welding a reloaded
                // .gcs cannot reproduce the thin-bevel topology; index sharing can).
                System.setOut(nul); g = loadState(new File(dir, "ckpt_"+(startIdx-1)+".state")); System.setOut(real);
            } catch (NumberFormatException | IndexOutOfBoundsException ex) {
                System.setOut(real);
                System.err.println("checkpoint corrupt ("+dir+"): delete it and rerun");
                System.exit(1);
                return;
            }
            System.out.printf("Resumed at edge %d/%d%n", startIdx, params.size());
        }

        int N=params.size();
        int step=Math.max(1, N*pct/100);
        final ProgressValue pv=new ProgressValue();
        final boolean[] done={false};
        Thread bar=new Thread(()->{ String last=""; while(!done[0]){ int done2= (int)(0); try{Thread.sleep(200);}catch(Exception e){break;} } });
        // simple progress print
        for(int i=startIdx;i<N;i++){
            double[] p=params.get(i);
            Plane plane=new Plane(P(p[3],p[4],p[5]), p[2]);
            System.setOut(nul);
            g=g.cut(gear, p[2], p[0]*180.0/Math.PI, p[1]*gear/(2*Math.PI), plane, 1, false);
            g.update();
            System.setOut(real);
            if((i+1)%step==0 || i==N-1){
                System.setOut(nul); saveCheckpoint(g, dir, i); System.setOut(real);
                int pctDone=(int)((i+1)*100L/N);
                System.out.printf("\r[%-20s] %3d%%  edge %d/%d  (checkpoint saved)   ",
                        bar(pctDone), pctDone, i+1, N); System.out.flush();
            }
        }
        System.out.println();
        System.setOut(nul); g.mergeTiers(); writeFinal(g, in, out); System.setOut(real);
        int frost=0; for(Facet f:g.facets){ if(f.points.size()<3) continue; if(!planeTier.containsKey(keyOf(f))) frost++; }
        System.out.println("Done. "+g.facets.size()+" facets ("+frost+" frosted). Wrote "+out);
        if(lap.menu.Messages.lastMessage!=null) System.out.println("Note: "+lap.menu.Messages.lastMessage.replace("\n"," ").trim());
    }

    static String bar(int p){ int f=p*20/100; StringBuilder b=new StringBuilder(); for(int i=0;i<20;i++) b.append(i<f?'#':' '); return b.toString(); }

    static double[] bounds(String path) throws Exception {
        double minx=1e18,maxx=-1e18,miny=1e18,maxy=-1e18;
        Document doc=parseSecure(new File(path));
        NodeList vs=doc.getElementsByTagName("vertex");
        for(int i=0;i<vs.getLength();i++){Element ve=(Element)vs.item(i);
            double x=Double.parseDouble(ve.getAttribute("x")),y=Double.parseDouble(ve.getAttribute("y"));
            minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);}
        return new double[]{minx,maxx,miny,maxy};
    }

    static void saveParams(File f, List<double[]> params, int gear, double tol) throws Exception {
        StringBuilder sb=new StringBuilder(); sb.append(gear).append(' ').append(tol).append('\n');
        for(double[] p:params) sb.append(p[0]+" "+p[1]+" "+p[2]+" "+p[3]+" "+p[4]+" "+p[5]+"\n");
        Files.write(f.toPath(), sb.toString().getBytes("UTF-8"));
    }
    static Object[] loadParams(File f) throws Exception {
        List<String> lines=Files.readAllLines(f.toPath());
        String[] h=lines.get(0).trim().split("\\s+"); int gear=Integer.parseInt(h[0]); double tol=Double.parseDouble(h[1]);
        List<double[]> params=new ArrayList<>();
        for(int i=1;i<lines.size();i++){ String[] t=lines.get(i).trim().split("\\s+"); if(t.length<6) continue;
            params.add(new double[]{Double.parseDouble(t[0]),Double.parseDouble(t[1]),Double.parseDouble(t[2]),Double.parseDouble(t[3]),Double.parseDouble(t[4]),Double.parseDouble(t[5])}); }
        return new Object[]{params, gear, tol};
    }

    static File latestCkpt(String dir){
        File[] fs=new File(dir).listFiles((d,n)->n.startsWith("ckpt_")&&n.endsWith(".gcs"));
        if(fs==null||fs.length==0) return null;
        Arrays.sort(fs, Comparator.comparingInt(a->ckptIdx(a.getName())));
        return fs[fs.length-1];
    }
    static int ckptIdx(String n){ try{ return Integer.parseInt(n.replaceAll("[^0-9]","")); }catch(Exception e){ return -1; } }

    /** minimal geometry dump (all facets, single tier) -- enough to reload. */
    static void saveCheckpoint(Gem g, String dir, int idx) throws Exception {
        StringBuilder sb=new StringBuilder();
        sb.append("<GemCutStudio version=\"1000\">\n\t<index gear=\"96\" base=\"0\" symmetry=\"1\" mirror=\"0\"/>\n");
        sb.append("\t<tier angle=\"0\" depth=\"1\" name=\"CK\" instructions=\"\" visible=\"true\" guide=\"false\">\n");
        for(Facet f:g.facets){ if(f.points.size()<3) continue; Point3D<Double> n=f.getPlane().getNormal();
            sb.append("\t\t<facet nx=\""+n.getX()+"\" ny=\""+n.getY()+"\" nz=\""+n.getZ()+"\" index_angle=\"0\">\n");
            for(Point3D<Double> p:f.points) sb.append("\t\t\t<vertex x=\""+p.getX()+"\" y=\""+p.getY()+"\" z=\""+p.getZ()+"\"/>\n");
            sb.append("\t\t</facet>\n"); }
        sb.append("\t</tier>\n</GemCutStudio>\n");
        File tmp=new File(dir,"ckpt_"+idx+".gcs.tmp"); Files.write(tmp.toPath(), sb.toString().getBytes("UTF-8"));
        Files.move(tmp.toPath(), new File(dir,"ckpt_"+idx+".gcs").toPath(), StandardCopyOption.REPLACE_EXISTING);
        saveState(g, dir, idx);                       // exact index-based state for resume
        Files.write(new File(dir,"progress.txt").toPath(), Integer.toString(idx).getBytes("UTF-8"));
        // rolling window: keep newest ROLL of each kind
        rollWindow(dir, ".gcs"); rollWindow(dir, ".state");
    }
    static void rollWindow(String dir, String ext){
        File[] fs=new File(dir).listFiles((d,n)->n.startsWith("ckpt_")&&n.endsWith(ext));
        if(fs!=null && fs.length>ROLL){ Arrays.sort(fs, Comparator.comparingInt(a->ckptIdx(a.getName())));
            for(int i=0;i<fs.length-ROLL;i++) fs[i].delete(); }
    }
    /** exact gem state: unique facet points (full precision, identity) + facets
     *  as point-index lists, so reload restores exact point-sharing/topology. */
    static void saveState(Gem g, String dir, int idx) throws Exception {
        Map<Point3D<Double>,Integer> pidx=new IdentityHashMap<>();
        List<Point3D<Double>> pts=new ArrayList<>();
        List<Facet> valid=new ArrayList<>();
        for(Facet f:g.facets){ if(f.points.size()<3) continue; valid.add(f);
            for(Point3D<Double> p:f.points) if(!pidx.containsKey(p)){ pidx.put(p,pts.size()); pts.add(p); } }
        StringBuilder sb=new StringBuilder();
        sb.append("P ").append(pts.size()).append('\n');
        for(Point3D<Double> p:pts) sb.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append('\n');
        sb.append("F ").append(valid.size()).append('\n');
        for(Facet f:valid){ Point3D<Double> n=f.getPlane().getNormal();
            sb.append(n.getX()).append(' ').append(n.getY()).append(' ').append(n.getZ()).append(' ').append(f.points.size());
            for(Point3D<Double> p:f.points) sb.append(' ').append(pidx.get(p));
            sb.append('\n'); }
        File tmp=new File(dir,"ckpt_"+idx+".state.tmp"); Files.write(tmp.toPath(), sb.toString().getBytes("UTF-8"));
        Files.move(tmp.toPath(), new File(dir,"ckpt_"+idx+".state").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    static Gem loadState(File f) throws Exception {
        Gem.maxError=1e-9;
        List<String> lines=Files.readAllLines(f.toPath());
        Gem g=new Gem(); int li=0;
        String[] ph=lines.get(li++).split("\\s+"); int np=Integer.parseInt(ph[1]);
        for(int i=0;i<np;i++){ String[] t=lines.get(li++).split("\\s+");
            g.points.add(P(Double.parseDouble(t[0]),Double.parseDouble(t[1]),Double.parseDouble(t[2]))); }
        String[] fh=lines.get(li++).split("\\s+"); int nf=Integer.parseInt(fh[1]);
        for(int i=0;i<nf;i++){ String[] t=lines.get(li++).split("\\s+");
            double nx=Double.parseDouble(t[0]),ny=Double.parseDouble(t[1]),nz=Double.parseDouble(t[2]); int k=Integer.parseInt(t[3]);
            Facet fc=new Facet(g); List<Point3D<Double>> pts=new ArrayList<>(); double cn=0;
            for(int j=0;j<k;j++){ int ix=Integer.parseInt(t[4+j]);
                if(ix<0||ix>=g.points.size()) throw new IndexOutOfBoundsException("vertex index "+ix+" of "+g.points.size());
                Point3D<Double> p=g.points.get(ix); pts.add(p); cn+=nx*p.getX()+ny*p.getY()+nz*p.getZ(); }
            cn/=k; fc.setPlane(new Plane(P(nx,ny,nz),cn)); for(Point3D<Double> p:pts) fc.points.add(p); g.facets.add(fc); }
        g.getEdgesFromFacets();
        return g;
    }

    // ---- full final exporter: proper per-(angle,depth) tiers ----
    static String block(String text,String tag){ Matcher m=Pattern.compile("<"+tag+"\\b[^>]*/>").matcher(text);
        if(m.find()) return m.group(); m=Pattern.compile("<"+tag+"\\b.*?</"+tag+">",Pattern.DOTALL).matcher(text); return m.find()?m.group():""; }
    static String trim(double a){ if(a==Math.rint(a)) return Long.toString((long)a);
        return new java.math.BigDecimal(a).setScale(4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    static String dflt(String s,String d){ return (s==null||s.isEmpty())?d:s; }
    static String orEmpty(String s){ return s==null?"":s; }

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

    static void writeFinal(Gem g, String origPath, String out) throws Exception {
        String otext=new String(Files.readAllBytes(new File(origPath).toPath()),"UTF-8");
        Map<String,List<Facet>> byTier=new LinkedHashMap<>();
        Map<String,Double> bAng=new HashMap<>(); Map<String,String> bName=new HashMap<>();
        Map<String,Boolean> bFr=new HashMap<>(); Map<String,Double> bDep=new HashMap<>();
        for(Facet f:g.facets){ if(f.points.size()<3) continue;
            String tier=planeTier.get(keyOf(f)); boolean isF=(tier==null);
            String bucket; double cn=cnOf(f);
            if(!isF){ bucket="O:"+tier; bName.put(bucket,tier); bFr.put(bucket,false);
                String a=tierAngle.get(tier); if(a!=null&&!a.isEmpty()) try{bAng.put(bucket,Double.parseDouble(a));}catch(Exception e){} }
            else { double nz=f.getPlane().getNormal().getZ(); double a=Math.toDegrees(Math.acos(clamp(nz)));
                bucket="F:"+Math.round(a*100)+":"+Math.round(cn*10000); bName.put(bucket,"FR"); bFr.put(bucket,true);
                bAng.put(bucket,a); bDep.put(bucket,cn); }
            byTier.computeIfAbsent(bucket,k->new ArrayList<>()).add(f);
        }
        List<String> order=new ArrayList<>();
        for(String nm:tierOrder) if(byTier.containsKey("O:"+nm)) order.add("O:"+nm);
        for(String k:byTier.keySet()) if(k.startsWith("O:")&&!order.contains(k)) order.add(k);
        List<String> fr=new ArrayList<>(); for(String k:byTier.keySet()) if(k.startsWith("F:")) fr.add(k);
        fr.sort(Comparator.<String>comparingDouble(k->bAng.getOrDefault(k,0.0)).thenComparingDouble(k->bDep.getOrDefault(k,0.0)));
        order.addAll(fr);
        StringBuilder sb=new StringBuilder("<GemCutStudio version=\"1000\">\n");
        String ib=block(otext,"index"); if(!ib.isEmpty()) sb.append("\t").append(ib).append("\n");
        for(String t:order){ boolean isFr=bFr.getOrDefault(t,false);
            double ang=bAng.getOrDefault(t,0.0); String name=bName.getOrDefault(t,"FR");
            String depth=isFr?trim(bDep.getOrDefault(t,1.0)):dflt(tierDepth.get(name),"1");
            String instr=isFr?"Frost edges":orEmpty(tierInstr.get(name));
            sb.append("\t<tier angle=\""+trim(ang)+"\" depth=\""+xmlAttr(depth)+"\" name=\""+xmlAttr(name)+"\" instructions=\""+xmlAttr(instr)+"\" visible=\"true\" guide=\"false\">\n");
            for(Facet f:byTier.get(t)){ Point3D<Double> n=f.getPlane().getNormal();
                double ia=Math.toDegrees(Math.atan2(n.getY(),n.getX()));
                sb.append("\t\t<facet nx=\""+n.getX()+"\" ny=\""+n.getY()+"\" nz=\""+n.getZ()+"\" index_angle=\""+trim(ia)+"\""+(isFr?" frosting=\"0.5\"":"")+">\n");
                for(Point3D<Double> p:f.points) sb.append("\t\t\t<vertex x=\""+p.getX()+"\" y=\""+p.getY()+"\" z=\""+p.getZ()+"\"/>\n");
                sb.append("\t\t</facet>\n"); }
            sb.append("\t</tier>\n"); }
        String rb=block(otext,"render"); if(!rb.isEmpty()) sb.append("\t").append(rb).append("\n");
        String nf=block(otext,"info"); if(!nf.isEmpty()) sb.append("\t").append(nf).append("\n");
        sb.append("</GemCutStudio>\n");
        Files.write(new File(out).toPath(), sb.toString().getBytes("UTF-8"));
    }
}
