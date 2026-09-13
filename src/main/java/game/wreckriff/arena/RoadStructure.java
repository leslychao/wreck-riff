package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import java.util.*;

/** Authoritative road underside and outside faces, derived once while loading the arena.
 * Support queries continue to use the authored top triangles. Adjacent top meshes
 * remove one another's shared edge intervals, including clipped T junctions. */
final class RoadStructure {
    private static final float EPS=.001f;
    private record Edge(String owner,Vector3f a,Vector3f b,float thickness) { }
    private RoadStructure() { }

    static Map<String,List<Vector3f>> shells(List<ArenaDefinition.TriangleSurface> surfaces) {
        Map<String,List<Vector3f>> result=new LinkedHashMap<>();
        Map<String,List<Edge>> exact=new LinkedHashMap<>();
        for(var surface:surfaces) {
            if(surface.thickness()<=0)continue;
            List<Vector3f> shell=new ArrayList<>();result.put(surface.id(),shell);
            for(int i=0;i<surface.indices().size();i+=3) {
                Vector3f a=vertex(surface,i),b=vertex(surface,i+1),c=vertex(surface,i+2);
                // Reverse the winding: the bottom is visible and collidable from below.
                Collections.addAll(shell,lower(a,surface.thickness()),lower(c,surface.thickness()),lower(b,surface.thickness()));
                for(var edge:List.of(new Edge(surface.id(),a,b,surface.thickness()),new Edge(surface.id(),b,c,surface.thickness()),new Edge(surface.id(),c,a,surface.thickness()))) {
                    String aa=key(edge.a),bb=key(edge.b);String key=aa.compareTo(bb)<0?aa+"/"+bb:bb+"/"+aa;
                    exact.computeIfAbsent(key,ignored->new ArrayList<>()).add(edge);
                }
            }
        }
        // Internal triangulation edges usually match exactly; discard these first
        // so the clipped-edge comparison works on exterior boundaries only.
        List<Edge> edges=new ArrayList<>();
        for(var group:exact.values()) {
            boolean[] paired=new boolean[group.size()];
            for(int i=0;i<group.size();i++)if(!paired[i])for(int j=i+1;j<group.size();j++)if(!paired[j]) {
                Edge a=group.get(i),b=group.get(j);
                if(a.a.distanceSquared(b.b)<EPS*EPS&&a.b.distanceSquared(b.a)<EPS*EPS&&Math.abs(a.thickness-b.thickness)<EPS) {
                    paired[i]=paired[j]=true;break;
                }
            }
            for(int i=0;i<group.size();i++)if(!paired[i])edges.add(group.get(i));
        }
        for(var edge:edges) {
            Vector3f direction=edge.b.subtract(edge.a);float lengthSquared=direction.lengthSquared();
            if(lengthSquared<EPS*EPS)continue;
            List<float[]> visible=new ArrayList<>();visible.add(new float[]{0,1});
            for(var other:edges) {
                if(edge==other||Math.abs(edge.thickness-other.thickness)>EPS)continue;
                Vector3f reverse=other.b.subtract(other.a);
                if(direction.dot(reverse)>=0)continue;
                if(direction.cross(other.a.subtract(edge.a)).lengthSquared()>EPS*EPS*lengthSquared
                        ||direction.cross(other.b.subtract(edge.a)).lengthSquared()>EPS*EPS*lengthSquared)continue;
                float start=other.a.subtract(edge.a).dot(direction)/lengthSquared,end=other.b.subtract(edge.a).dot(direction)/lengthSquared;
                float lo=Math.max(0,Math.min(start,end)),hi=Math.min(1,Math.max(start,end));
                if(hi-lo<EPS/Math.sqrt(lengthSquared))continue;
                List<float[]> next=new ArrayList<>();
                for(float[] span:visible) {
                    if(hi<=span[0]||lo>=span[1])next.add(span);
                    else {if(lo>span[0])next.add(new float[]{span[0],lo});if(hi<span[1])next.add(new float[]{hi,span[1]});}
                }
                visible=next;if(visible.isEmpty())break;
            }
            for(float[] span:visible) {
                Vector3f a=edge.a.add(direction.mult(span[0])),b=edge.a.add(direction.mult(span[1]));
                if(a.distanceSquared(b)<EPS*EPS)continue;
                Vector3f downA=lower(a,edge.thickness),downB=lower(b,edge.thickness);
                // Top winding has the surface interior on its right; this order
                // gives the side normal away from that interior.
                Collections.addAll(result.get(edge.owner),a,downA,b,b,downA,downB);
            }
        }
        result.replaceAll((id,triangles)->List.copyOf(triangles));return Map.copyOf(result);
    }
    private static Vector3f vertex(ArenaDefinition.TriangleSurface surface,int index) {return surface.vertices().get(surface.indices().get(index)).vector();}
    private static Vector3f lower(Vector3f p,float thickness) {return p.add(0,-thickness,0);}
    private static String key(Vector3f p) {return Math.round(p.x/EPS)+":"+Math.round(p.y/EPS)+":"+Math.round(p.z/EPS);}
}
