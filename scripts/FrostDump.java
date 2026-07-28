import lap.model.*;
import lap.math.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

// Expensive step: build verbatim gem, frost, dump ALL facets (verts+normal+
// frostedflag+origTier) to a fast intermediate .dump so formatting is cheap.
public class FrostDump {
    static Point3D<Double> P(double x,double y,double z){ return new Point3D<Double>(x,y,z); }
    static String key(double nx,double ny,double nz,double cn){ return String.format("%.5f_%.5f_%.5f_%.5f",nx,ny,nz,cn); }
    static String keyOf(Facet f){
        Point3D<Double> n=f.getPlane().getNormal(); double nx=n.getX(),ny=n.getY(),nz=n.getZ();
        Point3D<Double> v0=f.points.get(0);
        return key(nx,ny,nz, nx*v0.getX()+ny*v0.getY()+nz*v0.getZ());
    }
    public static void main(String[] a) throws Exception {
        String in=a[0], dump=a[1]; double thickness=Double.parseDouble(a[2]);
        PrintStream real=System.out;
        Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(in));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        Gem gem=new Gem();
        Map<String,String> planeTier=new HashMap<>();
        for(Node tn=doc.getElementsByTagName("tier").item(0);tn!=null;){ break; }
        NodeList tiers=doc.getElementsByTagName("tier");
        for(int ti=0;ti<tiers.getLength();ti++){
            Element te=(Element)tiers.item(ti); String tname=te.getAttribute("name");
            NodeList tf=te.getElementsByTagName("facet");
            for(int i=0;i<tf.getLength();i++){
                Element fe=(Element)tf.item(i);
                double nx=Double.parseDouble(fe.getAttribute("nx")),ny=Double.parseDouble(fe.getAttribute("ny")),nz=Double.parseDouble(fe.getAttribute("nz"));
                double nl=Math.sqrt(nx*nx+ny*ny+nz*nz); nx/=nl;ny/=nl;nz/=nl;
                Facet fc=new Facet(gem);
                NodeList vs=fe.getElementsByTagName("vertex");
                List<Point3D<Double>> pts=new ArrayList<>();
                for(int j=0;j<vs.getLength();j++){
                    Element ve=(Element)vs.item(j);
                    Point3D<Double> pt=P(Double.parseDouble(ve.getAttribute("x")),Double.parseDouble(ve.getAttribute("y")),Double.parseDouble(ve.getAttribute("z")));
                    int idx=gem.indexOfPoint(pt); if(idx>=0) pt=gem.points.get(idx); else gem.points.add(pt);
                    pts.add(pt);
                }
                Point3D<Double> v0=pts.get(0); double cn=nx*v0.getX()+ny*v0.getY()+nz*v0.getZ();
                fc.setPlane(new Plane(P(nx,ny,nz),cn));
                for(Point3D<Double> pt:pts) fc.addPoint(pt);
                gem.facets.add(fc);
                planeTier.put(key(nx,ny,nz,cn), tname.isEmpty()?"T?":tname);
            }
        }
        gem.getEdgesFromFacets();
        ProgressValue pv=new ProgressValue();
        Gem g=gem.cutFrostedEdges(true,true,true,thickness,false,pv);
        g.update();
        StringBuilder sb=new StringBuilder();
        int keep=0,frost=0;
        for(Facet f:g.facets){
            if(f.points.size()<3) continue;
            Point3D<Double> n=f.getPlane().getNormal();
            String k=keyOf(f);
            String tier=planeTier.get(k);
            boolean isF = (tier==null) || f.isFrosted();
            if(isF) frost++; else keep++;
            sb.append("F ").append(isF?1:0).append(' ').append(isF?"FR":tier)
              .append(' ').append(n.getX()).append(' ').append(n.getY()).append(' ').append(n.getZ()).append('\n');
            for(Point3D<Double> p:f.points) sb.append("V ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append('\n');
        }
        try(Writer w=new OutputStreamWriter(new FileOutputStream(dump),"UTF-8")){ w.write(sb.toString()); }
        System.setOut(real);
        System.out.println("DUMPED "+dump+" keep="+keep+" frosted="+frost+" bytes="+sb.length());
    }
}
