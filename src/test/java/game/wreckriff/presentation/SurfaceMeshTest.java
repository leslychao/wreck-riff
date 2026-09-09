package game.wreckriff.presentation;

import com.jme3.scene.*;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceMeshTest {
    @Test void normalMappingKeepsColorAndDataMapsInTheirCorrectColorSpaces() {
        var assets=new com.jme3.asset.DesktopAssetManager(true);
        var material=new SurfaceMaterials(assets).material("asphalt");
        assertEquals(1f,material.getParam("NormalType").getValue());
        assertEquals(com.jme3.texture.image.ColorSpace.Linear,
                ((com.jme3.material.MatParamTexture)material.getMaterialDef().getMaterialParam("SpecularMap")).getColorSpace());
        for(String parameter:new String[]{"DiffuseMap","NormalMap","SpecularMap"}) {
            var texture=(com.jme3.texture.Texture)material.getParam(parameter).getValue();
            assertEquals(2048,texture.getImage().getWidth());assertEquals(2048,texture.getImage().getHeight());
            assertEquals(parameter.equals("DiffuseMap")?com.jme3.texture.image.ColorSpace.sRGB:
                    com.jme3.texture.image.ColorSpace.Linear,texture.getImage().getColorSpace());
            assertEquals(com.jme3.texture.Texture.MinFilter.Trilinear,texture.getMinFilter());
            assertEquals(8,texture.getAnisotropicFilter());
        }
    }
    @Test void staticBatchPreservesEveryBoxFaceInsteadOfLeavingZeroIndicesAndFloatingCylinders() {
        var assets=new com.jme3.asset.DesktopAssetManager(true);
        var material=new com.jme3.material.Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
        Node group=new Node("building-and-girder");
        for(float x:new float[]{-20,20}) {
            Geometry box=new Geometry("authored-box",SurfaceMesh.box(2,3,4,2));box.setMaterial(material);
            box.setLocalTranslation(x,7,5);group.attachChild(box);
        }
        group.updateGeometricState();jme3tools.optimize.GeometryBatchFactory.optimize(group,false);
        assertEquals(1,group.getQuantity());Mesh mesh=((Geometry)group.getChild(0)).getMesh();
        assertEquals(24,mesh.getTriangleCount());
        var a=new com.jme3.math.Vector3f();var b=new com.jme3.math.Vector3f();var c=new com.jme3.math.Vector3f();
        int left=0,right=0;
        for(int triangle=0;triangle<mesh.getTriangleCount();triangle++) {
            mesh.getTriangle(triangle,a,b,c);
            assertTrue(b.subtract(a).cross(c.subtract(a)).lengthSquared()>1,"Merged triangles must remain drawable");
            float x=(a.x+b.x+c.x)/3;
            if(x<0)left++;else right++;
            for(var vertex:java.util.List.of(a,b,c)) {
                assertTrue(vertex.y>=4&&vertex.y<=10,"World placement remains on the original building");
                assertTrue(vertex.z>=1&&vertex.z<=9);
            }
        }
        assertEquals(12,left);assertEquals(12,right);
    }
    @Test void roadTextureRepeatsInMetresAndTangentBasisIsFinite() {
        Mesh floor=SurfaceMesh.box(80,.5f,70,4);
        FloatBuffer uv=(FloatBuffer)floor.getBuffer(VertexBuffer.Type.TexCoord).getData();
        float minU=Float.POSITIVE_INFINITY,maxU=Float.NEGATIVE_INFINITY,minV=minU,maxV=maxU;
        // Box top is the fifth face: six vertices map XZ over forty by thirty-five repeats.
        for(int vertex=24;vertex<30;vertex++) {
            minU=Math.min(minU,uv.get(vertex*2));maxU=Math.max(maxU,uv.get(vertex*2));
            minV=Math.min(minV,uv.get(vertex*2+1));maxV=Math.max(maxV,uv.get(vertex*2+1));
        }
        assertEquals(40,maxU-minU);assertEquals(35,maxV-minV);
        FloatBuffer tangent=(FloatBuffer)floor.getBuffer(VertexBuffer.Type.Tangent).getData();
        for(int i=0;i<tangent.limit();i++)assertTrue(Float.isFinite(tangent.get(i)));
    }
}
