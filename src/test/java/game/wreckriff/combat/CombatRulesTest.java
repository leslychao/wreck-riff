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
        assertEquals(3, rules.machineGun().damage());
        assertEquals(12, rules.machineGun().cooldownTicks());
        assertEquals(96, CombatRules.ticks(rules.homing().cooldownSeconds()));
        assertEquals(132, CombatRules.ticks(rules.power().cooldownSeconds()));
        assertEquals(18, CombatRules.ticks(rules.targeting().acquisitionSeconds()));
        assertEquals(36, CombatRules.ticks(rules.targeting().occlusionGraceSeconds()));
        assertEquals(72, CombatRules.ticks(rules.ram().cooldownSeconds()));
        assertEquals(new CombatRules.Blast(4,1),rules.homing().blast());
        assertEquals(new CombatRules.Blast(6,2),rules.power().blast());
        assertEquals(new CombatRules.Blast(4,5),rules.mine().blast());
        assertEquals(new CombatRules.Blast(2,.5f),rules.napalm().blast());
        assertEquals(new CombatRules.BlastLimits(9,6,2),rules.blastLimits());
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
}
