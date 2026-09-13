package game.wreckriff.simulation;

import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.config.VehicleRules;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(game.wreckriff.arena.NativeArenaAssets.class)
class NativeArenaMarkingTest {
    static Stream<String> arenaIds() {return ArenaRegistry.load().entries().stream().map(ArenaRegistry.Entry::id);}
    // This test changes the entire world between cases. Its texture cache must
    // end with that world, before the next map's first 2K image is decoded.
    @AfterEach void releaseCompletedArena() {NativeArenaAssets.MANAGER.clearCache();}
    @ParameterizedTest @MethodSource("arenaIds")
    void staticRoadPaintSharesAtMostThreeDrawsWithoutChangingCollisionOrHazardFootprints(String arenaId) {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            var arena=ArenaRegistry.load().definition(arenaId);
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            Node paint=(Node)content.visual().getChild("static-road-markings");assertNotNull(paint,arenaId);
            content.visual().updateGeometricState();
            assertEquals(RenderQueue.ShadowMode.Off,paint.getShadowMode());
            assertEquals(arena.boxes().stream().filter(ArenaDefinition.BoxPart::collision).count()+arena.ramps().size()
                    +arena.meshes().stream().filter(ArenaDefinition.TriangleSurface::collision).count()
                    +game.wreckriff.presentation.ArenaArt.load(arena).models().stream().filter(m->!m.collisionGeometryIds().isEmpty()).count(),content.bodies().size());
            int[] draws={0};
            paint.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
                draws[0]++;assertEquals(0,geometry.getNumControls());
                var positions=geometry.getMesh().getFloatBuffer(VertexBuffer.Type.Position);
                for(int i=0;i<positions.limit();i+=3) {
                    Vector3f p=geometry.localToWorld(new Vector3f(positions.get(i),positions.get(i+1),positions.get(i+2)),null);
                    assertTrue(Vector3f.isValidVector(p));
                    if(!arena.id().equals("dead-air-yard"))assertTrue(arena.hazards().stream().anyMatch(h->
                            p.x>=h.minX()-.001f&&p.x<=h.maxX()+.001f&&p.z>=h.minZ()-.001f&&p.z<=h.maxZ()+.001f),
                            arenaId+" painted geometry moved outside its authoritative hazard: "+p);
                }
            }});
            assertTrue(draws[0]>=1&&draws[0]<=3,arenaId+" paint draw budget: "+draws[0]);
            assertEquals(0,world.bodyCount(),"Road paint cannot register any additional physical obstacle");
        }
    }
}
