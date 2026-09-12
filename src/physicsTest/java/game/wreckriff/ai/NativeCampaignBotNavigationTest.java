package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Authored road colliders and the real shared driver. No transform correction during a route. */
class NativeCampaignBotNavigationTest {
    static final ArenaRegistry REGISTRY=ArenaRegistry.load();
    static final VehicleRules RULES=VehicleRules.load();
    static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    static final Map<String,ArenaContent> CONTENT=new HashMap<>();
    static Stream<Arguments> ordinaryRamps() {
        return REGISTRY.campaignIds().stream().limit(4).flatMap(a->Stream.of(1,2,3).map(id->Arguments.of(a,id)));
    }

    @ParameterizedTest @MethodSource("ordinaryRamps")
    void threeDifferentOrdinaryBotsDriveUpEachOfTheFirstFourMaps(String arenaId,int id) {
        assertRamp(arenaId,id,false);
    }
    @ParameterizedTest @ValueSource(strings={"construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena"})
    void allBossesUseTheirActualHullToDriveTheRamp(String arenaId) {assertRamp(arenaId,-1,true);}

    @Test void directorChangesFloorAndReachesTheOppositeRingWhileThePlayerStaysBelow() {
        var arena=repairFixture(REGISTRY.definition("doomsday_arena"),new ArenaDefinition.Pickup(
                "relocation-repair",ArenaDefinition.PickupType.REPAIR,new ArenaDefinition.Vec3(80,0,40),3600));
        try(var rig=new Rig(arena,-1,true,new Vector3f(120,0,40),Vector3f.UNIT_Z)) {
            rig.session.vehicle(rig.id).hp=rig.session.vehicle(rig.id).maximumHp;
            rig.session.vehicle(0).hp=rig.session.vehicle(0).maximumHp;
            rig.world.addVehicle(0,new Vector3f(144,VehicleProfile.rivet(RULES).roadOffset()+.3f,42),new Quaternion());
            for(int settle=0;settle<120;settle++)rig.world.step();
            rig.session.tick=2880;
            rig.tick();
            var goal=rig.bots.metrics(rig.id).destination();
            assertTrue(goal.y>=8&&goal.z>=200,"Independent relocation must choose the far upper side: "+goal);
            var route=rig.bots.navigation(rig.id).nodes();
            assertTrue(route.stream().anyMatch(id->arena.nodes().stream().anyMatch(n->n.id()==id&&n.surfaceId().equals("upper-ramp"))));
            // A higher-priority supply route may interrupt the manoeuvre, but must not replace its committed goal.
            rig.session.vehicle(rig.id).hp=rig.session.vehicle(rig.id).maximumHp*.1f;
            for(int step=0;step<24;step++)rig.tick();
            assertEquals(BotController.State.SEEK_PICKUP,rig.bots.state(rig.id));
            assertNotEquals(goal,rig.bots.metrics(rig.id).destination());
            rig.session.vehicle(rig.id).hp=rig.session.vehicle(rig.id).maximumHp;
            for(int step=0;step<24;step++)rig.tick();
            assertEquals(goal,rig.bots.metrics(rig.id).destination(),"Supply completion must resume the same opposite-side goal: "
                    +rig.bots.metrics(rig.id)+" road="+rig.world.roadContext(rig.id)+" hp="+rig.session.vehicle(rig.id).hp
                    +" / "+rig.bots.bossAction(rig.id)+" / "+rig.bots.navigation(rig.id));
            boolean arrived=false;
            for(int step=0;step<7200&&!arrived;step++) {
                rig.tick();arrived=rig.world.roadContext(rig.id).level()==1
                        &&rig.world.position(rig.id).subtract(goal).setY(0).length()<8;
            }
            assertTrue(arrived,"Director failed its actual opposite-side relocation: "+rig.world.position(rig.id)+" / "+rig.bots.navigation(rig.id));
            assertEquals(0,rig.world.teleportGeneration(rig.id));
            assertEquals(0,rig.world.roadContext(0).level());
        }
    }

