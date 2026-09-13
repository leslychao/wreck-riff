package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.*;

/** Splits static visual triangles at spatial cell boundaries, preserving interpolated UVs and normals.
 * Physics retains its original authored mesh; this only changes the renderer's batching granularity. */
public final class SpatialChunks {
    private SpatialChunks() { }
    private record Vertex(Vector3f position,Vector3f normal,float[] uv) {
        Vertex between(Vertex other,float t) {
            return new Vertex(position.clone().interpolateLocal(other.position,t),normal.clone().interpolateLocal(other.normal,t),
                    new float[]{uv[0]+(other.uv[0]-uv[0])*t,uv[1]+(other.uv[1]-uv[1])*t});
        }
    }
    public static List<Geometry> split(Geometry geometry,int cellSize) {
        if(cellSize<=0)throw new IllegalArgumentException("Positive cell size required");
        Mesh source=geometry.getMesh();
        if(source.getMode()!=Mesh.Mode.Triangles||source.getBuffer(VertexBuffer.Type.Normal)==null
                ||source.getBuffer(VertexBuffer.Type.TexCoord)==null)return List.of(geometry);
        geometry.updateGeometricState();
        var bounds=(com.jme3.bounding.BoundingBox)geometry.getWorldBound();
        if(bounds.getXExtent()*2<=cellSize&&bounds.getZExtent()*2<=cellSize)return List.of(geometry);
        var positions=(FloatBuffer)source.getBuffer(VertexBuffer.Type.Position).getData();
        var normals=(FloatBuffer)source.getBuffer(VertexBuffer.Type.Normal).getData();
        var uvs=(FloatBuffer)source.getBuffer(VertexBuffer.Type.TexCoord).getData();
        var indices=source.getIndicesAsList();Map<String,List<Vertex>> cells=new LinkedHashMap<>();
        for(int triangle=0;triangle<source.getTriangleCount();triangle++) {
            List<Vertex> vertices=new ArrayList<>(3);
            float minX=Float.POSITIVE_INFINITY,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxZ=maxX;
            for(int j=0;j<3;j++) {
                int index=indices.get(triangle*3+j);
                Vector3f p=geometry.localToWorld(new Vector3f(positions.get(index*3),positions.get(index*3+1),positions.get(index*3+2)),null);
                Vector3f n=new Vector3f(normals.get(index*3),normals.get(index*3+1),normals.get(index*3+2));
                n.divideLocal(geometry.getWorldScale());geometry.getWorldRotation().mult(n,n);n.normalizeLocal();
                vertices.add(new Vertex(p,n,new float[]{uvs.get(index*2),uvs.get(index*2+1)}));
                minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minZ=Math.min(minZ,p.z);maxZ=Math.max(maxZ,p.z);
            }
            int startX=(int)Math.floor(minX/cellSize),endX=(int)Math.floor(Math.nextDown(maxX)/cellSize);
            int startZ=(int)Math.floor(minZ/cellSize),endZ=(int)Math.floor(Math.nextDown(maxZ)/cellSize);
            endX=Math.max(startX,endX);endZ=Math.max(startZ,endZ);
            for(int x=startX;x<=endX;x++)for(int z=startZ;z<=endZ;z++) {
                List<Vertex> clipped=clip(vertices,0,x*cellSize,true);
                clipped=clip(clipped,0,(x+1)*cellSize,false);
                clipped=clip(clipped,2,z*cellSize,true);
                clipped=clip(clipped,2,(z+1)*cellSize,false);
                if(clipped.size()<3)continue;
                List<Vertex> output=cells.computeIfAbsent(x+":"+z,ignored->new ArrayList<>());
                for(int j=1;j<clipped.size()-1;j++) {
                    Vertex a=clipped.getFirst(),b=clipped.get(j),c=clipped.get(j+1);
                    if(b.position.subtract(a.position).cross(c.position.subtract(a.position)).lengthSquared()>1e-10f)
                        Collections.addAll(output,a,b,c);
                }
            }
        }
        List<Geometry> result=new ArrayList<>();
        for(var entry:cells.entrySet()) {
            if(entry.getValue().isEmpty())continue;
            Geometry part=new Geometry(geometry.getName()+"-chunk-"+entry.getKey(),mesh(entry.getValue()));
            part.setMaterial(geometry.getMaterial());part.setShadowMode(geometry.getShadowMode());part.setQueueBucket(geometry.getQueueBucket());
            part.setUserData("spatialCell",entry.getKey());result.add(part);
        }
        return result;
    }
    private static List<Vertex> clip(List<Vertex> polygon,int axis,float edge,boolean greater) {
        if(polygon.isEmpty())return polygon;
        List<Vertex> result=new ArrayList<>();Vertex previous=polygon.getLast();
        float prior=previous.position.get(axis)-edge;boolean priorInside=greater?prior>=0:prior<=0;
        for(Vertex current:polygon) {
            float side=current.position.get(axis)-edge;boolean inside=greater?side>=0:side<=0;
            if(inside!=priorInside)result.add(previous.between(current,prior/(prior-side)));
            if(inside)result.add(current);
            previous=current;prior=side;priorInside=inside;
        }
        return result;
    }
    private static Mesh mesh(List<Vertex> vertices) {
        int count=vertices.size();float[] p=new float[count*3],n=new float[count*3],uv=new float[count*2];int[] indices=new int[count];
        for(int i=0;i<count;i++) {
            Vertex v=vertices.get(i);Vector3f normal=v.normal.normalize();
            p[i*3]=v.position.x;p[i*3+1]=v.position.y;p[i*3+2]=v.position.z;
            n[i*3]=normal.x;n[i*3+1]=normal.y;n[i*3+2]=normal.z;uv[i*2]=v.uv[0];uv[i*2+1]=v.uv[1];indices[i]=i;
        }
        Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(p));
        mesh.setBuffer(VertexBuffer.Type.Normal,3,BufferUtils.createFloatBuffer(n));mesh.setBuffer(VertexBuffer.Type.TexCoord,2,BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(VertexBuffer.Type.Index,3,BufferUtils.createIntBuffer(indices));mesh.updateBound();
        com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(mesh);mesh.setStatic();return mesh;
    }
}
