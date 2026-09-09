package game.wreckriff.simulation;

import game.wreckriff.combat.AbilityId;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeRecoveryTest {
    private static final VehicleCommand RECOVER = new VehicleCommand(0, 0, 0, false, false, false, false, null, 0, false, true,AbilityId.NONE);

    @Test void recoveryRejectsNewerTwoWheelEdgePoseAndUsesFullySupportedHistory() {
        VehicleRules rules = VehicleRules.load();
        try (PhysicsWorld world = new PhysicsWorld(rules)) {
            // Real native platform: x=-40..0, with no support beyond the right edge.
            world.addStatic(new BoxCollisionShape(new Vector3f(20, 0.5f, 30)), new Vector3f(-20, -0.5f, 0), new Quaternion());
            world.addVehicle(0, new Vector3f(-15, 1, 0), new Quaternion());
            for (int tick = 0; tick < 240; tick++) world.step();
            assertEquals(4, world.wheelContacts(0));
            var state = new VehicleState(0, "Recovery test", true,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class));
            var driver = new VehicleController(world, state, rules);

            // This recorded pose differs from the constructor's initial fallback.
            Vector3f supported = new Vector3f(-10, world.position(0).y, 0);
            world.teleport(0, supported, new Quaternion());
            assertEquals(4, world.wheelContacts(0));
            assertTrue(world.freePose(0, supported, world.rotation(0)));
            driver.recordSafePose(240);

            Vector3f edge = new Vector3f(-0.3f, supported.y, 0);
            world.teleport(0, edge, new Quaternion());
            assertEquals(2, world.wheelContacts(0), "Native fixture must have exactly two unsupported wheels");
            assertTrue(world.freePose(0, edge, world.rotation(0)), "The failure is missing support, not an overlapping chassis");
            assertEquals(1, world.rotation(0).mult(Vector3f.UNIT_Y).y, 0.0001f);
            var ground = world.ray(edge.add(0, 0.4f, 0), edge.add(0, -2, 0), 0);
            assertNotNull(ground, "The old centre-only recovery ray still sees ground below this unsafe edge pose");
            assertEquals(-1, ground.vehicleId());
            assertTrue(ground.normal().y > 0.99f);
            driver.recordSafePose(252);

            // Request recovery on solid ground after both historical poses are old enough.
            // No newer positions are recorded while holding the recovery button.
            world.teleport(0, new Vector3f(-5, supported.y, 0), new Quaternion());
            assertEquals(4, world.wheelContacts(0));
            VehicleController.Recovery recovery = VehicleController.Recovery.NONE;
            int requestTicks = Math.round(rules.recoveryHold() * MatchSession.TICKS_PER_SECOND);
            long tick = 600;
            for (int held = 0; held < requestTicks; held++, tick++) {
                assertTrue(world.velocity(0).length() < 2, "Manual recovery speed condition remains satisfied");
                recovery = driver.prepare(RECOVER, tick);
                if (held < requestTicks - 1) {
                    assertEquals(VehicleController.Recovery.NONE, recovery, "Recovery must wait for the complete hold interval");
                    world.step();
                }
            }
            assertTrue(recovery.recovered(), "A fully supported historical pose remains available");
            assertFalse(recovery.fatal());
            assertEquals(rules.recoveryCost(), recovery.cost());
            assertEquals(1, state.recoveries);
            assertTrue(world.position(0).distance(supported) < 0.001f, "The newer two-wheel edge pose must not replace fully supported history");
            assertTrue(world.position(0).distance(edge) > 5);
            assertEquals(4, world.wheelContacts(0), "The restored vehicle must have native support under all four wheels");
            assertEquals(VehicleController.Recovery.NONE, driver.prepare(VehicleCommand.NONE, tick));
        }
    }
}
