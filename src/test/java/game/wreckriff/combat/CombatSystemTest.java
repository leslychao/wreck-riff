package game.wreckriff.combat;

import com.google.gson.JsonObject;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Pure combat rules. Fake geometry is intentional here; real sweeps have physicsTest coverage. */
class CombatSystemTest {
    private MatchSession session;
    private CombatRules rules;
    private CombatSystem combat;
    private FakeWorld world;

    @BeforeEach void createSession() {
        session = new MatchSession(42, 360);
        rules = Configs.load("combat", CombatRules.class);
        combat = new CombatSystem(session, rules);
        world = new FakeWorld();
    }

    @Test void T02_emptyAmmoDoesNotSpendCooldownOrCreateProjectile() {
        session.vehicle(0).homingAmmo = 0;
        step(rocket());
        assertEquals(0, session.vehicle(0).homingCooldown);
        assertEquals(0, session.vehicle(0).homingAmmo);
        assertTrue(combat.projectiles().isEmpty());
        assertEquals(0, count(combat.drainEvents(), GameEvent.Type.SHOT));
    }

    @Test void T02_limitRejectsAtomicallyButAllowsHitscanAndPulse() {
        configure(json -> json.addProperty("maximumProjectiles", 1));
        step(rocket());
        session.vehicle(0).homingCooldown = 0;
        session.vehicle(0).pulseCooldown = 0;
        var attack = new VehicleCommand(0, 0, 0, false, false, true, true, true, 0, false, false);
        combat.drainEvents();
        step(attack);
        assertEquals(5, session.vehicle(0).homingAmmo);
        assertEquals(0, session.vehicle(0).homingCooldown);
        assertEquals(1, combat.projectiles().size());
        List<GameEvent> events = combat.drainEvents();
        assertTrue(events.stream().anyMatch(e -> e.type() == GameEvent.Type.SHOT && e.kind().equals("machine-gun")));
        assertEquals(1, count(events, GameEvent.Type.PULSE));
    }

    @Test void T03_overheatBlocksUntilTheExactRecoveryThreshold() {
        session.vehicle(0).heat = 100;
        session.vehicle(0).overheated = true;
        step(machineGun());
        assertTrue(session.vehicle(0).overheated);
        assertEquals(99.75f, session.vehicle(0).heat, 0.0001f);
        assertEquals(0, count(combat.drainEvents(), GameEvent.Type.SHOT));
        session.vehicle(0).heat = 35.5f;
        step(machineGun());
        assertTrue(session.vehicle(0).overheated);
        step(machineGun());
        assertFalse(session.vehicle(0).overheated);
        assertEquals(1, count(combat.drainEvents(), GameEvent.Type.SHOT));
    }

    @Test void T03_heatHasSpecifiedRiseAndCoolingDelay() {
        for (int i = 0; i < 120; i++) step(machineGun());
        assertEquals(20, session.vehicle(0).heat, 0.001f);
        for (int i = 0; i < 23; i++) step(VehicleCommand.NONE);
        assertEquals(20, session.vehicle(0).heat, 0.001f);
        step(VehicleCommand.NONE);
        assertEquals(19.75f, session.vehicle(0).heat, 0.001f);
    }

    @Test void T04_weaponSwitchPreservesCooldownAndDoesNotFire() {
        step(rocket());
        int cooldown = session.vehicle(0).homingCooldown;
        combat.drainEvents();
        step(switchWeapon());
        step(switchWeapon());
        assertEquals(0, session.vehicle(0).selectedWeapon);
        assertEquals(cooldown - 2, session.vehicle(0).homingCooldown);
        assertEquals(0, count(combat.drainEvents(), GameEvent.Type.SHOT));
        step(rocket());
        assertEquals(5, session.vehicle(0).homingAmmo);
    }

    @Test void heldRocketRepeatsExactlyAtItsCooldownBoundary() {
        step(rocket());
        assertEquals(5, session.vehicle(0).homingAmmo);
        for (int i = 0; i < 95; i++) step(rocket());
        assertEquals(5, session.vehicle(0).homingAmmo);
        step(rocket());
        assertEquals(4, session.vehicle(0).homingAmmo);
        assertEquals(96, session.vehicle(0).homingCooldown);
    }

