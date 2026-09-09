package game.wreckriff.simulation;

import game.wreckriff.combat.AbilityId;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeVehicleTest {
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,false,0,false,false,AbilityId.NONE);
    private static final VehicleCommand BRAKE=new VehicleCommand(0,1,0,false,false,false,false,false,0,false,false,AbilityId.NONE);
    private PhysicsWorld world() {
        PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
        world.addStatic(new BoxCollisionShape(new Vector3f(300,0.5f,300)),new Vector3f(0,-0.5f,0),new Quaternion());
        world.addVehicle(0,new Vector3f(0,1,0),new Quaternion());
        for(int i=0;i<240;i++) world.step();
        return world;
    }
    @Test void nativeFloorSupportsFourCorrectlyOrientedWheelsAndCleansUp() {
        try(PhysicsWorld world=world()) {
            System.out.printf("P02 settled position=%s contacts=%d speed=%.3f%n",world.position(0),world.wheelContacts(0),world.velocity(0).length());
            assertTrue(world.position(0).y>0.25f && world.position(0).y<1.25f);
            assertEquals(4,world.wheelContacts(0));
            float lowest=world.position(0).y, highest=lowest;
            for(int i=0;i<120;i++) { world.step(); lowest=Math.min(lowest,world.position(0).y); highest=Math.max(highest,world.position(0).y); }
            assertTrue(highest-lowest<0.01f,"Settled suspension must not visibly bounce: "+(highest-lowest));
            assertEquals(2,world.bodyCount());
            world.removeVehicle(0); assertEquals(1,world.bodyCount());
        }
    }
    @Test void accelerationAndBrakingMeetMvpTargetsUsingForces() {
        try(PhysicsWorld world=world()) {
            VehicleController controller=new VehicleController(world,new VehicleState(0,"Test",true),VehicleRules.load());
            int acceleration=0;
            while(world.velocity(0).z<20 && acceleration<1200) { controller.drive(GAS); world.step(); acceleration++; }
            assertTrue(world.position(0).z>10,"Positive throttle must drive +Z");
            int braking=0;
            while(world.velocity(0).z>0.5f && braking<600) { controller.drive(BRAKE); world.step(); braking++; }
            System.out.printf("P03 acceleration=%.3fs braking=%.3fs%n",acceleration/120f,braking/120f);
            assertTrue(acceleration/120f>=2.6f && acceleration/120f<=3.2f,"0->20 m/s outside agreed 2.6-3.2 s: "+acceleration/120f);
            assertTrue(braking/120f>=1.0f && braking/120f<=1.5f,"20->0 outside agreed 1.0-1.5 s: "+braking/120f);
        }
    }
    @Test void positiveSteerTurnsTowardPositiveX() {
        try(PhysicsWorld world=world()) {
            VehicleController controller=new VehicleController(world,new VehicleState(0,"Test",true),VehicleRules.load());
            for(int i=0;i<120;i++) { controller.drive(GAS); world.step(); }
            VehicleCommand turn=new VehicleCommand(1,0,0.7f,false,false,false,false,false,0,false,false,AbilityId.NONE);
            for(int i=0;i<120;i++) { controller.drive(turn); world.step(); }
            System.out.printf("P02 steer result position=%s forward=%s%n",world.position(0),world.forward(0));
            assertTrue(world.position(0).x>1,"Positive steering must turn +X");
        }
    }
    @Test void staticAndRelativeSweepsSeeThinWallAndMovingTarget() {
        try(PhysicsWorld world=world()) {
            world.addStatic(new BoxCollisionShape(new Vector3f(3,3,0.025f)),new Vector3f(15,3,0),new Quaternion());
            var hit=world.sweep(new Vector3f(15,1,-3),new Vector3f(15,1,3),0.15f,0);
            assertNotNull(hit); assertEquals(-1,hit.vehicleId()); assertTrue(hit.fraction()<0.6f);
            world.teleport(0,new Vector3f(-2,0.6f,10),new Quaternion());
            world.vehicle(0).setLinearVelocity(new Vector3f(480,0,0));
            world.step();
            var crossing=world.sweep(new Vector3f(0,0.9f,7),new Vector3f(0,0.9f,13),0.15f,-1);
            assertNotNull(crossing,"Moving body crossing projectile in same tick must be swept relatively");
            assertEquals(0,crossing.vehicleId());
        }
    }
}
