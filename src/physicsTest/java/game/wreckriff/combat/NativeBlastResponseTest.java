package game.wreckriff.combat;

import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks actual Bullet velocity response and constraints, without replacing the physics owner. */
class NativeBlastResponseTest {
    private final MatchSession session=new MatchSession(42,360);
    private final CombatSystem combat=new CombatSystem(session,session.combatRules);
    private PhysicsWorld world() {
        PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
        world.addVehicle(0,new Vector3f(-.7f,10,0),new Quaternion());
        world.addVehicle(1,new Vector3f(0,10,7),new Quaternion());
        for(int id=2;id<5;id++)world.addVehicle(id,new Vector3f(100+id*10,10,100),new Quaternion());
        return world;
    }
    private static VehicleCommand power() {return new VehicleCommand(0,0,0,false,false,false,true,WeaponType.POWER,0,false,false,AbilityId.NONE);}
    private void tick(PhysicsWorld world,Map<Integer,VehicleCommand> commands) {
        combat.beginTick(commands,world);combat.advanceProjectiles(world);combat.resolveDamage(world);session.finishTick();
    }
    private void hit(PhysicsWorld world) {
        tick(world,Map.of(0,power()));
        for(int i=0;i<30&&!combat.projectiles().isEmpty();i++)tick(world,Map.of());
        assertTrue(combat.projectiles().isEmpty());
    }
    private static float horizontal(Vector3f value) {return (float)Math.sqrt(value.x*value.x+value.z*value.z);}

    @Test void offCentreDirectRocketAddsNativeHorizontalLiftAndPointTorque() {
        try(PhysicsWorld world=world()) {
            hit(world);assertEquals(350,session.vehicle(1).hp);
            assertEquals(6,horizontal(world.velocity(1)),.0001f);assertEquals(2,world.velocity(1).y,.0001f);
            float angular=world.vehicle(1).getAngularVelocity().length();assertTrue(angular>.1f);assertTrue(angular<=2.0001f);
            GameEvent impact=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.IMPACT&&e.subjectId()==1).findFirst().orElseThrow();
            assertEquals(0,world.distanceToHull(1,impact.position()),.045f,"Contact is on the collider, not the swept sphere center");
            Vector3f before=world.position(1);world.step();
            assertTrue(world.position(1).y>before.y);assertTrue(world.position(1).distance(before)>.01f);
        }
    }
    @Test void shieldReducesNativeLinearAndAngularResponseBySameMultiplier() {
        float expectedShieldAngular;
        try(PhysicsWorld world=world()) {
            hit(world);
            GameEvent contact=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.IMPACT&&e.subjectId()==1).findFirst().orElseThrow();
            Vector3f rawTorque=contact.position().subtract(world.position(1)).cross(world.velocity(1).mult(world.mass(1)));
            expectedShieldAngular=Math.min(2,world.vehicle(1).getInverseInertiaWorld(null).mult(rawTorque.mult(.3f)).length());
        }
        MatchSession shieldSession=new MatchSession(42,360);CombatSystem shieldCombat=new CombatSystem(shieldSession,shieldSession.combatRules);
        try(PhysicsWorld world=world()) {
            shieldSession.vehicle(1).shieldTicks=300;
            shieldCombat.beginTick(Map.of(0,power()),world);shieldCombat.advanceProjectiles(world);shieldCombat.resolveDamage(world);
            for(int i=0;i<30&&!shieldCombat.projectiles().isEmpty();i++) {shieldSession.finishTick();shieldCombat.beginTick(Map.of(),world);shieldCombat.advanceProjectiles(world);shieldCombat.resolveDamage(world);}
            assertEquals(385,shieldSession.vehicle(1).hp,.0001f);assertEquals(1.8f,horizontal(world.velocity(1)),.0001f);assertEquals(.6f,world.velocity(1).y,.0001f);
            assertEquals(expectedShieldAngular,world.vehicle(1).getAngularVelocity().length(),.001f);
        }
    }
    @Test void simultaneousRealRocketSweepsRespectAllAggregateCaps() {
        try(PhysicsWorld world=world()) {
            world.teleport(2,new Vector3f(.7f,10,0),new Quaternion());world.teleport(3,new Vector3f(.3f,10,0),new Quaternion());world.teleport(4,new Vector3f(-.3f,10,0),new Quaternion());
            tick(world,Map.of(0,power(),2,power(),3,power(),4,power()));
            for(int i=0;i<30&&!combat.projectiles().isEmpty();i++)tick(world,Map.of());
            assertEquals(200,session.vehicle(1).hp);assertTrue(horizontal(world.velocity(1))<=9.0001f);assertEquals(6,world.velocity(1).y,.0001f);
            assertTrue(world.vehicle(1).getAngularVelocity().length()<=2.0001f);
        }
    }
    @Test void angularCapLimitsAddedVelocityWithoutClampingExistingSpinAndUsesWorldInertia() {
        try(PhysicsWorld world=world()) {
            world.teleport(1,new Vector3f(0,10,7),new Quaternion().fromAngles(.3f,.8f,.2f));
            Vector3f before=new Vector3f(3,-4,2);world.vehicle(1).setAngularVelocity(before);
            world.impulse(1,new Vector3f(0,5500,0),new Vector3f(1e6f,-2e6f,3e6f),2);
            assertEquals(2,world.vehicle(1).getAngularVelocity().subtract(before).length(),.0001f);
            assertEquals(5,world.velocity(1).y,.0001f);
        }
    }
    @Test void frozenHullKeepsXZAndRotationWhileBlastCanLiftAndGravityStillActs() {
        try(PhysicsWorld world=world()) {
            world.immobilize(1,true);Vector3f start=world.position(1);Quaternion rotation=world.rotation(1);
            world.impulse(1,new Vector3f(6600,6600,3300),new Vector3f(50000,20000,10000),2);
            for(int i=0;i<12;i++)world.step();
            assertTrue(world.position(1).y>start.y+.2f);assertTrue(horizontal(world.position(1).subtract(start))<.03f);
            assertTrue(Math.abs(rotation.dot(world.rotation(1)))>.999f);
            for(int i=0;i<120;i++)world.step();assertTrue(world.velocity(1).y<0);assertTrue(horizontal(world.position(1).subtract(start))<.03f);
            world.immobilize(1,false);assertEquals(0,world.space().countJoints());
        }
    }
    @Test void closestHullPointUsesColliderGeometryInRotatedWorldPose() {
        try(PhysicsWorld world=world()) {
            Quaternion rotation=new Quaternion().fromAngles(.2f,.6f,0);Vector3f center=new Vector3f(0,10,7);
            world.teleport(1,center,rotation);
            Vector3f sample=center.add(rotation.mult(new Vector3f(8,.2f,0)));
            Vector3f point=world.closestHullPoint(1,sample);
            assertEquals(0,world.distanceToHull(1,point),.0001f);
            assertTrue(point.distance(center)>.5f);assertTrue(point.distance(sample)<center.distance(sample));
        }
    }
}
