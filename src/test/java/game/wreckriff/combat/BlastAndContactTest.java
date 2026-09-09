package game.wreckriff.combat;

import com.jme3.math.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlastAndContactTest {
    private final MatchSession session=new MatchSession(42,360);
    private final CombatSystem combat=new CombatSystem(session,session.combatRules);
    private final ContactWorld world=new ContactWorld();
    private static VehicleCommand fire(WeaponType type) {return new VehicleCommand(0,0,0,false,false,false,true,type,-1,false,false,AbilityId.NONE);}
    private static VehicleCommand ability(AbilityId ability) {return new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,ability);}
    private static VehicleCommand mg() {return new VehicleCommand(0,0,0,false,false,true,false,null,0,false,false,AbilityId.NONE);}
    private void tick(Map<Integer,VehicleCommand> commands) {combat.beginTick(commands,world);combat.advanceProjectiles(world);combat.resolveDamage(world);session.finishTick();}
    private void tick(VehicleCommand command) {tick(Map.of(0,command));}
    private WorldQuery.Hit contact(int target) {return new WorldQuery.Hit(target,new Vector3f(.7f,.5f,6),new Vector3f(0,0,-1),.5f);}
    private Vector3f delta(int id) {return world.linear.get(id).divide(world.mass(id));}
    private static float horizontal(Vector3f value) {return (float)Math.sqrt(value.x*value.x+value.z*value.z);}

    @Test void directSelectionWinsBeforeFiringAndDirectRocketUsesFullPointImpulse() {
        world.positions[1].set(0,0,7);world.hits.add(contact(1));tick(fire(WeaponType.POWER));
        assertEquals(WeaponType.POWER,session.vehicle(0).selectedWeapon);
        assertEquals(3,session.vehicle(0).weapon(WeaponType.POWER).ammo);
        assertEquals(6,session.vehicle(0).weapon(WeaponType.HOMING).ammo);
        assertEquals(350,session.vehicle(1).hp);assertEquals(6,horizontal(delta(1)),.0001f);assertEquals(2,delta(1).y,.0001f);
        Vector3f expectedTorque=contact(1).point().subtract(world.position(1)).cross(world.linear.get(1));
        assertEquals(expectedTorque,world.torque.get(1));assertTrue(expectedTorque.length()>0);
        assertEquals(2,world.angularCap);assertEquals(1,world.calls.get(1));
    }
    @Test void shieldScalesDirectDamageAndBothImpulseComponentsAndCarriesContact() {
        world.positions[1].set(0,0,7);world.hits.add(contact(1));
        tick(Map.of(0,fire(WeaponType.POWER),1,ability(AbilityId.SHIELD)));
        assertEquals(385,session.vehicle(1).hp,.0001f);
        assertEquals(1.8f,horizontal(delta(1)),.0001f);assertEquals(.6f,delta(1).y,.0001f);
        var hit=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.SHIELD_HIT).findFirst().orElseThrow();
        assertEquals(50,hit.value());assertEquals(contact(1).point(),hit.position());assertEquals(contact(1).normal(),hit.normal());
    }
    @Test void spawnProtectionRejectsDamageImpulseAndShieldFeedback() {
        world.positions[1].set(0,0,7);session.vehicle(1).protectionTicks=120;session.vehicle(1).shieldTicks=120;
        world.hits.add(contact(1));tick(fire(WeaponType.POWER));
        assertEquals(400,session.vehicle(1).hp);assertFalse(world.linear.containsKey(1));
        assertTrue(combat.drainEvents().stream().noneMatch(e->e.type()==GameEvent.Type.SHIELD_HIT));
    }
    @Test void radialForceUsesHullFalloffAndVisibilityAndOwnerMultiplier() {
        world.positions[0].set(0,0,0);world.positions[1].set(4,0,3);world.hidden.add(1);
        world.hits.add(new WorldQuery.Hit(-1,new Vector3f(0,0,3),Vector3f.ZERO,1));tick(fire(WeaponType.HOMING));
        assertEquals(4*(1-2f/6)*.5f,horizontal(delta(0)),.0001f);
        assertEquals((1-2f/6)*.5f,delta(0).y,.0001f);assertFalse(world.linear.containsKey(1));
        assertEquals(400,session.vehicle(1).hp);
    }
    @Test void simultaneousBlastsAreSummedOnceAndCappedSeparately() {
        world.positions[1].set(0,0,7);
        for(int i=0;i<4;i++)world.hits.add(contact(1));
        tick(Map.of(0,fire(WeaponType.POWER),2,fire(WeaponType.POWER),3,fire(WeaponType.POWER),4,fire(WeaponType.POWER)));
        assertEquals(200,session.vehicle(1).hp);assertEquals(9,horizontal(delta(1)),.0001f);assertEquals(6,delta(1).y,.0001f);
        assertEquals(1,world.calls.get(1));assertTrue(world.torque.get(1).length()>0);
    }
    @Test void mineDirectlyUnderHullHasNoInventedHorizontalDirection() {
        tick(fire(WeaponType.MINE));Vector3f center=combat.mines().getFirst().position().add(0,.2f,0);
        world.positions[0].set(30,0,0);world.positions[1].set(center.add(0,.5f,0));
        for(int i=0;i<72;i++)tick(VehicleCommand.NONE);
        assertTrue(combat.mines().isEmpty());assertEquals(0,horizontal(delta(1)),.00001f);assertEquals(5,delta(1).y,.0001f);
    }
    @Test void napalmImpactPushesButOngoingFireNeverAddsAnImpulse() {
        world.positions[1].set(0,1,10);
        world.hits.add(new WorldQuery.Hit(-1,new Vector3f(0,0,10),Vector3f.UNIT_Y,.5f));tick(fire(WeaponType.NAPALM));
        assertEquals(.5f,delta(1).y,.0001f);assertEquals(0,horizontal(delta(1)),.0001f);
        float hp=session.vehicle(1).hp;world.linear.clear();world.torque.clear();world.calls.clear();
        for(int i=0;i<30;i++)tick(VehicleCommand.NONE);
        assertTrue(session.vehicle(1).hp<hp);assertTrue(world.linear.isEmpty());
    }
    @Test void hitscanHasAlternatingActualOriginsAndContactGeometryButMissHasNoImpact() {
        world.rayHit=contact(1);tick(mg());session.vehicle(0).machineGunCooldown=0;tick(mg());
        var events=combat.drainEvents();var shots=events.stream().filter(e->e.type()==GameEvent.Type.SHOT).toList();
        assertEquals(2,shots.size());assertEquals(world.machineGunMuzzle(0,0),shots.get(0).origin());assertEquals(world.machineGunMuzzle(0,1),shots.get(1).origin());
        for(GameEvent shot:shots) {
            var impact=events.stream().filter(e->e.type()==GameEvent.Type.IMPACT&&e.eventId()==shot.eventId()).findFirst().orElseThrow();
            assertEquals(contact(1).point(),shot.position());assertEquals(shot.position(),impact.position());assertEquals(1,impact.subjectId());assertEquals(contact(1).normal(),impact.normal());
        }
        world.rayHit=null;session.vehicle(0).machineGunCooldown=0;tick(mg());
        assertTrue(combat.drainEvents().stream().noneMatch(e->e.type()==GameEvent.Type.IMPACT));
    }
    @Test void blockedFreezeHasShieldFeedbackWithoutFrostAndFeedbackIsBoundedPerCar() {
        world.hits.add(contact(1));tick(Map.of(0,ability(AbilityId.FREEZE),1,ability(AbilityId.SHIELD)));
        var events=combat.drainEvents();assertFalse(session.vehicle(1).controlled());
        assertTrue(events.stream().noneMatch(e->e.type()==GameEvent.Type.FREEZE));
        var blocked=events.stream().filter(e->e.type()==GameEvent.Type.SHIELD_HIT).findFirst().orElseThrow();assertEquals(0,blocked.value());
        world.rayHit=contact(1);
        for(int i=0;i<11;i++) {session.vehicle(0).machineGunCooldown=0;tick(mg());}
        assertTrue(combat.drainEvents().stream().noneMatch(e->e.type()==GameEvent.Type.SHIELD_HIT));
        session.vehicle(0).machineGunCooldown=0;tick(mg());
        var feedback=combat.drainEvents();
        var shieldHit=feedback.stream().filter(e->e.type()==GameEvent.Type.SHIELD_HIT).findFirst().orElseThrow();
        var shot=feedback.stream().filter(e->e.type()==GameEvent.Type.SHOT&&e.eventId()==shieldHit.eventId()).findFirst().orElseThrow();
        assertEquals(shot.origin(),shieldHit.origin());assertEquals(shot.position(),shieldHit.position());
        assertEquals(1,feedback.stream().filter(e->e.type()==GameEvent.Type.SHIELD_HIT).count());
    }
    @Test void naturalShieldExpiryIsDistinctFromTeardownAndEventVectorsAreDefensive() {
        tick(ability(AbilityId.SHIELD));combat.drainEvents();
        for(int i=0;i<299;i++)tick(VehicleCommand.NONE);
        assertEquals(1,session.vehicle(0).shieldTicks);assertTrue(combat.drainEvents().isEmpty());
        tick(VehicleCommand.NONE);assertEquals(1,combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.SHIELD_ENDED).count());
        session.vehicle(0).frozenTicks=120;session.vehicle(0).shieldTicks=120;combat.clear();assertTrue(combat.drainEvents().isEmpty());
        Vector3f vector=new Vector3f(1,2,3);GameEvent event=new GameEvent(GameEvent.Type.IMPACT,1,1,0,vector,"power",0,vector,vector);
        vector.zero();event.position().zero();event.origin().zero();event.normal().zero();assertEquals(new Vector3f(1,2,3),event.position());assertEquals(event.position(),event.origin());assertEquals(event.position(),event.normal());
    }
    @Test void deathAtFreezeExpiryAndActiveShieldDoesNotEmitExpiryOrCleanseNoise() {
        session.vehicle(1).frozenTicks=1;session.vehicle(1).shieldTicks=120;
        combat.queueDamage(1,0,2000,"power",10000);tick(VehicleCommand.NONE);
        assertFalse(session.vehicle(1).alive());assertEquals(0,session.vehicle(1).frozenTicks);assertEquals(0,session.vehicle(1).shieldTicks);
        assertTrue(combat.drainEvents().stream().noneMatch(e->e.type()==GameEvent.Type.CONTROL_ENDED||e.type()==GameEvent.Type.SHIELD_ENDED));
    }
    @Test void lethalHitOnShieldExpiryDoesNotLeaveAnEndSoundForDestroyedVehicle() {
        session.vehicle(1).shieldTicks=1;combat.queueDamage(1,0,1000,"power",10000);tick(VehicleCommand.NONE);
        assertFalse(session.vehicle(1).alive());assertTrue(combat.drainEvents().stream().noneMatch(e->e.type()==GameEvent.Type.SHIELD_ENDED));
    }

    private static final class ContactWorld implements WorldQuery {
        final Vector3f[] positions={new Vector3f(),new Vector3f(100,0,100),new Vector3f(120,0,100),new Vector3f(140,0,100),new Vector3f(160,0,100)};
        final Map<Integer,Vector3f> linear=new HashMap<>(),torque=new HashMap<>();final Map<Integer,Integer> calls=new HashMap<>();
        final Set<Integer> hidden=new HashSet<>();final Deque<Hit> hits=new ArrayDeque<>();Hit rayHit;float angularCap;
        public Vector3f position(int id){return positions[id].clone();}public Vector3f velocity(int id){return new Vector3f();}
        public Quaternion rotation(int id){return new Quaternion();}public boolean grounded(int id){return true;}public float mass(int id){return 1100;}
        public Hit ray(Vector3f from,Vector3f to,int ignored){return from.distance(to)<2?null:rayHit;}
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored){return hits.pollFirst();}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int id){return !hidden.contains(id);}
        public float distanceToHull(int id,Vector3f point){return Math.max(0,position(id).distance(point)-1);}
        public Vector3f closestHullPoint(int id,Vector3f from){Vector3f offset=from.subtract(position(id));return offset.length()<=1?from.clone():position(id).add(offset.normalizeLocal());}
        public void impulse(int id,Vector3f value,Vector3f angular,float cap){linear.put(id,value.clone());torque.put(id,angular.clone());calls.merge(id,1,Integer::sum);angularCap=cap;}
        public Support support(Vector3f from,float depth){return new Support(0,new Vector3f(from.x,0,from.z),Vector3f.UNIT_Y);}
    }
}
