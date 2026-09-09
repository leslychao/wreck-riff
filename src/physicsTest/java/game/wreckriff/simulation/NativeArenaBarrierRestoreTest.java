package game.wreckriff.simulation;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class NativeArenaBarrierRestoreTest {
    @Test void savedActiveBarrierBlocksTheNativeWorldBeforeParticipantsArePlaced() {
        var arena=ArenaRegistry.load().definition("neon_zero");
        var session=new MatchSession(42,arena,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
        var systems=new ArenaSystems(session,arena);
        var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
        var barrier=arena.barriers().getFirst();
        var box=arena.boxes().stream().filter(b->b.id().equals(barrier.geometryId())).findFirst().orElseThrow();
        var initial=systems.snapshot();var hazards=new LinkedHashMap<>(initial.hazards());
        hazards.put(barrier.id(),new ProgressStore.HazardState(ProgressStore.HazardPhase.ACTIVE,barrier.activeTicks()/2,0,1));
        var checkpoint=new ProgressStore.ArenaState(initial.pickups(),initial.objects(),hazards,initial.eventCooldownTicks(),initial.randomState());
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            Vector3f center=box.center().vector();
            Vector3f from=center.add(-3,0,0),to=center.add(3,0,0);
            ArenaSystems.restoreGeometry(checkpoint,world,content.graph(),arena);
            assertFalse(content.graph().isOpen(barrier.id()));
            var hit=world.ray(from,to,-1);
            assertNotNull(hit,"Saved closed route must be physically blocked before player spawn clearance is tested");
            assertEquals(barrier.geometryId(),hit.objectId());
            assertEquals(barrier.geometryId(),world.staticSweep(from,to,.1f).objectId());
            int bodies=world.bodyCount();
            ArenaSystems.restoreGeometry(checkpoint,world,content.graph(),arena);
            assertEquals(bodies,world.bodyCount(),"Restoration is idempotent");
            systems.restore(checkpoint,world,content.graph());
            systems.synchronizeGeometry(world,content.graph());
            assertEquals(bodies,world.bodyCount(),"The live schedule adopts the restored native barrier");
            assertEquals(checkpoint.hazards(),systems.snapshot().hazards());
        }
    }
}
