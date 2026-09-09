package game.wreckriff.combat;

import game.wreckriff.combat.AbilityId;

import game.wreckriff.combat.WeaponType;

import com.google.gson.JsonObject;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Pure combat rules. Fake geometry is intentional here; real sweeps have physicsTest coverage. */
class CombatSystemTest {
    @Test void localMultiplierUsesActualHitscanPointAndNeverGenericDamage() {
        createSession();world.positions[1].set(0,0,10);Vector3f local=new Vector3f(.2f,.4f,-2);
        world.rayHit=new WorldQuery.Hit(1,world.position(1).add(local),new Vector3f(0,0,-1),.5f);
        int[] calls={0};combat.directDamageMultiplier((target,point)->{assertEquals(1,target);assertEquals(local,point);calls[0]++;return 1.25f;});
        step(machineGun());assertEquals(200-rules.machineGun().damage()*1.25f,session.vehicle(1).hp,.001f);
        assertEquals(1,calls[0]);combat.queueDamage(1,0,10,"hazard",91234);combat.resolveDamage(world);
        assertEquals(200-rules.machineGun().damage()*1.25f-10,session.vehicle(1).hp,.001f);assertEquals(1,calls[0]);
    }
    @Test void explosionOnlyMultipliesTheDirectTargetNotSyntheticSplashContactPoints() {
        createSession();world.positions[1].set(0,0,10);world.positions[2].set(1,0,10);
        world.nextSweep=new WorldQuery.Hit(1,new Vector3f(0,0,9),new Vector3f(0,0,-1),.5f);
        List<Integer> targets=new ArrayList<>();combat.directDamageMultiplier((target,point)->{targets.add(target);return 1.25f;});
        step(rocket());assertEquals(List.of(1),targets);assertTrue(session.vehicle(2).hp<200,"Actual splash fixture must also take damage");
    }
    private MatchSession session;
    private CombatRules rules;
    private CombatSystem combat;
    private FakeWorld world;

    @BeforeEach void createSession() {
        session = new MatchSession(42, 360);
        rules = Configs.load("combat", CombatRules.class);
        combat = new CombatSystem(session, rules);
        world = new FakeWorld();
        session.vehicles.forEach(v -> v.hp=200); // Damaged-hull fixtures isolate damage arithmetic from spawn health.
    }

    @Test void T02_emptyAmmoDoesNotSpendCooldownOrCreateProjectile() {
        session.vehicle(0).weapon(WeaponType.HOMING).ammo = 0;
        step(rocket());
        assertEquals(0, session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks);
        assertEquals(0, session.vehicle(0).weapon(WeaponType.HOMING).ammo);
        assertTrue(combat.projectiles().isEmpty());
        assertEquals(0, count(combat.drainEvents(), GameEvent.Type.SHOT));
    }

    @Test void T02_limitRejectsAtomicallyButAllowsHitscan() {
        configure(json -> json.addProperty("maximumProjectiles", 1));
        step(rocket());
        session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks = 0;
        var attack = new VehicleCommand(0, 0, 0, false, false, true, true, null, 0, false, false,AbilityId.NONE);
        combat.drainEvents();
        step(attack);
        assertEquals(5, session.vehicle(0).weapon(WeaponType.HOMING).ammo);
        assertEquals(0, session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks);
        assertEquals(1, combat.projectiles().size());
        List<GameEvent> events = combat.drainEvents();
        assertTrue(events.stream().anyMatch(e -> e.type() == GameEvent.Type.SHOT && e.kind().equals("machine-gun")));
    }

    @Test void continuousMachineGunRetainsItsCadenceForThirtySeconds() {
        for(int i=0;i<30*120;i++)step(machineGun());
        assertEquals(300,count(combat.drainEvents(),GameEvent.Type.SHOT));
    }

    @Test void T04_weaponSwitchPreservesCooldownAndDoesNotFire() {
        step(rocket());
        int cooldown = session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks;
        combat.drainEvents();
        step(switchWeapon());
        step(new VehicleCommand(0,0,0,false,false,false,false,null,-1,false,false,AbilityId.NONE));
        assertEquals(WeaponType.HOMING, session.vehicle(0).selectedWeapon);
        assertEquals(cooldown - 2, session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks);
        assertEquals(0, count(combat.drainEvents(), GameEvent.Type.SHOT));
        step(rocket());
        assertEquals(5, session.vehicle(0).weapon(WeaponType.HOMING).ammo);
    }

