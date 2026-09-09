package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ordinary disturbances must retain real Bullet motion without tipping a car onto its hull. */
class NativeStabilityTest {
    @Test void oneSupportedWheelStillResistsAnOutwardRollThroughNativeTorque() {
        float assisted=singleWheelRoll(true),unassisted=singleWheelRoll(false);
        assertTrue(assisted<unassisted-.02f,"One wheel must retain roll resistance: assisted="+assisted+", unassisted="+unassisted);
    }
    private float singleWheelRoll(boolean assist) {
        VehicleRules rules=VehicleRules.load();VehicleProfile profile=VehicleProfile.rivet(rules);
        try(var world=new PhysicsWorld(rules)) {
            Quaternion rotation=new Quaternion().fromAngleAxis(.4f,Vector3f.UNIT_Z);
            Vector3f position=new Vector3f(0,2,0);
            Vector3f down=rotation.mult(Vector3f.UNIT_Y).negateLocal();
            Vector3f contact=position.add(rotation.mult(profile.wheelConnection(0)))
                    .addLocal(down.mult(profile.suspensionRestLength()+profile.wheelRadius()-.08f));
            world.addStatic(new BoxCollisionShape(new Vector3f(.25f,.1f,.25f)),contact.add(0,-.1f,0),new Quaternion());
            world.addVehicle(0,position,rotation);
            assertEquals(1,world.wheelContacts(0));assertEquals(1,world.supportedWheelContacts(0));
            world.vehicle(0).setAngularVelocity(new Vector3f(0,0,1));
            if(assist)new VehicleController(world,new VehicleState(0,"Single wheel",true,Configs.load("combat",CombatRules.class)),rules).drive(VehicleCommand.NONE);
            world.step();
            return world.vehicle(0).getAngularVelocity().z;
        }
    }
    private static VehicleCommand command(float throttle,float steer,boolean turbo) {
        return new VehicleCommand(throttle,0,steer,false,turbo,false,false,null,0,false,false,AbilityId.NONE);
    }

    private static final class Rig implements AutoCloseable {
        final VehicleRules rules=VehicleRules.load();
        final PhysicsWorld world=new PhysicsWorld(rules);
        final VehicleController driver;

        Rig() {
            world.addStatic(new BoxCollisionShape(new Vector3f(300,.5f,300)),new Vector3f(0,-.5f,0),new Quaternion());
            world.addVehicle(0,new Vector3f(0,1,0),new Quaternion());
            for(int tick=0;tick<360;tick++)world.step();
            driver=driver(0);
            assertEquals(4,world.wheelContacts(0),"Fixture must start on all four native wheels");
        }

        VehicleController driver(int id) {
            return new VehicleController(world,new VehicleState(id,"Stability "+id,id==0,
                    Configs.load("combat",CombatRules.class)),rules);
        }

        void step(VehicleCommand command) {driver.drive(command);world.step();}
        float upright(int id) {return world.rotation(id).mult(Vector3f.UNIT_Y).y;}
        @Override public void close() {world.close();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void repeatedNormalAndTurboTurnsStayUprightInBothDirections(boolean turbo) {
        for(int direction:new int[]{-1,1})try(var rig=new Rig()) {
            float speed=turbo?rig.rules.turboSpeed():rig.rules.maxSpeed();
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,speed));
            float minimumUp=1,totalYaw=0,distance=0;
            int turboTicks=0;
            Vector3f previousForward=rig.world.forward(0),previousPosition=rig.world.position(0);
            for(int tick=0;tick<720;tick++) {
                // Four successive left/right changes, without re-seeding velocity between turns.
                float steer=direction*((tick/180)%2==0?1:-1);
                rig.step(command(1,steer,turbo));
                if(rig.driver.turboActive())turboTicks++;
                Vector3f forward=rig.world.forward(0),position=rig.world.position(0);
                totalYaw+=Math.abs((float)Math.atan2(previousForward.cross(forward).y,previousForward.dot(forward)));
                distance+=position.subtract(previousPosition).setY(0).length();
                minimumUp=Math.min(minimumUp,rig.upright(0));
                previousForward=forward;previousPosition=position;
            }
            assertTrue(totalYaw>2,"The car must actually make repeated turns: "+totalYaw);
            assertTrue(distance>25,"Stability must preserve driving: "+distance);
            assertTrue(minimumUp>.6f,"Ordinary steering must remain well short of a rollover: "+minimumUp);
            assertTrue(rig.upright(0)>.9f,"The final turn must leave the wheels underneath the car");
            if(turbo)assertTrue(turboTicks>120,"Turbo must really engage during the steering scenario");
            assertEquals(0,rig.world.teleportGeneration(0));
        }
    }

