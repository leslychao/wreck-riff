package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.scene.*;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VehicleVisualTest {
    @Test void allFiveRivetsHaveCompleteSilhouettesIndependentWheelsAndStayWithinBudget() {
        var assets=new DesktopAssetManager(true);
        for(int livery=0;livery<5;livery++) {
            Node car=VehicleVisual.create(assets,livery);
            for(int i=0;i<4;i++) assertInstanceOf(Node.class,car.getChild("wheel-"+i));
            assertNotNull(car.getChild("glass")); assertNotNull(car.getChild("headlights"));
            assertNotNull(car.getChild("taillights")); assertNotNull(car.getChild("livery-markings"));
            assertNotNull(car.getChild("exhaust-left")); assertNotNull(car.getChild("exhaust-right"));
            int[] triangles={0},draws={0};
            car.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
                Mesh mesh=geometry.getMesh();triangles[0]+=mesh.getTriangleCount();draws[0]++;
                FloatBuffer positions=(FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
                for(int i=0;i<positions.limit();i++) assertTrue(Float.isFinite(positions.get(i)));
                for(var type:List.of(VertexBuffer.Type.Normal,VertexBuffer.Type.TexCoord,VertexBuffer.Type.Tangent)) {
                    assertNotNull(mesh.getBuffer(type),"Textured vehicle mesh requires "+type);
                    FloatBuffer data=(FloatBuffer)mesh.getBuffer(type).getData();
                    for(int i=0;i<data.limit();i++)assertTrue(Float.isFinite(data.get(i)),"Finite normal-map basis required");
                }
                if(geometry.getName().equals("paint")) {
                    assertNotNull(geometry.getMaterial().getParam("DiffuseMap"));
                    assertNotNull(geometry.getMaterial().getParam("NormalMap"));
                    assertNotNull(geometry.getMaterial().getParam("SpecularMap"));
                }
            }});
            assertTrue(triangles[0]>800,"Complete detailed authored car required");
            assertTrue(triangles[0]<=8000,"Per-vehicle triangle budget: "+triangles[0]);
            assertTrue(draws[0]<=16,"Batch decorative primitives by material: "+draws[0]);
            assertEquals("original-java-procedural",car.getUserData("assetOrigin"));
        }
    }
}
