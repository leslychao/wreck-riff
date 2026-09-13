package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.vehicle.VehicleController;
import game.wreckriff.presentation.ChaseCamera;
import com.jme3.renderer.Camera;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Authored road colliders and the real shared driver. No transform correction during a route. */
class NativeCampaignBotNavigationTest {
    static final ArenaRegistry REGISTRY=ArenaRegistry.load();
    static final VehicleRules RULES=VehicleRules.load();
    static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    static final Map<String,ArenaContent> CONTENT=new HashMap<>();
    record ReviewRoute(String arenaId,String id,String kind,List<ArenaDefinition.Vec3> points) {}
    record ReviewRoutes(int schemaVersion,List<ReviewRoute> routes) {}
    static final List<ReviewRoute> REVIEW_ROUTES=reviewRoutes();
    static List<ReviewRoute> reviewRoutes() {
        try(var reader=Files.newBufferedReader(Path.of("src/tools/assets/architecture/review-routes.json"))) {
            return Configs.gson().fromJson(reader,ReviewRoutes.class).routes();
        } catch(java.io.IOException error) {throw new java.io.UncheckedIOException(error);}
    }
    static Stream<Arguments> ramps() {
        return Stream.of(
                new String[]{"construction_17","pit-west-slope"},
                new String[]{"construction_17","road-interchange-rise-0"},
                new String[]{"neon_zero","road-tunnel-south-ramp-0"},
                new String[]{"neon_zero","road-parking-ramp-one-0"},
                new String[]{"euphoria_park","road-west-bridge-rise-0"},
                new String[]{"euphoria_park","road-north-bridge-fall-0"})
                .flatMap(row->Stream.of(1,2,3,-1).map(id->Arguments.of(row[0],row[1],id)));
    }
    static Stream<Arguments> interiors() {
        return REVIEW_ROUTES.stream().filter(r->r.kind().equals("interior"))
                .flatMap(route->Stream.of(1,2,3,-1).map(id->Arguments.of(route.arenaId(),route.id(),id)));
    }
    static Stream<Arguments> districts() {
        return REVIEW_ROUTES.stream().filter(r->r.kind().equals("district"))
                .flatMap(route->Stream.of(1,2,3,-1).map(id->Arguments.of(route.arenaId(),route.id(),id)));
    }
    static Stream<Integer> emceePads() {
        return java.util.stream.IntStream.range(0,REGISTRY.definition("euphoria_park").launchPads().size()).boxed();
    }