    @ParameterizedTest @ValueSource(ints={-1,1})
    void moderateNativeSideRamMovesTheTargetWithoutOverturningIt(int direction) {
        try(var rig=new Rig()) {
            Quaternion heading=new Quaternion().fromAngleAxis(direction*FastMath.HALF_PI,Vector3f.UNIT_Y);
            rig.world.addVehicle(1,new Vector3f(-direction*10,1,.8f),heading);
            for(int tick=0;tick<360;tick++)rig.world.step();
            Vector3f targetStart=rig.world.position(0);
            // Close enough to retain the small height difference at contact, as when
            // a car comes off a bump into the other car's side. Offset Z adds point torque.
            rig.world.teleport(1,new Vector3f(-direction*4.3f,targetStart.y+.25f,.8f),heading);
            VehicleController attacker=rig.driver(1);
            rig.world.vehicle(1).setLinearVelocity(new Vector3f(direction*18,0,0));
            float minimumUp=1,peakTargetSpeed=0,maxClosingSpeed=0,peakDisplacement=0;
            boolean contacted=false;
            for(int tick=0;tick<480;tick++) {
                rig.driver.drive(VehicleCommand.NONE);attacker.drive(VehicleCommand.NONE);rig.world.step();
                for(var ram:rig.world.rams()) {
                    if(ram.first()==0&&ram.second()==1||ram.first()==1&&ram.second()==0) {
                        contacted=true;maxClosingSpeed=Math.max(maxClosingSpeed,ram.closingSpeed());
                    }
                }
                minimumUp=Math.min(minimumUp,rig.upright(0));
                peakTargetSpeed=Math.max(peakTargetSpeed,rig.world.velocity(0).length());
                peakDisplacement=Math.max(peakDisplacement,(rig.world.position(0).x-targetStart.x)*direction);
            }
            float displacement=(rig.world.position(0).x-targetStart.x)*direction;
            String measurements="direction="+direction+", displacement="+displacement+", peakDisplacement="+peakDisplacement
                    +", minUp="+minimumUp+", maxClosingSpeed="+maxClosingSpeed+", peakTargetSpeed="+peakTargetSpeed;
            assertTrue(contacted,"The cars must actually collide, not pass each other: "+measurements);
            assertTrue(maxClosingSpeed>4,"Fixture must deliver a moderate moving ram: "+measurements);
            assertTrue(peakTargetSpeed>1,"Stability must not erase collision momentum: "+measurements);
            assertTrue(displacement>.5f,"The collision must physically displace the target: "+measurements);
            assertTrue(minimumUp>.35f,"Moderate side ram must not tip the target onto its hull: "+measurements);
            assertTrue(rig.upright(0)>.9f);
            assertTrue(rig.world.wheelContacts(0)>=2,"The target must finish supported by wheels");
            assertEquals(0,rig.world.teleportGeneration(0));
        }
    }

    @ParameterizedTest @ValueSource(ints={-1,1})
    void staggeredWheelBumpsAndSmallRampDropKeepTheCarDrivable(int firstSide) {
        try(var rig=new Rig()) {
            // A 20 cm bump under one side, then under the other: real asymmetric wheel loading.
            rig.world.addStatic(new BoxCollisionShape(new Vector3f(.65f,.1f,.45f)),
                    new Vector3f(firstSide,.1f,5),new Quaternion());
            rig.world.addStatic(new BoxCollisionShape(new Vector3f(.65f,.1f,.45f)),
                    new Vector3f(-firstSide,.1f,8),new Quaternion());
            // A shallow 8 m ramp rises about 80 cm; its far edge drops back to the floor.
            rig.world.addStatic(new BoxCollisionShape(new Vector3f(3,.12f,4)),
                    new Vector3f(0,.12f+4*FastMath.sin(.1f),15),
                    new Quaternion().fromAngleAxis(-.1f,Vector3f.UNIT_X));
            float startHeight=rig.world.position(0).y,maximumHeight=startHeight,minimumUp=1;
            int airborneTicks=0,settledTicks=0;
            rig.world.vehicle(0).setLinearVelocity(new Vector3f(0,0,14));
            for(int tick=0;tick<600;tick++) {
                rig.step(command(.6f,0,false));
                maximumHeight=Math.max(maximumHeight,rig.world.position(0).y);
                minimumUp=Math.min(minimumUp,rig.upright(0));
                if(rig.world.wheelContacts(0)==0)airborneTicks++;
                if(tick>=480&&rig.world.wheelContacts(0)>=2&&rig.upright(0)>.9f)settledTicks++;
            }
            assertTrue(maximumHeight>startHeight+.5f,"The car must actually climb the ramp: "+maximumHeight);
            assertTrue(airborneTicks>=6,"The car must leave support and land through native physics: "+airborneTicks);
            assertTrue(minimumUp>.6f,"Small road disturbances must not overturn the car: "+minimumUp);
            assertTrue(rig.world.position(0).z>35,"The car must continue beyond the landing area");
            assertTrue(settledTicks>=110,"Landing must remain stable during the final second: "+settledTicks);
            assertTrue(rig.world.velocity(0).dot(rig.world.forward(0))>5,"The landed car must remain drivable");
            assertEquals(0,rig.world.teleportGeneration(0));
        }
    }
}
