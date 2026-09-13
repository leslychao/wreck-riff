package game.wreckriff.presentation;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.*;
import com.jme3.scene.*;
import java.nio.FloatBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialChunksTest {
    @Test void largeRotatedSlabIsClippedIntoTrueBoundsWithoutChangingAreaOrUv() {
        Geometry source=new Geometry("road",SurfaceMesh.box(330,.25f,210,4));
        source.setLocalTranslation(510,2,410);source.rotate(0,.37f,0);source.updateGeometricState();
        var pieces=SpatialChunks.split(source,160);assertTrue(pieces.size()>15);
        double actual=0;
        for(var piece:pieces) {
            piece.updateGeometricState();var bounds=(BoundingBox)piece.getWorldBound();
            assertTrue(bounds.getXExtent()<=80.002f);assertTrue(bounds.getZExtent()<=80.002f);
            var points=(FloatBuffer)piece.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
            var uv=(FloatBuffer)piece.getMesh().getBuffer(VertexBuffer.Type.TexCoord).getData();
            for(int i=0;i<piece.getMesh().getVertexCount();i++) {
                Vector3f point=source.worldToLocal(new Vector3f(points.get(i*3),points.get(i*3+1),points.get(i*3+2)),null);
                assertTrue(Float.isFinite(uv.get(i*2)));assertTrue(Float.isFinite(uv.get(i*2+1)));
                assertTrue(point.x>=-330.001f&&point.x<=330.001f);
            }
            actual+=area(piece.getMesh());
        }
        assertEquals(area(source.getMesh()),actual,1.0);
    }
    @Test void smallModelsKeepTheirOwnTransformAndIdentity() {
        var source=new Geometry("pillar",SurfaceMesh.box(1,6,1,4));source.setLocalTranslation(159,6,80);
        assertSame(source,SpatialChunks.split(source,160).getFirst());
    }
    private static double area(Mesh mesh) {
        Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();double area=0;
        for(int i=0;i<mesh.getTriangleCount();i++){mesh.getTriangle(i,a,b,c);area+=b.subtract(a).cross(c.subtract(a)).length()*.5;}
        return area;
    }
}
