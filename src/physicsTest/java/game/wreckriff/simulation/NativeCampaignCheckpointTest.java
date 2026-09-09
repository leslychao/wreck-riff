package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCampaignCheckpointTest {
    private static final VehicleRules RULES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static final ArenaRegistry REGISTRY=ArenaRegistry.load();

    @ParameterizedTest @ValueSource(strings={"construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena"})
    void bossRetryRestoresAnActualRoadPoseAndResourcesIntoACleanNativeAttempt(String arenaId) {
        var arena=REGISTRY.definition(arenaId);ProgressStore.Checkpoint checkpoint;
        try(var original=new Rig(arena,null)) {
            original.runtime.skipIntro();
            for(int tick=0;tick<250;tick++)original.runtime.tick(Map.of(),false);
            var player=original.session.vehicle(0);player.hp=100;player.turbo=29;
            player.weapon(WeaponType.CANNON).ammo=0;player.weapon(WeaponType.HOMING).cooldownTicks=73;
            player.abilityCooldown(AbilityId.SHIELD,91);player.selectedWeapon=WeaponType.CANNON;
            for(var rival:original.session.vehicles)if(!rival.player)
                original.runtime.combat().queueDamage(rival.id,0,100000,"fixture",80000+rival.id);
            original.runtime.tick(Map.of(),false);
            assertEquals(MatchSession.Phase.BOSS_ENTRY,original.session.phase);assertEquals(520,player.hp);
            Vector3f position=original.world.position(0);long generation=original.world.teleportGeneration(0);
            checkpoint=original.runtime.checkpoint(ProgressStore.CheckpointStage.BOSS);
            MatchCheckpoint.validateReferences(REGISTRY,checkpoint);
            assertEquals(position,original.world.position(0));assertEquals(generation,original.world.teleportGeneration(0));
            assertEquals(MatchCheckpoint.player(player),checkpoint.player());
        }
        try(var retry=new Rig(arena,checkpoint)) {
            assertEquals(1,retry.session.vehicles.size());assertEquals(0,retry.session.activeTicks);
            assertEquals(checkpoint.player(),MatchCheckpoint.player(retry.session.vehicle(0)));
            assertEquals(checkpoint.arena(),retry.runtime.arenaSystems().snapshot());
            assertEquals(4,retry.world.wheelContacts(0));
            assertTrue(retry.runtime.combat().projectiles().isEmpty());assertTrue(retry.runtime.combat().mines().isEmpty());
            assertTrue(retry.runtime.combat().fireZones().isEmpty());assertFalse(retry.runtime.hasWrecks());
            retry.runtime.tick(Map.of(),false);
            assertTrue(retry.session.bossParticipantId>=0,"At least one specified boss entrance must be free");
            assertEquals(arena.bosses().getFirst().maximumHp(),retry.session.vehicle(retry.session.bossParticipantId).hp);
            assertEquals(MatchSession.Mode.CAMPAIGN,retry.session.mode);
        }
    }
    private static final class Rig implements AutoCloseable {
        final MatchSession session;
        final PhysicsWorld world=new PhysicsWorld(RULES);
        final MatchRuntime runtime;
        Rig(ArenaDefinition arena,ProgressStore.Checkpoint checkpoint) {
            session=new MatchSession(42,arena,MatchSession.Mode.CAMPAIGN,COMBAT,UUID.randomUUID(),checkpoint!=null,0);
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            if(checkpoint!=null)ArenaSystems.restoreGeometry(checkpoint.arena(),world,content.graph(),arena);
            world.configureArena(arena);
            for(var state:session.vehicles) {
                var profile=VehicleDefinition.forId(state.profileId).profile(RULES);
                var spawn=arena.spawns().get(state.id);
                Vector3f position=spawn.position().vector().add(0,profile.roadOffset(),0);
                float yaw=spawn.yawDegrees()*FastMath.DEG_TO_RAD;
                if(checkpoint!=null) {
                    var pose=checkpoint.safePose();position.set((float)pose.x(),(float)pose.y(),(float)pose.z());yaw=(float)pose.yaw();
                }
                world.addVehicle(state.id,position,new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y),profile);
            }
            runtime=new MatchRuntime(session,world,arena,content.graph(),RULES);
            if(checkpoint!=null)runtime.restoreCheckpoint(checkpoint);
            for(int tick=0;tick<360;tick++)world.step();
            runtime.drivers().values().forEach(driver->driver.recordSafePose(0));
        }
        @Override public void close() {runtime.close();}
    }
}
