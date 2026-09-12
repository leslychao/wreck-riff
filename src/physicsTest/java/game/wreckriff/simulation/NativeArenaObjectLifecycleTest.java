package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeArenaObjectLifecycleTest {
    private static final VehicleRules RULES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static final ArenaRegistry REGISTRY=ArenaRegistry.load();

    @Test void realRamDestroysTwentyFiveHpPanelAndOpensItsRouteInTheSameTransaction() {
        var arena=REGISTRY.definition("construction_17");
        var object=arena.destructibles().stream().filter(o->o.id().equals("short-cut")).findFirst().orElseThrow();
        assertEquals(25,object.maximumHp());
        var box=box(arena,object);Vector3f center=box.center().vector();
        try(var rig=new Rig(arena,null,center.add(-8,-1.5f,0),FastMath.HALF_PI)) {
            rig.assertClosed(object);
            var support=rig.world.support(center.add(-3,-1.5f,0),2);
            assertNotNull(support);
            String surface=rig.world.ray(center.add(-3,-1.5f,0),center.add(-3,-3,0),-1).objectId();
            long revision=rig.graph.revision();int bodies=rig.world.bodyCount();
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(22,0,0));
            boolean struck=false;
            for(int tick=0;tick<120&&!struck;tick++) {
                rig.world.step();
                for(var contact:rig.world.arenaContacts())if(contact.objectId().equals(object.geometryId())&&contact.vehicleId()==0) {
                    assertTrue(contact.closingSpeed()>18.5f,"Real native impact must reach the common 25 HP ram cap");
                    rig.combat.queueArenaRam(contact.objectId(),0,contact.closingSpeed());
                    rig.combat.queueArenaRam(contact.objectId(),0,contact.closingSpeed());
                    struck=true;
                }
                long fixedTick=rig.session.tick;
                rig.combat.prepareArenaDamage(rig.world);rig.systems.synchronizeGeometry(rig.world,rig.graph);
                if(struck) {
                    rig.assertOpen(object);
                    assertEquals(fixedTick,rig.session.tick,"Collider and route change without waiting for another tick");
                    assertEquals(bodies-1,rig.world.bodyCount());assertEquals(revision+1,rig.graph.revision());
                    assertEquals(support.surfaceId(),rig.world.support(center.add(-3,-1.5f,0),2).surfaceId());
                    assertEquals(surface,rig.world.ray(center.add(-3,-1.5f,0),center.add(-3,-3,0),0).objectId());
                    rig.combat.prepareArenaDamage(rig.world);rig.systems.synchronizeGeometry(rig.world,rig.graph);
                    assertEquals(bodies-1,rig.world.bodyCount());assertEquals(revision+1,rig.graph.revision());
                    assertEquals(1,rig.systems.drainEvents().stream().filter(e->e.type()==GameEvent.Type.ARENA_OBJECT_DESTROYED).count());
                    assertEquals(0,rig.session.vehicle(0).damageDealt,"Environment HP is not vehicle damage credit");
                }
                rig.session.tick++;
            }
            assertTrue(struck,"A moving chassis must actually contact the authored panel");
        }
    }

    @Test void statueRetainsItsColliderForTheFullWarningAndCheckpointPreservesTheRemainingDelay() {
        var arena=REGISTRY.definition("ash_necropolis");
        var statue=arena.destructibles().stream().filter(o->o.effect()==ArenaDefinition.ObjectEffect.STATUE).findFirst().orElseThrow();
        assertEquals(240,statue.delayTicks());
        try(var original=new Rig(arena,null,null,0)) {
            original.combat.queueArenaRam(statue.geometryId(),0,22);
            original.combat.prepareArenaDamage(original.world);original.systems.synchronizeGeometry(original.world,original.graph);
            original.assertClosed(statue);
            for(int elapsed=0;elapsed<=120;elapsed++) {
                original.session.tick=elapsed;original.advanceEnvironment();original.assertClosed(statue);
            }
            var midpoint=original.systems.snapshot();
            assertEquals(120,midpoint.hazards().get(statue.id()).remainingTicks());
            try(var retry=new Rig(arena,midpoint,null,0)) {
                retry.assertClosed(statue);assertEquals(midpoint,retry.systems.snapshot());
                for(int step=0;step<119;step++) {
                    retry.advanceEnvironment();retry.assertClosed(statue);retry.session.tick++;
                }
                assertEquals(1,retry.systems.snapshot().hazards().get(statue.id()).remainingTicks());
                retry.advanceEnvironment();retry.assertOpen(statue);
            }
            for(int elapsed=121;elapsed<240;elapsed++) {
                original.session.tick=elapsed;original.advanceEnvironment();original.assertClosed(statue);
            }
            original.session.tick=240;original.advanceEnvironment();original.assertOpen(statue);
            assertEquals(ProgressStore.HazardPhase.DISABLED,original.systems.snapshot().hazards().get(statue.id()).phase());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void realCraneContactDealsOneHeavyHitToPlayerAndBossWithoutWarningDamage(boolean hitBoss) {
        var arena=REGISTRY.definition("construction_17");
        var hazard=arena.hazards().stream().filter(h->h.id().equals("crane-1")).findFirst().orElseThrow();
        assertEquals(240,hazard.warningTicks());assertEquals(120,hazard.damage());
        var session=new MatchSession(42,arena,MatchSession.Mode.CAMPAIGN,COMBAT,UUID.randomUUID(),true,0);
        var boss=session.registerBoss(arena.bosses().getFirst());session.phase=MatchSession.Phase.BOSS_COMBAT;
        var target=session.vehicle(hitBoss?boss.id:0);var other=session.vehicle(hitBoss?0:boss.id);
        var systems=new ArenaSystems(session,arena);
        var saved=systems.snapshot();var hazards=new LinkedHashMap<>(saved.hazards());
        hazards.put(hazard.id(),new ProgressStore.HazardState(ProgressStore.HazardPhase.WARNING,hazard.warningTicks(),0,1));
        var warning=new ProgressStore.ArenaState(saved.pickups(),saved.objects(),hazards,saved.eventCooldownTicks(),saved.randomState());
        try(var world=new PhysicsWorld(RULES)) {
            world.addStatic("test-road",new com.jme3.bullet.collision.shapes.BoxCollisionShape(new Vector3f(300,.5f,300)),
                    new Vector3f(0,-.5f,0),new Quaternion());
            for(var vehicle:session.vehicles) {
                var profile=vehicle.boss?VehicleProfile.boss(vehicle.profileId,RULES):VehicleDefinition.forId(vehicle.profileId).profile(RULES);
                Vector3f position=vehicle==target?new Vector3f((hazard.minX()+hazard.maxX())/2,profile.roadOffset(),(hazard.minZ()+hazard.maxZ())/2)
                        :new Vector3f(20,profile.roadOffset(),20);
                world.addVehicle(vehicle.id,position,new Quaternion(),profile);
            }
            for(int tick=0;tick<360;tick++)world.step();
            systems.restore(warning,world,new NavGraph(arena));
            var combat=new CombatSystem(session,COMBAT);int[] hits={0};boolean contacted=false,restoredPlayerDamageTimer=false;
            for(int tick=0;tick<hazard.warningTicks()+hazard.activeTicks();tick++) {
                systems.beforePhysics(world,Map.of());world.step();
                boolean active=systems.hazardPhase(hazard.id())==ArenaSystems.HazardPhase.ACTIVE;
                boolean contact=world.arenaContacts().stream().anyMatch(c->c.objectId().equals("mechanism-"+hazard.id())&&c.vehicleId()==target.id);
                contacted|=active&&contact;
                ArenaSystems.DamageSink sink=(id,amount,cause,event)->{
                    assertTrue(active,"The full warning cannot deal damage");
                    assertTrue(world.arenaContacts().stream().anyMatch(c->c.objectId().equals("mechanism-"+hazard.id())&&c.vehicleId()==id),
                            "Heavy damage requires a real native manifold, not zone proximity");
                    assertEquals(target.id,id);assertEquals(120,amount);hits[0]++;
                    combat.queueDamage(id,-1,amount,cause,event);
                };
                systems.updateHazard(world,sink);systems.updateHazard(world,sink);combat.resolveDamage(world);
                if(!hitBoss&&hits[0]==1&&!restoredPlayerDamageTimer) {
                    var checkpoint=systems.snapshot();
                    assertTrue(checkpoint.hazards().get(hazard.id()).cooldownTicks()>0,"Checkpoint retains the player's already applied heavy-hit interval");
                    systems=new ArenaSystems(session,arena);systems.restore(checkpoint,world,new NavGraph(arena));
                    assertEquals(checkpoint,systems.snapshot());restoredPlayerDamageTimer=true;
                }
                if(!active&&!contacted)assertEquals(target.maximumHp,target.hp);
                assertEquals(other.maximumHp,other.hp,"The other car outside the physical load remains unharmed");
                session.tick++;
            }
            assertTrue(contacted,"The descending crane must actually hit the selected chassis");
            assertEquals(1,hits[0],"A heavy drop damages each car once despite persistent native contacts and duplicate consumers");
            assertEquals(target.maximumHp-120,target.hp,hitBoss?"Bosses have no environment immunity":"Player takes the same configured heavy hit");
            assertEquals(0,world.teleportGeneration(target.id));
        }
    }

    private static ArenaDefinition.BoxPart box(ArenaDefinition arena,ArenaDefinition.Destructible object) {
        return arena.boxes().stream().filter(b->b.id().equals(object.geometryId())).findFirst().orElseThrow();
    }
    private static final class Rig implements AutoCloseable {
        final ArenaDefinition arena;
        final MatchSession session;
        final PhysicsWorld world=new PhysicsWorld(RULES);
        final ArenaSystems systems;
        final CombatSystem combat;
        final NavGraph graph;
        Rig(ArenaDefinition arena,ProgressStore.ArenaState state,Vector3f position,float yaw) {
            this.arena=arena;
            session=new MatchSession(42,arena,MatchSession.Mode.ARENA,COMBAT,UUID.randomUUID(),true,0);
            session.phase=MatchSession.Phase.ARENA_COMBAT;
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);graph=content.graph();
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            if(state!=null)ArenaSystems.restoreGeometry(state,world,graph,arena);
            world.configureArena(arena);
            var profile=VehicleDefinition.RIVET.profile(RULES);
            if(position==null)position=arena.spawns().getFirst().position().vector().add(0,profile.roadOffset(),0);
            world.addVehicle(0,position,new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y),profile);
            systems=new ArenaSystems(session,arena);
            if(state!=null)systems.restore(state,world,graph);
            combat=new CombatSystem(session,COMBAT);combat.configureArenaDamage(systems::damageTargets,systems::damageObject);
            for(int tick=0;tick<360;tick++)world.step();
        }
        void advanceEnvironment() {systems.beforePhysics(world,Map.of());world.step();systems.synchronizeGeometry(world,graph);}
        void assertClosed(ArenaDefinition.Destructible object) {
            var center=box(arena,object).center().vector();
            var ray=world.ray(center.add(-2,0,0),center.add(2,0,0),0);
            assertNotNull(ray);assertEquals(object.geometryId(),ray.objectId());
            assertEquals(object.geometryId(),world.staticSweep(center.add(-2,0,0),center.add(2,0,0),.1f).objectId());
            assertFalse(graph.isOpen(object.id()));
        }
        void assertOpen(ArenaDefinition.Destructible object) {
            var center=box(arena,object).center().vector();
            assertNull(world.ray(center.add(-2,0,0),center.add(2,0,0),0));
            assertNull(world.staticSweep(center.add(-2,0,0),center.add(2,0,0),.1f));
            assertTrue(graph.isOpen(object.id()));
        }
        @Override public void close() {world.close();}
    }
}