    @Test void powerRocketUsesItsOwnAmmoAndDirectDamage() {
        session.vehicle(0).selectedWeapon = 1;
        world.positions[1].set(0, 0.55f, 6);
        world.nextSweep = new WorldQuery.Hit(1, new Vector3f(0, 0.55f, 5), new Vector3f(0, 0, -1), 0.5f);
        step(rocket());
        assertEquals(150, session.vehicle(1).hp);
        assertEquals(3, session.vehicle(0).powerAmmo);
        assertEquals(6, session.vehicle(0).homingAmmo);
        assertEquals(132, session.vehicle(0).powerCooldown);
        assertEquals(0, session.vehicle(0).homingCooldown);
    }

    @Test void T05_edgesConsumedAcrossPhysicsStepsDoNotRepeatPulseOrSwitch() {
        session.vehicle(0).pulseCooldown = 0;
        VehicleCommand command = new VehicleCommand(0, 0, 0, false, false, false, false, true, 1, false, false);
        step(command);
        for (int i = 0; i < 11; i++) step(command.withoutEdges());
        assertEquals(1, session.vehicle(0).selectedWeapon);
        assertEquals(1, count(combat.drainEvents(), GameEvent.Type.PULSE));
    }

    @Test void T07_directTargetReceivesOnlyDirectDamage() {
        world.positions[1].set(0, 0.55f, 6);
        world.nextSweep = new WorldQuery.Hit(1, new Vector3f(0, 0.55f, 5), new Vector3f(0, 0, -1), 0.5f);
        step(rocket());
        assertEquals(168, session.vehicle(1).hp, 0.0001f);
        assertEquals(1, count(combat.drainEvents(), GameEvent.Type.EXPLOSION));
        assertTrue(combat.projectiles().isEmpty());
    }

    @Test void T08_ownerTakesHalfSplashAndPulseNeverHitsOwner() {
        world.positions[0].set(0, 0, 0);
        world.nextSweep = new WorldQuery.Hit(-1, new Vector3f(0, 0, 3), Vector3f.ZERO, 1);
        step(rocket());
        assertEquals(192, session.vehicle(0).hp, 0.0001f); // Hull radius 1: d=2, 24*(1-2/6)*0.5.
        session.vehicle(0).pulseCooldown = 0;
        float hp = session.vehicle(0).hp;
        step(pulse());
        assertEquals(hp, session.vehicle(0).hp);
    }

    @Test void explosionAndPulseRequireVisibilityToAtLeastOneHullSample() {
        world.positions[1].set(0, 0, 4);
        world.hidden.add(1);
        world.nextSweep = new WorldQuery.Hit(-1, new Vector3f(0, 0, 3), Vector3f.ZERO, 1);
        step(rocket());
        session.vehicle(0).pulseCooldown = 0;
        step(pulse());
        assertEquals(200, session.vehicle(1).hp);
        world.hidden.clear();
        world.onlyLastSampleVisible = true;
        session.vehicle(0).pulseCooldown = 0;
        step(pulse());
        assertEquals(180, session.vehicle(1).hp);
    }

    @Test void T09_duplicateDamageCannotDuplicateDamageOrDeath() {
        combat.queueDamage(1, 0, 210, "power", 999);
        combat.queueDamage(1, 0, 210, "power", 999);
        combat.resolveDamage(world);
        combat.queueDamage(1, 0, 210, "power", 999);
        combat.resolveDamage(world);
        List<GameEvent> events = combat.drainEvents();
        assertEquals(1, count(events, GameEvent.Type.DESTROYED));
        assertEquals(1, count(events, GameEvent.Type.DAMAGE));
        assertEquals(200, session.vehicle(0).damageDealt);
        assertEquals(1, session.vehicle(0).eliminations);
    }

