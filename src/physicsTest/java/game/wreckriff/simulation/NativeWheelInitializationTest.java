package game.wreckriff.simulation;

import game.wreckriff.combat.AbilityId;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.PhysicsTickListener;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.ArenaFactory;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Uses raw native angles: VehicleWheel's presentation quaternion masks NaNs. */
class NativeWheelInitializationTest {
    private record Sample(Vector3f position, Quaternion rotation, Vector3f velocity) {}
    private record Drive(List<Sample> samples, boolean injected) {}
    private static final VehicleCommand REVERSE_TURN = new VehicleCommand(0, 1, 1, false, false, false, false, false, 0, false, false,AbilityId.NONE);
    private static final VehicleCommand REVERSE_STRAIGHT = new VehicleCommand(0, 1, 0, false, false, false, false, false, 0, false, false,AbilityId.NONE);

    @Test void repeatedInclineStartsKeepRawWheelAnglesFinite() {
        VehicleRules rules = VehicleRules.load();
        for (int run = 0; run < 12; run++) {
            boolean north = run % 2 == 0;
            try (PhysicsWorld world = arenaWorld(rules)) {
                // A high-side wheel already reaches the real ramp on the first step.
                // An airborne flat-ground spawn would initialize native previous
                // wheel locations before its first contact and miss this defect.
                float height = 3.76f + (run % 4) * 0.025f;
                world.addVehicle(0, new Vector3f(53.5f + (run % 3) * 0.5f, height, north ? 24 : -24),
                        yaw(north ? -135 : 45));
                assertTrue(world.wheelContacts(0) > 0, "Fixture must contact the incline immediately, run=" + run);
                world.vehicle(0).brake(rules.brakeForce());
                int supportedTicks = 0;
                for (int tick = 0; tick < 240; tick++) {
                    world.step();
                    assertRawAngles(world, "run=" + run + " tick=" + tick);
                    supportedTicks = world.wheelContacts(0) == 4 ? supportedTicks + 1 : 0;
                    // Libbulletjme has no delta-angle setter. Once all wheels have
                    // been supported for two updates, native code must have replaced
                    // its first-contact delta from the now-initialized locations.
                    if (supportedTicks >= 2) assertRawDeltas(world, "run=" + run + " tick=" + tick);
                }
                assertTrue(supportedTicks >= 2, "Fixture must settle on all four wheels, run=" + run);
            }
        }
    }

    @Test void invalidNativeOutputAngleIsContainedBeforeItCanChangeTheNextDriveStep() {
        Drive reference = drive(false);
        Drive corrupted = drive(true);
        assertTrue(corrupted.injected(), "The native post-step callback must actually write a raw NaN angle");
        assertEquals(reference.samples().size(), corrupted.samples().size());
        assertTrue(reference.samples().stream().anyMatch(s -> s.velocity().length() > 2), "The fixture must actually drive");
        assertTrue(reference.samples().getLast().position().z < -27, "The fixture must reverse down the real ramp");
        for (int tick = 0; tick < reference.samples().size(); tick++) {
            Sample expected = reference.samples().get(tick), actual = corrupted.samples().get(tick);
            assertVector(expected.position(), actual.position(), 0.001f, "position", tick);
            assertVector(expected.velocity(), actual.velocity(), 0.001f, "velocity", tick);
            assertVector(expected.rotation().mult(Vector3f.UNIT_Z), actual.rotation().mult(Vector3f.UNIT_Z), 0.0001f, "forward", tick);
            assertVector(expected.rotation().mult(Vector3f.UNIT_Y), actual.rotation().mult(Vector3f.UNIT_Y), 0.0001f, "up", tick);
        }
    }

    private Drive drive(boolean corruptNativeOutput) {
        VehicleRules rules = VehicleRules.load();
        List<Sample> samples = new ArrayList<>();
        boolean[] injected = {false};
        try (PhysicsWorld world = arenaWorld(rules)) {
            world.addVehicle(0, new Vector3f(54, 3.85f, -24), yaw(45));
            world.vehicle(0).brake(rules.brakeForce());
            for (int tick = 0; tick < 240; tick++) {
                world.step();
                assertRawAngles(world, "settle tick=" + tick);
            }
            assertEquals(4, world.wheelContacts(0));
            assertRawDeltas(world, "after settle");
            var driver = new VehicleController(world, new VehicleState(0, "Wheel initialization test", true), rules);
            PhysicsTickListener corruption = new PhysicsTickListener() {
                @Override public void prePhysicsTick(PhysicsSpace space, float timeStep) {}
                @Override public void physicsTick(PhysicsSpace space, float timeStep) {
                    // Model the upstream first-contact output defect after Bullet
                    // integrates the chassis, before PhysicsWorld updates wheels.
                    var wheel = world.vehicle(0).getWheel(0);
                    wheel.setRotationAngle(Float.NaN);
                    injected[0] = Float.isNaN(wheel.getRotationAngle());
                }
            };
            if (corruptNativeOutput) world.space().addTickListener(corruption);
            boolean listenerRegistered = corruptNativeOutput;
            try {
                for (int tick = 0; tick < 240; tick++) {
                    driver.drive(tick < 120 ? REVERSE_TURN : REVERSE_STRAIGHT);
                    world.step();
                    if (tick == 0 && listenerRegistered) {
                        world.space().removeTickListener(corruption);
                        listenerRegistered = false;
                    }
                    assertRawAngles(world, "drive tick=" + tick);
                    assertRawDeltas(world, "drive tick=" + tick);
                    samples.add(new Sample(world.position(0), world.rotation(0), world.velocity(0)));
                }
            } finally {
                if (listenerRegistered) world.space().removeTickListener(corruption);
            }
        }
        return new Drive(List.copyOf(samples), injected[0]);
    }

    private PhysicsWorld arenaWorld(VehicleRules rules) {
        PhysicsWorld world = new PhysicsWorld(rules);
        var content = new ArenaFactory(new DesktopAssetManager(true)).build(ArenaDefinition.load());
        for (var body : content.bodies()) world.addStatic(body.shape(), body.position(), body.rotation());
        return world;
    }

    private Quaternion yaw(float degrees) {
        return new Quaternion().fromAngleAxis(degrees * FastMath.DEG_TO_RAD, Vector3f.UNIT_Y);
    }

    private void assertRawAngles(PhysicsWorld world, String context) {
        for (int wheel = 0; wheel < 4; wheel++) {
            float angle = world.vehicle(0).getWheel(wheel).getRotationAngle();
            assertTrue(Float.isFinite(angle), "Nonfinite raw wheel rotation: " + context + " wheel=" + wheel + " angle=" + angle);
        }
    }

    private void assertRawDeltas(PhysicsWorld world, String context) {
        for (int wheel = 0; wheel < 4; wheel++) {
            float delta = world.vehicle(0).getWheel(wheel).getDeltaRotation();
            assertTrue(Float.isFinite(delta), "Nonfinite raw wheel delta: " + context + " wheel=" + wheel + " delta=" + delta);
        }
    }

    private void assertVector(Vector3f expected, Vector3f actual, float tolerance, String field, int tick) {
        assertTrue(expected.distance(actual) <= tolerance,
                "Native wheel angle contamination changed " + field + " at tick " + tick + ": " + expected + " -> " + actual);
    }
}
