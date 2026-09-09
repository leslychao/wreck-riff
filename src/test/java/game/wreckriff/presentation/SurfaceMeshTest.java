package game.wreckriff.presentation;

import com.jme3.scene.*;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceMeshTest {
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
