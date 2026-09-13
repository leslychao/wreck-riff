package game.wreckriff.arena;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.simulation.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeRotatedBarrierTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void scheduledAndCheckpointRestoredBarriersUseTheirAuthoredYawThenFullyRetract(boolean restoreActive) {
        try(var rig=new Rig(false)) {
            var phase=restoreActive?ProgressStore.HazardPhase.ACTIVE:ProgressStore.HazardPhase.WARNING;
            rig.restore(phase,restoreActive?rig.barrier.activeTicks():1);
            rig.systems.beforePhysics(rig.world,Map.of());rig.world.step();rig.systems.synchronizeGeometry(rig.world,rig.graph);
            assertEquals(ArenaSystems.HazardPhase.ACTIVE,rig.systems.barrierPhase(rig.barrier.id()));
            assertTrue(rig.world.containsArenaBody(rig.box.id()));
            Vector3f c=rig.box.center().vector();
            var acrossWideAxis=rig.world.ray(c.add(8,0,-4),c.add(8,0,4),0);
            assertNotNull(acrossWideAxis,"A quarter-turn barrier extends across the road in X");
            assertEquals(rig.box.id(),acrossWideAxis.objectId());
            var sweep=rig.world.staticSweep(c.add(8,0,-4),c.add(8,0,4),.1f);
            assertNotNull(sweep);assertEquals(rig.box.id(),sweep.objectId());
            assertNull(rig.world.ray(c.add(-4,0,8),c.add(4,0,8),0),"The unrotated long axis must remain clear");
            rig.restore(ProgressStore.HazardPhase.ACTIVE,1);rig.session.tick++;
            rig.systems.beforePhysics(rig.world,Map.of());rig.world.step();rig.systems.synchronizeGeometry(rig.world,rig.graph);
            assertEquals(ArenaSystems.HazardPhase.OFF,rig.systems.barrierPhase(rig.barrier.id()));
            assertFalse(rig.world.containsArenaBody(rig.box.id()));
            assertNull(rig.world.ray(c.add(8,0,-4),c.add(8,0,4),0));
            assertTrue(rig.graph.isOpen(rig.barrier.id()));
        }
    }
    @Test void carInsideTheRotatedFootprintCancelsTheRiseEvenOutsideItsUnrotatedBounds() {
        try(var rig=new Rig(true)) {
            rig.restore(ProgressStore.HazardPhase.WARNING,1);
            rig.systems.beforePhysics(rig.world,Map.of());rig.world.step();
            assertEquals(ArenaSystems.HazardPhase.OFF,rig.systems.barrierPhase(rig.barrier.id()));
            assertFalse(rig.world.containsArenaBody(rig.box.id()),"Clearance must use the oriented world's footprint before spawning a collider");
        }
    }
    private static final class Rig implements AutoCloseable {
        final ArenaDefinition arena=ArenaRegistry.load().definition("neon_zero");
        final ArenaDefinition.Barrier barrier=arena.barriers().getFirst();
        final ArenaDefinition.BoxPart box=arena.boxes().stream().filter(b->b.id().equals(barrier.geometryId())).findFirst().orElseThrow();
        final VehicleRules rules=VehicleRules.load();
        final MatchSession session=new MatchSession(42,arena,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class),UUID.randomUUID(),true,0);
        final ArenaSystems systems=new ArenaSystems(session,arena);
        final PhysicsWorld world=new PhysicsWorld(rules);
        final NavGraph graph=new NavGraph(arena);
        Rig(boolean occupied) {
            assertEquals(-90,box.yawDegrees(),.001f);session.phase=MatchSession.Phase.ARENA_COMBAT;
            world.configureArena(arena);Vector3f c=box.center().vector();float floor=c.y-box.size().y()/2;
            world.addStatic("test-floor",new BoxCollisionShape(new Vector3f(50,.5f,50)),new Vector3f(c.x,floor-.5f,c.z),new Quaternion());
            var profile=VehicleProfile.rivet();world.addVehicle(0,new Vector3f(c.x+(occupied?8:30),floor+profile.roadOffset(),c.z),new Quaternion(),profile);
        }
        void restore(ProgressStore.HazardPhase phase,long ticks) {
            var saved=systems.snapshot();var hazards=new LinkedHashMap<>(saved.hazards());
            hazards.put(barrier.id(),new ProgressStore.HazardState(phase,ticks,0,1));
            systems.restore(new ProgressStore.ArenaState(saved.pickups(),saved.objects(),hazards,1680,saved.randomState()),world,graph);
        }
        @Override public void close(){world.close();}
    }
}
