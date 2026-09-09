package game.wreckriff.combat;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.VehicleState;
import game.wreckriff.simulation.WorldQuery;
import org.junit.jupiter.api.Test;
import java.util.*;
import static game.wreckriff.combat.CombatRules.ticks;
import static org.junit.jupiter.api.Assertions.*;

/** Combat contracts only: contacts and road observations are controlled; native movement has separate tests. */
class VehicleSpecialsTest {
    private static final CombatRules RULES=Configs.load("combat",CombatRules.class);

    @Test void pulseWaitsThirtySixTicksHitsOnlyNearestVisibleForwardHullAndSpendsCooldownOnMiss() {
        var fight=new Fight("rivet","rivet","rivet","rivet");
        fight.world.place(1,0,8);fight.world.place(2,0,12);fight.world.place(3,8,0);
        fight.tick(Map.of(0,special()));
        assertEquals(1680,fight.player().abilityCooldown(AbilityId.SPECIAL));
        fight.idle(34);assertEquals(800,fight.target().hp);
        fight.tick();assertEquals(740,fight.target().hp,.001f);
        assertEquals(800,fight.session.vehicle(2).hp);assertEquals(800,fight.session.vehicle(3).hp);
        assertFalse(fight.player().specialActive());
        assertEquals(8,fight.world.impulses.get(1).length()/fight.world.mass(1),.001f);
        fight.tick(Map.of(0,special()));assertFalse(fight.player().specialActive());
        assertTrue(fight.player().abilityCooldown(AbilityId.SPECIAL)>0);

        var miss=new Fight("rivet","rivet");miss.world.place(1,20,2);
        miss.tick(Map.of(0,special()));miss.idle(35);
        assertEquals(800,miss.target().hp);assertTrue(miss.world.impulses.isEmpty());
        assertEquals(1645,miss.player().abilityCooldown(AbilityId.SPECIAL));
    }

    @Test void pulseRespectsConeRangeOcclusionAndUsesClosestVisibleCandidate() {
        for(Vector3f rejected:List.of(new Vector3f(15,.18f,3),new Vector3f(0,.18f,25),new Vector3f(0,15,4),new Vector3f(0,.18f,-8))) {
            var fight=new Fight("rivet","rivet");fight.world.positions[1].set(rejected);
            fight.tick(Map.of(0,special()));fight.idle(35);
            assertEquals(800,fight.target().hp,"Outside pulse cone/range: "+rejected);
        }
        var hidden=new Fight("rivet","rivet","rivet");hidden.world.place(1,2,8);hidden.world.place(2,0,12);hidden.world.hidden.add(1);
        hidden.tick(Map.of(0,special()));hidden.idle(35);
        assertEquals(800,hidden.target().hp);assertEquals(740,hidden.session.vehicle(2).hp,.001f);
    }

    @Test void pulseUsesTargetMassAndShieldReducesBothDamageAndImpulse() {
        for(String profile:List.of("rivet","grinder","spark"))for(boolean shield:List.of(false,true)) {
            var fight=new Fight("rivet",profile);fight.world.place(1,0,8);
            if(shield)fight.target().shieldTicks=200;
            float hp=fight.target().hp;
            fight.tick(Map.of(0,special()));fight.idle(35);
            assertEquals(hp-(shield?18:60),fight.target().hp,.002f);
            float expected=Math.min(8800,9*fight.world.mass(1))*(shield?.3f:1);
            assertEquals(expected,fight.world.impulses.get(1).length(),.05f);
            assertEquals(0,fight.world.impulses.get(1).y,.0001f);
        }
    }

    @Test void grinderNeedsNativeContactThenDealsAtMostOneHundredTwentyToOneTarget() {
        var fight=new Fight("grinder","rivet","rivet");fight.intakeContact(1);
        fight.world.contacts.clear();fight.tick(Map.of(0,special()));fight.idle(72);
        assertEquals(800,fight.target().hp);assertEquals(-1,fight.target().grabbedBy);
        fight.world.contact(0,1);fight.tick();assertEquals(0,fight.target().grabbedBy);
        assertTrue(fight.player().grinding());assertEquals(1,fight.world.grabs.get(0));
        fight.idle(179);
        assertEquals(680,fight.target().hp,.02f);assertFalse(fight.player().specialActive());
        assertEquals(-1,fight.target().grabbedBy);assertEquals(360,fight.target().controlImmunityTicks);
        assertFalse(fight.world.grabs.containsKey(0));
        fight.intakeContact(2);fight.idle(20);assertEquals(800,fight.session.vehicle(2).hp);
        assertTrue(fight.player().abilityCooldown(AbilityId.SPECIAL)>0);
    }

