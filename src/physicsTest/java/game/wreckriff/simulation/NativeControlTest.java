package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real Bullet constraints: no fake immobilization flag can satisfy these trajectories. */
class NativeControlTest {
    private PhysicsWorld world(boolean floor) {
        PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
        if(floor)world.addStatic(new BoxCollisionShape(new Vector3f(100,.5f,100)),new Vector3f(0,-.5f,0),new Quaternion());
        world.addVehicle(0,new Vector3f(0,floor?1:20,0),new Quaternion().fromAngleAxis(.7f,Vector3f.UNIT_Y));
        if(floor)for(int tick=0;tick<240;tick++)world.step();
        return world;
    }
    @Test void freezePinsHorizontalPoseAgainstRepeatedBlastImpulsesButReleasesImmediately() {
        try(PhysicsWorld world=world(true)) {
            Vector3f start=world.position(0);Quaternion orientation=world.rotation(0);
            world.vehicle(0).setLinearVelocity(new Vector3f(20,0,15));
            world.vehicle(0).setAngularVelocity(new Vector3f(2,3,4));
            world.immobilize(0,true);world.immobilize(0,true);
            assertEquals(1,world.immobilizerCount());assertEquals(1,world.space().countJoints());
            for(int tick=0;tick<240;tick++) {
                if(tick%12==0)world.impulse(0,new Vector3f(6600,0,3300),Vector3f.ZERO,2);
                world.step();
                assertTrue(horizontalDistance(start,world.position(0))<.15f,"Native constraint must pin XZ, tick "+tick+" pose "+world.position(0));
                assertTrue(Math.abs(orientation.dot(world.rotation(0)))>.995f,"Rotation remains locked");
            }
            world.immobilize(0,false);assertEquals(0,world.space().countJoints());
            world.impulse(0,new Vector3f(6600,0,0),Vector3f.ZERO,2);
            for(int i=0;i<60;i++)world.step();
            assertTrue(world.position(0).x-start.x>1,"Fresh impulse must move released body");
        }
    }
    @Test void airborneFreezePreservesGravityAndDoesNotRestoreOldVelocity() {
        try(PhysicsWorld world=world(false)) {
            world.vehicle(0).setLinearVelocity(new Vector3f(20,3,10));
            world.immobilize(0,true);Vector3f start=world.position(0);
            for(int tick=0;tick<120;tick++)world.step();
            assertTrue(world.position(0).y<start.y-4,"World Y must remain free under gravity");
            assertTrue(horizontalDistance(start,world.position(0))<.03f);
            world.immobilize(0,false);
            assertEquals(0,world.velocity(0).x,.1f);assertEquals(0,world.velocity(0).z,.1f);
            for(int tick=0;tick<12;tick++)world.step();
            assertTrue(horizontalDistance(start,world.position(0))<.05f);
        }
    }
    @Test void rampFreezeAndPhysicalRamDoNotBreakConstraintAndDeathRecoveryRemoveIt() {
        try(PhysicsWorld world=new PhysicsWorld(VehicleRules.load())) {
            Quaternion ramp=new Quaternion().fromAngleAxis(-.18f,Vector3f.UNIT_X);
            world.addStatic(new BoxCollisionShape(new Vector3f(20,.5f,25)),new Vector3f(0,0,0),ramp);
            world.addVehicle(0,new Vector3f(0,2,0),ramp);
            world.addVehicle(1,new Vector3f(0,2,-10),ramp);
            for(int tick=0;tick<240;tick++)world.step();
            world.immobilize(0,true);Vector3f start=world.position(0);
            world.teleport(1,start.add(0,.05f,-7),ramp);world.vehicle(1).setLinearVelocity(new Vector3f(0,0,22));
            for(int tick=0;tick<120;tick++)world.step();
            assertTrue(horizontalDistance(start,world.position(0))<.2f,"Ramp/ram must not move frozen chassis horizontally");
            world.teleport(0,start,ramp);assertEquals(0,world.immobilizerCount());
            world.immobilize(0,true);world.removeVehicle(0);assertEquals(0,world.space().countJoints());
        }
    }
    @Test void repeatedConstraintLifecyclesLeaveNoJoints() {
        try(PhysicsWorld world=world(true)) {
            for(int cycle=0;cycle<40;cycle++) {
                world.immobilize(0,true);world.step();world.immobilize(0,false);
                assertEquals(0,world.space().countJoints());assertEquals(0,world.immobilizerCount());
            }
        }
    }
    private static float horizontalDistance(Vector3f a,Vector3f b) {return new Vector3f(a.x-b.x,0,a.z-b.z).length();}
}
