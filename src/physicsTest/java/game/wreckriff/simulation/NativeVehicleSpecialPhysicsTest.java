package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.joints.New6Dof;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Native trajectories, contact witnesses and constraint lifetime for player specials. */
class NativeVehicleSpecialPhysicsTest {
    private static final VehicleRules RULES=VehicleRules.load();
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
    private static final VehicleCommand TURBO=new VehicleCommand(1,0,0,false,true,false,false,null,0,false,false,AbilityId.NONE);

    private PhysicsWorld road() {
        var world=new PhysicsWorld(RULES);
        world.addStatic("road",new BoxCollisionShape(new Vector3f(200,.5f,200)),new Vector3f(0,-.5f,0),new Quaternion());
        return world;
    }
    private VehicleState add(PhysicsWorld world,int id,VehicleDefinition definition,float z) {
        var profile=definition.profile(RULES);
        world.addVehicle(id,new Vector3f(0,profile.roadOffset()+.4f,z),new Quaternion(),profile);
        return new VehicleState(id,definition.displayName(),true,definition.id(),0,false,definition.maximumHp(),Configs.load("combat",CombatRules.class));
    }
    private void settle(PhysicsWorld world) {for(int tick=0;tick<240;tick++)world.step();}

    @ParameterizedTest @EnumSource(VehicleDefinition.class)
    void eachPlayerProfileHasFourSupportedWheelsAndOwnNativeDynamics(VehicleDefinition definition) {
        try(var world=road()) {
            var state=add(world,0,definition,0);settle(world);
            assertEquals(4,world.supportedWheelContacts(0));
            var controller=new VehicleController(world,state,RULES);
            for(int tick=0;tick<480;tick++){controller.drive(GAS);world.step();}
            assertTrue(world.position(0).z>25,definition+" actual acceleration");
            assertTrue(world.velocity(0).z>10&&world.velocity(0).z<RULES.maxSpeed()*definition.profile(RULES).speedMultiplier()+1);
            assertTrue(world.rotation(0).mult(Vector3f.UNIT_Y).y>.95f);
            assertEquals(4,world.supportedWheelContacts(0));
            assertNull(world.ray(world.muzzle(0),world.muzzle(0).add(0,0,10),-1),"Own muzzle must clear physical hull");
        }
    }

    @Test void dashMovesSevenMetresThroughNativeBodyAndKeepsForwardMomentum() {
        try(var world=road()) {
            var state=add(world,0,VehicleDefinition.SPARK,0);settle(world);
            world.vehicle(0).setLinearVelocity(new Vector3f(0,0,12));
            Vector3f start=world.position(0);
            assertTrue(world.beginDash(0,Vector3f.UNIT_X));
            for(int tick=0;tick<42;tick++)world.step();
            assertTrue(world.dashActive(0));
            assertEquals(7,world.position(0).x-start.x,.25f);
            assertTrue(world.position(0).z-start.z>3.5f,"Forward momentum survives lateral impulse");
            assertTrue(world.velocity(0).length()<=48);
            world.endDash(0);
            assertEquals(0,world.velocity(0).x,.25f);assertTrue(world.velocity(0).z>10);
            assertFalse(world.dashActive(0));assertTrue(state.alive());
        }
    }

    @Test void wallStopsDashAndHoldingPhaseCannotRestartIt() {
        try(var world=road()) {
            var state=add(world,0,VehicleDefinition.SPARK,0);settle(world);
            world.addStatic("wall",new BoxCollisionShape(new Vector3f(.1f,3,10)),new Vector3f(3,2,0),new Quaternion());
            state.specialPhase=VehicleState.SpecialPhase.DASH;state.dashDirection.set(1,0,0);
            var controller=new VehicleController(world,state,RULES);
            for(int tick=0;tick<42;tick++){controller.drive(VehicleCommand.NONE);world.step();}
            assertFalse(world.dashActive(0));
            assertTrue(world.position(0).x<2.15f,"The hull cannot cross the wall");
            assertTrue(world.position(0).x>1,"Dash must move before meeting the wall");
            assertTrue(Math.abs(world.velocity(0).x)<.5f);
        }
    }

