package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.util.ArrayList;
import java.util.List;

/** Per-face metric projection gives large architecture repeating UVs without texture stretching. */
public final class SurfaceMesh {
    private SurfaceMesh(){}
    public static Mesh box(float x,float y,float z,float metresPerTile) {
        Vector3f[] v={v(-x,-y,-z),v(x,-y,-z),v(x,y,-z),v(-x,y,-z),
                v(-x,-y,z),v(x,-y,z),v(x,y,z),v(-x,y,z)};
        int[] indices={0,3,2,0,2,1,4,5,6,4,6,7,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        List<Vector3f> vertices=new ArrayList<>();for(int index:indices)vertices.add(v[index]);
        return triangles(vertices,metresPerTile);
    }
    public static Mesh triangles(List<Vector3f> vertices,float metresPerTile) {
        int size=vertices.size();float[] p=new float[size*3],n=new float[size*3],uv=new float[size*2];
        for(int i=0;i<size;i+=3) {
            Vector3f a=vertices.get(i),b=vertices.get(i+1),c=vertices.get(i+2);
            Vector3f normal=b.subtract(a).cross(c.subtract(a)).normalizeLocal();
            for(int j=0;j<3;j++) {
                int index=i+j;Vector3f point=vertices.get(index);
                p[index*3]=point.x;p[index*3+1]=point.y;p[index*3+2]=point.z;
                n[index*3]=normal.x;n[index*3+1]=normal.y;n[index*3+2]=normal.z;
                float[] coord=uv(point,normal,metresPerTile);uv[index*2]=coord[0];uv[index*2+1]=coord[1];
            }
        }
        Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(p));
        mesh.setBuffer(VertexBuffer.Type.Normal,3,BufferUtils.createFloatBuffer(n));
        mesh.setBuffer(VertexBuffer.Type.TexCoord,2,BufferUtils.createFloatBuffer(uv));
        mesh.updateBound();MikktspaceTangentGenerator.generate(mesh);mesh.setStatic();return mesh;
    }
    public static float[] uv(Vector3f point,Vector3f normal,float metresPerTile) {
        float nx=Math.abs(normal.x),ny=Math.abs(normal.y),nz=Math.abs(normal.z);
        return ny>=nx&&ny>=nz?new float[]{point.x/metresPerTile,point.z/metresPerTile}:
                nx>=nz?new float[]{point.z/metresPerTile,point.y/metresPerTile}:
                new float[]{point.x/metresPerTile,point.y/metresPerTile};
    }
    private static Vector3f v(float x,float y,float z){return new Vector3f(x,y,z);}
}
