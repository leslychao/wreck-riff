package game.wreckriff.simulation;

import com.jme3.bullet.collision.PhysicsCollisionListener;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.config.VehicleProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeBodySupportTest {
    private record Sample(Vector3f position,Vector3f velocity,Vector3f up,boolean supported,boolean touching,int supportedWheels) {}

    @ParameterizedTest @ValueSource(ints={-90,90,180})
    void floorSupportsTheChassisOnEitherSideAndOnItsRoof(int degrees) {
        try(PhysicsWorld world=floor()) {
            world.addVehicle(0,new Vector3f(0,3,0),roll(degrees));
            // Hold just the orientation/XZ so each fixture continues to exercise its
            // named face; native gravity and the floor determine vertical support.
            world.immobilize(0,true);
            assertFalse(world.chassisSupported(0),"Creation cannot claim contact before a native step");
            for(int tick=0;tick<180;tick++)world.step();
            assertTrue(world.chassisSupported(0),"Native chassis support missing for roll="+degrees);
            assertEquals(0,world.wheelContacts(0));
            assertFalse(world.touchingVehicle(0));
            for(int tick=0;tick<30;tick++) {
                world.step();
                assertTrue(world.chassisSupported(0),"Ongoing resting contact must refresh the snapshot");
            }
        }
    }

    @Test void suspendedWheelsAreSeparateFromChassisSupport() {
        try(PhysicsWorld world=floor()) {
            world.addVehicle(0,new Vector3f(0,2,0),new Quaternion());
            for(int tick=0;tick<180;tick++)world.step();
            assertEquals(4,world.wheelContacts(0));
            assertEquals(4,world.supportedWheelContacts(0));
            assertFalse(world.chassisSupported(0),"Wheel rays cannot invent a chassis contact");
            world.removeStatic("floor");
            assertEquals(0,world.supportedWheelContacts(0),"Removed static support must disappear immediately");
            world.teleport(0,new Vector3f(0,20,0),new Quaternion());
            assertEquals(0,world.supportedWheelContacts(0));
        }
    }

    @Test void unconstrainedRestingRoofKeepsSupportDespiteNativeSolverSlop() {
        try(PhysicsWorld world=floor()) {
            world.addVehicle(0,new Vector3f(0,2,0),roll(180));
            for(int tick=0;tick<240;tick++)world.step();
            assertTrue(world.rotation(0).mult(Vector3f.UNIT_Y).y<-.95f);
            for(int tick=0;tick<240;tick++) {
                world.step();
                assertTrue(world.chassisSupported(0),"Dynamic resting roof lost support at tick "+tick);
                assertEquals(0,world.supportedWheelContacts(0));
            }
        }
    }

    @ParameterizedTest @ValueSource(ints={-90,90,180})
    void wheelSupportRecoversAfterOverturnedSuspensionReturnsUprightAndMoves(int degrees) {
        try(PhysicsWorld world=floor(300)) {
            var body=world.addVehicle(0,new Vector3f(0,3,0),roll(degrees));
            for(int tick=0;tick<240;tick++)world.step();
            Quaternion leaning=new Quaternion().fromAngleAxis(.4f,Vector3f.UNIT_Y).mult(roll(8));
            world.teleport(0,new Vector3f(0,world.profile(0).roadOffset(),0),leaning);
            body.setLinearVelocity(new Vector3f(4,.16f,2));
            boolean allFour=false;
            for(int tick=0;tick<240;tick++) {
                world.step();
                int contacts=world.wheelContacts(0);
                assertEquals(contacts,world.supportedWheelContacts(0),
                        "Every real wheel ray on the single flat floor needs upward support after roll="+degrees+", tick="+tick);
                allFour|=contacts==4;
            }
            assertTrue(allFour,"Fixture must actually regain all four suspension contacts");
        }
    }

    @Test void suspensionOnAnotherVehicleCannotBecomeStaticWheelSupport() {
        try(PhysicsWorld world=floor()) {
            var boss=VehicleProfile.boss("boss_foreman",VehicleRules.load());
            var lower=world.addVehicle(0,new Vector3f(0,10,0),new Quaternion(),boss);
            float roof=10+boss.hullBounds().maxY();
            var upper=world.addVehicle(1,new Vector3f(0,roof+.45f,0),new Quaternion());
            lower.setGravity(Vector3f.ZERO);lower.setLinearFactor(Vector3f.ZERO);lower.setAngularFactor(Vector3f.ZERO);
            upper.setGravity(Vector3f.ZERO);upper.setLinearFactor(Vector3f.ZERO);upper.setAngularFactor(Vector3f.ZERO);
            for(int tick=0;tick<30;tick++) {
                world.step();
                assertEquals(4,world.wheelContacts(1),"Fixture requires the upper wheels to reach the boss roof");
                assertEquals(0,world.supportedWheelContacts(1),"An upward vehicle roof is not registered static support");
                assertFalse(world.chassisSupported(1));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void suspensionRaysIntoWallsOrCeilingsDoNotCountAsSupportedWheels(boolean ceiling) {
        try(PhysicsWorld world=new PhysicsWorld(VehicleRules.load())) {
            world.addStatic(new BoxCollisionShape(ceiling?new Vector3f(20,.5f,20):new Vector3f(.5f,20,20)),
                    ceiling?new Vector3f(0,4,0):new Vector3f(2,10,0),new Quaternion());
            var body=world.addVehicle(0,ceiling?new Vector3f(0,3,0):new Vector3f(1,10,0),roll(ceiling?180:90));
            // Keep the deliberate suspension-only fixture in place. The native
            // chassis stays clear while the original four wheel rays hit the face.
            body.setGravity(Vector3f.ZERO);body.setLinearFactor(Vector3f.ZERO);body.setAngularFactor(Vector3f.ZERO);
            for(int tick=0;tick<30;tick++) {
                world.step();
                assertEquals(4,world.wheelContacts(0),"Fixture requires real wheel-ray contact");
                assertEquals(0,world.supportedWheelContacts(0),ceiling?"Ceiling suspension is not support":"Wall suspension is not support");
                assertFalse(world.chassisSupported(0));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void wallAndCeilingContactCannotProvideUpwardSupport(boolean ceiling) {
        try(PhysicsWorld world=new PhysicsWorld(VehicleRules.load())) {
            world.addStatic(new BoxCollisionShape(ceiling?new Vector3f(20,.5f,20):new Vector3f(.5f,20,20)),
                    ceiling?new Vector3f(0,3,0):new Vector3f(2,0,0),new Quaternion());
            var body=world.addVehicle(0,ceiling?new Vector3f(0,1.6f,0):new Vector3f(.5f,0,0),new Quaternion());
            body.setGravity(ceiling?new Vector3f(0,18,0):new Vector3f(18,0,0));
            boolean[] actualContact={false};
            PhysicsCollisionListener witness=event->{if(event.getDistance1()<=0)actualContact[0]=true;};
            world.space().addCollisionListener(witness);
            world.space().addOngoingCollisionListener(witness);
            try {
                for(int tick=0;tick<120;tick++) {
                    world.step();
                    assertFalse(world.chassisSupported(0),ceiling?"Ceiling is not support":"Wall is not support");
                }
                assertTrue(actualContact[0],"The negative fixture must actually collide");
            } finally {
                world.space().removeCollisionListener(witness);
                world.space().removeOngoingCollisionListener(witness);
            }
        }
    }

    @Test void anotherCarIsAContactButNeverStaticSupportAndTeleportInvalidatesBothEnds() {
        try(PhysicsWorld world=new PhysicsWorld(VehicleRules.load())) {
            var lower=world.addVehicle(0,new Vector3f(0,10,0),new Quaternion());
            var upper=world.addVehicle(1,new Vector3f(0,11.85f,0),roll(180));
            lower.setGravity(Vector3f.ZERO);upper.setGravity(Vector3f.ZERO);
            world.step();
            assertTrue(world.touchingVehicle(0));
            assertTrue(world.touchingVehicle(1));
            assertFalse(world.chassisSupported(0));assertFalse(world.chassisSupported(1));
            assertFalse(world.rams().isEmpty(),"Body observation must preserve native ram collection");

            world.teleport(1,new Vector3f(20,10,0),roll(180));
            assertFalse(world.touchingVehicle(0));assertFalse(world.touchingVehicle(1));
            world.step();
            assertFalse(world.touchingVehicle(0));assertFalse(world.touchingVehicle(1));
        }
    }

    @Test void separatingVehiclesClearTheNextStepSnapshot() {
        try(PhysicsWorld world=new PhysicsWorld(VehicleRules.load())) {
            var first=world.addVehicle(0,new Vector3f(-1,10,0),new Quaternion());
            var second=world.addVehicle(1,new Vector3f(1,10,0),new Quaternion());
            first.setGravity(Vector3f.ZERO);second.setGravity(Vector3f.ZERO);
            world.step();
            assertTrue(world.touchingVehicle(0));assertTrue(world.touchingVehicle(1));
            first.setLinearVelocity(new Vector3f(-10,0,0));second.setLinearVelocity(new Vector3f(10,0,0));
            for(int tick=0;tick<30;tick++)world.step();
            assertFalse(world.touchingVehicle(0));assertFalse(world.touchingVehicle(1));
        }
    }

    @Test void removedSupportsAndVehicleIdsCannotLeaveStaleObservations() {
        PhysicsWorld world=floor();
        try {
            world.addVehicle(0,new Vector3f(0,2,0),roll(180));
            for(int tick=0;tick<180;tick++)world.step();
            assertTrue(world.chassisSupported(0));
            assertTrue(world.removeStatic("floor"));
            assertFalse(world.chassisSupported(0),"Removed statics invalidate support immediately");
            world.step();
            assertFalse(world.chassisSupported(0));

            world.teleport(0,new Vector3f(-1,10,0),new Quaternion());
            world.addVehicle(1,new Vector3f(1,10,0),new Quaternion());
            world.step();
            assertTrue(world.touchingVehicle(0));assertTrue(world.touchingVehicle(1));
            world.removeVehicle(1);
            assertFalse(world.touchingVehicle(0));assertFalse(world.touchingVehicle(1));
            assertFalse(world.chassisSupported(1));
            assertEquals(0,world.supportedWheelContacts(1));
            world.addVehicle(1,new Vector3f(30,10,0),new Quaternion());
            assertFalse(world.chassisSupported(1));assertFalse(world.touchingVehicle(1));
        } finally {world.close();}
        assertFalse(world.chassisSupported(0));assertFalse(world.touchingVehicle(0));
        assertEquals(0,world.supportedWheelContacts(0));
    }

    @Test void teleportClearsSupportAndAccessorsReadOnlyTheCompletedStep() {
        try(PhysicsWorld world=floor()) {
            var body=world.addVehicle(0,new Vector3f(0,2,0),roll(180));
            for(int tick=0;tick<180;tick++)world.step();
            assertTrue(world.chassisSupported(0));
            // Native movement deliberately bypasses the lifecycle helper: observers
            // must still read the previous snapshot until the next completed step.
            body.setPhysicsLocation(new Vector3f(0,20,0));
            assertTrue(world.chassisSupported(0));
            world.step();
            assertFalse(world.chassisSupported(0));
            world.teleport(0,new Vector3f(0,.97f,0),roll(180));
            assertFalse(world.chassisSupported(0));
            for(int tick=0;tick<30;tick++)world.step();
            assertTrue(world.chassisSupported(0));
            world.teleport(0,new Vector3f(0,20,0),roll(180));
            assertFalse(world.chassisSupported(0));
        }
    }

    @Test void repeatedBodyObservationsCannotChangeTheNativeTrajectory() {
        List<Sample> reference=fallAndCollide(false),observed=fallAndCollide(true);
        assertTrue(reference.stream().anyMatch(Sample::supported));
        assertTrue(reference.stream().anyMatch(Sample::touching));
        for(int tick=0;tick<reference.size();tick++) {
            Sample expected=reference.get(tick),actual=observed.get(tick);
            assertTrue(expected.position().distance(actual.position())<.00001f,"position at tick "+tick);
            assertTrue(expected.velocity().distance(actual.velocity())<.00001f,"velocity at tick "+tick);
            assertTrue(expected.up().distance(actual.up())<.00001f,"orientation at tick "+tick);
            assertEquals(expected.supported(),actual.supported(),"support at tick "+tick);
            assertEquals(expected.touching(),actual.touching(),"vehicle contact at tick "+tick);
            assertEquals(expected.supportedWheels(),actual.supportedWheels(),"supported wheels at tick "+tick);
        }
    }

    private List<Sample> fallAndCollide(boolean observe) {
        List<Sample> samples=new ArrayList<>();
        try(PhysicsWorld world=floor()) {
            world.addVehicle(0,new Vector3f(-1,3,0),roll(180));
            world.addVehicle(1,new Vector3f(1,3,0),roll(180));
            for(int tick=0;tick<180;tick++) {
                if(observe)for(int read=0;read<100;read++) {
                    world.chassisSupported(0);world.chassisSupported(1);
                    world.touchingVehicle(0);world.touchingVehicle(1);
                    world.supportedWheelContacts(0);world.supportedWheelContacts(1);
                }
                world.step();
                samples.add(new Sample(world.position(0),world.velocity(0),world.rotation(0).mult(Vector3f.UNIT_Y),
                        world.chassisSupported(0),world.touchingVehicle(0),world.supportedWheelContacts(0)));
            }
        }
        return samples;
    }

    private PhysicsWorld floor() {
        return floor(100);
    }
    private PhysicsWorld floor(float halfExtent) {
        PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
        world.addStatic("floor",new BoxCollisionShape(new Vector3f(halfExtent,.5f,halfExtent)),new Vector3f(0,-.5f,0),new Quaternion());
        return world;
    }
    private Quaternion roll(int degrees) {return new Quaternion().fromAngleAxis(degrees*(float)Math.PI/180,Vector3f.UNIT_Z);}
}
