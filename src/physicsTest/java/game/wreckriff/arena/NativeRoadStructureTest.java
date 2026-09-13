package game.wreckriff.arena;

import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.presentation.SurfaceMesh;
import game.wreckriff.simulation.PhysicsWorld;
import game.wreckriff.simulation.ContactSurface;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRoadStructureTest {
    @Test void nativeTopTriangleAndConcreteShellUseTheirActualVisibleMaterials() {
        var top=new ArenaDefinition.TriangleSurface("material-bridge",List.of(new ArenaDefinition.Vec3(0,6,0),
                new ArenaDefinition.Vec3(0,6,10),new ArenaDefinition.Vec3(20,6,10),new ArenaDefinition.Vec3(20,6,0)),
                List.of(0,1,2,0,2,3),"road-surface",true,List.of("wood","steel"),1.2f);
        var source=ArenaDefinition.load();var meshes=new ArrayList<>(source.meshes());meshes.add(top);
        var arena=new ArenaDefinition(source.schemaVersion(),source.id(),source.metadata(),source.bounds(),source.boxes(),source.ramps(),
                source.spawns(),source.pickups(),source.hazards(),source.nodes(),source.edges(),source.surfaces(),source.launchPads(),
                source.drops(),source.destructibles(),source.secrets(),source.barriers(),source.bosses(),source.layoutRevision(),meshes,source.districts(),source.roads());
        var triangles=new ArrayList<Vector3f>();for(int i:top.indices())triangles.add(top.vertices().get(i).vector());
        triangles.addAll(RoadStructure.shells(List.of(top)).get(top.id()));
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.configureArena(arena);world.addStatic(top.id(),new MeshCollisionShape(SurfaceMesh.triangles(triangles,4)),Vector3f.ZERO,new Quaternion());
            var wood=world.ray(new Vector3f(2,9,8),new Vector3f(2,3,8),-1);
            var steel=world.ray(new Vector3f(18,9,2),new Vector3f(18,3,2),-1);
            assertNotNull(wood);assertNotNull(steel);assertEquals(ContactSurface.WOOD,wood.surface());assertEquals(ContactSurface.METAL,steel.surface());
            assertEquals(ContactSurface.WOOD,world.support(new Vector3f(2,9,8),6).surface());
            assertEquals(ContactSurface.METAL,world.staticSweep(new Vector3f(18,9,2),new Vector3f(18,3,2),.1f).surface());
            var below=world.ray(new Vector3f(10,2,5),new Vector3f(10,9,5),-1);
            assertNotNull(below);assertEquals(4.8f,below.point().y,.002f);assertEquals(ContactSurface.CONCRETE,below.surface());
            var side=world.staticSweep(new Vector3f(-4,5.3f,5),new Vector3f(4,5.3f,5),.1f);
            assertNotNull(side);assertEquals(ContactSurface.CONCRETE,side.surface());
        }
    }
    @Test void projectileSegmentsAndCameraVolumesHitTheSameRoadBottomAndSidesThatAreRendered() {
        var top=new ArenaDefinition.TriangleSurface("bridge",List.of(new ArenaDefinition.Vec3(0,6,0),
                new ArenaDefinition.Vec3(0,6,10),new ArenaDefinition.Vec3(20,6,10),new ArenaDefinition.Vec3(20,6,0)),
                List.of(0,1,2,0,2,3),"concrete",true,List.of(),1.2f);
        var triangles=new ArrayList<Vector3f>();for(int i:top.indices())triangles.add(top.vertices().get(i).vector());
        triangles.addAll(RoadStructure.shells(List.of(top)).get(top.id()));
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addStatic(top.id(),new MeshCollisionShape(SurfaceMesh.triangles(triangles,4)),Vector3f.ZERO,new Quaternion());
            var below=world.ray(new Vector3f(10,2,5),new Vector3f(10,9,5),-1);
            assertNotNull(below);assertEquals("bridge",below.objectId());assertEquals(4.8f,below.point().y,.002f);
            var side=world.ray(new Vector3f(-4,5.3f,5),new Vector3f(4,5.3f,5),-1);
            assertNotNull(side);assertEquals(0,side.point().x,.002f);
            var camera=world.staticSweep(new Vector3f(10,2,5),new Vector3f(10,7,5),.5f);
            assertNotNull(camera);assertTrue(camera.fraction()<.5f,"Camera envelope stops before its near plane enters the visible underside");
            assertNull(world.ray(new Vector3f(-4,4.2f,5),new Vector3f(24,4.2f,5),-1),"Space below the actual structure stays open");
        }
    }
}
