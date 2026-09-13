package game.wreckriff.combat;

import com.google.gson.JsonObject;
import game.wreckriff.config.Configs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatRulesTest {
    private JsonObject configuration() {
        return Configs.gson().toJsonTree(Configs.load("combat", CombatRules.class)).getAsJsonObject();
    }

    @Test void T01_bundledWeaponNumbersMatchTheApprovedSpecification() {
        CombatRules rules = Configs.load("combat", CombatRules.class);
        assertEquals(64, rules.maximumProjectiles());
        assertEquals(2.4f, rules.machineGun().damage());
        assertEquals(12, rules.machineGun().cooldownTicks());
        assertEquals(78, CombatRules.ticks(rules.homing().cooldownSeconds()));
        assertEquals(108, CombatRules.ticks(rules.power().cooldownSeconds()));
        assertEquals(120, CombatRules.ticks(rules.mine().cooldownSeconds()));
        assertEquals(144, CombatRules.ticks(rules.napalm().cooldownSeconds()));
        assertEquals(216, CombatRules.ticks(rules.cannon().cooldownSeconds()));
        assertEquals(480, CombatRules.ticks(rules.ballistic().cooldownSeconds()));
        assertEquals(18, CombatRules.ticks(rules.targeting().acquisitionSeconds()));
        assertEquals(36, CombatRules.ticks(rules.targeting().occlusionGraceSeconds()));
        assertEquals(96, CombatRules.ticks(rules.ram().cooldownSeconds()));
        assertEquals(new CombatRules.Blast(4,1),rules.homing().blast());
        assertEquals(new CombatRules.Blast(6,2),rules.power().blast());
        assertEquals(new CombatRules.Blast(4,5),rules.mine().blast());
        assertEquals(new CombatRules.Blast(2,.5f),rules.napalm().blast());
        assertEquals(new CombatRules.BlastLimits(9,6,2),rules.blastLimits());
        assertEquals(new CombatRules.BlastLimits(16,10,6),rules.heavyBlastLimits());
        assertEquals(65,rules.cannon().directDamage());assertEquals(13200,rules.cannon().horizontalImpulse());
        assertEquals(2,rules.cannon().ricochets());assertEquals(4,rules.ballistic().charges());
        assertEquals(28,rules.ballistic().damage());assertEquals(54,CombatRules.ticks(rules.ballistic().releaseIntervalSeconds()));
        assertEquals(12,rules.ballistic().turnDegreesPerSecond());assertEquals(70,rules.ballistic().maximumRange());
        assertEquals(30,rules.ballistic().acquisitionConeDegrees());assertEquals(3,rules.ballistic().maximumDeviation());
        assertEquals(.35f,rules.ballistic().maximumLeadSeconds());assertEquals(6,rules.ballistic().maximumLead());
        assertEquals(.6f,rules.ballistic().maximumGuidanceSeconds());assertEquals(.6f,rules.ballistic().guidanceCutoffSeconds());
        assertEquals(25,rules.napalm().damagePerSecond());assertEquals(1.5f,rules.control().freezeSeconds());
        assertEquals(12,rules.control().freezeCooldownSeconds());
        assertEquals(18,SpecialRules.cooldownSeconds("rivet"));assertEquals(26,SpecialRules.cooldownSeconds("grinder"));
        assertEquals(16,SpecialRules.cooldownSeconds("spark"));
        assertEquals(18,rules.napalm().assist().turnDegreesPerSecond());assertEquals(3,rules.napalm().assist().maximumDeviation());
    }

    @Test void T01_unknownOrMissingNestedFieldsAreRejected() {
        JsonObject extra = configuration();
        extra.getAsJsonObject("homing").addProperty("legacyDamage", 100);
        assertThrows(IllegalArgumentException.class, () -> Configs.validate(extra, CombatRules.class, "combat"));
        JsonObject missing = configuration();
        missing.getAsJsonObject("homing").remove("speed");
        assertThrows(IllegalArgumentException.class, () -> Configs.validate(missing, CombatRules.class, "combat"));
    }

    @Test void T01_nonFiniteAndWrongPrimitiveTypesAreRejected() {
        JsonObject nonFinite = configuration();
        nonFinite.addProperty("projectileRadius", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> Configs.validate(nonFinite, CombatRules.class, "combat"));
        JsonObject text = configuration();
        text.addProperty("projectileRadius", "0.15");
        assertThrows(IllegalArgumentException.class, () -> Configs.validate(text, CombatRules.class, "combat"));
    }

    @Test void T01_invalidProjectileLimitsRadiiTtlAndAmmoAreRejected() {
        JsonObject limit = configuration();
        limit.addProperty("maximumProjectiles", 65);
        assertThrows(RuntimeException.class, () -> Configs.gson().fromJson(limit, CombatRules.class));
        JsonObject radius = configuration();
        radius.getAsJsonObject("power").addProperty("explosionRadius", 0);
        assertThrows(RuntimeException.class, () -> Configs.gson().fromJson(radius, CombatRules.class));
        JsonObject ttl = configuration();
        ttl.getAsJsonObject("homing").addProperty("ttlSeconds", -1);
        assertThrows(RuntimeException.class, () -> Configs.gson().fromJson(ttl, CombatRules.class));
        JsonObject ammo = configuration();
        ammo.getAsJsonObject("homing").addProperty("initialAmmo", 13);
        assertThrows(RuntimeException.class, () -> Configs.gson().fromJson(ammo, CombatRules.class));
    }

    @Test void T01_invalidTargetRetentionIsRejected() {
        JsonObject cone = configuration();
        cone.getAsJsonObject("targeting").addProperty("retentionConeDegrees", 17);
        assertThrows(RuntimeException.class, () -> Configs.gson().fromJson(cone, CombatRules.class));
    }
    @Test void fasterRoundsRetainTheirFlightBudgetsAndMachineGunAndBallisticStayUnchanged() {
        var rules=Configs.load("combat",CombatRules.class);
        assertEquals(100,rules.homing().speed());assertEquals(1.6f,rules.homing().ttlSeconds());
        assertEquals(140,rules.power().speed());assertEquals(1.3f,rules.power().ttlSeconds());
        assertEquals(42,rules.napalm().speed());assertEquals(2,rules.napalm().ttlSeconds());
        assertEquals(80,rules.cannon().speed());assertEquals(2.75f,rules.cannon().ttlSeconds());
        assertEquals(80,rules.control().freezeSpeed());assertEquals(.75f,rules.control().freezeTtlSeconds());
        assertEquals(160,rules.homing().speed()*rules.homing().ttlSeconds(),.001f);
        assertEquals(182,rules.power().speed()*rules.power().ttlSeconds(),.001f);
        assertEquals(84,rules.napalm().speed()*rules.napalm().ttlSeconds(),.001f);
        assertEquals(220,rules.cannon().speed()*rules.cannon().ttlSeconds(),.001f);
        assertEquals(60,rules.control().freezeSpeed()*rules.control().freezeTtlSeconds(),.001f);
        assertEquals(0,rules.homing().initialAmmo());assertEquals(0,rules.power().initialAmmo());
        assertEquals(0,rules.napalm().initialAmmo());assertEquals(0,rules.cannon().initialAmmo());
        assertEquals(0,rules.ballistic().initialAmmo());assertEquals(0,rules.mine().initialAmmo());
        assertEquals(2.4f,rules.machineGun().damage());assertEquals(10,rules.machineGun().shotsPerSecond());
        assertEquals(75,rules.machineGun().range());assertEquals(1,rules.machineGun().spreadDegrees());
        assertEquals(45,rules.ballistic().carrierSpeed());assertEquals(18,rules.ballistic().gravity());
        assertEquals(80,rules.targeting().turnDegreesPerSecond());
    }
    @Test void mineIsLastForDisplayCyclingAndStableWeaponIds() {
        assertArrayEquals(new String[]{"homing","power","napalm","ballistic","cannon","mine"},
                java.util.Arrays.stream(WeaponType.values()).map(WeaponType::id).toArray(String[]::new));
        assertEquals(WeaponType.MINE,WeaponType.HOMING.cycle(-1));
        assertEquals(WeaponType.HOMING,WeaponType.MINE.cycle(1));
    }

    @Test void ballisticGuidanceLimitsMustBePresentAndPositive() {
        for(String field:new String[]{"acquisitionConeDegrees","maximumDeviation","maximumGuidanceSeconds","guidanceCutoffSeconds"}) {
            JsonObject invalid=configuration();invalid.getAsJsonObject("ballistic").addProperty(field,0);
            assertThrows(RuntimeException.class,()->Configs.gson().fromJson(invalid,CombatRules.class));
            JsonObject missing=configuration();missing.getAsJsonObject("ballistic").remove(field);
            assertThrows(IllegalArgumentException.class,()->Configs.validate(missing,CombatRules.class,"combat"));
        }
    }
}
