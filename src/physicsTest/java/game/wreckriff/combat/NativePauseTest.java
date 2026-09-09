package game.wreckriff.combat;

import game.wreckriff.combat.AbilityId;

import game.wreckriff.combat.WeaponType;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pause protects the complete shared runtime, including resources about to cross a boundary. */
class NativePauseTest {
    @Test void T14_pauseFreezesFullRuntimeAndResumesWithoutTimeDebt() {
        ArenaDefinition definition = ArenaDefinition.load();
        VehicleRules vehicleRules = VehicleRules.load();
        MatchSession session = new MatchSession(42, 360);
        PhysicsWorld world = new PhysicsWorld(vehicleRules);
        world.addStatic(new BoxCollisionShape(new Vector3f(300, 0.5f, 300)), new Vector3f(0, -0.5f, 0), new Quaternion());
        for (int id = 0; id < 5; id++) world.addVehicle(id, new Vector3f(id * 12, 0.6f, 50), new Quaternion());
        try (MatchRuntime runtime = new MatchRuntime(session, world, definition, new NavGraph(definition), vehicleRules)) {
            VehicleState player = session.vehicle(0);
            for (int id = 1; id < 5; id++) session.vehicle(id).protectionTicks = 1000;
            var repair = definition.pickups().stream().filter(p -> p.type() == ArenaDefinition.PickupType.REPAIR).findFirst().orElseThrow();
            player.hp = 150;
            world.teleport(0, repair.position().vector().add(0, 0.6f, 0), new Quaternion());
            runtime.arenaSystems().collectPickups(world);
            assertFalse(runtime.arenaSystems().active(repair.id()), "Fixture includes a running pickup respawn timer");
            runtime.arenaSystems().drainEvents();

            var hazard = definition.hazard();
            world.teleport(0, new Vector3f((hazard.minX() + hazard.maxX()) / 2, 0.6f,
                    (hazard.minZ() + hazard.maxZ()) / 2), new Quaternion());
            player.hp = 170;
            player.turbo = 40;
            session.tick = hazard.offTicks() + hazard.warningTicks();
            for (int tick = 0; tick < hazard.damageIntervalTicks() - 1; tick++) {
                var command = tick == 0 ? new VehicleCommand(0, 0, 0, false, false, false, true, false, 0, false, false,AbilityId.NONE) : VehicleCommand.NONE;
                runtime.tick(command, false);
            }
            assertEquals(170, player.hp, "Hazard has not reached its first full damage interval");
            assertFalse(runtime.combat().projectiles().isEmpty(), "Fixture includes a live projectile with TTL");
            assertEquals(ArenaSystems.HazardPhase.ACTIVE, runtime.arenaSystems().hazardPhase());
            player.machineGunCooldown = 31;
            player.weapon(WeaponType.POWER).cooldownTicks = 41;
            player.pulseCooldown = 51;
            player.heat = 72;
            player.heatQuietTicks = 100;
            player.turboQuietTicks = CombatRules.ticks(vehicleRules.turboRegenDelay()) - 1;
            player.recoveryCooldown = 21;

            SimulationLoop loop = new SimulationLoop(MatchRules.load());
            Runnable tick = () -> runtime.tick(VehicleCommand.NONE, false);
            assertEquals(0, loop.advance(SimulationLoop.STEP / 2, true, tick));
            String before = snapshot(session, world, runtime);
            long beforeTick = session.tick;
            float beforeTurbo = player.turbo;
            for (int fps : new int[]{30, 60, 144}) {
                for (int frame = 0; frame < fps * 2; frame++) assertEquals(0, loop.advance(1.0 / fps, false, tick));
                assertEquals(before, snapshot(session, world, runtime), "All runtime state must remain identical while paused at render FPS " + fps);
            }
            assertEquals(0, loop.steps());
            assertEquals(0, loop.droppedSimulationTime());
            assertEquals(0, loop.advance(SimulationLoop.STEP / 2, true, tick), "Pause must discard the old partial-step debt");
            assertEquals(1, loop.advance(SimulationLoop.STEP / 2, true, tick));
            assertEquals(beforeTick + 1, session.tick);
            assertEquals(170 - hazard.damage(), player.hp, 0.001f, "The preserved hazard interval completes once after Resume");
            assertEquals(30, player.machineGunCooldown);
            assertEquals(40, player.weapon(WeaponType.POWER).cooldownTicks);
            assertEquals(50, player.pulseCooldown);
            assertEquals(20, player.recoveryCooldown);
            assertTrue(player.heat < 72, "Cooling resumes on the first real tick");
            assertTrue(player.turbo > beforeTurbo, "Turbo regeneration resumes on the first real tick");
            assertFalse(runtime.arenaSystems().active(repair.id()));
        }
    }

    private static String snapshot(MatchSession session, PhysicsWorld world, MatchRuntime runtime) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("session", session);
        List<Object> bodies = new ArrayList<>();
        for (int id = 0; id < 5; id++) bodies.add(List.of(world.position(id), world.rotation(id), world.velocity(id)));
        state.put("bodies", bodies);
        state.put("projectiles", runtime.combat().projectiles());
        state.put("pendingDamage", runtime.combat().pendingDamageCount());
        state.put("hazardPhase", runtime.arenaSystems().hazardPhase());
        state.put("activePickups", runtime.arenaSystems().activePickups());
        return Configs.gson().toJson(state);
    }
}
