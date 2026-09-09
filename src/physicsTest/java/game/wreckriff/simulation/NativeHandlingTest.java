package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeHandlingTest {
    private static VehicleCommand command(float gas,float reverse,float steer,boolean handbrake) {
        return new VehicleCommand(gas,reverse,steer,handbrake,false,false,false,null,0,false,false,AbilityId.NONE);
    }
    private static final VehicleCommand GAS=command(1,0,0,false),REVERSE=command(0,1,0,false);
    private static final class Rig implements AutoCloseable {
        final VehicleRules rules=VehicleRules.load();
        final PhysicsWorld world=new PhysicsWorld(rules);
        final VehicleController driver;
        Rig() {this("rivet");}
        Rig(String profileId) {
            var profile=profileId.equals("rivet")?VehicleProfile.rivet(rules):VehicleProfile.boss(profileId,rules);
            world.addStatic(new BoxCollisionShape(new Vector3f(300,.5f,300)),new Vector3f(0,-.5f,0),new Quaternion());
            world.addVehicle(0,new Vector3f(0,profile.roadOffset()+.5f,0),new Quaternion(),profile);
            for(int i=0;i<360;i++)world.step();
            driver=new VehicleController(world,new VehicleState(0,"Handling",true,Configs.load("combat",CombatRules.class)),rules);
        }
        void step(VehicleCommand command) {driver.drive(command);world.step();}
        float speed() {return world.velocity(0).dot(world.forward(0));}
        @Override public void close() {world.close();}
    }
    private record Turn(float radius,float yaw,float upright) {}
    private Turn turn(float speed,float steer,boolean handbrake,String profile) {
        try(var rig=new Rig(profile)) {
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,speed));
            float previousYaw=0,totalYaw=0,distance=0,upright=1;
            Vector3f previousPosition=rig.world.position(0);
            for(int tick=0;tick<120;tick++) {
                rig.step(command(0,0,steer,handbrake));
                Vector3f forward=rig.world.forward(0),position=rig.world.position(0);
                float yaw=(float)Math.atan2(forward.x,forward.z),delta=yaw-previousYaw;
                if(delta>FastMath.PI)delta-=FastMath.TWO_PI;
                if(delta< -FastMath.PI)delta+=FastMath.TWO_PI;
                totalYaw+=delta;previousYaw=yaw;
                distance+=position.subtract(previousPosition).multLocal(1,0,1).length();previousPosition=position;
                upright=Math.min(upright,rig.world.rotation(0).mult(Vector3f.UNIT_Y).y);
            }
            return new Turn(distance/Math.abs(totalYaw),totalYaw,upright);
        }
    }
    private float reverseToEight() {
        try(var rig=new Rig()) {
            int ticks=0;
            while(rig.speed()>-8&&ticks<600) {rig.step(REVERSE);ticks++;}
            assertTrue(ticks<600,"Must reach 8 m/s in reverse");
            return ticks*MatchSession.DT;
        }
    }
    @Test void maneuverMeasurements() {
        Turn slow=turn(8,1,false,"rivet"),medium=turn(14,1,false,"rivet");
        float reverseSeconds=reverseToEight();
        System.out.printf(java.util.Locale.ROOT,"HANDLING radius8=%.6f radius14=%.6f reverse0to8=%.6f%n",slow.radius,medium.radius,reverseSeconds);
        // Measured on this native fixture before the September 9 handling change.
        assertTrue(slow.radius<=6.419723f*.85f,"8 m/s turn must be at least 15% tighter: "+slow.radius);
        assertTrue(medium.radius<=7.662365f*.85f,"14 m/s turn must be at least 15% tighter: "+medium.radius);
        assertTrue(reverseSeconds<=1.266667f*.85f,"Reverse acceleration must be at least 15% faster: "+reverseSeconds);
        assertTrue(slow.upright>.9f&&medium.upright>.9f);
    }
    @Test void directionChangesBrakeThenWaitSixStepsAndReapplyWithoutDelay() {
        for(int direction:new int[]{-1,1})try(var rig=new Rig()) {
            VehicleCommand requested=direction<0?REVERSE:GAS;
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,-direction*8));
            for(int i=0;i<12;i++) {
                rig.driver.drive(requested);
                assertEquals(0,rig.world.vehicle(0).getWheel(0).getEngineForce());
                assertTrue(rig.world.vehicle(0).getWheel(0).getBrake()>0);
            }
            rig.world.vehicle(0).setLinearVelocity(Vector3f.ZERO);
            for(int i=1;i<6;i++) {
                rig.step(requested);
                assertEquals(0,rig.world.vehicle(0).getWheel(0).getEngineForce(),"Must wait through step "+i);
            }
            rig.step(requested);
            assertTrue(rig.world.vehicle(0).getWheel(0).getEngineForce()*direction>0);
            rig.step(VehicleCommand.NONE);
            rig.step(requested);
            assertTrue(rig.world.vehicle(0).getWheel(0).getEngineForce()*direction>0,"Committed direction resumes immediately");
        }
    }
    @Test void releasingPendingSwitchResetsDelayAndBrakeWinsSimultaneousInput() {
        try(var rig=new Rig()) {
            for(int i=0;i<3;i++)rig.step(REVERSE);
            rig.step(VehicleCommand.NONE);
            for(int i=0;i<5;i++) {
                rig.step(REVERSE);
                assertEquals(0,rig.world.vehicle(0).getWheel(0).getEngineForce());
            }
            rig.step(command(1,1,0,false));
            assertTrue(rig.world.vehicle(0).getWheel(0).getEngineForce()<0);
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,8));
            rig.driver.drive(command(1,1,0,false));
            assertEquals(0,rig.world.vehicle(0).getWheel(0).getEngineForce());
            assertTrue(rig.world.vehicle(0).getWheel(0).getBrake()>0);
        }
    }
    @Test void rollingInRequestedDirectionNeedsNoPauseAndReverseTapersAtLimit() {
        try(var rig=new Rig()) {
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,-3));
            rig.step(REVERSE);
            assertTrue(rig.world.vehicle(0).getWheel(0).getEngineForce()<0);
            for(int tick=0;tick<1200;tick++)rig.step(REVERSE);
            assertTrue(rig.speed()< -14&&rig.speed()>=-15.1f,"Actual reverse speed: "+rig.speed());
            rig.world.vehicle(0).setLinearVelocity(rig.world.forward(0).mult(-20));
            rig.driver.drive(REVERSE);
            assertEquals(0f,rig.world.vehicle(0).getWheel(0).getEngineForce(),0f);
            assertEquals(20,rig.world.velocity(0).length(),.001f,"Controller must not truncate impact velocity");
        }
    }
    @Test void steeringAndHandbrakeAreSymmetricAndFollowActualDirection() {
        for(float speed:new float[]{8,14,-8,-14})for(boolean handbrake:new boolean[]{false,true}) {
            Turn right=turn(speed,1,handbrake,"rivet"),left=turn(speed,-1,handbrake,"rivet");
            assertTrue(right.yaw*Math.signum(speed)<-.1f,"Right turn yaw at "+speed+" handbrake "+handbrake+": "+right.yaw);
            assertTrue(left.yaw*Math.signum(speed)>.1f);
            assertEquals(Math.abs(right.yaw),Math.abs(left.yaw),.04f);
            assertTrue(right.upright>.6f&&left.upright>.6f);
        }
        for(float speed:new float[]{28,40}) {
            Turn highSpeed=turn(speed,1,false,"rivet");
            assertTrue(highSpeed.upright>.6f,"High-speed steering must not overturn");
        }
    }
    @Test void allBossProfilesTurnAndReverseWithoutOverturning() {
        for(var profile:VehicleProfile.bosses(VehicleRules.load())) {
            for(float speed:new float[]{8,-8}) {
                Turn maneuver=turn(speed,1,false,profile.id());
                assertTrue(maneuver.yaw*Math.signum(speed)<-.04f,profile.id()+" must turn in the requested direction");
                assertTrue(maneuver.upright>.6f,profile.id()+" must stay upright");
            }
            try(var rig=new Rig(profile.id())) {
                for(int tick=0;tick<360;tick++)rig.step(REVERSE);
                assertTrue(rig.speed()< -4,profile.id()+" must back up under real mass");
            }
        }
    }
    @Test void alternatingDriveAndReverseAgreeAcrossRenderRates() {
        Vector3f reference=null;Quaternion referenceRotation=null;
        for(int fps:new int[]{30,60,144})try(var rig=new Rig()) {
            var loop=new SimulationLoop(MatchRules.load());int[] ticks={0};
            for(int frame=0;frame<fps*8;frame++)loop.advance(1.0/fps,true,()->{
                int tick=ticks[0]++;
                rig.step(tick<240?command(1,0,.3f,false):tick<600?command(0,1,-.6f,false):command(1,0,.6f,false));
            });
            assertEquals(960,ticks[0]);
            if(reference==null) {reference=rig.world.position(0);referenceRotation=rig.world.rotation(0);}
            else {
                assertTrue(reference.distance(rig.world.position(0))<.01f);
                assertTrue(Math.abs(referenceRotation.dot(rig.world.rotation(0)))>.999f);
            }
        }
    }
}