    @Test void shieldImmediatelyReleasesHeldVictimAndEndsThisAttemptWithoutASecondCapture() {
        var fight=new Fight("grinder","rivet","spark");fight.capture();
        float hp=fight.target().hp;
        fight.tick(Map.of(1,ability(AbilityId.SHIELD)));
        assertEquals(hp,fight.target().hp,.001f);assertFalse(fight.player().specialActive());
        assertEquals(-1,fight.target().grabbedBy);assertEquals(360,fight.target().controlImmunityTicks);
        assertFalse(fight.world.grabs.containsKey(0));
        fight.intakeContact(2);fight.tick();assertEquals(640,fight.session.vehicle(2).hp);
    }

    @Test void frozenOrImmuneTargetCanTakeContactDamageButCannotBeGrabbed() {
        for(boolean frozen:List.of(false,true)) {
            var fight=new Fight("grinder","rivet");fight.intakeContact(1);
            if(frozen)fight.target().frozenTicks=600;else fight.target().controlImmunityTicks=600;
            fight.tick(Map.of(0,special()));fight.idle(72);
            assertEquals(-1,fight.target().grabbedBy);assertTrue(fight.world.grabs.isEmpty());
            assertTrue(fight.target().hp<800);fight.idle(179);assertEquals(680,fight.target().hp,.02f);
        }
    }

    @Test void freezeCannotStackOnHeldVictimButFreezingTruckReleasesIt() {
        var fight=new Fight("grinder","rivet","rivet");fight.capture();
        fight.world.nextSweep=new WorldQuery.Hit(1,fight.world.position(1),Vector3f.UNIT_Z,.5f);
        fight.tick(Map.of(2,ability(AbilityId.FREEZE)));
        assertEquals(0,fight.target().frozenTicks);assertEquals(0,fight.target().grabbedBy);
        fight.session.vehicle(2).abilityCooldown(AbilityId.FREEZE,0);
        fight.world.nextSweep=new WorldQuery.Hit(0,fight.world.position(0),Vector3f.UNIT_Z,.5f);
        fight.tick(Map.of(2,ability(AbilityId.FREEZE)));
        assertTrue(fight.player().frozenTicks>0);assertFalse(fight.player().specialActive());
        assertEquals(-1,fight.target().grabbedBy);assertEquals(360,fight.target().controlImmunityTicks);
    }

    @Test void contactLossBrokenConstraintAirborneOrObstructionEndsCapture() {
        for(String reason:List.of("contact","constraint","truck-airborne","victim-airborne","wall")) {
            var fight=new Fight("grinder","rivet");fight.capture();float hp=fight.target().hp;
            switch(reason) {
                case "contact"->fight.world.contacts.clear();
                case "constraint"->fight.world.grabs.clear();
                case "truck-airborne"->fight.world.airborne.add(0);
                case "victim-airborne"->fight.world.airborne.add(1);
                case "wall"->fight.world.hidden.add(1);
            }
            fight.tick();assertEquals(hp,fight.target().hp,.001f,reason);
            assertFalse(fight.player().specialActive(),reason);assertEquals(-1,fight.target().grabbedBy,reason);
            assertEquals(360,fight.target().controlImmunityTicks,reason);
        }
    }