    @Test void T09_ramsDeduplicateAnUnorderedPairAndUseGreatestClosingSpeed() {
        combat.queueRam(0, 1, 7);
        combat.queueRam(1, 0, 10);
        combat.queueRam(0, 1, 8);
        combat.resolveDamage(world);
        assertEquals(192, session.vehicle(0).hp);
        assertEquals(192, session.vehicle(1).hp);
        combat.queueRam(0, 1, 40);
        combat.resolveDamage(world);
        assertEquals(192, session.vehicle(0).hp);
        session.tick = 72;
        combat.queueRam(0, 1, 40);
        combat.resolveDamage(world);
        assertEquals(167, session.vehicle(0).hp);
    }

    @Test void ramThresholdIsInclusiveAndRejectsNonFiniteSpeed() {
        combat.queueRam(0, 1, 5.99f);
        combat.queueRam(0, 1, 6);
        combat.resolveDamage(world);
        assertEquals(200, session.vehicle(0).hp);
        combat.queueRam(0, 1, 6.01f);
        combat.resolveDamage(world);
        assertEquals(199.98f, session.vehicle(0).hp, 0.0001f);
        assertThrows(IllegalArgumentException.class, () -> combat.queueRam(0, 1, Float.NaN));
    }

    @Test void T10_simultaneousDeathsAndDamageStatsDoNotDependOnQueueOrder() {
        assertEquals(simultaneous(false), simultaneous(true));
        assertEquals(MatchSession.Outcome.DRAW, session.outcome);
    }

    @Test void T11_finalTickEliminationWinsBeforeTimeLimitAndResultIsStable() {
        session = new MatchSession(42, 1);
        combat = new CombatSystem(session, rules);
        session.tick = 119;
        for (int i = 1; i < 5; i++) combat.queueDamage(i, 0, 200, "machine-gun", i);
        combat.resolveDamage(world);
        session.finishTick();
        assertEquals(MatchSession.Outcome.VICTORY, session.outcome);
        combat.queueDamage(0, 1, 999, "power", 1000);
        combat.resolveDamage(world);
        session.finishTick();
        assertEquals(MatchSession.Outcome.VICTORY, session.outcome);
    }

    @Test void sameTickCreditUsesLargestActualDamageThenStableAttackerId() {
        combat.queueDamage(4, 2, 150, "power", 1);
        combat.queueDamage(4, 1, 150, "power", 2);
        combat.resolveDamage(world);
        assertEquals(1, session.vehicle(1).eliminations);
        assertEquals(0, session.vehicle(2).eliminations);
        assertEquals(100, session.vehicle(1).damageDealt);
        assertEquals(100, session.vehicle(2).damageDealt);
    }

    @Test void equalCreditDoesNotDependOnHowAnAttackersDamageWasSplitIntoEvents() {
        session.vehicle(4).hp = 5;
        combat.queueDamage(4, 2, 3, "machine-gun", 1);
        combat.queueDamage(4, 1, 1, "machine-gun", 2);
        combat.queueDamage(4, 1, 1, "machine-gun", 3);
        combat.queueDamage(4, 1, 1, "machine-gun", 4);
        combat.resolveDamage(world);
        assertEquals(1, session.vehicle(1).eliminations);
        assertEquals(0, session.vehicle(2).eliminations);
        assertEquals(2.5f, session.vehicle(1).damageDealt);
        assertEquals(2.5f, session.vehicle(2).damageDealt);
    }

    @Test void environmentalDeathCreditsRecentAttackerAtFiveSecondBoundary() {
        combat.queueDamage(1, 0, 3, "machine-gun", 1);
        combat.resolveDamage(world);
        session.tick = 600;
        combat.queueDamage(1, -1, 500, "hazard", 2);
        combat.resolveDamage(world);
        assertEquals(1, session.vehicle(0).eliminations);
    }

    @Test void environmentalDeathAfterCreditWindowHasNoKiller() {
        combat.queueDamage(1, 0, 3, "machine-gun", 1);
        combat.resolveDamage(world);
        combat.drainEvents();
        session.tick = 601;
        combat.queueDamage(1, -1, 500, "hazard", 2);
        combat.resolveDamage(world);
        assertEquals(0, session.vehicle(0).eliminations);
        assertEquals(-1, combat.drainEvents().stream().filter(e -> e.type() == GameEvent.Type.DESTROYED).findFirst().orElseThrow().sourceId());
    }

