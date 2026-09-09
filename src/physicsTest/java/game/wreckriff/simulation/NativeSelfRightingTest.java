package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class NativeSelfRightingTest {
    private static VehicleCommand command(float gas,float reverse,float steer) {
        return new VehicleCommand(gas,reverse,steer,false,false,false,false,null,0,false,false,AbilityId.NONE);
    }
    private static final VehicleCommand GAS=command(1,0,0),REVERSE=command(0,1,0),LEFT=command(0,0,-1),RIGHT=command(0,0,1);
    private static Stream<Arguments> overturnedInputs() {
        return Stream.of(90f,-90f,180f).flatMap(angle->Stream.of(
                Arguments.of(angle,"gas",GAS),Arguments.of(angle,"reverse",REVERSE),
                Arguments.of(angle,"left",LEFT),Arguments.of(angle,"right",RIGHT)));
    }
    private static final class Rig implements AutoCloseable {
        final VehicleRules rules=VehicleRules.load();
        final PhysicsWorld world=new PhysicsWorld(rules);
        final VehicleProfile profile;
        final VehicleState state=new VehicleState(0,"Righting",true,Configs.load("combat",CombatRules.class));
        final VehicleController driver;
        int ticks;
        Rig(float roll) {this(roll,"rivet");}
        Rig(float roll,String profileId) {
            profile=profileId.equals("rivet")?VehicleProfile.rivet(rules):VehicleProfile.boss(profileId,rules);
            world.addStatic("floor",new BoxCollisionShape(new Vector3f(300,.5f,300)),new Vector3f(0,-.5f,0),new Quaternion());
            Quaternion rotation=new Quaternion().fromAngleAxis(.4f,Vector3f.UNIT_Y)
                    .mult(new Quaternion().fromAngleAxis(roll*FastMath.DEG_TO_RAD,Vector3f.UNIT_Z));
            float minimum=Float.POSITIVE_INFINITY;
            for(var box:profile.hullBoxes())for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1}) {
                Vector3f point=box.center().add(box.extent().mult(new Vector3f(x,y,z)));
                minimum=Math.min(minimum,rotation.mult(point).y);
            }
            world.addVehicle(0,new Vector3f(0,-minimum+.03f,0),rotation,profile);
            for(int i=0;i<240;i++)world.step();
            assertTrue(up()<.35f,"Fixture must remain overturned before input: "+up());
            assertTrue(world.chassisSupported(0),"Fixture must rest on its actual hull");
            state.turbo=100;
            driver=new VehicleController(world,state,rules);
        }
        float up() {return world.rotation(0).mult(Vector3f.UNIT_Y).y;}
        void step(VehicleCommand command) {
            assertEquals(VehicleController.Recovery.NONE,driver.prepare(command,ticks++));
            driver.drive(command);world.step();
        }
        int right(VehicleCommand command,int maxTicks) {
            boolean engaged=false;int stable=0;
            for(int tick=1;tick<=maxTicks;tick++) {
                step(command);engaged|=driver.rightingActive();
                stable=up()>.9f&&world.wheelContacts(0)>=2?stable+1:0;
                if(stable>=18&&!driver.rightingActive()) {
                    assertTrue(engaged,"Input must actually invoke righting");
                    return tick;
                }
            }
            fail("Must stand on wheels within "+maxTicks+" ticks; up="+up()+" contacts="+world.wheelContacts(0)+" staticWheels="+world.supportedWheelContacts(0)
                    +" supported="+world.chassisSupported(0)+" angular="+world.vehicle(0).getAngularVelocity()
                    +" velocity="+world.velocity(0)+" active="+driver.rightingActive());
            return -1;
        }
        @Override public void close() {world.close();}
    }
    @ParameterizedTest(name="{0} degrees, {1}") @MethodSource("overturnedInputs")
    void ordinaryHeldInputRightsBothSidesAndExactRoofThenCarCanDrive(float roll,String name,VehicleCommand input) {
        try(var rig=new Rig(roll)) {
            float hp=rig.state.hp;long generation=rig.world.teleportGeneration(0);
            var ammunition=rig.state.weapons().stream().map(weapon->weapon.ammo).toList();
            int elapsed=rig.right(input,240);
            System.out.printf(Locale.ROOT,"RIGHTING roll=%.0f input=%s seconds=%.3f%n",roll,name,elapsed*MatchSession.DT);
            assertEquals(hp,rig.state.hp);assertEquals(100,rig.state.turbo);
            assertEquals(ammunition,rig.state.weapons().stream().map(weapon->weapon.ammo).toList());
            assertEquals(0,rig.state.recoveries);assertEquals(0,rig.state.recoveryCooldown);assertEquals(0,rig.state.protectionTicks);
            assertEquals(generation,rig.world.teleportGeneration(0));
            for(int tick=0;tick<120;tick++) {rig.step(input);assertTrue(rig.up()>.85f,"No second roll while held");}
            Vector3f before=rig.world.position(0);
            for(int tick=0;tick<120;tick++)rig.step(GAS);
            assertTrue(rig.world.position(0).distance(before)>1,"Must be able to drive away");
        }
    }
    @ParameterizedTest @ValueSource(strings={"boss_foreman","boss_prefect","boss_emcee","boss_ash_shepherd","boss_director"})
    void realBossMassAndInertiaCanRightOnTheSameControls(String profileId) {
        for(float angle:new float[]{90,-90,180})try(var rig=new Rig(angle,profileId)) {
            int elapsed=rig.right(GAS,240);
            System.out.printf(Locale.ROOT,"BOSS_RIGHTING profile=%s roll=%.0f seconds=%.3f%n",profileId,angle,elapsed*MatchSession.DT);
            assertEquals(0,rig.state.recoveries);
        }
    }
    @Test void noInputDoesNotRightAndHoldMustBeContinuous() {
        try(var rig=new Rig(180)) {
            for(int i=0;i<480;i++) {rig.step(VehicleCommand.NONE);assertFalse(rig.driver.rightingActive());}
            assertTrue(rig.up()<-.95f);
            int hold=Math.round(rig.rules.selfRighting().holdSeconds()*120);
            for(int i=0;i<hold-1;i++) {rig.step(GAS);assertFalse(rig.driver.rightingActive());}
            rig.step(VehicleCommand.NONE);
            for(int i=0;i<hold-1;i++) {rig.step(RIGHT);assertFalse(rig.driver.rightingActive());}
            rig.step(RIGHT);assertTrue(rig.driver.rightingActive());
            rig.step(VehicleCommand.NONE);assertFalse(rig.driver.rightingActive());
        }
    }
    @ParameterizedTest @ValueSource(strings={"frozen","dead","launch","impact","fast","airborne","car-contact"})
    void blockedStatesCannotStartAssistance(String blocked) {
        try(var rig=new Rig(180)) {
            switch(blocked) {
                case "frozen" -> {rig.state.frozenTicks=500;rig.world.immobilize(0,true);}
                case "dead" -> {rig.state.hp=0;rig.world.makeWreck(0);}
                case "launch" -> rig.driver.beginLaunch(Vector3f.UNIT_Z);
                case "impact" -> rig.state.heavyImpactPending=true;
                case "fast" -> rig.world.vehicle(0).setLinearVelocity(new Vector3f(4,0,0));
                case "airborne" -> {rig.world.teleport(0,new Vector3f(0,50,0),rig.world.rotation(0));rig.world.step();}
                case "car-contact" -> {
                    // Two hull roofs touching; the upper upside-down car cannot
                    // suspend itself above the lower body on wheel rays.
                    rig.world.addVehicle(1,rig.world.position(0).add(0,1.05f,0),rig.world.rotation(0));
                    rig.world.immobilize(1,true);
                    for(int i=0;i<240;i++)rig.world.step();
                    assertTrue(rig.world.touchingVehicle(0));
                }
            }
            for(int tick=0;tick<24;tick++) {
                if(blocked.equals("fast"))rig.world.vehicle(0).setLinearVelocity(new Vector3f(4,0,0));
                rig.step(GAS);
                assertFalse(rig.driver.rightingActive(),blocked);
            }
        }
    }
    @Test void releasingTeleportingOrFreezingAnActiveAttemptClearsItsState() {
        for(String change:new String[]{"release","teleport","freeze","death","launch"})try(var rig=new Rig(180)) {
            for(int tick=0;tick<25;tick++)rig.step(RIGHT);
            assertTrue(rig.driver.rightingActive());
            switch(change) {
                case "teleport" -> rig.world.teleport(0,new Vector3f(0,50,0),new Quaternion());
                case "freeze" -> {rig.state.frozenTicks=500;rig.world.immobilize(0,true);}
                case "death" -> {rig.state.hp=0;rig.world.makeWreck(0);}
                case "launch" -> rig.driver.beginLaunch(Vector3f.UNIT_Z);
            }
            rig.step(change.equals("release")?VehicleCommand.NONE:RIGHT);
            assertFalse(rig.driver.rightingActive(),change);
        }
    }
    @Test void exactRoofSteeringChoosesOppositeSidesAndDoesNotChangeSideMidAttempt() {
        Vector3f left=null;
        for(VehicleCommand input:new VehicleCommand[]{LEFT,RIGHT})try(var rig=new Rig(180)) {
            for(int tick=0;tick<36;tick++)rig.step(input);
            Vector3f angular=rig.world.vehicle(0).getAngularVelocity();
            assertTrue(angular.length()>.3f);
            if(left==null)left=angular;else assertTrue(left.dot(angular)<0,"Mirrored steer must choose opposite roll");
            rig.step(input==LEFT?RIGHT:LEFT);
            assertTrue(angular.dot(rig.world.vehicle(0).getAngularVelocity())>0,"Changing steer must not flip the locked roll axis");
        }
    }
    @Test void wreckStopsReportingRightingWithoutAnotherDriverTick() {
        try(var rig=new Rig(180)) {
            for(int tick=0;tick<25;tick++)rig.step(GAS);
            assertTrue(rig.driver.rightingActive());
            rig.state.hp=0;rig.world.makeWreck(0);
            // MatchRuntime omits dead drivers and advances only the native wreck.
            rig.world.step();
            assertFalse(rig.driver.rightingActive());
        }
    }
    @Test void selfRightingIsIndependentOfRenderRate() {
        Vector3f position=null;Quaternion rotation=null;
        for(int fps:new int[]{30,60,144})try(var rig=new Rig(180)) {
            var loop=new SimulationLoop(MatchRules.load());
            for(int frame=0;frame<fps*3;frame++)loop.advance(1.0/fps,true,()->rig.step(GAS));
            assertEquals(360,rig.ticks);assertTrue(rig.up()>.9f);
            if(position==null) {position=rig.world.position(0);rotation=rig.world.rotation(0);}
            else {
                assertTrue(position.distance(rig.world.position(0))<.01f);
                assertTrue(Math.abs(rotation.dot(rig.world.rotation(0)))>.999f);
            }
        }
    }
}