    @Test void airFreezeRecoveryAndDeathEndDash() {
        try(var world=road()) {
            add(world,0,VehicleDefinition.SPARK,0);settle(world);
            assertTrue(world.beginDash(0,Vector3f.UNIT_X));world.step();
            world.teleport(0,new Vector3f(0,10,0),new Quaternion());
            assertFalse(world.dashActive(0));assertFalse(world.beginDash(0,Vector3f.UNIT_X));
            world.teleport(0,new Vector3f(0,1,0),new Quaternion());settle(world);
            assertTrue(world.beginDash(0,Vector3f.UNIT_X));world.immobilize(0,true);
            assertFalse(world.dashActive(0));world.immobilize(0,false);
            assertTrue(world.beginDash(0,Vector3f.UNIT_X));world.makeWreck(0);
            assertFalse(world.dashActive(0));
        }
    }

    private VehicleState contact(PhysicsWorld world) {
        var owner=add(world,0,VehicleDefinition.GRINDER,0);add(world,1,VehicleDefinition.RIVET,6.5f);settle(world);
        assertFalse(world.beginGrab(0,1),"Proximity is not actual intake contact");
        var driver=new VehicleController(world,owner,RULES);
        for(int tick=0;tick<240&&!world.touchingVehicles(0,1);tick++){driver.drive(GAS);world.step();}
        assertTrue(world.touchingVehicles(0,1),"The native chassis must contact the target");
        assertTrue(world.beginGrab(0,1));return owner;
    }
    @Test void captureConstrainsTwoCollidingBodiesAndCapsDrivingWithoutTurbo() {
        try(var world=road()) {
            var owner=contact(world);owner.specialPhase=VehicleState.SpecialPhase.GRINDER_CONTACT;
            var driver=new VehicleController(world,owner,RULES);
            assertEquals(1,world.grabCount());assertEquals(1,world.space().countJoints());
            assertTrue(((New6Dof)world.space().getJointList().iterator().next()).isCollisionBetweenLinkedBodies(),
                    "Capture cannot disable physical contacts between the vehicles");
            world.vehicle(0).setLinearVelocity(new Vector3f(0,0,24));
            world.vehicle(1).setLinearVelocity(new Vector3f(0,0,24));
            float initialDistance=world.position(0).distance(world.position(1));float turbo=owner.turbo;
            for(int tick=0;tick<180;tick++) {
                driver.drive(TURBO);world.step();
                assertTrue(world.grabIntact(0,1),"Native capture must remain at intake, tick "+tick);
                assertTrue(world.touchingVehicles(0,1),"Grinding requires a persistent native contact, tick "+tick);
                assertEquals(initialDistance,world.position(0).distance(world.position(1)),.15f);
                assertTrue(world.velocity(0).clone().setY(0).length()<12.1f,"Capture speed at tick "+tick+": "+world.velocity(0));
            }
            assertFalse(driver.turboActive());assertEquals(turbo,owner.turbo);
            world.endGrab(0);assertEquals(0,world.space().countJoints());
            float gap=world.position(1).z-world.position(0).z;
            Vector3f ownerVelocity=world.velocity(0),targetVelocity=world.velocity(1);
            world.impulse(1,new Vector3f(0,0,11000),Vector3f.ZERO,2);
            assertEquals(ownerVelocity,world.velocity(0),"Released target impulse must not alter the former owner");
            assertEquals(targetVelocity.z+10,world.velocity(1).z,.001f);
            for(int tick=0;tick<60;tick++)world.step();
            assertTrue(world.position(1).z-world.position(0).z>gap+1,"Released target rolls independently on its wheels");
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"teleport","wreck","remove","freeze"})
    void captureLifecycleReleasesBothEnds(String operation) {
        try(var world=road()) {
            contact(world);
            switch(operation) {
                case "teleport" -> world.teleport(1,world.position(1).add(0,0,5),world.rotation(1));
                case "wreck" -> world.makeWreck(1);
                case "remove" -> world.removeVehicle(1);
                case "freeze" -> world.immobilize(0,true);
                default -> throw new AssertionError(operation);
            }
            assertEquals(0,world.grabCount());assertFalse(world.grabIntact(0,1));
            assertEquals(operation.equals("freeze")?1:0,world.space().countJoints());
            world.endGrab(0);
        }
    }
}