    @ParameterizedTest
    @CsvSource({"HOMING,60", "POWER,84", "MINE,84", "NAPALM,108", "CANNON,168", "BALLISTIC,360"})
    void heldWeaponRepeatsExactlyAtItsCooldownBoundary(WeaponType type, int intervalTicks) {
        session.vehicle(0).selectedWeapon = type;
        WeaponSlot slot = session.vehicle(0).weapon(type);
        int initialAmmo = slot.ammo;
        world.flatSupport = true;
        step(rocket());
        assertEquals(initialAmmo - 1, slot.ammo, "Ready weapon fires on the first tick");
        assertEquals(1, weaponLaunches(combat.drainEvents(), type));
        // A second mine needs free supported ground, away from the first placement.
        world.positions[0].x += 10;
        for (int i = 1; i < intervalTicks; i++) {
            step(rocket());
            assertEquals(initialAmmo - 1, slot.ammo, "No early shot at tick " + i);
        }
        assertEquals(0, weaponLaunches(combat.drainEvents(), type));
        step(rocket());
        assertEquals(initialAmmo - 2, slot.ammo);
        assertEquals(1, weaponLaunches(combat.drainEvents(), type));
        assertEquals(intervalTicks, slot.cooldownTicks);
    }

    private static long weaponLaunches(List<GameEvent> events, WeaponType type) {
        return events.stream().filter(event -> event.kind().equals(type.id())
                && event.type() == (type == WeaponType.MINE ? GameEvent.Type.MINE_PLACED : GameEvent.Type.SHOT)).count();
    }

    @Test void restoredReloadFinishesBeforeTheNextShotUsesTheNewInterval() {
        var player = session.vehicle(0);
        player.selectedWeapon = WeaponType.BALLISTIC;
        var slot = player.weapon(WeaponType.BALLISTIC);
        slot.cooldownTicks = 540; // A checkpoint from the previous 4.5-second balance.
        var saved = MatchCheckpoint.player(player);
        slot.cooldownTicks = 0;
        MatchCheckpoint.restorePlayer(player, saved);
        assertEquals(540, slot.cooldownTicks);
        int ammo = slot.ammo;
        for (int i = 0; i < 539; i++) step(rocket());
        assertEquals(ammo, slot.ammo);
        assertEquals(0, weaponLaunches(combat.drainEvents(), WeaponType.BALLISTIC));
        step(rocket());
        assertEquals(ammo - 1, slot.ammo);
        assertEquals(1, weaponLaunches(combat.drainEvents(), WeaponType.BALLISTIC));
        assertEquals(360, slot.cooldownTicks);
    }

    @Test void powerRocketUsesItsOwnAmmoAndDirectDamage() {
        session.vehicle(0).selectedWeapon = WeaponType.POWER;
        world.positions[1].set(0, 0.55f, 6);
        world.nextSweep = new WorldQuery.Hit(1, new Vector3f(0, 0.55f, 5), new Vector3f(0, 0, -1), 0.5f);
        step(rocket());
        assertEquals(150, session.vehicle(1).hp);
        assertEquals(3, session.vehicle(0).weapon(WeaponType.POWER).ammo);
        assertEquals(6, session.vehicle(0).weapon(WeaponType.HOMING).ammo);
        assertEquals(84, session.vehicle(0).weapon(WeaponType.POWER).cooldownTicks);
        assertEquals(0, session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks);
    }

    @Test void T05_edgesConsumedAcrossPhysicsStepsDoNotRepeatSwitch() {
        VehicleCommand command = new VehicleCommand(0, 0, 0, false, false, false, false, null, 1, false, false,AbilityId.NONE);
        step(command);
        for (int i = 0; i < 11; i++) step(command.withoutEdges());
        assertEquals(WeaponType.POWER, session.vehicle(0).selectedWeapon);
    }