    @ParameterizedTest(name="{0} {1} participant={2}") @MethodSource("ramps")
    void threeOrdinaryChassisAndTheMapBossClimbAuthoredTrianglesWithoutRecovery(String arenaId,String rampId,int participant) {
        var arena=REGISTRY.definition(arenaId);
        var edges=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.RAMP&&e.objectId().equals(rampId)).toList();
        assertFalse(edges.isEmpty(),"The native scenario must reference a shipped triangle ramp");
        var graph=new NavGraph(arena);
        var points=edges.stream().flatMap(e->Stream.of(graph.position(e.from()),graph.position(e.to()))).toList();
        Vector3f lower=points.stream().min(Comparator.comparingDouble(p->p.y)).orElseThrow();
        Vector3f upper=points.stream().max(Comparator.comparingDouble(p->p.y)).orElseThrow();
        assertTrue(upper.y-lower.y>=3,"This must exercise a real height transition");
        Vector3f direction=upper.subtract(lower).setY(0).normalizeLocal();
        // Follow the authored adjoining roads: a curved bridge or tunnel exit need not
        // have another twenty metres of pavement in the ramp's straight direction.
        Vector3f start=adjoiningRoadPoint(arena,graph,lower,direction.negate(),rampId);
        Vector3f goal=adjoiningRoadPoint(arena,graph,upper,direction,rampId);
        if(arena.launchPads().stream().anyMatch(p->p.source().vector().distance(lower)<90)) {
            // This scenario isolates the bridge. Its nearby catapult is a valid faster route from the shore.
            start=lower.add(direction.mult(20));start.y=arena.surfaceHeight(rampId,start.x,start.z);
        }
        arena.surfaceAt(start,3,.1f).orElseThrow();
        var targetSurface=arena.surfaceAt(goal,3,.1f).orElseThrow();
        var pickup=new ArenaDefinition.Pickup("ai-route-repair",ArenaDefinition.PickupType.REPAIR,
                new ArenaDefinition.Vec3(goal.x,goal.y,goal.z),3600);
        try(var rig=new Rig(repairFixture(arena,pickup),participant,participant<0,start,direction)) {
            boolean arrived=false,onSlope=false;var trace=new StringBuilder("\n initial support=").append(rig.world.roadContext(rig.id))
                    .append(" entryProbe=").append(rig.world.sweep(rig.world.position(rig.id).add(0,1.2f,0),
                            lower.add(0,rig.world.profile(rig.id).roadOffset()+1.2f,0),rig.world.profile(rig.id).width()/2,rig.id));
            for(int tick=0;tick<7200&&!arrived;tick++) {
                rig.tick();var road=rig.world.roadContext(rig.id);
                if(tick%600==0)trace.append("\n tick=").append(tick).append(" position=").append(rig.world.position(rig.id))
                        .append(" forward=").append(rig.world.forward(rig.id)).append(" command=").append(rig.command)
                        .append(" navigation=").append(rig.bots.navigation(rig.id));
                onSlope|=road.surfaceId().equals(rampId);
                arrived=road.level()==targetSurface.level()&&road.motion()==RoadContext.Motion.ROAD
                        &&rig.world.position(rig.id).subtract(goal).setY(0).length()<6&&rig.world.supportedWheelContacts(rig.id)==4;
            }
            assertTrue(arrived,"AI did not finish authored climb: "+arenaId+" / "+rampId+" / "+rig.world.position(rig.id)+" / "+rig.bots.navigation(rig.id)+trace);
            assertTrue(onSlope,"The bot must physically drive on the intended triangle surface");
            assertEquals(targetSurface.level(),rig.world.roadContext(rig.id).level());
            assertEquals(0,rig.world.teleportGeneration(rig.id));
        }
    }

    private static Vector3f adjoiningRoadPoint(ArenaDefinition arena,NavGraph graph,Vector3f seam,Vector3f outward,String rampId) {
        int origin=graph.nearest(seam);
        return arena.edges().stream().filter(edge->edge.type()==ArenaDefinition.Transition.ROAD
                &&(edge.from()==origin||(edge.bidirectional()&&edge.to()==origin)))
                .map(edge->graph.position(edge.from()==origin?edge.to():edge.from()))
                .filter(point->Math.abs(point.y-seam.y)<.1f&&point.distance(seam)>=8)
                .max(Comparator.comparingDouble(point->point.subtract(seam).setY(0).normalizeLocal().dot(outward)))
                .orElseThrow(()->new AssertionError("Ramp must have an actual adjoining road: "+arena.id()+" / "+rampId+" / "+seam));
    }

    @ParameterizedTest(name="{0} {1} participant={2}") @MethodSource("interiors")
    void allChassisAndTheMapBossCrossTheEntireInteriorWithAClearCamera(String arenaId,String roofId,int participant) {
        driveReviewRoute(arenaId,roofId,"interior",participant);
    }
    @ParameterizedTest(name="{0} {1} participant={2}") @MethodSource("districts")
    void allChassisAndTheMapBossDriveTheDistrictApproachCombatAreaAndExit(String arenaId,String districtId,int participant) {
        driveReviewRoute(arenaId,districtId,"district",participant);
    }
    private void driveReviewRoute(String arenaId,String roofId,String kind,int participant) {
        var arena=REGISTRY.definition(arenaId);
        var route=REVIEW_ROUTES.stream().filter(r->r.arenaId().equals(arenaId)&&r.id().equals(roofId)&&r.kind().equals(kind)).findFirst().orElseThrow();
        assertTrue(route.points().size()>=3,"Review must include an approach, combat area and exit");
        Vector3f start=route.points().getFirst().vector(),goal=route.points().getLast().vector();
        var targets=new ArrayList<ArenaDefinition.Pickup>();
        for(int i=1;i<route.points().size();i++)targets.add(new ArenaDefinition.Pickup("interior-route-"+i,
                ArenaDefinition.PickupType.REPAIR,route.points().get(i),3600));
        try(var rig=new Rig(arena.withPickups(targets),participant,participant<0,start,
                route.points().get(1).vector().subtract(start).setY(0).normalizeLocal(),targets)) {
            var camera=new Camera(1920,1080);var cameraRules=CameraRules.load();var chase=new ChaseCamera(camera,cameraRules);
            boolean arrived=false;int reached=0;
            for(int tick=0;tick<7200&&!arrived;tick++) {
                rig.tick();var position=rig.world.position(rig.id);
                var target=targets.get(rig.waypointIndex).position().vector();
                if(position.subtract(target).setY(0).length()<8&&Math.abs(position.y-target.y)<4
                        &&rig.world.supportedWheelContacts(rig.id)==4) {
                    reached++;
                    if(rig.waypointIndex+1<targets.size())rig.waypointIndex++;
                    else arrived=true;
                }
                chase.update(rig.world,rig.id,1,1f/120,false,false,0);
                assertNull(rig.world.staticSweep(position.add(0,cameraRules.lookHeight(),0),camera.getLocation(),cameraRules.sweepRadius()),
                        "The camera must remain clear of the authored interior walls and roof");
            }
            assertEquals(targets.size(),reached,"The vehicle must visit every authored turn before the exit: "+arenaId+" / "+roofId
                    +" / "+rig.world.position(rig.id)+" / "+rig.bots.navigation(rig.id));
            assertTrue(arrived,"AI did not leave the opposite opening: "+arenaId+" / "+roofId+" / "+rig.world.position(rig.id)+" / "+rig.bots.navigation(rig.id));
            assertEquals(0,rig.world.teleportGeneration(rig.id));
        }
    }

    @ParameterizedTest @MethodSource("emceePads")
    void emceeApproachesEveryAuthoredPlatformThroughOrdinaryCommandsThenReallyLands(int padIndex) {
        var arena=REGISTRY.definition("euphoria_park");var pad=arena.launchPads().get(padIndex);
        var goal=pad.target();var pickup=new ArenaDefinition.Pickup("ai-route-repair",ArenaDefinition.PickupType.REPAIR,goal,3600);
        var graph=new NavGraph(arena);
        int launchSource=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.LAUNCH&&e.objectId().equals(pad.id()))
                .findFirst().orElseThrow().from();
        Vector3f start=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.ROAD
                        &&(e.to()==launchSource||(e.bidirectional()&&e.from()==launchSource)))
                .map(e->graph.position(e.to()==launchSource?e.from():e.to())).filter(p->p.subtract(pad.source().vector()).dot(pad.direction())<0)
                .min(Comparator.comparingDouble(p->p.distanceSquared(pad.source().vector()))).orElseThrow();
        try(var rig=new Rig(repairFixture(arena,pickup),-1,true,start,pad.direction())) {
            for(int tick=0;tick<7200&&rig.events.stream().noneMatch(e->e.type()==GameEvent.Type.LANDED);tick++)rig.tick();
            var launches=rig.events.stream().filter(e->e.type()==GameEvent.Type.LAUNCHED&&e.subjectId()==rig.id).toList();
            assertEquals(1,launches.size(),"AI never entered its intended platform: "+rig.world.position(rig.id)+" / "+rig.bots.navigation(rig.id));
            assertEquals(pad.id(),launches.getFirst().kind());
            assertEquals(1,rig.events.stream().filter(e->e.type()==GameEvent.Type.LANDED&&e.subjectId()==rig.id).count());
            assertEquals(pad.landingSurfaceId(),rig.world.roadContext(rig.id).surfaceId());
            assertEquals(0,rig.world.teleportGeneration(rig.id));
        }
    }
    private static ArenaDefinition repairFixture(ArenaDefinition arena,ArenaDefinition.Pickup pickup) {
        var pickups=new ArrayList<>(arena.pickups().stream().filter(p->p.type()!=ArenaDefinition.PickupType.REPAIR).toList());
        pickups.add(pickup);return arena.withPickups(pickups);
    }

    static final class Rig implements AutoCloseable {
        final PhysicsWorld world=new PhysicsWorld(RULES);final MatchSession session;final ArenaSystems systems;
        final BotController bots;final VehicleController driver;final int id;final List<GameEvent> events=new ArrayList<>();
        final List<ArenaDefinition.Pickup> routeTargets;int waypointIndex;
        game.wreckriff.input.VehicleCommand command;
        Rig(ArenaDefinition arena,int participant,boolean boss,Vector3f start,Vector3f direction) {
            this(arena,participant,boss,start,direction,null);
        }
        Rig(ArenaDefinition arena,int participant,boolean boss,Vector3f start,Vector3f direction,List<ArenaDefinition.Pickup> targets) {
            routeTargets=targets;
            session=new MatchSession(73,arena,boss?MatchSession.Mode.BOSS_DUEL:MatchSession.Mode.ARENA,COMBAT);
            id=boss?session.registerBoss(arena.bosses().getFirst()).id:participant;
            for(var state:session.vehicles)state.hp=state.id==id?state.maximumHp*.1f:0;
            session.phase=boss?MatchSession.Phase.BOSS_COMBAT:MatchSession.Phase.ARENA_COMBAT;
            var content=CONTENT.computeIfAbsent(arena.id(),key->new ArenaFactory(NativeArenaAssets.MANAGER).build(REGISTRY.definition(key)));
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            world.configureArena(arena);
            var profile=boss?VehicleProfile.boss(session.vehicle(id).profileId,RULES):VehicleProfile.player(session.vehicle(id).profileId,RULES);
            world.addVehicle(id,start.add(0,profile.roadOffset()+.3f,0),new Quaternion().fromAngleAxis((float)Math.atan2(direction.x,direction.z),Vector3f.UNIT_Y),profile);
            for(int tick=0;tick<360;tick++)world.step();
            driver=new VehicleController(world,session.vehicle(id),RULES,arena.bounds(),arena.metadata().recoveryCost());driver.recordSafePose(0);
            systems=new ArenaSystems(session,arena);bots=new BotController(session,arena,new NavGraph(arena),AiRules.load(),
                    ()->routeTargets==null?systems.activePickups():List.of(routeTargets.get(waypointIndex)));
        }
        void tick() {
            command=bots.commands(world).get(id).withoutAttacks();var recovery=driver.prepare(command,session.tick);
            assertFalse(recovery.recovered()||recovery.fatal(),"Native route used recovery: "+world.position(id)+" / "+bots.navigation(id));
            systems.beforePhysics(world,Map.of(id,driver));driver.drive(command);world.step();
            systems.afterPhysics(world,Map.of(id,driver));driver.recordSafePose(session.tick);
            // Route probes select successive destinations without healing away the navigation intent.
            // The vehicle and its momentum remain continuous through every interior turn.
            if(routeTargets==null)systems.collectPickups(world);
            events.addAll(systems.drainEvents());bots.drainBossCommands();session.tick++;
        }
        public void close(){world.close();}
    }
}
