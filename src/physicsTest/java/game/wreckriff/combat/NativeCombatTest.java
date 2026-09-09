package game.wreckriff.combat;

import game.wreckriff.combat.AbilityId;

import game.wreckriff.combat.WeaponType;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.PhysicsCollisionListener;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual Bullet colliders through the same geometry boundary used by the game. */
class NativeCombatTest {
    private final MatchSession session = new MatchSession(42, 360);
    private final CombatRules rules = Configs.load("combat", CombatRules.class);
    private final CombatSystem combat = new CombatSystem(session, rules);

    private PhysicsWorld world() {
        session.vehicles.forEach(v->v.hp=200); // Damaged-hull fixtures exercise geometry independently of spawn health.
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

    @Test void directRocketHitsARealCompoundHullOnlyOnce() {
        try (PhysicsWorld world = world()) {
            step(world, rocket());
            for (int i = 0; i < 120 && !combat.projectiles().isEmpty(); i++) step(world, VehicleCommand.NONE);
            assertTrue(combat.projectiles().isEmpty());
            assertEquals(168, session.vehicle(1).hp, 0.0001f);
            assertEquals(1, combat.drainEvents().stream().filter(e -> e.type() == GameEvent.Type.EXPLOSION).count());
        }
    }

    @Test void P08_powerRocketSplashCannotCrossAClosedWall() {
        try (PhysicsWorld world = world()) {
            world.teleport(2, new Vector3f(4, 1, 1), new Quaternion());
            world.addStatic(new BoxCollisionShape(new Vector3f(8, 4, 0.15f)), new Vector3f(0, 4, 4), new Quaternion());
            GameEvent explosion = firePowerIntoObstacle(world);
            assertTrue(world.distanceToHull(1, explosion.position()) < rules.power().explosionRadius(), "Protected hull is genuinely within splash range");
            assertEquals(200, session.vehicle(1).hp, "Closed wall must block actual rocket splash");
            assertTrue(session.vehicle(2).hp < 200, "The same explosion must damage a visible hull on the source side");
        }
    }

    @Test void P08_powerRocketSplashCannotCrossAnUpperFloor() {
        try (PhysicsWorld world = world()) {
            world.teleport(1, new Vector3f(0, 4, 4), new Quaternion());
            world.teleport(2, new Vector3f(4, 1, 1), new Quaternion());
            world.addStatic(new BoxCollisionShape(new Vector3f(8, 0.2f, 8)), new Vector3f(0, 3, 4), new Quaternion());
            world.addStatic(new BoxCollisionShape(new Vector3f(0.3f, 0.8f, 0.15f)), new Vector3f(0, 1.4f, 4.1f), new Quaternion());
            GameEvent explosion = firePowerIntoObstacle(world);
            assertTrue(explosion.position().y < 2.8f, "Explosion must occur under the deck");
            assertTrue(world.distanceToHull(1, explosion.position()) < rules.power().explosionRadius(), "Upper hull is within the real splash radius");
            assertEquals(200, session.vehicle(1).hp, "Concrete deck must block actual rocket splash");
            assertTrue(session.vehicle(2).hp < 200, "The same explosion must still damage a visible lower-floor hull");
        }
    }

    @Test void P09_multipleNativeContactPointsDamageEachPairOncePerCooldown() {
        try (PhysicsWorld world = world()) {
            Set<Vector3f> nativeContactPoints = new HashSet<>();
            PhysicsCollisionListener listener = event -> {
                boolean pair = (event.getObjectA() == world.vehicle(0) && event.getObjectB() == world.vehicle(1))
                        || (event.getObjectA() == world.vehicle(1) && event.getObjectB() == world.vehicle(0));
                if (pair) nativeContactPoints.add(event.getPositionWorldOnA().clone());
            };
            world.space().addCollisionListener(listener);
            world.space().addOngoingCollisionListener(listener);
            try {
                int cooldown = CombatRules.ticks(rules.ram().cooldownSeconds());
                float damagePerRam = Math.min(rules.ram().maximumDamage(), rules.ram().damagePerExcessSpeed() * (20 - rules.ram().minimumClosingSpeed()));
                for (int tick = 0; tick <= cooldown; tick++) {
                    // Recreate the same physical impact so every tick has native contacts;
                    // the cooldown, rather than separating bodies, must reject repeat damage.
                    world.teleport(0, new Vector3f(0, 1, 0), new Quaternion());
                    world.teleport(1, new Vector3f(0, 1, 4.55f), new Quaternion());
                    world.vehicle(0).setLinearVelocity(new Vector3f(0, 0, 10));
                    world.vehicle(1).setLinearVelocity(new Vector3f(0, 0, -10));
                    nativeContactPoints.clear();
                    step(world, VehicleCommand.NONE);
                    assertTrue(nativeContactPoints.size() >= 2, "The native fixture must produce multiple distinct points, tick " + tick);
                    assertEquals(1, world.rams().size(), "PhysicsWorld must collapse contact points to one unordered pair");
                    assertEquals(20, world.rams().getFirst().closingSpeed(), 0.001f, "Closing speed must come from before Bullet resolves the collision");
                    int accepted = tick < cooldown ? 1 : 2;
                    assertEquals(200 - accepted * damagePerRam, session.vehicle(0).hp, 0.001f, "First hull, tick " + tick);
                    assertEquals(200 - accepted * damagePerRam, session.vehicle(1).hp, 0.001f, "Second hull, tick " + tick);
                }
                assertEquals(4, combat.drainEvents().stream().filter(e -> e.type() == GameEvent.Type.DAMAGE && e.kind().equals("ram")).count());
            } finally {
                world.space().removeCollisionListener(listener);
                world.space().removeOngoingCollisionListener(listener);
            }
        }
    }

    private GameEvent firePowerIntoObstacle(PhysicsWorld world) {
        session.vehicle(0).selectedWeapon = WeaponType.POWER;
        step(world, rocket());
        for (int tick = 0; tick < 30 && !combat.projectiles().isEmpty(); tick++) step(world, VehicleCommand.NONE);
        assertTrue(combat.projectiles().isEmpty(), "Rocket must hit the authored native obstacle");
        List<GameEvent> explosions = combat.drainEvents().stream().filter(e -> e.type() == GameEvent.Type.EXPLOSION).toList();
        assertEquals(1, explosions.size());
        assertEquals("power", explosions.getFirst().kind());
        assertEquals(-1, explosions.getFirst().subjectId(), "Splash test must detonate against world geometry, not directly hit a vehicle");
        return explosions.getFirst();
    }

    private void step(PhysicsWorld world, VehicleCommand command) {
        combat.beginTick(Map.of(0, command), world);
        world.step();
        combat.advanceProjectiles(world);
        for (PhysicsWorld.Ram ram : world.rams()) combat.queueRam(ram.first(), ram.second(), ram.closingSpeed(),ram.point(),ram.normal());
        combat.resolveDamage(world);
        session.finishTick();
    }

    private static VehicleCommand rocket() { return new VehicleCommand(0, 0, 0, false, false, false, true, null, 0, false, false,AbilityId.NONE); }
}
