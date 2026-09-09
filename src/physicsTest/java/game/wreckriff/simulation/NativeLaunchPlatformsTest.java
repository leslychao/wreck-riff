package game.wreckriff.simulation;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Full native colliders, four raycast wheels, the shipped roads, and the real fixed-step accumulator. */
class NativeLaunchPlatformsTest {
    private static final VehicleRules RULES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static final ArenaRegistry REGISTRY=ArenaRegistry.load();
    private static final Map<String,ArenaContent> CONTENT=new HashMap<>();

    static Stream<Arguments> launches() {
        List<Arguments> rows=new ArrayList<>();
        for(String arena:REGISTRY.campaignIds())for(int pad=0;pad<2;pad++)for(float speed:new float[]{2,RULES.maxSpeed()/2,RULES.maxSpeed(),RULES.turboSpeed()})
            for(int angle:new int[]{0,-25,25,-50,50})rows.add(Arguments.of(arena,pad,speed,angle));
        return rows.stream();
    }
    static Stream<Arguments> renderRates() {
        return REGISTRY.campaignIds().stream().flatMap(a->Stream.of(0,1).flatMap(p->Stream.of(30,60,120).map(fps->Arguments.of(a,p,fps))));
    }

    @ParameterizedTest(name="{0} pad={1} speed={2} angle={3}") @MethodSource("launches")
    void allPlatformsDeliverTheCompleteOrdinaryChassis(String arenaId,int padIndex,float speed,int angle) {
        try(var rig=new Rig(REGISTRY.definition(arenaId),false)) {
            rig.place(0,padIndex,speed,angle);
            assertEquals(4,rig.world.wheelContacts(0));
            assertTrue(rig.world.roadContext(0).known());
            rig.fly(0,padIndex,60);
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void emceeUsesBothRealPlatformsWithItsActualLargeHull(int padIndex) {
        try(var rig=new Rig(REGISTRY.definition("euphoria_park"),true)) {
            rig.place(rig.focus,padIndex,14,0);
            assertEquals(4,rig.world.wheelContacts(rig.focus));
            rig.fly(rig.focus,padIndex,60);
        }
    }
    @ParameterizedTest @MethodSource("renderRates")
    void renderRateDoesNotChangeLaunchTicksOrLanding(String arenaId,int padIndex,int fps) {
        Vector3f landing;
        float duration;
        try(var rig=new Rig(REGISTRY.definition(arenaId),false)) {
            rig.place(0,padIndex,14,25);
            rig.fly(0,padIndex,fps);
            assertEquals(12,rig.launchTick,"Compression is .1s of physics at every render rate");
            assertEquals(0,rig.loop.droppedSimulationTime());
            var event=rig.events.stream().filter(e->e.type()==GameEvent.Type.LANDED).findFirst().orElseThrow();
            landing=event.position();duration=event.value();
        }
        if(fps!=120)try(var reference=new Rig(REGISTRY.definition(arenaId),false)) {
            reference.place(0,padIndex,14,25);reference.fly(0,padIndex,120);
            var event=reference.events.stream().filter(e->e.type()==GameEvent.Type.LANDED).findFirst().orElseThrow();
            assertTrue(landing.distance(event.position())<.005f,"Render observations must not change the native landing");
            assertEquals(duration,event.value(),.000001f);
        }
    }
    @ParameterizedTest @ValueSource(ints={12,36,72})
    void oneCarDoesNotGloballyLockThePlatformForTheNext(int interval) {
        try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,14,0);
            boolean secondPlaced=false;
            for(int tick=0;tick<430;tick++) {
                if(!secondPlaced&&tick==interval) {rig.placeMovingSecond(1,0);secondPlaced=true;}
                rig.tick();
            }
            assertEquals(1,rig.events.stream().filter(e->e.type()==GameEvent.Type.LAUNCHED&&e.subjectId()==0).count());
            assertEquals(1,rig.events.stream().filter(e->e.type()==GameEvent.Type.LAUNCHED&&e.subjectId()==1).count());
            assertTrue(rig.world.containsVehicle(0)&&rig.world.containsVehicle(1));
        }
    }
    @Test void wrongWaySidewaysAndStoppedCarsDoNotLaunchOrReceiveDamage() {
        for(int angle:new int[]{90,180})try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,4,angle);
            for(int tick=0;tick<18;tick++)rig.tick();
            assertTrue(rig.events.stream().noneMatch(e->e.type()==GameEvent.Type.LAUNCHED));
            assertEquals(1,rig.events.stream().filter(e->e.type()==GameEvent.Type.LAUNCH_REJECTED).count());
            assertEquals(800,rig.session.vehicle(0).hp);
        }
        try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,0,0);
            for(int tick=0;tick<240;tick++)rig.tick();
            assertTrue(rig.events.stream().noneMatch(e->e.type()==GameEvent.Type.LAUNCHED));
        }
    }
    @Test void frozenCompressionCancelsAndAirborneFreezeKeepsNativeConstraintsAndGravity() {
        try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,14,0);for(int tick=0;tick<5;tick++)rig.tick();
            rig.session.vehicle(0).frozenTicks=120;rig.world.immobilize(0,true);
            for(int tick=0;tick<30;tick++)rig.tick();
            assertTrue(rig.events.stream().noneMatch(e->e.type()==GameEvent.Type.LAUNCHED));
        }
        try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,14,0);
            for(int tick=0;tick<45;tick++)rig.tick();
            assertTrue(rig.systems.launches().flight(0).orElseThrow().airborne());
            rig.session.vehicle(0).frozenTicks=120;rig.world.immobilize(0,true);
            Vector3f frozenAt=rig.world.position(0);
            for(int tick=0;tick<90;tick++)rig.tick();
            Vector3f now=rig.world.position(0);
            assertEquals(frozenAt.x,now.x,.05f);assertEquals(frozenAt.z,now.z,.05f);
            assertTrue(Math.abs(frozenAt.y-now.y)>1,"Gravity remains live during launch Freeze");
            assertEquals(1,rig.world.immobilizerCount());
            assertEquals(0,rig.session.vehicle(0).protectionTicks);
        }
    }
    @Test void heavyHitStillChangesMomentumAndSuspendsAirStabilization() {
        try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,14,0);for(int tick=0;tick<45;tick++)rig.tick();
            Vector3f before=rig.world.velocity(0);
            rig.world.impulse(0,new Vector3f(0,8800,13200),new Vector3f(9000,0,0),6);
            rig.session.vehicle(0).heavyImpactPending=true;rig.tick();
            assertTrue(rig.world.velocity(0).z-before.z>10,"Launch does not overwrite a later cannon impulse");
            assertTrue(rig.world.velocity(0).y-before.y>7);
            assertEquals(0,rig.drivers.get(0).stabilizerScale());
            assertEquals(0,rig.session.vehicle(0).protectionTicks);
            assertEquals(0,rig.world.teleportGeneration(0));
        }
    }
    @Test void rearmRequiresExitAndOneSecondAndPauseCannotAdvanceCompression() {
        try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,14,0);
            for(int i=0;i<5;i++)rig.tick();
            float before=rig.systems.launches().compression("launch-a",0);
            rig.loop.advance(.1,false,rig::tick);
            assertEquals(before,rig.systems.launches().compression("launch-a",0));
            for(int i=0;i<25;i++)rig.tick();
            assertEquals(1,rig.events.stream().filter(e->e.type()==GameEvent.Type.LAUNCHED).count());
            var pad=rig.arena.launchPads().getFirst();
            rig.world.teleport(0,pad.source().vector().add(0,rig.world.profile(0).roadOffset(),0),new Quaternion().fromAngleAxis((float)Math.PI/2,Vector3f.UNIT_Y));
            rig.world.vehicle(0).setLinearVelocity(pad.direction().mult(2));
            for(int i=0;i<50;i++)rig.tick();
            assertEquals(1,rig.events.stream().filter(e->e.type()==GameEvent.Type.LAUNCHED).count());
        }
    }
    @Test void checkpointDuringLaunchCopiesTheLastRoadPoseWithoutMovingTheFlyingCar() {
        try(var rig=new Rig(REGISTRY.definition("construction_17"),false)) {
            rig.place(0,0,14,0);Vector3f road=rig.world.position(0);
            for(int tick=0;tick<60;tick++)rig.tick();
            Vector3f flying=rig.world.position(0);long generation=rig.world.teleportGeneration(0);
            var saved=rig.drivers.get(0).checkpointPose().orElseThrow();
            assertTrue(saved.position().distance(road)<.001f);
            saved.position().set(999,999,999);
            assertTrue(rig.drivers.get(0).checkpointPose().orElseThrow().position().distance(road)<.001f);
            assertEquals(flying,rig.world.position(0));assertEquals(generation,rig.world.teleportGeneration(0));
        }
    }

    private static final class Rig implements AutoCloseable {
        final ArenaDefinition arena;
        final MatchSession session;
        final PhysicsWorld world=new PhysicsWorld(RULES);
        final Map<Integer,VehicleController> drivers=new LinkedHashMap<>();
        final ArenaSystems systems;
        final List<GameEvent> events=new ArrayList<>();
        final SimulationLoop loop=new SimulationLoop(MatchRules.load());
        final int focus;
        long launchTick=-1;
        Rig(ArenaDefinition arena,boolean boss) {
            this.arena=arena;
            session=new MatchSession(42,arena,boss?MatchSession.Mode.BOSS_DUEL:MatchSession.Mode.ARENA,COMBAT);
            focus=boss?session.registerBoss(arena.bosses().getFirst()).id:0;
            ArenaContent content=CONTENT.computeIfAbsent(arena.id(),key->new ArenaFactory(NativeArenaAssets.MANAGER).build(arena));
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            world.configureArena(arena);systems=new ArenaSystems(session,arena);
        }
        void place(int id,int padIndex,float speed,float angle) {
            var pad=arena.launchPads().get(padIndex);var state=session.vehicle(id);
            var profile=state.boss?VehicleProfile.boss(state.profileId,RULES):VehicleProfile.rivet(RULES);
            float yaw=(float)Math.atan2(pad.direction().x,pad.direction().z)+(float)Math.toRadians(angle);
            world.addVehicle(id,pad.source().vector().add(0,profile.roadOffset()+.3f,0),new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y),profile);
            for(int tick=0;tick<360;tick++)world.step();
            drivers.put(id,new VehicleController(world,state,RULES,arena.bounds(),0));
            world.vehicle(id).setLinearVelocity(world.forward(id).setY(0).normalizeLocal().multLocal(speed));
        }
        void placeMovingSecond(int id,int padIndex) {
            var pad=arena.launchPads().get(padIndex);
            var profile=VehicleProfile.rivet(RULES);
            float yaw=(float)Math.atan2(pad.direction().x,pad.direction().z);
            // Native suspension starts close to its established loaded road height;
            // this helper must not advance the first car while creating the second.
            Vector3f side=new Vector3f(-pad.direction().z,0,pad.direction().x).multLocal(3.2f);
            world.addVehicle(id,pad.source().vector().add(side).addLocal(0,profile.roadOffset()-.08f,0),new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y));
            drivers.put(id,new VehicleController(world,session.vehicle(id),RULES,arena.bounds(),0));
            world.vehicle(id).setLinearVelocity(pad.direction().mult(14));
        }
        void tick() {
            systems.beforePhysics(world,drivers);
            for(var driver:drivers.values())driver.drive(VehicleCommand.NONE);
            world.step();systems.afterPhysics(world,drivers);
            var fresh=systems.drainEvents();events.addAll(fresh);
            if(launchTick<0&&fresh.stream().anyMatch(e->e.type()==GameEvent.Type.LAUNCHED&&e.subjectId()==focus))launchTick=session.tick;
            session.tick++;
        }
        void fly(int id,int padIndex,int fps) {
            int baseBodies=world.bodyCount();
            float maxY=world.position(id).y;
            for(int frame=0;frame<fps*4;frame++) {
                loop.advance(1.0/fps,true,this::tick);
                maxY=Math.max(maxY,world.position(id).y);
                if(events.stream().anyMatch(e->e.type()==GameEvent.Type.LANDED&&e.subjectId()==id))break;
                var context=world.roadContext(id);
                if(context.motion()==RoadContext.Motion.LAUNCH) {
                    assertEquals(0,context.level());assertEquals(1,context.targetLevel());
                    assertEquals(arena.launchPads().get(padIndex).id(),context.transitionId());
                }
            }
            var launched=events.stream().filter(e->e.type()==GameEvent.Type.LAUNCHED&&e.subjectId()==id).toList();
            var landed=events.stream().filter(e->e.type()==GameEvent.Type.LANDED&&e.subjectId()==id).toList();
            assertEquals(1,launched.size(),"Exactly one launch: "+events);
            assertEquals(1,landed.size(),"Real wheels must land: "+world.position(id));
            var pad=arena.launchPads().get(padIndex);Vector3f contact=landed.getFirst().position();
            assertTrue(contact.subtract(pad.target().vector()).setY(0).length()<4,"Landing must reach the authored centre: "+contact);
            assertTrue(contact.y>8&&contact.y<10,"Must land on the upper road, not downstairs");
            assertTrue(maxY>12,"The collider must actually fly over the slab edge");
            assertTrue(world.rotation(id).mult(Vector3f.UNIT_Y).y>.75f,"Correct wheel-side landing");
            assertFalse(drivers.get(id).launchActive());
            assertEquals(1,world.roadContext(id).level());
            assertEquals(0,world.teleportGeneration(id));assertEquals(baseBodies,world.bodyCount());
        }
        @Override public void close() {world.close();}
    }
}