    @Test void protectionBlocksAttacksAndDamageButNotRecoveryCost() {
        session.vehicle(0).protectionTicks = 42;
        combat.queueDamage(0, 1, 200, "power", 1);
        combat.queueDamage(0, -1, 15, "recovery", 2);
        step(rocket());
        assertEquals(185, session.vehicle(0).hp);
        assertEquals(6, session.vehicle(0).homingAmmo);
        assertTrue(combat.projectiles().isEmpty());
    }

    @Test void acceptedShotSurvivesOwnersDestruction() {
        combat.beginTick(Map.of(0, rocket()), world);
        combat.queueDamage(0, 1, 200, "power", 2000);
        combat.advanceProjectiles(world);
        combat.resolveDamage(world);
        assertFalse(session.vehicle(0).alive());
        assertEquals(1, combat.projectiles().size());
        Vector3f position = combat.projectiles().getFirst().position();
        combat.advanceProjectiles(world);
        assertTrue(combat.projectiles().getFirst().position().z > position.z);
    }

    @Test void blockedMuzzleExplodesAtObstacleWithoutSpawningBehindIt() {
        world.muzzleBlock = new WorldQuery.Hit(-1, new Vector3f(0, 0.55f, 2), new Vector3f(0, 0, -1), 0.5f);
        step(rocket());
        assertTrue(combat.projectiles().isEmpty());
        GameEvent explosion = combat.drainEvents().stream().filter(e -> e.type() == GameEvent.Type.EXPLOSION).findFirst().orElseThrow();
        assertTrue(explosion.position().z <= 2);
        assertEquals(5, session.vehicle(0).homingAmmo);
    }

    @Test void ttlAndCollisionInSameTickProduceOneExplosion() {
        configure(json -> json.getAsJsonObject("homing").addProperty("ttlSeconds", MatchSession.DT));
        world.nextSweep = new WorldQuery.Hit(-1, new Vector3f(0, 0.55f, 2.8f), new Vector3f(0, 0, -1), 0.5f);
        step(rocket());
        assertEquals(1, count(combat.drainEvents(), GameEvent.Type.EXPLOSION));
        assertTrue(combat.projectiles().isEmpty());
    }

    @Test void ttlExpiresWithoutCollisionAndProjectilesAreReadOnlyToPresentation() {
        configure(json -> json.getAsJsonObject("homing").addProperty("ttlSeconds", 2 * MatchSession.DT));
        step(rocket());
        Vector3f point = combat.projectiles().getFirst().position();
        point.set(999, 999, 999);
        assertNotEquals(point, combat.projectiles().getFirst().position());
        step(VehicleCommand.NONE);
        assertTrue(combat.projectiles().isEmpty());
        assertEquals(1, count(combat.drainEvents(), GameEvent.Type.EXPLOSION));
    }

    @Test void lockRequiresContinuousVisibleConeAndStableTieBreak() {
        world.positions[1].set(0, 0.55f, 25);
        world.positions[2].set(0, 0.55f, 25);
        for (int i = 0; i < 17; i++) step(VehicleCommand.NONE);
        assertEquals(-1, combat.lockTarget(0));
        step(VehicleCommand.NONE);
        assertEquals(1, combat.lockTarget(0));
        world.hidden.add(1);
        step(VehicleCommand.NONE);
        assertEquals(-1, combat.lockTarget(0));
        for (int i = 0; i < 17; i++) step(VehicleCommand.NONE);
        assertEquals(2, combat.lockTarget(0));
        world.positions[2].set(40, 0.55f, 10);
        step(VehicleCommand.NONE);
        assertEquals(-1, combat.lockTarget(0));
    }

    @Test void acquisitionRangeIncludesSeventyMetresButRejectsBeyondIt() {
        world.positions[1].set(0, 0.55f, 72.5f);
        for (int i = 0; i < 18; i++) step(VehicleCommand.NONE);
        assertEquals(1, combat.lockTarget(0));
        world.positions[1].z = 72.501f;
        step(VehicleCommand.NONE);
        assertEquals(-1, combat.lockTarget(0));
        world.positions[1].z = 72.499f;
        for (int i = 0; i < 18; i++) step(VehicleCommand.NONE);
        assertEquals(1, combat.lockTarget(0));
    }