    @Test void bothDriversCanFireMachineGunAndSelectedWeaponDuringHold() {
        var fight=new Fight("grinder","rivet");fight.capture();fight.combat.drainEvents();
        fight.tick(Map.of(0,fireBoth(),1,fireBoth()));
        var shots=fight.combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.SHOT).toList();
        for(int id:new int[]{0,1}) {
            final int owner=id;assertEquals(2,shots.stream().filter(e->e.sourceId()==owner).count());
            assertEquals(RULES.power().initialAmmo()-1,fight.session.vehicle(id).weapon(WeaponType.POWER).ammo);
        }
        assertEquals(0,fight.target().grabbedBy);
    }

    @Test void sparkBombWaitsPointSevenSecondsUsesLinearHullFalloffSelfHalfAndOcclusion() {
        var fight=new Fight("spark","rivet","rivet","rivet");
        fight.world.place(1,0,0);fight.world.place(2,3.1f,0);fight.world.place(3,0,0);fight.world.hidden.add(3);
        fight.tick(Map.of(0,special()));assertEquals(1,fight.combat.specialBombs().size());
        var center=fight.combat.specialBombs().getFirst().position().add(0,.08f,0);
        float own=70*Math.max(0,1-fight.world.distanceToHull(0,center)/4)*.5f;
        float edge=70*Math.max(0,1-fight.world.distanceToHull(2,center)/4);
        fight.idle(82);assertEquals(640,fight.player().hp);assertEquals(800,fight.target().hp);
        fight.tick();
        assertEquals(640-own,fight.player().hp,.01f);assertEquals(730,fight.target().hp,.01f);
        assertEquals(800-edge,fight.session.vehicle(2).hp,.01f);assertEquals(800,fight.session.vehicle(3).hp);
        assertTrue(fight.combat.specialBombs().isEmpty());
        assertEquals(1,fight.combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("special-bomb")).count());
    }

    @Test void dashCapturesSteeringOnceDoesNotCleanseControlAndRejectsUnsupportedRoadForFree() {
        for(float steering:new float[]{-1,0,1}) {
            var fight=new Fight("spark","rivet");fight.tick(Map.of(0,new VehicleCommand(0,0,steering,false,false,false,false,null,0,false,false,AbilityId.SPECIAL)));
            assertEquals(steering<0?1:-1,fight.player().dashDirection.x,.001f);
            assertEquals(1440,fight.player().abilityCooldown(AbilityId.SPECIAL));
            assertEquals(0,fight.player().dashDirection.z,.001f);
            fight.idle(41);assertFalse(fight.player().specialActive());assertTrue(fight.world.endedDash.contains(0));
            assertEquals(1,fight.combat.specialBombs().size(),"Dash duration is independent of the bomb fuse");
        }
        for(String reason:List.of("airborne","support","frozen","grabbed")) {
            var fight=new Fight("spark","rivet");
            switch(reason) {
                case "airborne"->fight.world.airborne.add(0);case "support"->fight.world.supported=false;
                case "frozen"->fight.player().frozenTicks=50;case "grabbed"->fight.player().grabbedBy=1;
            }
            fight.tick(Map.of(0,special()));assertEquals(0,fight.player().abilityCooldown(AbilityId.SPECIAL),reason);
            assertFalse(fight.player().specialActive(),reason);assertTrue(fight.combat.specialBombs().isEmpty(),reason);
        }
    }

    @Test void collisionEndingNativeDashAlsoEndsItsPhaseWhileTheBombAndCooldownContinue() {
        var fight=new Fight("spark","rivet");fight.tick(Map.of(0,special()));
        assertTrue(fight.player().dashing());fight.world.endedDash.add(0);fight.tick();
        assertFalse(fight.player().specialActive());assertEquals(1,fight.combat.specialBombs().size());
        assertEquals(1439,fight.player().abilityCooldown(AbilityId.SPECIAL));
    }

    @Test void clearAndDestructionReleaseCapturesAndRemoveTransientBombsWithoutRefundingCooldowns() {
        var held=new Fight("grinder","rivet");held.capture();held.combat.queueDamage(0,1,2000,"power",98765);held.tick();
        assertFalse(held.player().alive());assertEquals(-1,held.target().grabbedBy);assertTrue(held.world.grabs.isEmpty());
        var fight=new Fight("grinder","rivet","spark");fight.capture();fight.tick(Map.of(2,special()));
        assertEquals(1,fight.combat.specialBombs().size());int cooldown=fight.session.vehicle(2).abilityCooldown(AbilityId.SPECIAL);
        fight.combat.clear();assertTrue(fight.combat.specialBombs().isEmpty());assertTrue(fight.world.grabs.isEmpty());
        assertEquals(cooldown,fight.session.vehicle(2).abilityCooldown(AbilityId.SPECIAL));
        for(var state:fight.session.vehicles) {assertFalse(state.specialActive());assertEquals(-1,state.grabbedBy);}
    }

    private static VehicleCommand special() {return ability(AbilityId.SPECIAL);}
    private static VehicleCommand ability(AbilityId id) {return new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,id);}
    private static VehicleCommand fireBoth() {return new VehicleCommand(0,0,0,false,false,true,true,WeaponType.POWER,0,false,false,AbilityId.NONE);}
    private static final class Fight {
        final MatchSession session;final CombatSystem combat;final ObservedWorld world;
        Fight(String... profiles) {session=MatchSession.balanced(19,List.of(profiles),RULES);combat=new CombatSystem(session,RULES);world=new ObservedWorld(session);}
        VehicleState player(){return session.vehicle(0);}VehicleState target(){return session.vehicle(1);}
        void tick(){tick(Map.of());}
        void tick(Map<Integer,VehicleCommand> commands) {
            combat.beginTick(commands,world);combat.advanceProjectiles(world);combat.advanceSpecials(world);combat.resolveDamage(world);session.tick++;
        }
        void idle(int count){for(int i=0;i<count;i++)tick();}
        void intakeContact(int target) {
            float z=world.profile(0).grinderIntake().z+world.profile(target).length()/2;
            world.place(target,0,z);world.contact(0,target);
        }
        void capture(){intakeContact(1);tick(Map.of(0,special()));idle(ticks(SpecialRules.GRINDER_WINDUP));assertEquals(0,target().grabbedBy);}
    }

    /** Static box hulls model geometry; contact and constraint liveness are explicit observations. */
    private static final class ObservedWorld implements WorldQuery {
        final MatchSession session;final VehicleRules vehicleRules=VehicleRules.load();
        final Vector3f[] positions;final Map<Integer,Vector3f> impulses=new HashMap<>();
        final Set<Integer> hidden=new HashSet<>(),airborne=new HashSet<>(),frozen=new HashSet<>(),endedDash=new HashSet<>();
        final Set<String> contacts=new HashSet<>();final Map<Integer,Integer> grabs=new HashMap<>();
        boolean supported=true;Hit nextSweep;
        ObservedWorld(MatchSession session) {
            this.session=session;positions=new Vector3f[session.vehicles.size()];
            for(int id=0;id<positions.length;id++)positions[id]=new Vector3f(id==0?0:80+id*20,.18f,id==0?0:80);
        }
        void place(int id,float x,float z){positions[id].set(x,.18f,z);}
        void contact(int first,int second){contacts.add(pair(first,second));}
        String pair(int first,int second){return Math.min(first,second)+":"+Math.max(first,second);}
        public Vector3f position(int id){return positions[id].clone();}public Vector3f velocity(int id){return new Vector3f();}
        public Quaternion rotation(int id){return new Quaternion();}public boolean grounded(int id){return !airborne.contains(id);}
        public VehicleProfile profile(int id){return VehicleProfile.player(session.vehicle(id).profileId,vehicleRules);}
        public float mass(int id){return profile(id).mass();}
        public Hit ray(Vector3f from,Vector3f to,int ignored){
            // The fixture models blocked sight-lines, including specials that ignore their own body.
            for(int target:hidden)for(var sample:hullVisibilityPoints(target))if(sample.distanceSquared(to)<.000001f)
                return new Hit(-1,from.add(to.subtract(from).mult(.5f)),Vector3f.UNIT_Z,.5f);
            return null;
        }
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float stepStart,float stepEnd){var hit=nextSweep;nextSweep=null;return hit;}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int id){return !hidden.contains(id);}
        public float distanceToHull(int id,Vector3f from){return closestHullPoint(id,from).distance(from);}
        public Vector3f closestHullPoint(int id,Vector3f from) {
            Vector3f result=null;float nearest=Float.POSITIVE_INFINITY;
            for(var box:profile(id).hullBoxes()) {
                Vector3f center=positions[id].add(box.center()),extent=box.extent();
                var point=new Vector3f(Math.clamp(from.x,center.x-extent.x,center.x+extent.x),
                        Math.clamp(from.y,center.y-extent.y,center.y+extent.y),Math.clamp(from.z,center.z-extent.z,center.z+extent.z));
                float distance=point.distanceSquared(from);if(distance<nearest){nearest=distance;result=point;}
            }
            return result;
        }
        public void impulse(int id,Vector3f linear,Vector3f torque,float angular){impulses.computeIfAbsent(id,unused->new Vector3f()).addLocal(linear);}
        public Support support(Vector3f from,float depth){return supported?new Support(0,new Vector3f(from.x,0,from.z),Vector3f.UNIT_Y):null;}
        public boolean touchingVehicles(int first,int second){return contacts.contains(pair(first,second));}
        public boolean beginGrab(int owner,int target){grabs.put(owner,target);return true;}
        public void endGrab(int owner){grabs.remove(owner);}
        public boolean grabIntact(int owner,int target){return Objects.equals(grabs.get(owner),target);}
        public boolean beginDash(int id,Vector3f direction){return true;}
        public boolean dashActive(int id){return !endedDash.contains(id);}
        public void endDash(int id){endedDash.add(id);}
        public void immobilize(int id,boolean value){if(value)frozen.add(id);else frozen.remove(id);}
    }
}
