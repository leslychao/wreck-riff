package game.wreckriff.arena;

import game.wreckriff.combat.WeaponType;

import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.config.Configs;
import game.wreckriff.simulation.MatchSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AmmoPickupTest {
    @Test void nonDefaultWeaponCapacitiesAlsoBoundPickupEligibilityAndRefills() {
        verifyPickupLimits(1, 1);
        verifyPickupLimits(5, 3);
        verifyPickupLimits(15, 11);
    }

    @Test void validIntegerMaximumCapacityCannotOverflowWhenCollectingAmmo() {
        verifyPickupLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private void verifyPickupLimits(int homingMaximum, int powerMaximum) {
        var json = Configs.gson().toJsonTree(Configs.load("combat", CombatRules.class)).getAsJsonObject();
        json.getAsJsonObject("homing").addProperty("maximumAmmo", homingMaximum);
        json.getAsJsonObject("homing").addProperty("initialAmmo", homingMaximum - 1);
        json.getAsJsonObject("power").addProperty("maximumAmmo", powerMaximum);
        json.getAsJsonObject("power").addProperty("initialAmmo", powerMaximum - 1);
        Configs.validate(json, CombatRules.class, "combat");
        CombatRules rules = Configs.gson().fromJson(json, CombatRules.class);
        ArenaDefinition arena = ArenaDefinition.load();
        for (var type : new ArenaDefinition.PickupType[]{ArenaDefinition.PickupType.HOMING_AMMO, ArenaDefinition.PickupType.POWER_AMMO}) {
            MatchSession session = new MatchSession(42, 360);
            // MatchRuntime constructs arena systems first; combat initializes shared resources
            // before the first collection, so this order must not capture stale capacities.
            ArenaSystems systems = new ArenaSystems(session, arena);
            CombatSystem combat = new CombatSystem(session, rules);
            TestWorld world = new TestWorld();
            var pickup = arena.pickups().stream().filter(p -> p.type() == type).findFirst().orElseThrow();
            world.positions[0] = pickup.position().vector().add(0, 0.8f, 0);
            var player = session.vehicle(0);
            for (var vehicle : session.vehicles) {
                assertEquals(homingMaximum, vehicle.weapon(WeaponType.HOMING).maximumAmmo);
                assertEquals(powerMaximum, vehicle.weapon(WeaponType.POWER).maximumAmmo);
            }
            assertTrue(ArenaSystems.needs(player, type), "There is room for exactly one round at the configured capacity");
            systems.collectPickups(world);
            assertEquals(type == ArenaDefinition.PickupType.HOMING_AMMO ? homingMaximum : homingMaximum - 1, player.weapon(WeaponType.HOMING).ammo);
            assertEquals(type == ArenaDefinition.PickupType.POWER_AMMO ? powerMaximum : powerMaximum - 1, player.weapon(WeaponType.POWER).ammo);
            assertFalse(ArenaSystems.needs(player, type), "The configured maximum is full even when it differs from 12/8");
            assertFalse(systems.active(pickup.id()));
            var events = systems.drainEvents();
            assertEquals(1, events.size());
            assertEquals(1f, events.getFirst().value(), "Only the accepted round is reported; excess pickup ammo is discarded");
            session.tick = pickup.respawnTicks();
            systems.collectPickups(world);
            assertTrue(systems.active(pickup.id()), "A full vehicle must leave the respawned pickup available");
            assertTrue(systems.drainEvents().isEmpty());
            combat.clear();
        }
    }
}
