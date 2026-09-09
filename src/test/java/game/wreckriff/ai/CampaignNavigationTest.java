package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Rule tests use explicit confirmed road contexts; native driving has its own replay. */
class CampaignNavigationTest {
    final ArenaRegistry arenas=ArenaRegistry.load();
    final VehicleRules vehicleRules=VehicleRules.load();
    final CombatRules combat=Configs.load("combat",CombatRules.class);

    @Test void everyBossUsesItsActualEnvelopeAndOnlyEmceeMayLaunch() {
        for(String id:arenas.campaignIds()) {
            var arena=arenas.definition(id);var session=new MatchSession(1,arena,MatchSession.Mode.BOSS_DUEL,combat);
            var boss=session.registerBoss(arena.bosses().getFirst());var world=new RoadWorld(arena);
            world.profiles.put(boss.id,VehicleProfile.boss(boss.profileId,vehicleRules));
            var bots=new BotController(session,arena,AiRules.load());var mobility=bots.mobility(boss.id,world);
            var envelope=world.profile(boss.id).fullBounds();
            assertEquals(envelope.maxX()-envelope.minX(),mobility.width(),.001);
            assertEquals(world.profile(boss.id).roadOffset()+envelope.maxY(),mobility.height(),.001);
            assertEquals(boss.profileId.equals("boss_emcee"),mobility.launch());
            assertEquals(Set.copyOf(arena.bosses().getFirst().launchPadIds()),mobility.launchPadIds());
            var graph=new NavGraph(arena);
            for(var ramp:arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.RAMP).toList())
                assertTrue(graph.route(ramp.from(),ramp.to(),mobility,Set.of(),8,150).found(),id+" boss ramp clearance");
        }
    }

    @Test void confirmedLowerSurfaceAtApexCannotBecomeAnUpperRouteStart() {
        var arena=arenas.definition("construction_17");var pad=arena.launchPads().getFirst();
        var session=new MatchSession(2,arena,MatchSession.Mode.ARENA,combat);var world=new RoadWorld(arena);
        var source=arena.nodes().stream().filter(n->n.surfaceId().equals(pad.sourceSurfaceId())).findFirst().orElseThrow();
        world.positions.put(0,source.position().vector().add(0,16,0));
        world.contexts.put(0,new RoadContext(pad.sourceSurfaceId(),0,1,RoadContext.Motion.LAUNCH,pad.id(),pad.landingSurfaceId(),1));
        var bots=new BotController(session,arena,AiRules.load());
        for(int tick=0;tick<1000;tick++) {
            session.tick=tick;var command=bots.commands(world).get(0);
            assertEquals(0,command.steer());assertFalse(command.recover());
        }
        assertEquals(0,bots.metrics(0).reverseAttempts());
        assertTrue(bots.route(0).isEmpty(),"Flight must not replan using apex height");
    }

    @Test void healingBotsReserveDifferentReachablePickupsForThreeSeconds() {
        var source=arenas.definition("construction_17");var pickups=new ArrayList<>(source.pickups().stream()
                .filter(p->p.type()!=ArenaDefinition.PickupType.REPAIR).toList());
        for(int nodeId:List.of(12,13)) {
            var node=source.nodes().stream().filter(n->n.id()==nodeId).findFirst().orElseThrow();
            pickups.add(new ArenaDefinition.Pickup("lease-"+nodeId,ArenaDefinition.PickupType.REPAIR,node.position(),4800));
        }
        var arena=source.withPickups(pickups);var session=new MatchSession(2,arena,MatchSession.Mode.ARENA,combat);
        var world=new RoadWorld(arena);var start=new Vector3f(13,0,88);
        for(var state:session.vehicles) {state.hp=state.maximumHp*.1f;world.positions.put(state.id,start.add(state.id,world.profile(state.id).roadOffset(),0));}
        var bots=new BotController(session,arena,AiRules.load());bots.commands(world);
        var reservations=bots.pickupReservations();assertEquals(2,reservations.size());
        assertEquals(reservations.size(),reservations.stream().map(BotController.PickupReservation::pickupId).distinct().count());
        assertTrue(reservations.stream().allMatch(r->r.untilTick()==360));
        for(var state:session.vehicles)state.hp=0;
        session.tick=360;bots.commands(world);assertTrue(bots.pickupReservations().isEmpty());
    }

    @Test void bossMemoryExpiresAfterSixSecondsAndNeverTracksHiddenMovement() {
        var arena=arenas.definition("construction_17");var session=new MatchSession(2,arena,MatchSession.Mode.BOSS_DUEL,combat);
        var boss=session.registerBoss(arena.bosses().getFirst());session.phase=MatchSession.Phase.BOSS_COMBAT;
        var world=new RoadWorld(arena);world.profiles.put(boss.id,VehicleProfile.boss(boss.profileId,vehicleRules));
        world.positions.put(boss.id,new Vector3f(40,world.profile(boss.id).roadOffset(),40));
        world.positions.put(0,new Vector3f(40,.85f,75));
        var bots=new BotController(session,arena,AiRules.load());bots.commands(world);
        var observed=bots.observation(boss.id).visible(0);assertNotNull(observed);
        world.hidden=true;world.positions.put(0,new Vector3f(220,.85f,210));
        session.tick=360;bots.commands(world);assertEquals(observed.position(),bots.observation(boss.id).known(0).position());
        session.tick=732;var command=bots.commands(world).get(boss.id);
        assertNull(bots.observation(boss.id).known(0));assertFalse(command.machineGun());assertFalse(command.selectedWeapon());
    }

    @Test void aChangedPassageRevisionInvalidatesTheBotsSavedRouteImmediatelyAtItsNextDecision() {
        var source=arenas.definition("construction_17");var target=source.nodes().stream().filter(n->n.id()==46).findFirst().orElseThrow();
        var pickups=new ArrayList<>(source.pickups().stream().filter(p->p.type()!=ArenaDefinition.PickupType.REPAIR).toList());
        pickups.add(new ArenaDefinition.Pickup("route-test",ArenaDefinition.PickupType.REPAIR,target.position(),3600));
        var arena=source.withPickups(pickups);var session=new MatchSession(2,arena,MatchSession.Mode.ARENA,combat);
        for(var state:session.vehicles)state.hp=state.id==0?60:0;
        var world=new RoadWorld(arena);world.positions.put(0,new Vector3f(132,.51f,28));
        world.contexts.put(0,new RoadContext("floor-east",0,1,RoadContext.Motion.ROAD,"","",0));
        var graph=new NavGraph(arena);var bots=new BotController(session,arena,graph,AiRules.load());bots.commands(world);
        assertFalse(bots.route(0).isEmpty());assertEquals(0,bots.navigation(0).revision());
        graph.setOpen("short-cut",true);session.tick=12;bots.commands(world);
        assertEquals(graph.revision(),bots.navigation(0).revision());assertEquals(1,bots.navigation(0).revision());
    }

    @Test void bossesUseOnlyConfiguredFiniteWeaponsAndCannotGetInfiniteAmmoFromTheirPolicy() {
        for(String arenaId:arenas.campaignIds()) {
            var arena=arenas.definition(arenaId);var session=new MatchSession(2,arena,MatchSession.Mode.BOSS_DUEL,combat);
            var boss=session.registerBoss(arena.bosses().getFirst());session.phase=MatchSession.Phase.BOSS_COMBAT;
            var world=new RoadWorld(arena);world.profiles.put(boss.id,VehicleProfile.boss(boss.profileId,vehicleRules));
            world.positions.put(boss.id,new Vector3f(40,world.profile(boss.id).roadOffset(),40));world.positions.put(0,new Vector3f(40,.51f,80));
            var bots=new BotController(session,arena,AiRules.load());var allowed=Set.of(arena.bosses().getFirst().primary(),arena.bosses().getFirst().secondary());
            for(int tick=0;tick<360;tick++) {
                session.tick=tick;var command=bots.commands(world).get(boss.id);
                if(command.directWeapon()!=null)assertTrue(allowed.contains(command.directWeapon()));
                if(command.selectedWeapon()) {
                    var weapon=command.directWeapon()==null?boss.selectedWeapon:command.directWeapon();
                    assertTrue(allowed.contains(weapon));boss.weapon(weapon).ammo=0;
                }
            }
            for(var weapon:WeaponType.values())boss.weapon(weapon).ammo=0;
            for(int tick=360;tick<390;tick++) {
                session.tick=tick;assertFalse(bots.commands(world).get(boss.id).selectedWeapon());
                for(var weapon:WeaponType.values())assertEquals(0,boss.weapon(weapon).ammo);
            }
        }
    }

    @Test void prefectRequestsOnlyAuthoredProtocolTargetsAtTheFourteenSecondLimit() {
        var arena=arenas.definition("neon_zero");var session=new MatchSession(2,arena,MatchSession.Mode.BOSS_DUEL,combat);
        var boss=session.registerBoss(arena.bosses().getFirst());session.phase=MatchSession.Phase.BOSS_COMBAT;session.bossMode=2;
        var world=new RoadWorld(arena);world.profiles.put(boss.id,VehicleProfile.boss(boss.profileId,vehicleRules));
        world.positions.put(boss.id,new Vector3f(40,world.profile(boss.id).roadOffset(),40));world.positions.put(0,new Vector3f(40,.51f,80));
        var bots=new BotController(session,arena,AiRules.load());session.tick=720;bots.commands(world);
        var first=bots.drainBossCommands();assertEquals(1,first.size());
        assertTrue(arena.barriers().stream().anyMatch(b->b.id().equals(first.getFirst().targetId())));
        // Demonstrated movement keeps this timing test separate from the stationary recovery policy.
        Vector3f movement=bots.metrics(boss.id).destination().subtract(world.position(boss.id)).setY(0).normalizeLocal().multLocal(3);
        world.positions.put(boss.id,world.position(boss.id).add(movement));
        session.tick=2388;bots.commands(world);assertTrue(bots.drainBossCommands().isEmpty());
        session.tick=2400;bots.commands(world);var second=bots.drainBossCommands();assertEquals(1,second.size());
        assertTrue(arena.hazards().stream().anyMatch(h->h.id().equals(second.getFirst().targetId())));
        assertEquals(BotController.BossCommandStage.BEGIN,second.getFirst().stage());
        assertEquals(new ArenaDefinition.Vec3(40,.51f,80),second.getFirst().observedTarget());
        assertTrue(bots.drainBossCommands().isEmpty(),"Each command is drained once");
    }

    static final class RoadWorld implements WorldQuery {
        final ArenaDefinition arena;final Map<Integer,Vector3f> positions=new HashMap<>();
        final Map<Integer,RoadContext> contexts=new HashMap<>();final Map<Integer,VehicleProfile> profiles=new HashMap<>();
        boolean hidden;
        RoadWorld(ArenaDefinition arena) {this.arena=arena;}
        public Vector3f position(int id){return positions.getOrDefault(id,new Vector3f(1000+id*100,1,1000)).clone();}
        public Vector3f velocity(int id){return new Vector3f();}
        public Quaternion rotation(int id){return new Quaternion();}
        public VehicleProfile profile(int id){return profiles.getOrDefault(id,VehicleProfile.rivet());}
        public RoadContext roadContext(int id){return contexts.getOrDefault(id,new RoadContext(arena.surfaces().getFirst().id(),0,1,RoadContext.Motion.ROAD,"","",0));}
        public boolean grounded(int id){return !roadContext(id).flying();}
        public float mass(int id){return profile(id).mass();}
        public Hit ray(Vector3f from,Vector3f to,int ignored){
            if(Math.abs(from.x-to.x)<.001&&Math.abs(from.z-to.z)<.001&&from.y>to.y) {
                float highest=Float.NEGATIVE_INFINITY;
                for(var node:arena.nodes())if(node.position().y()<=from.y&&node.position().y()>=to.y) {
                    var p=new Vector3f(from.x,node.position().y(),from.z);
                    if(arena.surfaceAt(p,0,.2f).isPresent())highest=Math.max(highest,p.y);
                }
                if(Float.isFinite(highest))return new Hit(-1,new Vector3f(from.x,highest,from.z),Vector3f.UNIT_Y,(from.y-highest)/(from.y-to.y));
            }
            return hidden?new Hit(-1,from.clone().interpolateLocal(to,.5f),Vector3f.UNIT_X,.5f):null;
        }
        public Hit sweep(Vector3f a,Vector3f b,float radius,int ignored,float start,float end){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float radius){return null;}
        public boolean visible(Vector3f a,Vector3f b,int id){return !hidden;}
        public float distanceToHull(int id,Vector3f p){return position(id).distance(p);}
        public Vector3f closestHullPoint(int id,Vector3f p){return position(id);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){throw new AssertionError("AI cannot apply native impulses");}
    }
}