    @Test void acquisitionConeRejectsJustOutsideEighteenDegrees() {
        float inside = (float) Math.toRadians(17.99);
        world.positions[1].set(30 * (float) Math.sin(inside), 0.55f, 2.5f + 30 * (float) Math.cos(inside));
        for (int i = 0; i < 18; i++) step(VehicleCommand.NONE);
        assertEquals(1, combat.lockTarget(0));
        float outside = (float) Math.toRadians(18.01);
        world.positions[1].set(30 * (float) Math.sin(outside), 0.55f, 2.5f + 30 * (float) Math.cos(outside));
        step(VehicleCommand.NONE);
        assertEquals(-1, combat.lockTarget(0));
    }

    @Test void pulseIncludesHullAtRadiusAndRejectsJustBeyondIt() {
        world.positions[1].set(11, 0, 0); // Fake hull radius 1, so nearest hull distance is exactly 10.
        world.positions[2].set(-11.001f, 0, 0);
        world.positions[3].set(0, 0, 10.999f);
        session.vehicle(0).pulseCooldown = 0;
        step(pulse());
        assertEquals(180, session.vehicle(1).hp);
        assertEquals(200, session.vehicle(2).hp);
        assertEquals(180, session.vehicle(3).hp);
    }

    @Test void homingHasLimitedAngularSpeedAndNeverReacquiresLostTarget() {
        world.positions[1].set(0, 0.55f, 50);
        for (int i = 0; i < 18; i++) step(VehicleCommand.NONE);
        step(rocket());
        ProjectileState projectile = combat.projectiles().getFirst();
        assertEquals(1, projectile.targetId());
        world.positions[1].x = 10;
        Vector3f before = projectile.direction();
        step(VehicleCommand.NONE);
        float angle = before.angleBetween(projectile.direction());
        assertTrue(angle > 0 && angle <= Math.toRadians(80) * MatchSession.DT + 0.0001f);
        world.hidden.add(1);
        for (int i = 0; i < 36; i++) step(VehicleCommand.NONE);
        assertEquals(1, projectile.targetId());
        step(VehicleCommand.NONE);
        assertEquals(-1, projectile.targetId());
        world.hidden.clear();
        step(VehicleCommand.NONE);
        assertEquals(-1, projectile.targetId());
    }

    @Test void deadTargetIsLostImmediately() {
        world.positions[1].set(0, 0.55f, 50);
        for (int i = 0; i < 18; i++) step(VehicleCommand.NONE);
        step(rocket());
        session.vehicle(1).hp = 0;
        step(VehicleCommand.NONE);
        assertEquals(-1, combat.projectiles().getFirst().targetId());
    }

    @Test void impulsesAreHorizontalAndHaveCommonPerTickLimit() {
        world.positions[1].set(0, 0, 5);
        for (int id : List.of(0, 2, 3, 4)) {
            world.positions[id].set(0, 0, 0);
            session.vehicle(id).pulseCooldown = 0;
        }
        combat.beginTick(Map.of(0, pulse(), 2, pulse(), 3, pulse(), 4, pulse()), world);
        combat.advanceProjectiles(world);
        combat.resolveDamage(world);
        assertTrue(world.impulses.get(1).length() / world.mass(1) <= 6.0001f);
        assertEquals(0, world.impulses.get(1).y);
    }

    @Test void T16_weaponSpreadIsRepeatableAndIndependentOfUnrelatedSoundRandomness() {
        List<Vector3f> first = spreadTrace(42, false);
        List<Vector3f> second = spreadTrace(42, true);
        assertEquals(first, second);
        assertNotEquals(first, spreadTrace(99, false));
    }

    @Test void hitscanDoesNotDamageTwoCarsBehindEachOther() {
        world.rayHit = new WorldQuery.Hit(1, new Vector3f(0, 0.55f, 10), new Vector3f(0, 0, -1), 0.1f);
        step(machineGun());
        assertEquals(197, session.vehicle(1).hp);
        assertEquals(200, session.vehicle(2).hp);
        assertEquals(0, world.lastIgnoredVehicle);
    }

