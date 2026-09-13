package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BotSupplyRoutingTest {
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static final VehicleRules VEHICLES=VehicleRules.load();

    @Test void emptyBotsChooseTheNearestCombatWeaponAndReleaseAnAbandonedReservationImmediately() {
        var source=ArenaDefinition.load();
        var mine=pickup(source,"near-mine",ArenaDefinition.PickupType.MINE_AMMO,0);
        var power=pickup(source,"near-power",ArenaDefinition.PickupType.POWER_AMMO,1);
        var homing=pickup(source,"far-homing",ArenaDefinition.PickupType.HOMING_AMMO,4);
        var arena=source.withPickups(List.of(mine,power,homing));var rig=new Rig(arena,0);
        rig.bots.commands(rig.world);
        assertEquals("near-power",rig.bots.pickupReservations().getFirst().pickupId());
        rig.session.vehicle(0).weapon(WeaponType.POWER).ammo=2;
        rig.session.tick=12;rig.bots.commands(rig.world);
        assertTrue(rig.bots.pickupReservations().isEmpty(),"An armed bot releases supply it no longer intends to collect");
    }

    @Test void anObservedUnavailableTargetReleasesItsOldLeaseWhenAnotherSupplyIsChosen() {
        var source=ArenaDefinition.load();
        var homing=pickup(source,"near-homing",ArenaDefinition.PickupType.HOMING_AMMO,1);
        var power=pickup(source,"next-power",ArenaDefinition.PickupType.POWER_AMMO,2);
        var arena=source.withPickups(List.of(homing,power));var rig=new Rig(arena,0);
        rig.bots.commands(rig.world);assertEquals("near-homing",rig.bots.pickupReservations().getFirst().pickupId());
        rig.active.set(List.of(power));rig.session.tick=12;rig.bots.commands(rig.world);
        var reservations=rig.bots.pickupReservations();assertEquals(1,reservations.size());
        assertEquals("next-power",reservations.getFirst().pickupId());
    }

    @Test void oneSearchServesSupplySelectionAndDrivingAndOnlyChangedInputsInvalidateIt() {
        var source=ArenaDefinition.load();
        var arena=source.withPickups(List.of(pickup(source,"goal",ArenaDefinition.PickupType.HOMING_AMMO,2)));
        var rig=new Rig(arena,0);var hazards=new AtomicReference<List<ArenaDefinition.Hazard>>(List.of());
        rig.bots.observeHazards(hazards::get);rig.bots.commands(rig.world);
        assertEquals(1,rig.graph.searchCount(),"Selection and its driving route share one graph search");
        for(int tick=1;tick<=132;tick++){rig.session.tick=tick;rig.bots.commands(rig.world);}
        assertEquals(1,rig.graph.searchCount(),"Neither render ticks nor a one-second timer expire a valid route tree");
        rig.world.profiles.put(0,VehicleDefinition.SPARK.profile(VEHICLES));
        rig.session.tick=144;rig.bots.commands(rig.world);assertEquals(2,rig.graph.searchCount(),"Mobility changes invalidate the tree");
        hazards.set(arena.hazards());rig.session.tick=156;rig.bots.commands(rig.world);
        assertEquals(3,rig.graph.searchCount(),"Observed active hazards invalidate the tree");
        rig.world.place(0,rig.graph.position(1));rig.session.tick=168;rig.bots.commands(rig.world);
        assertEquals(4,rig.graph.searchCount(),"Moving to another route start invalidates the tree");
    }

    @Test void graphRevisionsInvalidateAStoredSupplyTree() {
        var source=Configs.load("arena-construction-17",ArenaDefinition.class);
        var goal=source.pickups().stream().filter(p->p.type()==ArenaDefinition.PickupType.HOMING_AMMO).findFirst().orElseThrow();
        var rig=new Rig(source.withPickups(List.of(goal)),0);rig.bots.commands(rig.world);
        long before=rig.graph.searchCount();
        var gate=source.edges().stream().filter(edge->edge.type()==ArenaDefinition.Transition.OPENABLE).findFirst().orElseThrow();
        rig.graph.setOpen(gate.objectId(),true);rig.session.tick=12;rig.bots.commands(rig.world);
        assertEquals(before+1,rig.graph.searchCount(),"Opening a real passage invalidates the cached multi-goal route exactly once");
    }

    @Test void prefectWithOnlyMinesStillSeeksHomingBeforeStartingAnOffensiveRun() {
        var source=Configs.load("arena-neon-zero",ArenaDefinition.class);var graph=new NavGraph(source);
        var arena=source.withPickups(List.of(pickup(source,"near-mine",ArenaDefinition.PickupType.MINE_AMMO,1),
                pickup(source,"needed-homing",ArenaDefinition.PickupType.HOMING_AMMO,2)));
        var session=new MatchSession(73,arena,MatchSession.Mode.BOSS_DUEL,COMBAT);
        var boss=session.registerBoss(arena.bosses().getFirst());session.vehicle(0).hp=0;session.phase=MatchSession.Phase.BOSS_COMBAT;
        boss.weapon(WeaponType.MINE).ammo=2;
        var world=new SupplyWorld(arena);world.profiles.put(boss.id,VehicleProfile.boss(boss.profileId,VEHICLES));world.place(boss.id,graph.position(0));
        var bots=new BotController(session,arena,graph,AiRules.load());bots.commands(world);
        assertEquals(BotController.State.SEEK_PICKUP,bots.state(boss.id));
        assertEquals("needed-homing",bots.pickupReservations().getFirst().pickupId());
        assertEquals(0,boss.weapon(WeaponType.HOMING).ammo,"The decision itself never grants ammunition");
    }

    @Test void authoredElevatedSuppliesKeepTheirActualFloorForEveryChassisAndThePrefect() {
        var source=Configs.load("arena-neon-zero",ArenaDefinition.class);
        var profiles=new ArrayList<VehicleProfile>();for(var chassis:VehicleDefinition.values())profiles.add(chassis.profile(VEHICLES));
        profiles.add(VehicleProfile.boss("boss_prefect",VEHICLES));
        for(var pickup:source.pickups())if(pickup.position().y()!=0)for(var profile:profiles) {
            var arena=source.withPickups(List.of(new ArenaDefinition.Pickup(pickup.id(),ArenaDefinition.PickupType.HOMING_AMMO,pickup.position(),pickup.respawnTicks())));
            var rig=new Rig(arena,0);rig.world.profiles.put(0,profile);rig.world.place(0,rig.graph.position(0));
            rig.bots.commands(rig.world);var path=rig.bots.route(0);assertFalse(path.isEmpty(),pickup.id()+" / "+profile.id());
            var endpoint=rig.graph.position(path.getLast());
            assertEquals(pickup.position().y(),endpoint.y,2.2f,pickup.id()+" must not target an underlying street");
            var mobility=rig.bots.mobility(0,rig.world);
            for(var traversal:rig.bots.navigation(0).nodes().isEmpty()?List.<NavGraph.Traversal>of():rig.graph.route(path.getFirst(),path.getLast(),mobility,Set.of(),8,150).traversals()) {
                var link=rig.graph.links(traversal.from()).stream().filter(edge->edge.id().equals(traversal.edgeId())).findFirst().orElseThrow();
                assertTrue(link.width()>=mobility.width()&&link.clearance()>=mobility.height());
            }
        }
    }

    private static ArenaDefinition.Pickup pickup(ArenaDefinition arena,String id,ArenaDefinition.PickupType type,int node) {
        var point=arena.nodes().stream().filter(n->n.id()==node).findFirst().orElseThrow().position();
        return new ArenaDefinition.Pickup(id,type,point,3000);
    }
    private static final class Rig {
        final MatchSession session;final NavGraph graph;final SupplyWorld world;final BotController bots;
        final AtomicReference<List<ArenaDefinition.Pickup>> active;
        Rig(ArenaDefinition arena,int start) {
            session=new MatchSession(73,arena,arena.bosses().isEmpty()?MatchSession.Mode.LEGACY:MatchSession.Mode.ARENA,COMBAT);session.phase=MatchSession.Phase.ARENA_COMBAT;
            for(var vehicle:session.vehicles)if(vehicle.id!=0)vehicle.hp=0;
            graph=new NavGraph(arena);world=new SupplyWorld(arena);world.place(0,graph.position(start));
            active=new AtomicReference<>(arena.pickups());bots=new BotController(session,arena,graph,AiRules.load(),active::get);
        }
    }
    /** Deterministic road queries; native route drivability is covered by the physics suite. */
    private static final class SupplyWorld implements WorldQuery {
        final ArenaDefinition arena;final Map<Integer,Vector3f> positions=new HashMap<>();final Map<Integer,VehicleProfile> profiles=new HashMap<>();
        SupplyWorld(ArenaDefinition arena){this.arena=arena;}
        float offset(int id){return arena.bosses().isEmpty()?.45f:profile(id).roadOffset();}
        void place(int id,Vector3f road){positions.put(id,road.add(0,offset(id),0));}
        public Vector3f position(int id){return positions.getOrDefault(id,new Vector3f(1000+id*100,1,1000)).clone();}
        public Vector3f velocity(int id){return new Vector3f();}
        public Quaternion rotation(int id){return new Quaternion();}
        public VehicleProfile profile(int id){return profiles.getOrDefault(id,VehicleProfile.rivet());}
        public RoadContext roadContext(int id){
            var point=position(id).add(0,-offset(id),0);var surface=arena.surfaceAt(point,0,.2f).orElseThrow();
            return new RoadContext(surface.id(),surface.level(),surface.grip(),RoadContext.Motion.ROAD,"","",surface.level());
        }
        public boolean grounded(int id){return true;}
        public float mass(int id){return profile(id).mass();}
        public Hit ray(Vector3f from,Vector3f to,int ignored){
            if(Math.abs(from.x-to.x)<.001&&Math.abs(from.z-to.z)<.001&&from.y>to.y) {
                float highest=Float.NEGATIVE_INFINITY;
                for(var surface:arena.surfaces()) {
                    float height=arena.surfaceHeight(surface.id(),from.x,from.z);
                    if(Float.isFinite(height)&&height<=from.y&&height>=to.y)highest=Math.max(highest,height);
                }
                if(Float.isFinite(highest))return new Hit(-1,new Vector3f(from.x,highest,from.z),Vector3f.UNIT_Y,(from.y-highest)/(from.y-to.y));
            }
            return null;
        }
        public Hit sweep(Vector3f a,Vector3f b,float radius,int ignored,float start,float end){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int target){return true;}
        public float distanceToHull(int id,Vector3f point){return position(id).distance(point);}
        public Vector3f closestHullPoint(int id,Vector3f point){return position(id);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){throw new AssertionError("AI cannot move the physics owner directly");}
    }
}
