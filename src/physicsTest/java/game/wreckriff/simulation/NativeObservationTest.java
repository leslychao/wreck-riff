package game.wreckriff.simulation;

import game.wreckriff.combat.AbilityId;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** HUD/AI observations must not change Bullet's wheel or suspension state. */
class NativeObservationTest {
    private record Sample(Vector3f position, Quaternion rotation, Vector3f velocity) {}
    private record Run(List<VehicleCommand> commands, List<Sample> samples) {}
    private static final VehicleCommand GAS = new VehicleCommand(1, 0, 0, false, false, false, false, null, 0, false, false,AbilityId.NONE);
    private static final VehicleCommand TURN_AND_BRAKE = new VehicleCommand(0, 1, 0.7f, false, false, false, false, null, 0, false, false,AbilityId.NONE);

    @Test void repeatedWheelObservationsCannotAlterARampAndTurnTrajectory() {
        Run reference = drive(null, false);
        Run observed = drive(reference.commands(), true);
        assertEquals(reference.samples().size(), observed.samples().size());
        assertTrue(reference.samples().stream().anyMatch(s -> s.position().y >= 6.3f), "The fixture must actually climb onto the upper deck");
        assertTrue(reference.samples().stream().anyMatch(s -> Math.abs(s.rotation().mult(Vector3f.UNIT_Z).y) > 0.1f), "The fixture must exercise suspension on a real incline");
        for (int tick = 0; tick < reference.samples().size(); tick++) {
            Sample a = reference.samples().get(tick), b = observed.samples().get(tick);
            assertVector(a.position(), b.position(), "position", tick);
            assertVector(a.velocity(), b.velocity(), "velocity", tick);
            // Basis vectors compare orientation without treating q and -q as different rotations.
            assertVector(a.rotation().mult(Vector3f.UNIT_Y), b.rotation().mult(Vector3f.UNIT_Y), "rotation/up", tick);
            assertVector(a.rotation().mult(Vector3f.UNIT_Z), b.rotation().mult(Vector3f.UNIT_Z), "rotation/forward", tick);
        }
    }

    private Run drive(List<VehicleCommand> replay, boolean extraObservations) {
        VehicleRules rules = VehicleRules.load();
        ArenaDefinition definition = ArenaDefinition.load();
        List<VehicleCommand> commands = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        try (PhysicsWorld world = new PhysicsWorld(rules)) {
            ArenaContent arena = new ArenaFactory(game.wreckriff.arena.NativeArenaAssets.MANAGER).build(definition);
            for (var body : arena.bodies()) world.addStatic(body.shape(), body.position(), body.rotation());
            world.addVehicle(0, new Vector3f(54, 0.8f, -46), new Quaternion());
            for (int tick = 0; tick < 240; tick++) world.step();
            assertEquals(4, world.wheelContacts(0));
            var driver = new VehicleController(world, new VehicleState(0, "Observation test", true,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class)), rules);
            int turnStarted = -1;
            for (int tick = 0; tick < (replay == null ? 1800 : replay.size()); tick++) {
                if (replay == null && turnStarted < 0 && world.position(0).z >= 42) turnStarted = tick;
                // Only the reference chooses the end of the route. Replay receives the
                // exact recorded command stream, so observations cannot change the inputs.
                VehicleCommand command = replay != null ? replay.get(tick) : turnStarted < 0 ? GAS : TURN_AND_BRAKE;
                commands.add(command);
                driver.drive(command);
                if (extraObservations) observe(world, 50);
                world.step();
                if (extraObservations) observe(world, 50);
                samples.add(new Sample(world.position(0), world.rotation(0), world.velocity(0)));
                if (replay == null && turnStarted >= 0 && tick - turnStarted >= 119) break;
            }
            if (replay == null) assertTrue(turnStarted >= 0, "The reference drive must cross both real ramps before braking and turning");
        }
        return new Run(List.copyOf(commands), List.copyOf(samples));
    }

    private void observe(PhysicsWorld world, int count) {
        for (int read = 0; read < count; read++) {
            int contacts = world.wheelContacts(0);
            assertEquals(contacts > 0, world.grounded(0));
        }
    }

    private void assertVector(Vector3f expected, Vector3f actual, String field, int tick) {
        assertTrue(expected.distance(actual) <= 0.00001f,
                "Extra observation changed " + field + " at tick " + tick + ": " + expected + " -> " + actual);
    }
}