    @Test void cleanupReleasesAllSessionOwnedCombatCollections() {
        step(rocket());
        combat.queueDamage(1, 0, 5, "machine-gun", 555);
        combat.clear();
        assertTrue(combat.projectiles().isEmpty());
        assertEquals(0, combat.pendingDamageCount());
        assertTrue(combat.drainEvents().isEmpty());
        assertEquals(-1, combat.lockTarget(0));
    }

    private List<Float> simultaneous(boolean reversed) {
        createSession();
        List<Integer> targets = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        if (reversed) Collections.reverse(targets);
        for (int id : targets) combat.queueDamage(id, (id + 1) % 5, 250, "power", id + 100);
        combat.resolveDamage(world);
        session.finishTick();
        assertEquals(5, count(combat.drainEvents(), GameEvent.Type.DESTROYED));
        return session.vehicles.stream().map(v -> v.damageDealt).toList();
    }

    private List<Vector3f> spreadTrace(long seed, boolean soundNoise) {
        session = new MatchSession(seed, 360);
        combat = new CombatSystem(session, rules);
        world = new FakeWorld();
        Random sound = new Random(17);
        for (int i = 0; i < 120; i++) {
            if (soundNoise) for (int j = 0; j < 17; j++) sound.nextFloat();
            step(machineGun());
        }
        return List.copyOf(world.shotDirections);
    }

    private void configure(Consumer<JsonObject> mutate) {
        JsonObject json = Configs.gson().toJsonTree(rules).getAsJsonObject();
        mutate.accept(json);
        rules = Configs.gson().fromJson(json, CombatRules.class);
        combat = new CombatSystem(session, rules);
    }

    private void step(VehicleCommand command) {
        combat.beginTick(Map.of(0, command), world);
        combat.advanceProjectiles(world);
        combat.resolveDamage(world);
        session.finishTick();
    }

    private static long count(List<GameEvent> events, GameEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    private static VehicleCommand rocket() { return new VehicleCommand(0, 0, 0, false, false, false, true, false, 0, false, false); }
    private static VehicleCommand machineGun() { return new VehicleCommand(0, 0, 0, false, false, true, false, false, 0, false, false); }
    private static VehicleCommand pulse() { return new VehicleCommand(0, 0, 0, false, false, false, false, true, 0, false, false); }
    private static VehicleCommand switchWeapon() { return new VehicleCommand(0, 0, 0, false, false, false, false, false, 1, false, false); }

    private static final class FakeWorld implements WorldQuery {
        final Vector3f[] positions = {new Vector3f(), new Vector3f(100, 0, 100), new Vector3f(120, 0, 100), new Vector3f(140, 0, 100), new Vector3f(160, 0, 100)};
        final Map<Integer, Vector3f> impulses = new HashMap<>();
        final Set<Integer> hidden = new HashSet<>();
        final List<Vector3f> shotDirections = new ArrayList<>();
        Hit nextSweep, muzzleBlock, rayHit;
        boolean onlyLastSampleVisible;
        int lastIgnoredVehicle = -1;
        @Override public Vector3f position(int id) { return positions[id].clone(); }
        @Override public Vector3f velocity(int id) { return new Vector3f(); }
        @Override public Quaternion rotation(int id) { return new Quaternion(); }
        @Override public boolean grounded(int id) { return true; }
        @Override public float mass(int id) { return 1100; }
        @Override public Hit ray(Vector3f from, Vector3f to, int ignored) {
            lastIgnoredVehicle = ignored;
            if (from.distance(to) < 2) return muzzleBlock;
            shotDirections.add(to.subtract(from).normalizeLocal());
            return rayHit;
        }
        @Override public Hit sweep(Vector3f from, Vector3f to, float radius, int ignored) {
            Hit hit = nextSweep;
            nextSweep = null;
            return hit;
        }
        @Override public boolean visible(Vector3f from, Vector3f to, int id) {
            return !hidden.contains(id) && (!onlyLastSampleVisible || to.z < positions[id].z);
        }
        @Override public float distanceToHull(int id, Vector3f point) { return Math.max(0, positions[id].distance(point) - 1); }
        @Override public void impulse(int id, Vector3f value) { impulses.merge(id, value.clone(), Vector3f::add); }
    }
}
