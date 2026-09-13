package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCampaignCheckpointTest {
    private static final VehicleRules RULES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static ArenaRegistry registryFor(String arenaId) {
        var catalogue=Configs.load("arenas",ArenaRegistry.Catalogue.class);
        return new ArenaRegistry(new ArenaRegistry.Catalogue(catalogue.schemaVersion(),
                catalogue.entries().stream().filter(entry->entry.id().equals(arenaId)).toList()));
    }

    @ParameterizedTest @ValueSource(strings={"construction_17","neon_zero","euphoria_park"})
    void bossRetryRestoresAnActualRoadPoseAndResourcesIntoACleanNativeAttempt(String arenaId) {
        var registry=registryFor(arenaId);var arena=registry.definition(arenaId);ProgressStore.Checkpoint checkpoint;
        try(var original=new Rig(arena,null)) {
            original.runtime.skipIntro();
            for(int tick=0;tick<250;tick++)original.runtime.tick(Map.of(),false);
            var player=original.session.vehicle(0);player.hp=100;player.turbo=29;
            player.weapon(WeaponType.CANNON).ammo=2;player.weapon(WeaponType.HOMING).ammo=3;player.weapon(WeaponType.HOMING).cooldownTicks=73;
            player.abilityCooldown(AbilityId.SHIELD,91);player.selectedWeapon=WeaponType.CANNON;
            for(var rival:original.session.vehicles)if(!rival.player)
                original.runtime.combat().queueDamage(rival.id,0,100000,"fixture",80000+rival.id);
            long repairTick=original.session.tick;
            var transitionEvents=original.runtime.tick(Map.of(),false);
            assertEquals(MatchSession.Phase.BOSS_ENTRY,original.session.phase);assertEquals(520,player.hp);
            var repairs=transitionEvents.stream().filter(event->event.type()==GameEvent.Type.REPAIRED).toList();
            assertEquals(1,repairs.size());var repair=repairs.getFirst();
            assertEquals("pre-boss-repair",repair.kind());assertEquals(new HealthChange(100,520),repair.healthChange());
            assertEquals(420,repair.value());assertEquals(0,repair.subjectId());assertEquals(0,repair.sourceId());
            assertEquals(original.session.sessionId,repair.sessionId());assertEquals(repairTick,repair.simulationTick());
            assertEquals(transitionEvents.indexOf(repair),repair.ordinalWithinTick());
            assertEquals(original.world.position(0),repair.position());
            Vector3f position=original.world.position(0);long generation=original.world.teleportGeneration(0);
            checkpoint=original.runtime.checkpoint(ProgressStore.CheckpointStage.BOSS);
            MatchCheckpoint.validateReferences(registry,checkpoint);
            assertEquals(position,original.world.position(0));assertEquals(generation,original.world.teleportGeneration(0));
            assertEquals(MatchCheckpoint.player(player),checkpoint.player());
            assertEquals(2,player.weapon(WeaponType.CANNON).ammo,"A continuous boss entry preserves collected ammunition");
            var pickupTimers=new LinkedHashMap<String,ProgressStore.PickupState>();
            arena.pickups().forEach(p->pickupTimers.put(p.id(),new ProgressStore.PickupState(700)));
            var savedArena=checkpoint.arena();
            checkpoint=new ProgressStore.Checkpoint(checkpoint.arenaId(),checkpoint.layoutRevision(),checkpoint.profileId(),checkpoint.liveryId(),
                    checkpoint.seed(),checkpoint.difficulty(),checkpoint.stage(),checkpoint.player(),checkpoint.safePose(),
                    new ProgressStore.ArenaState(pickupTimers,savedArena.objects(),savedArena.hazards(),savedArena.eventCooldownTicks(),savedArena.randomState()),checkpoint.activeTicksBeforeBoss());
        }
        try(var retry=new Rig(arena,checkpoint)) {
            assertEquals(1,retry.session.vehicles.size());assertEquals(0,retry.session.activeTicks);
            assertEquals(checkpoint.player().withoutAmmunition(),MatchCheckpoint.player(retry.session.vehicle(0)));
            var restoredArena=retry.runtime.arenaSystems().snapshot();
            assertEquals(checkpoint.arena().objects(),restoredArena.objects());assertEquals(checkpoint.arena().hazards(),restoredArena.hazards());
            for(var pickup:arena.pickups()) {
                boolean ammo=pickup.type()!=ArenaDefinition.PickupType.REPAIR&&pickup.type()!=ArenaDefinition.PickupType.TURBO_CELL;
                assertEquals(ammo?0:700,restoredArena.pickups().get(pickup.id()).respawnTicks());
            }
            assertEquals(4,retry.world.wheelContacts(0));
            assertTrue(retry.runtime.combat().projectiles().isEmpty());assertTrue(retry.runtime.combat().mines().isEmpty());
            assertTrue(retry.runtime.combat().fireZones().isEmpty());assertFalse(retry.runtime.hasWrecks());
            var retryEvents=retry.runtime.tick(Map.of(),false);
            assertTrue(retryEvents.stream().noneMatch(event->event.type()==GameEvent.Type.REPAIRED),
                    "Restoring a checkpoint must not apply or present another pre-boss repair");
            assertTrue(retry.session.bossParticipantId>=0,"At least one specified boss entrance must be free");
            assertEquals(arena.bosses().getFirst().maximumHp(),retry.session.vehicle(retry.session.bossParticipantId).hp);
            assertTrue(retry.session.vehicle(retry.session.bossParticipantId).weapons().stream().allMatch(slot->slot.ammo==0));
            assertEquals(MatchSession.Mode.CAMPAIGN,retry.session.mode);
        }
    }
    @ParameterizedTest @CsvSource({"construction_17,rivet","construction_17,grinder","construction_17,spark",
            "neon_zero,rivet","neon_zero,grinder","neon_zero,spark","euphoria_park,rivet","euphoria_park,grinder","euphoria_park,spark"})
    void migratedBossCheckpointHasClearNativeSupportForEveryChassis(String arenaId,String profileId) {
        var registry=registryFor(arenaId);var arena=registry.definition(arenaId);
        var source=new MatchSession(42,arena,MatchSession.Mode.CAMPAIGN,COMBAT,UUID.randomUUID(),true,2,profileId);
        source.vehicle(0).hp=300;source.vehicle(0).turbo=37;
        var old=new ProgressStore.Checkpoint(arenaId,1,profileId,2,42,"normal",ProgressStore.CheckpointStage.BOSS,
                MatchCheckpoint.player(source.vehicle(0)),new ProgressStore.SafePose(12,1.25,14,.2,"old-road","old-spawn"),
                new ProgressStore.ArenaState(Map.of(),Map.of(),Map.of(),0,71),720);
        var checkpoint=MatchCheckpoint.rebase(registry,old);
        try(var restored=new Rig(arena,checkpoint)) {
            assertEquals(profileId,restored.session.vehicle(0).profileId);assertEquals(2,restored.session.vehicle(0).liveryId);
            assertEquals(1,restored.session.vehicles.size());assertEquals(checkpoint.player(),MatchCheckpoint.player(restored.session.vehicle(0)));
            assertEquals(4,restored.world.wheelContacts(0));
            assertTrue(restored.world.rotation(0).mult(Vector3f.UNIT_Y).y>.98f);
            var pose=checkpoint.safePose();var position=restored.world.position(0);
            assertTrue(Math.abs(position.x-pose.x())<.15&&Math.abs(position.z-pose.z())<.15,"Migrated start must not be pushed out of geometry");
            restored.runtime.tick(Map.of(),false);assertTrue(restored.session.bossParticipantId>=0);
        }
    }
    private static final class Rig implements AutoCloseable {
        final MatchSession session;
        final PhysicsWorld world=new PhysicsWorld(RULES);
        final MatchRuntime runtime;
        Rig(ArenaDefinition arena,ProgressStore.Checkpoint checkpoint) {
            session=new MatchSession(42,arena,MatchSession.Mode.CAMPAIGN,COMBAT,UUID.randomUUID(),checkpoint!=null,
                    checkpoint==null?0:checkpoint.liveryId(),checkpoint==null?"rivet":checkpoint.profileId());
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
