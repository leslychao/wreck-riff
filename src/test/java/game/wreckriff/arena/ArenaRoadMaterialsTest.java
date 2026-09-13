package game.wreckriff.arena;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import game.wreckriff.presentation.SurfaceMesh;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaRoadMaterialsTest {
    @Test void paintAndAsphaltPartitionTheSameTrianglesWithoutOffsetOrExtraFaces() {
        var points=List.of(new ArenaDefinition.Vec3(0,3,0),new ArenaDefinition.Vec3(0,3,10),
                new ArenaDefinition.Vec3(10,3,10),new ArenaDefinition.Vec3(10,3,0));
        var surface=new ArenaDefinition.TriangleSurface("road",points,List.of(0,1,2,0,2,3),"road-surface",true,List.of("road-surface","road-marking"));
        var triangles=surface.indices().stream().map(index->points.get(index).vector()).toList();
        var mesh=SurfaceMesh.triangles(triangles,4);
        var visuals=new ArenaFactory(new DesktopAssetManager(true)).surfaceVisuals(surface,mesh);
        assertEquals(2,visuals.size());assertEquals(2,visuals.stream().mapToInt(g->g.getMesh().getTriangleCount()).sum());
        Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();double area=0;
        for(var visual:visuals) {
            visual.getMesh().getTriangle(0,a,b,c);assertEquals(3,a.y);assertEquals(3,b.y);assertEquals(3,c.y);
            area+=b.subtract(a).cross(c.subtract(a)).length()*.5;
            assertEquals(0,visual.getMaterial().getAdditionalRenderState().getPolyOffsetFactor());
        }
        assertEquals(100,area,.001);
    }
}
