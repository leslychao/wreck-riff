package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrivingBoundaryTest {
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,false,0,false,false);
    private static final VehicleCommand DRIFT=new VehicleCommand(0,0,1,true,false,false,false,false,0,false,false);
    private static final VehicleCommand RECOVER=new VehicleCommand(0,0,0,false,false,false,false,false,0,false,true);
    private PhysicsWorld world() {
        PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
        world.addStatic(new BoxCollisionShape(new Vector3f(200,0.5f,200)),new Vector3f(0,-0.5f,0),new Quaternion());
        world.addVehicle(0,new Vector3f(0,1,0),new Quaternion());
        for(int i=0;i<240;i++)world.step();
        return world;
    }
    @Test void handbrakeReversesCourseWithinAgreedWindowWithoutFlipping() {
        try(PhysicsWorld world=world()) {
            var state=new VehicleState(0,"Test",true); var driver=new VehicleController(world,state,VehicleRules.load());
            for(int tick=0;tick<600&&world.velocity(0).z<20;tick++){driver.drive(GAS);world.step();}
            float previous=0,total=0; int first150=-1;
            for(int tick=1;tick<=156;tick++) {
                driver.drive(DRIFT);world.step();Vector3f forward=world.forward(0);
                float yaw=(float)Math.atan2(forward.x,forward.z),change=yaw-previous;
                if(change>FastMath.PI)change-=FastMath.TWO_PI;if(change< -FastMath.PI)change+=FastMath.TWO_PI;
                total+=change;previous=yaw;
                if(first150<0&&total>=150*FastMath.DEG_TO_RAD)first150=tick;
                assertTrue(world.rotation(0).mult(Vector3f.UNIT_Y).y>0.6f,"Handbrake must not flip the chassis");
            }
            System.out.printf(java.util.Locale.ROOT,"P04 handbrake first150=%.3fs yawAt1.3s=%.1f%n",first150/120f,total*FastMath.RAD_TO_DEG);
            assertTrue(first150>=96 && first150<=156,"150 degree turn must take 0.8..1.3s: "+first150/120f);
        }
    }
    @Test void nativeTrajectoriesAgreeAtThirtySixtyAnd144RenderFps() {
        Vector3f reference=null; float referenceYaw=0;
        for(int fps:new int[]{30,60,144}) {
            try(PhysicsWorld world=world()) {
                var driver=new VehicleController(world,new VehicleState(0,"Test",true),VehicleRules.load());
                var loop=new SimulationLoop(MatchRules.load()); int[] ticks={0};
                for(int frame=0;frame<fps*4;frame++)loop.advance(1.0/fps,true,()->{
                    VehicleCommand command=ticks[0]<340?GAS:DRIFT; driver.drive(command);world.step();ticks[0]++;
                });
                assertEquals(480,ticks[0]);Vector3f position=world.position(0);float yaw=(float)Math.atan2(world.forward(0).x,world.forward(0).z);
                if(reference==null){reference=position;referenceYaw=yaw;}
                else {assertTrue(reference.distance(position)/Math.max(1,reference.length())<=0.05f);assertEquals(referenceYaw,yaw,0.05f);}
            }
        }
    }
    @Test void turboConsumesOnlyOnGroundAndWaitsBeforeRegenerating() {
        try(PhysicsWorld world=world()) {
            var state=new VehicleState(0,"Test",true);var driver=new VehicleController(world,state,VehicleRules.load());
            VehicleCommand turbo=new VehicleCommand(1,0,0,false,true,false,false,false,0,false,false);
            driver.drive(turbo);world.step();assertTrue(state.turbo<100);
            float after=state.turbo;world.teleport(0,new Vector3f(0,20,0),new Quaternion());
            for(int i=0;i<60;i++){driver.drive(turbo);world.step();}
            assertEquals(after,state.turbo,0.001f,"Airborne turbo must neither consume nor regenerate before 1s");
            for(int i=0;i<61;i++){driver.drive(VehicleCommand.NONE);world.step();}
            assertTrue(state.turbo>after);
        }
    }
    @Test void manualOccupiedRecoveryDoesNotSpendAndEmergencyWithoutSafePoseIsFatal() {
        try(PhysicsWorld world=world()) {
            var state=new VehicleState(0,"Test",true);var driver=new VehicleController(world,state,VehicleRules.load());
            Vector3f safe=world.position(0);world.teleport(0,new Vector3f(4,1,0),new Quaternion());
            world.addVehicle(1,safe,new Quaternion());
            VehicleController.Recovery recovery=VehicleController.Recovery.NONE;
            for(int i=0;i<120;i++)recovery=driver.prepare(RECOVER,240+i);
            assertFalse(recovery.recovered());assertFalse(recovery.fatal());assertEquals(0,recovery.cost());assertEquals(0,state.recoveryCooldown);
            world.teleport(0,new Vector3f(90,1,0),new Quaternion());state.recoveryCooldown=600;
            world.vehicle(0).setLinearVelocity(new Vector3f(40,0,0));
            recovery=driver.prepare(VehicleCommand.NONE,400);
            assertTrue(recovery.fatal());assertEquals(200,recovery.cost());
        }
    }
    @Test void emergencyBypassesSpeedAndCooldownButPaysOnce() {
        try(PhysicsWorld world=world()) {
            var state=new VehicleState(0,"Test",true);var driver=new VehicleController(world,state,VehicleRules.load());
            Vector3f safe=world.position(0);world.teleport(0,new Vector3f(90,1,0),new Quaternion());
            state.recoveryCooldown=600;world.vehicle(0).setLinearVelocity(new Vector3f(40,0,0));
            var recovery=driver.prepare(VehicleCommand.NONE,300);
            assertTrue(recovery.recovered());assertEquals(15,recovery.cost());assertEquals(1,state.recoveries);
            assertTrue(world.position(0).distance(safe)<0.001f);assertEquals(0,world.velocity(0).length(),0.001f);
            assertEquals(VehicleController.Recovery.NONE,driver.prepare(VehicleCommand.NONE,301));
        }
    }
}
