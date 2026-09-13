package game.wreckriff.simulation;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCheckpointRosterPlacementTest {
    private static final VehicleRules VEHICLES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static final ArenaRegistry REGISTRY=ArenaRegistry.load();

    @ParameterizedTest @CsvSource({"construction_17,rivet","construction_17,grinder","construction_17,spark",
            "neon_zero,rivet","neon_zero,grinder","neon_zero,spark","euphoria_park,rivet","euphoria_park,grinder","euphoria_park,spark"})
    void migratedArenaCheckpointReservesItsRoadPoseBeforePlacingTheEntireShuffledRoster(String arenaId,String profileId) {
        var arena=REGISTRY.definition(arenaId);ArenaContent content=null;
        boolean exercisedConflict=false;
        for(long seed:new long[]{0,7,42}) {
            var authoredOrder=MatchSpawns.ordered(arena,seed);
            assertEquals(arena.spawns().getFirst(),authoredOrder.getFirst(),"Live campaign ordering preserves the safe authored first-contact start");
            assertEquals(Set.copyOf(arena.spawns()),Set.copyOf(authoredOrder));
            var session=new MatchSession(seed,arena,MatchSession.Mode.CAMPAIGN,COMBAT,UUID.randomUUID(),false,2,profileId);
            session.vehicle(0).hp=300;
            var old=new ProgressStore.Checkpoint(arenaId,1,profileId,2,seed,"normal",ProgressStore.CheckpointStage.ARENA,
                    MatchCheckpoint.player(session.vehicle(0)),new ProgressStore.SafePose(12,1.25,14,.2,"old-road","old-spawn"),
                    new ProgressStore.ArenaState(Map.of(),Map.of(),Map.of(),0,71),720);
            var checkpoint=MatchCheckpoint.rebase(REGISTRY,old);
            try(var world=new PhysicsWorld(VEHICLES)) {
                // Construct native shapes only after this test's PhysicsWorld loads Libbulletjme.
                if(content==null)content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
                world.configureArena(arena);
                for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
                var spawns=arena.shuffledSpawns(seed);var starts=new HashMap<Integer,Vector3f>();
                for(var state:session.vehicles) {
                    var profile=VehicleDefinition.forId(state.profileId).profile(VEHICLES);
                    var nominal=spawns.get(state.id).position().vector();
                    if(state.id>0&&nominal.subtract(starts.get(0)).setY(0).length()<1)exercisedConflict=true;
                    var selected=MatchSpawns.select(world,profile,spawns,state.id,checkpoint);
                    assertTrue(world.freeSpawnPose(profile,selected.position(),selected.rotation()),arenaId+" seed="+seed+" participant="+state.id);
                    if(state.player)assertEquals(new Vector3f((float)checkpoint.safePose().x(),(float)checkpoint.safePose().y(),
                            (float)checkpoint.safePose().z()),selected.position());
                    world.addVehicle(state.id,selected.position(),selected.rotation(),profile);starts.put(state.id,selected.position().clone());
                }
                assertEquals(arena.metadata().normalEnemies()+1,session.vehicles.size());
                for(int tick=0;tick<180;tick++)world.step();
                for(var state:session.vehicles) {
                    assertEquals(4,world.wheelContacts(state.id),arenaId+" seed="+seed+" participant="+state.id);
                    assertTrue(world.position(state.id).subtract(starts.get(state.id)).setY(0).length()<.5f,
                            "Initial spawn may settle vertically but cannot be pushed out of an overlapping roster: "+arenaId+" seed="+seed+" participant="+state.id+" start="+starts.get(state.id)+" actual="+world.position(state.id));
                }
                assertEquals(checkpoint.player(),MatchCheckpoint.player(session.vehicle(0)));
            }
        }
        assertTrue(exercisedConflict,"At least one shuffled opponent must claim the player's relocated authored spawn");
    }
    @Test void exhaustedSpawnCandidatesFailWithoutInstallingAnOverlappingBody() {
        var profile=VehicleDefinition.RIVET.profile(VEHICLES);
        var spawn=new ArenaDefinition.Spawn(0,new ArenaDefinition.Vec3(0,0,0),0);
        try(var world=new PhysicsWorld(VEHICLES)) {
            var first=MatchSpawns.select(world,profile,List.of(spawn),0,null);
            world.addVehicle(0,first.position(),first.rotation(),profile);
            int bodies=world.bodyCount();
            assertThrows(IllegalArgumentException.class,()->MatchSpawns.select(world,profile,List.of(spawn),1,null));
            assertEquals(bodies,world.bodyCount());
        }
    }
}
