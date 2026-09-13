package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ArenaSurfaceSeamTest {
    @Test void paintedFacesKeepOneContinuousRoadSurfaceWithoutOverlayGeometry() {
        var vertices=List.of(new ArenaDefinition.Vec3(-4,0,-4),new ArenaDefinition.Vec3(4,0,-4),
                new ArenaDefinition.Vec3(4,0,4),new ArenaDefinition.Vec3(-4,0,4));
        var indices=List.of(0,2,1,0,3,2);
        var road=new ArenaDefinition.TriangleSurface("painted-road",vertices,indices,"road-surface",true,
                List.of("road-marking","road-surface"));
        assertEquals("road-marking",road.materialAtTriangle(0));
        assertEquals("road-surface",road.materialAtTriangle(1));
        assertEquals(0,road.heightAt(0,0));
        assertTrue(road.containsXZ(0,0,3),"The paint boundary cannot become a physical seam");
        assertThrows(IllegalArgumentException.class,()->new ArenaDefinition.TriangleSurface("bad",vertices,indices,
                "road-surface",true,List.of("road-marking")));
    }
    @Test void seamKeepsTheFirstAuthoredSurfaceAndItsNavigationAnchorIncludingExactTolerance() {
        var arena=seam(20);
        for(float offset:new float[]{0,.125f}) {
            var surface=arena.surfaceAt(new Vector3f(0,20+offset,0),0,offset).orElseThrow();
            assertEquals("seam-left",surface.id());
            assertTrue(arena.nodes().stream().anyMatch(node->node.surfaceId().equals(surface.id())&&node.id()==10000));
        }
    }
    @Test void aCloserSupportStillWinsOverTheEarlierAuthoredSurface() {
        assertEquals("seam-right",seam(20.0625f).surfaceAt(new Vector3f(0,20.0625f,0),0,.125f).orElseThrow().id());
    }
    @Test void subMillimetreInterpolationNoiseKeepsTheFirstSurfaceWithoutExpandingTheHeightTolerance() {
        var arena=seam(20.00005f);
        assertEquals("seam-left",arena.surfaceAt(new Vector3f(0,20.00005f,0),0,.125f).orElseThrow().id());
        assertEquals("seam-right",arena.surfaceAt(new Vector3f(0,20.00005f,0),0,0).orElseThrow().id(),
                "The tie epsilon cannot admit an initial support outside the caller's tolerance");
    }
    @Test void groundTunnelJunctionDoesNotChangeFloorForOneMicrometreOfRayInterpolationNoise() {
        var arena=ArenaRegistry.load().definition("neon_zero");
        for(float height:new float[]{0,9.536743E-7f}) {
            var surface=arena.surfaceAt(new Vector3f(1190,height,400),0,.15f).orElseThrow();
            assertEquals("road-south-avenue-2",surface.id());assertEquals(0,surface.level());
        }
        var underground=arena.surfaceAt(new Vector3f(1190,-12,680),0,.15f).orElseThrow();
        assertEquals(-1,underground.level(),"A real twelve-metre floor difference remains authoritative");
    }
    @Test void campaignStartsResolveToTheirAuthoredNavigationSurfaceAtRoadJunctions() {
        var registry=ArenaRegistry.load();
        for(var id:registry.campaignIds()) {
            var arena=registry.definition(id);var start=arena.spawns().getFirst().position().vector();
            var surface=arena.surfaceAt(start,0,.2f).orElseThrow();
            assertTrue(arena.nodes().stream().anyMatch(node->node.surfaceId().equals(surface.id())&&node.position().vector().distance(start)<.01f),id+"/"+surface.id());
        }
    }
    private static ArenaDefinition seam(float rightHeight) {
        var tree=Configs.gson().toJsonTree(ArenaDefinition.load()).getAsJsonObject();
        var left=new ArenaDefinition.TriangleSurface("seam-left",List.of(
                new ArenaDefinition.Vec3(-10,20,-5),new ArenaDefinition.Vec3(0,20,-5),
                new ArenaDefinition.Vec3(0,20,5),new ArenaDefinition.Vec3(-10,20,5)),List.of(0,2,1,0,3,2),"concrete",true);
        var right=new ArenaDefinition.TriangleSurface("seam-right",List.of(
                new ArenaDefinition.Vec3(0,rightHeight,-5),new ArenaDefinition.Vec3(10,rightHeight,-5),
                new ArenaDefinition.Vec3(10,rightHeight,5),new ArenaDefinition.Vec3(0,rightHeight,5)),List.of(0,2,1,0,3,2),"concrete",true);
        for(var mesh:List.of(left,right)) {
            tree.getAsJsonArray("meshes").add(Configs.gson().toJsonTree(mesh));
            tree.getAsJsonArray("surfaces").add(Configs.gson().toJsonTree(new ArenaDefinition.Surface(mesh.id(),mesh.id(),1,1)));
        }
        tree.getAsJsonArray("nodes").add(Configs.gson().toJsonTree(new ArenaDefinition.NavNode(10000,new ArenaDefinition.Vec3(0,20,0),"seam-left")));
        return Configs.gson().fromJson(tree,ArenaDefinition.class);
    }
}
