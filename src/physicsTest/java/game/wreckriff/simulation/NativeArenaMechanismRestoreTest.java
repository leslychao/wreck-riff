package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeArenaMechanismRestoreTest {
    private static final VehicleRules RULES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);

    @ParameterizedTest @CsvSource({"euphoria_park,carousel,60","construction_17,crane-1,300"})
    void actualSavedMechanismPoseExistsBeforeSpawnAndSurvivesRuntimeRestorationAndWarmup(String arenaId,String hazardId,int remaining) {
        var arena=ArenaRegistry.load().definition(arenaId);
        var hazard=arena.hazards().stream().filter(h->h.id().equals(hazardId)).findFirst().orElseThrow();
        var session=new MatchSession(42,arena,MatchSession.Mode.CAMPAIGN,COMBAT,UUID.randomUUID(),true,0);
        var initial=new ArenaSystems(session,arena).snapshot();var states=new LinkedHashMap<>(initial.hazards());
        states.put(hazardId,new ProgressStore.HazardState(ProgressStore.HazardPhase.ACTIVE,remaining,0,1));
        var savedArena=new ProgressStore.ArenaState(initial.pickups(),initial.objects(),states,initial.eventCooldownTicks(),initial.randomState());
        var profile=VehicleDefinition.RIVET.profile(RULES);var anchor=arena.nodes().getFirst();
        var position=anchor.position().vector().add(0,profile.roadOffset(),0);
        var pose=new ProgressStore.SafePose(position.x,position.y,position.z,0,anchor.surfaceId(),"node-"+anchor.id());
        var checkpoint=new ProgressStore.Checkpoint(arenaId,"rivet",0,42,"normal",ProgressStore.CheckpointStage.BOSS,
                MatchCheckpoint.player(session.vehicle(0)),pose,savedArena,1000);
        MatchCheckpoint.validateReferences(ArenaRegistry.load(),checkpoint);
        try(var world=new PhysicsWorld(RULES)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            ArenaSystems.restoreGeometry(savedArena,world,content.graph(),arena);
            assertPose(world,hazard);
            int bodies=world.bodyCount();
            ArenaSystems.restoreGeometry(savedArena,world,content.graph(),arena);assertEquals(bodies,world.bodyCount());
            world.addVehicle(0,position,new Quaternion(),profile);
            try(var runtime=new MatchRuntime(session,world,arena,content.graph(),RULES)) {
                runtime.restoreCheckpoint(checkpoint);assertPose(world,hazard);
                for(int step=0;step<360;step++)world.step();
                assertPose(world,hazard);
                assertEquals(savedArena,runtime.arenaSystems().snapshot(),"Suspension warmup cannot spend saved hazard time");
                assertEquals(0,session.tick);assertEquals(0,session.activeTicks);
                assertEquals(4,world.wheelContacts(0));
            }
        }
    }
    private static void assertPose(PhysicsWorld world,ArenaDefinition.Hazard hazard) {
        float x=(hazard.minX()+hazard.maxX())/2,z=(hazard.minZ()+hazard.maxZ())/2;
        String id="mechanism-"+hazard.id();Vector3f from,to,wrongFrom,wrongTo;float expectedX;
        if(hazard.type()==ArenaDefinition.HazardType.CAROUSEL) {
            // Remaining60 of480 means3.5seconds:3.5π, so the17m arm lies along worldZ.
            from=new Vector3f(x-3,1.2f,z+10);to=new Vector3f(x+3,1.2f,z+10);expectedX=x-.65f;
            wrongFrom=new Vector3f(x+10,1.2f,z-3);wrongTo=new Vector3f(x+10,1.2f,z+3);
        } else {
            // Remaining300 of360 is0.5seconds after impact: the load is on the road, not at warning height12m.
            from=new Vector3f(x-6,1.5f,z);to=new Vector3f(x+6,1.5f,z);expectedX=x-4;
            wrongFrom=new Vector3f(x-6,12,z);wrongTo=new Vector3f(x+6,12,z);
        }
        var hit=world.ray(from,to,0);assertNotNull(hit,"Saved moving collider must exist immediately");
        assertEquals(id,hit.objectId());assertEquals(expectedX,hit.point().x,.06f);
        var sweep=world.staticSweep(from,to,.1f);assertNotNull(sweep);assertEquals(id,sweep.objectId());
        var wrong=world.ray(wrongFrom,wrongTo,0);assertTrue(wrong==null||!id.equals(wrong.objectId()),"Collider must not occupy its initial pose");
    }
}
