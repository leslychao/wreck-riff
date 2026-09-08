package game.wreckriff.combat;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual Bullet colliders through the same geometry boundary used by the game. */
class NativeCombatTest {
    private final MatchSession session = new MatchSession(42, 360);
    private final CombatRules rules = Configs.load("combat", CombatRules.class);
    private final CombatSystem combat = new CombatSystem(session, rules);

    private PhysicsWorld world() {
        PhysicsWorld world = new PhysicsWorld(VehicleRules.load());
        world.addStatic(new BoxCollisionShape(new Vector3f(300, 0.5f, 300)), new Vector3f(0, -0.5f, 0), new Quaternion());
        world.addVehicle(0, new Vector3f(0, 1, 0), new Quaternion());
        world.addVehicle(1, new Vector3f(0, 1, 7), new Quaternion());
        for (int i = 2; i < 5; i++) world.addVehicle(i, new Vector3f(i * 15, 1, 50), new Quaternion());
        return world;
    }

    @Test void P06_wallBetweenWeaponBaseAndMuzzleStopsRocketBeforeSpawn() {
        try (PhysicsWorld world = world()) {
            world.addStatic(new BoxCollisionShape(new Vector3f(5, 4, 0.025f)), new Vector3f(0, 4, 2.42f), new Quaternion());
            step(world, rocket());
            assertTrue(combat.projectiles().isEmpty());
            GameEvent explosion = combat.drainEvents().stream().filter(e -> e.type() == GameEvent.Type.EXPLOSION).findFirst().orElseThrow();
            assertTrue(explosion.position().z < 2.42f, "Rocket must explode on the source side of the wall");
            assertEquals(200, session.vehicle(1).hp, "A wall must also block splash");
        }
    }

    @Test void P08_pulseHitsVisibleCarDespiteItsSourceColliderAndAppliesRealImpulse() {
        try (PhysicsWorld world = world()) {
            session.vehicle(0).pulseCooldown = 0;
            step(world, pulse());
            assertEquals(200, session.vehicle(0).hp);
            assertEquals(180, session.vehicle(1).hp);
            assertTrue(world.velocity(1).z > 0, "Pulse must apply an outward native impulse");
            assertTrue(world.velocity(1).z <= 4.0001f);
            assertTrue(world.velocity(1).y < 0.1f, "Pulse must not launch the vehicle upwards");
        }
    }

    @Test void P08_closedWallBlocksPulseEvenAtShortDistance() {
        try (PhysicsWorld world = world()) {
            world.addStatic(new BoxCollisionShape(new Vector3f(5, 4, 0.15f)), new Vector3f(0, 4, 3.5f), new Quaternion());
            session.vehicle(0).pulseCooldown = 0;
            step(world, pulse());
            assertEquals(200, session.vehicle(1).hp);
            assertEquals(0, world.velocity(1).z, 0.0001f);
        }
    }

    @Test void P08_floorBlocksPulseBetweenArenaLevels() {
        try (PhysicsWorld world = world()) {
            world.teleport(1, new Vector3f(0, 4, 0), new Quaternion());
            world.addStatic(new BoxCollisionShape(new Vector3f(6, 0.2f, 6)), new Vector3f(0, 3, 0), new Quaternion());
            session.vehicle(0).pulseCooldown = 0;
            step(world, pulse());
            assertEquals(200, session.vehicle(1).hp);
        }
    }

    @Test void directRocketHitsARealCompoundHullOnlyOnce() {
        try (PhysicsWorld world = world()) {
            step(world, rocket());
            for (int i = 0; i < 120 && !combat.projectiles().isEmpty(); i++) step(world, VehicleCommand.NONE);
            assertTrue(combat.projectiles().isEmpty());
            assertEquals(168, session.vehicle(1).hp, 0.0001f);
            assertEquals(1, combat.drainEvents().stream().filter(e -> e.type() == GameEvent.Type.EXPLOSION).count());
        }
    }

    private void step(PhysicsWorld world, VehicleCommand command) {
        combat.beginTick(Map.of(0, command), world);
        world.step();
        combat.advanceProjectiles(world);
        for (PhysicsWorld.Ram ram : world.rams()) combat.queueRam(ram.first(), ram.second(), ram.closingSpeed());
        combat.resolveDamage(world);
        session.finishTick();
    }

    private static VehicleCommand rocket() { return new VehicleCommand(0, 0, 0, false, false, false, true, false, 0, false, false); }
    private static VehicleCommand pulse() { return new VehicleCommand(0, 0, 0, false, false, false, false, true, 0, false, false); }
}