    private void assertRamp(String arenaId,int participant,boolean boss) {
        var arena=REGISTRY.definition(arenaId);
        var ramp=arena.ramps().stream().filter(r->Math.max(r.startY(),r.endY())>=8).findFirst().orElseThrow();
        var edges=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.RAMP&&e.objectId().equals(ramp.id())).toList();
        var graph=new NavGraph(arena);Vector3f start=graph.position(edges.getFirst().from());
        Vector3f goal=graph.position(edges.getLast().to());
        var pickup=new ArenaDefinition.Pickup("ai-route-repair",ArenaDefinition.PickupType.REPAIR,
                new ArenaDefinition.Vec3(goal.x,goal.y,goal.z),3600);
        try(var rig=new Rig(repairFixture(arena,pickup),participant,boss,start,
                graph.position(edges.getFirst().to()).subtract(start))) {
            boolean upper=false;
            for(int tick=0;tick<7200&&!upper;tick++) {
                rig.tick();upper=rig.world.roadContext(rig.id).level()==1&&rig.world.roadContext(rig.id).motion()==RoadContext.Motion.ROAD
                        &&rig.world.position(rig.id).subtract(goal).setY(0).length()<8;
            }
            assertTrue(upper,"AI did not finish upper ramp: "+arenaId+" / "+rig.world.position(rig.id)+" / "+rig.bots.navigation(rig.id));
            assertEquals(0,rig.world.teleportGeneration(rig.id));
        }
    }

    @ParameterizedTest @ValueSource(ints={0,1})
    void emceeApproachesAndFiresBothPlatformsThroughOrdinaryCommandsThenReallyLands(int padIndex) {
        var arena=REGISTRY.definition("euphoria_park");var pad=arena.launchPads().get(padIndex);
        var goal=pad.target();var pickup=new ArenaDefinition.Pickup("ai-route-repair",ArenaDefinition.PickupType.REPAIR,goal,3600);
        var graph=new NavGraph(arena);
        int launchSource=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.LAUNCH&&e.objectId().equals(pad.id()))
                .findFirst().orElseThrow().from();
        Vector3f start=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.ROAD&&e.to()==launchSource)
                .map(e->graph.position(e.from())).filter(p->p.subtract(pad.source().vector()).dot(pad.direction())<0)
                .findFirst().orElseThrow();
        try(var rig=new Rig(repairFixture(arena,pickup),-1,true,start,pad.direction())) {
            for(int tick=0;tick<7200&&rig.events.stream().noneMatch(e->e.type()==GameEvent.Type.LANDED);tick++)rig.tick();
            var launches=rig.events.stream().filter(e->e.type()==GameEvent.Type.LAUNCHED&&e.subjectId()==rig.id).toList();
            assertEquals(1,launches.size(),"AI never entered its intended platform: "+rig.world.position(rig.id)+" / "+rig.bots.navigation(rig.id));
            assertEquals(pad.id(),launches.getFirst().kind());
            assertEquals(1,rig.events.stream().filter(e->e.type()==GameEvent.Type.LANDED&&e.subjectId()==rig.id).count());
            assertEquals(1,rig.world.roadContext(rig.id).level());assertEquals(0,rig.world.teleportGeneration(rig.id));
        }
    }
    private static ArenaDefinition repairFixture(ArenaDefinition arena,ArenaDefinition.Pickup pickup) {
        var pickups=new ArrayList<>(arena.pickups().stream().filter(p->p.type()!=ArenaDefinition.PickupType.REPAIR).toList());
        pickups.add(pickup);return arena.withPickups(pickups);
    }

    static final class Rig implements AutoCloseable {
        final PhysicsWorld world=new PhysicsWorld(RULES);final MatchSession session;final ArenaSystems systems;
        final BotController bots;final VehicleController driver;final int id;final List<GameEvent> events=new ArrayList<>();
        Rig(ArenaDefinition arena,int participant,boolean boss,Vector3f start,Vector3f direction) {
            session=new MatchSession(73,arena,boss?MatchSession.Mode.BOSS_DUEL:MatchSession.Mode.ARENA,COMBAT);
            id=boss?session.registerBoss(arena.bosses().getFirst()).id:participant;
            for(var state:session.vehicles)state.hp=state.id==id?state.maximumHp*.1f:0;
            session.phase=boss?MatchSession.Phase.BOSS_COMBAT:MatchSession.Phase.ARENA_COMBAT;
            var content=CONTENT.computeIfAbsent(arena.id(),key->new ArenaFactory(NativeArenaAssets.MANAGER).build(REGISTRY.definition(key)));
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            world.configureArena(arena);
            var profile=boss?VehicleProfile.boss(session.vehicle(id).profileId,RULES):VehicleProfile.rivet(RULES);
            world.addVehicle(id,start.add(0,profile.roadOffset()+.3f,0),new Quaternion().fromAngleAxis((float)Math.atan2(direction.x,direction.z),Vector3f.UNIT_Y),profile);
            for(int tick=0;tick<360;tick++)world.step();
            driver=new VehicleController(world,session.vehicle(id),RULES,arena.bounds(),arena.metadata().recoveryCost());driver.recordSafePose(0);
            systems=new ArenaSystems(session,arena);bots=new BotController(session,arena,content.graph(),AiRules.load(),systems::activePickups);
        }
        void tick() {
            var command=bots.commands(world).get(id).withoutAttacks();var recovery=driver.prepare(command,session.tick);
            assertFalse(recovery.recovered()||recovery.fatal(),"Native route used recovery: "+world.position(id)+" / "+bots.navigation(id));
            systems.beforePhysics(world,Map.of(id,driver));driver.drive(command);world.step();
            systems.afterPhysics(world,Map.of(id,driver));driver.recordSafePose(session.tick);systems.collectPickups(world);
            events.addAll(systems.drainEvents());bots.drainBossCommands();session.tick++;
        }
        public void close(){world.close();}
    }
}
