package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeArcadeEnvelopeTest {
    private static VehicleCommand command(float gas,float reverse,float steer,boolean handbrake,boolean turbo) {
        return new VehicleCommand(gas,reverse,steer,handbrake,turbo,false,false,null,0,false,false,AbilityId.NONE);
    }
    private static final VehicleCommand GAS=command(1,0,0,false,false),BRAKE=command(0,1,0,false,false);
    private static final class Rig implements AutoCloseable {
        final VehicleRules rules=VehicleRules.load();
        final PhysicsWorld world=new PhysicsWorld(rules);
        final VehicleProfile profile;
        final VehicleState state=new VehicleState(0,"Arcade",true,Configs.load("combat",CombatRules.class));
        final VehicleController driver;
        Rig(VehicleProfile profile) {
            this.profile=profile;
            for(int z=-600;z<=1200;z+=600)
                world.addStatic(new BoxCollisionShape(new Vector3f(300,.5f,300)),new Vector3f(0,-.5f,z),new Quaternion());
            world.addVehicle(0,new Vector3f(0,profile.roadOffset()+.5f,0),new Quaternion(),profile);
            for(int tick=0;tick<360;tick++)world.step();
            assertEquals(4,world.wheelContacts(0),"Fixture must settle on all four wheels");
            driver=new VehicleController(world,state,rules);
        }
        void step(VehicleCommand command) {driver.drive(command);world.step();}
        float speed() {return world.velocity(0).dot(world.forward(0));}
        float upright() {return world.rotation(0).mult(Vector3f.UNIT_Y).y;}
        @Override public void close() {world.close();}
    }
    private static List<VehicleProfile> profiles() {
        var rules=VehicleRules.load();
        var profiles=new ArrayList<VehicleProfile>();
        for(String id:List.of("rivet","grinder","spark"))profiles.add(VehicleProfile.player(id,rules));
        profiles.addAll(VehicleProfile.bosses(rules));
        return profiles;
    }
    @Test void everyProfileBrakesWithinConservativeAiEnvelopeAtBothSpeedLimits() {
        for(var profile:profiles())for(boolean turbo:new boolean[]{false,true})try(var rig=new Rig(profile)) {
            float speed=turbo?rig.rules.turboSpeed()*profile.turboMultiplier():rig.rules.maxSpeed()*profile.speedMultiplier();
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,speed));
            Vector3f start=rig.world.position(0);
            int ticks=0;
            while(rig.speed()>.5f&&ticks<1200) {rig.step(BRAKE);ticks++;}
            float distance=rig.world.position(0).subtract(start).setY(0).length();
            System.out.printf(Locale.ROOT,"ARCADE_BRAKE profile=%s speed=%.3f time=%.3f distance=%.3f%n",profile.id(),speed,ticks*MatchSession.DT,distance);
            assertTrue(ticks<1200,profile.id()+" must stop");
            assertTrue(distance<=speed*speed/(2*10),profile.id()+" exceeds conservative AI braking distance: "+distance);
            assertTrue(rig.upright()>.9f,profile.id()+" must brake upright");
        }
    }
    @Test void everyProfileRespectsForwardTurboAndReverseLimitsWithoutClampingImpacts() {
        for(var profile:profiles())for(int mode=0;mode<3;mode++)try(var rig=new Rig(profile)) {
            boolean reverse=mode==2,turbo=mode==1;
            float limit=reverse?rig.rules.reverseSpeed()*profile.speedMultiplier():turbo?rig.rules.turboSpeed()*profile.turboMultiplier():rig.rules.maxSpeed()*profile.speedMultiplier();
            var input=command(reverse?0:1,reverse?1:0,0,false,turbo);
            // Unlimited test fuel isolates the force taper from the resource budget.
            for(int tick=0;tick<2400;tick++) {rig.state.turbo=100;rig.step(input);}
            float speed=rig.speed()*(reverse?-1:1);
            assertTrue(speed>=limit*.9f&&speed<=limit+.1f,profile.id()+" mode "+mode+" speed "+speed+" limit "+limit);
            rig.world.vehicle(0).setLinearVelocity(rig.world.forward(0).mult((reverse?-1:1)*(limit+10)));
            rig.state.turbo=100;rig.driver.drive(input);
            assertEquals(limit+10,rig.world.velocity(0).length(),.002f,"External impulse must survive drive");
            assertEquals(0,rig.world.vehicle(0).getWheel(0).getEngineForce(),.001f);
        }
    }
    @Test void releasingHandbrakeRestoresGripAndControlledAccelerationInBothDirections() {
        var rules=VehicleRules.load();
        for(int direction:new int[]{-1,1})for(float steer:new float[]{-1,1})try(var rig=new Rig(VehicleProfile.rivet(rules))) {
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,direction*14));
            for(int tick=0;tick<48;tick++)rig.step(command(0,0,steer,true,false));
            var exit=direction>0?GAS:BRAKE;
            for(int tick=0;tick<24;tick++)rig.step(exit);
            assertTrue(rig.world.vehicle(0).getWheel(2).getFrictionSlip()>=rules.frictionSlip()*.95f);
            for(int tick=0;tick<96;tick++) {
                rig.step(exit);
                assertTrue(rig.upright()>.6f,"Exit must stay upright");
            }
            float speed=rig.speed()*direction;
            Vector3f horizontal=rig.world.velocity(0).setY(0);
            assertTrue(speed>6,"Must accelerate away after releasing handbrake: "+speed);
            assertTrue(horizontal.normalizeLocal().dot(rig.world.forward(0))*direction>.95f,"Exit motion must align with heading");
        }
    }
}
