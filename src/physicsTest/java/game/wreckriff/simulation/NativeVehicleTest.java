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
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
    private static final VehicleCommand BRAKE=new VehicleCommand(0,1,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
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
    @Test void accelerationAndBrakingMeetArcadeTargetsUsingForces() {
        try(PhysicsWorld world=world()) {
            VehicleController controller=new VehicleController(world,new VehicleState(0,"Test",true,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class)),VehicleRules.load());
            int acceleration=0;
            while(world.velocity(0).z<20 && acceleration<1200) { controller.drive(GAS); world.step(); acceleration++; }
            assertTrue(world.position(0).z>10,"Positive throttle must drive +Z");
            int braking=0;
            while(world.velocity(0).z>0.5f && braking<600) { controller.drive(BRAKE); world.step(); braking++; }
            System.out.printf("P03 acceleration=%.3fs braking=%.3fs%n",acceleration/120f,braking/120f);
            assertTrue(acceleration/120f>=1.8f && acceleration/120f<=2.2f,"0->20 m/s outside agreed 1.8-2.2 s: "+acceleration/120f);
            assertTrue(braking/120f>=.75f && braking/120f<=.95f,"20->0.5 m/s outside agreed 0.75-0.95 s: "+braking/120f);
        }
    }
    @Test void positiveSteerTurnsTowardDriversRight() {
        try(PhysicsWorld world=world()) {
            VehicleController controller=new VehicleController(world,new VehicleState(0,"Test",true,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class)),VehicleRules.load());
            for(int i=0;i<120;i++) { controller.drive(GAS); world.step(); }
            VehicleCommand turn=new VehicleCommand(1,0,0.7f,false,false,false,false,null,0,false,false,AbilityId.NONE);
            for(int i=0;i<120;i++) { controller.drive(turn); world.step(); }
            System.out.printf("P02 steer result position=%s forward=%s%n",world.position(0),world.forward(0));
            assertTrue(world.position(0).x < -1,"Positive steering must turn toward driver-right (-X for +Z heading)");
        }
    }
    @Test void steeringDirectionUsesDriversFrameAtEveryCardinalHeadingAndReversesYawWhileBacking() {
        for(float yaw:new float[]{0,FastMath.HALF_PI,FastMath.PI,-FastMath.HALF_PI})for(int direction:new int[]{-1,1}) {
            try(PhysicsWorld world=world()) {
                Quaternion rotation=new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y);
                world.teleport(0,new Vector3f(0,1,0),rotation);for(int i=0;i<180;i++)world.step();
                var state=new VehicleState(0,"Direction",true,Configs.load("combat",game.wreckriff.combat.CombatRules.class));
                var controller=new VehicleController(world,state,VehicleRules.load());
                VehicleCommand straight=direction>0?GAS:BRAKE;
                for(int i=0;i<120;i++){controller.drive(straight);world.step();}
                Vector3f right=rotation.mult(Vector3f.UNIT_Z).cross(Vector3f.UNIT_Y).normalizeLocal();
                VehicleCommand turn=new VehicleCommand(direction>0?1:0,direction<0?1:0,.7f,false,false,false,false,null,0,false,false,AbilityId.NONE);
                for(int i=0;i<90;i++){controller.drive(turn);world.step();}
                assertTrue(world.forward(0).dot(right)*direction>.12f,"Heading "+yaw+" drive "+direction+" forward "+world.forward(0)+" right "+right);
            }
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