    @Test void T07_directTargetReceivesOnlyDirectDamage() {
        world.positions[1].set(0, 0.55f, 6);
        world.nextSweep = new WorldQuery.Hit(1, new Vector3f(0, 0.55f, 5), new Vector3f(0, 0, -1), 0.5f);
        step(rocket());
        assertEquals(168, session.vehicle(1).hp, 0.0001f);
        assertEquals(1, count(combat.drainEvents(), GameEvent.Type.EXPLOSION));
        assertTrue(combat.projectiles().isEmpty());
    }

    @Test void T08_ownerTakesHalfSplash() {
        world.positions[0].set(0, 0, 0);
        world.nextSweep = new WorldQuery.Hit(-1, new Vector3f(0, 0, 3), Vector3f.ZERO, 1);
        step(rocket());
        assertEquals(192, session.vehicle(0).hp, 0.0001f); // Hull radius 1: d=2, 24*(1-2/6)*0.5.
    }

    @Test void explosionRequiresVisibilityToAtLeastOneHullSample() {
        world.positions[1].set(0, 0, 4);
        world.hidden.add(1);
        world.nextSweep = new WorldQuery.Hit(-1, new Vector3f(0, 0, 3), Vector3f.ZERO, 1);
        step(rocket());
        assertEquals(200, session.vehicle(1).hp);
        world.hidden.clear();
        world.onlyLastSampleVisible = true;
        session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks=0;
        world.nextSweep = new WorldQuery.Hit(-1, new Vector3f(0,0,3), Vector3f.ZERO, 1);
        step(rocket());
        assertEquals(176, session.vehicle(1).hp);
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
        combat.queueRam(0, 1, 7, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.queueRam(1, 0, 10, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.queueRam(0, 1, 8, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.resolveDamage(world);
        assertEquals(192, session.vehicle(0).hp);
        assertEquals(192, session.vehicle(1).hp);
        combat.queueRam(0, 1, 40, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.resolveDamage(world);
        assertEquals(192, session.vehicle(0).hp);
        session.tick = 72;
        combat.queueRam(0, 1, 40, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.resolveDamage(world);
        assertEquals(167, session.vehicle(0).hp);
    }

    @Test void moderateBodyContactsAreAudibleWithoutDamageAndQuietTouchesStaySilent() {
        combat.queueRam(0,1,1.2f,Vector3f.ZERO,Vector3f.UNIT_Z);
        assertTrue(combat.drainEvents().isEmpty());
        combat.queueRam(0,1,3,Vector3f.ZERO,Vector3f.UNIT_Z);
        combat.queueRam(1,0,3,Vector3f.ZERO,Vector3f.UNIT_Z);
        combat.resolveDamage(world);
        var events=combat.drainEvents();assertEquals(1,count(events,GameEvent.Type.RAM));
        assertEquals(0,count(events,GameEvent.Type.DAMAGE));assertEquals(200,session.vehicle(0).hp);
        session.tick=17;combat.queueRam(0,1,3,Vector3f.ZERO,Vector3f.UNIT_Z);assertTrue(combat.drainEvents().isEmpty());
        session.tick=18;combat.queueRam(0,1,3,Vector3f.ZERO,Vector3f.UNIT_Z);assertEquals(1,count(combat.drainEvents(),GameEvent.Type.RAM));
    }
    @Test void ramThresholdIsInclusiveAndRejectsNonFiniteSpeed() {
        combat.queueRam(0, 1, 5.99f, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.queueRam(0, 1, 6, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.resolveDamage(world);
        assertEquals(200, session.vehicle(0).hp);
        combat.queueRam(0, 1, 6.01f, Vector3f.ZERO, Vector3f.UNIT_Z);
        combat.resolveDamage(world);
        assertEquals(199.98f, session.vehicle(0).hp, 0.0001f);
        assertThrows(IllegalArgumentException.class, () -> combat.queueRam(0, 1, Float.NaN, Vector3f.ZERO, Vector3f.UNIT_Z));
    }

    @Test void T10_simultaneousDeathsAndDamageStatsDoNotDependOnQueueOrder() {
        assertEquals(simultaneous(false), simultaneous(true));
        assertEquals(MatchSession.Outcome.DRAW, session.outcome);
    }

    @Test void T11_finalTickEliminationWinsBeforeTimeLimitAndResultIsStable() {
        session = new MatchSession(42, 1);
        combat = new CombatSystem(session, rules);
        session.tick = 119;
        session.vehicles.forEach(v->v.hp=200);
        for (int i = 1; i < 5; i++) combat.queueDamage(i, 0, 200, "machine-gun", i);
        combat.resolveDamage(world);
        game.wreckriff.simulation.MatchRuntime.finishTick(session);
        assertEquals(MatchSession.Outcome.VICTORY, session.outcome);
        combat.queueDamage(0, 1, 999, "power", 1000);
        combat.resolveDamage(world);
        game.wreckriff.simulation.MatchRuntime.finishTick(session);
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
        assertEquals(6, session.vehicle(0).weapon(WeaponType.HOMING).ammo);
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
        assertEquals(5, session.vehicle(0).weapon(WeaponType.HOMING).ammo);
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
        game.wreckriff.simulation.MatchRuntime.finishTick(session);
        assertEquals(5, count(combat.drainEvents(), GameEvent.Type.DESTROYED));
        return session.vehicles.stream().map(v -> v.damageDealt).toList();
    }

    private List<Vector3f> spreadTrace(long seed, boolean soundNoise) {
        session = new MatchSession(seed, 360);
        combat = new CombatSystem(session, rules);
        world = new FakeWorld();
        session.vehicles.forEach(v -> v.hp=200); // Damaged-hull fixtures isolate damage arithmetic from spawn health.
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
        session=new MatchSession(session.seed,360,rules);session.vehicles.forEach(v->v.hp=200);
        combat = new CombatSystem(session, rules);
    }

    private void step(VehicleCommand command) {
        combat.beginTick(Map.of(0, command), world);
        combat.advanceProjectiles(world);
        combat.resolveDamage(world);
        game.wreckriff.simulation.MatchRuntime.finishTick(session);
    }

    private static long count(List<GameEvent> events, GameEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    private static VehicleCommand rocket() { return new VehicleCommand(0, 0, 0, false, false, false, true, null, 0, false, false,AbilityId.NONE); }
    private static VehicleCommand machineGun() { return new VehicleCommand(0, 0, 0, false, false, true, false, null, 0, false, false,AbilityId.NONE); }
    private static VehicleCommand switchWeapon() { return new VehicleCommand(0, 0, 0, false, false, false, false, null, 1, false, false,AbilityId.NONE); }

    private static final class FakeWorld implements WorldQuery {
        final Vector3f[] positions = {new Vector3f(), new Vector3f(100, 0, 100), new Vector3f(120, 0, 100), new Vector3f(140, 0, 100), new Vector3f(160, 0, 100)};
        final Map<Integer, Vector3f> impulses = new HashMap<>();
        final Set<Integer> hidden = new HashSet<>();
        final List<Vector3f> shotDirections = new ArrayList<>();
        Hit nextSweep, muzzleBlock, rayHit;
        boolean onlyLastSampleVisible, flatSupport;
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
        @Override public Hit sweep(Vector3f from, Vector3f to, float radius, int ignored,float stepStart,float stepEnd) {
            Hit hit = nextSweep;
            nextSweep = null;
            return hit;
        }
        @Override public Hit staticSweep(Vector3f from,Vector3f to,float radius) {return null;}
        @Override public Support support(Vector3f from, float depth) {
            return flatSupport && from.y >= 0 && from.y <= depth
                    ? new Support(0, new Vector3f(from.x, 0, from.z), Vector3f.UNIT_Y)
                    : WorldQuery.super.support(from, depth);
        }
        @Override public boolean visible(Vector3f from, Vector3f to, int id) {
            return !hidden.contains(id) && (!onlyLastSampleVisible || to.z < positions[id].z);
        }
        @Override public float distanceToHull(int id, Vector3f point) { return Math.max(0, positions[id].distance(point) - 1); }
        public void impulse(int id,Vector3f linear,Vector3f torque,float angularCap) { impulses.merge(id,linear.clone(),Vector3f::add); }
        public Vector3f closestHullPoint(int id,Vector3f from) { return position(id); }
    }
}
