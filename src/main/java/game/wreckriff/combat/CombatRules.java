package game.wreckriff.combat;

import game.wreckriff.simulation.MatchSession;
import java.util.Objects;

/** All weapon balance numbers are supplied by the strictly validated bundled combat.json. */
public record CombatRules(int maximumProjectiles, float projectileRadius, float explosionSurfaceOffset,
        float ownerSplashMultiplier, float maximumCombinedDeltaSpeed, float killCreditSeconds,
        float emptyFeedbackSeconds, MachineGun machineGun, Rocket homing, Rocket power,
        Pulse pulse, Targeting targeting, Ram ram, Mine mine, Napalm napalm, Control control) {
    public CombatRules {
        if (maximumProjectiles < 1 || maximumProjectiles > 64) throw new IllegalArgumentException("maximumProjectiles must be 1..64");
        positive("projectileRadius", projectileRadius);
        positive("explosionSurfaceOffset", explosionSurfaceOffset);
        range("ownerSplashMultiplier", ownerSplashMultiplier, 0, 1);
        positive("maximumCombinedDeltaSpeed", maximumCombinedDeltaSpeed);
        positive("killCreditSeconds", killCreditSeconds);
        positive("emptyFeedbackSeconds", emptyFeedbackSeconds);
        Objects.requireNonNull(machineGun, "machineGun");
        Objects.requireNonNull(homing, "homing");
        Objects.requireNonNull(power, "power");
        Objects.requireNonNull(pulse, "pulse");
        Objects.requireNonNull(targeting, "targeting");
        Objects.requireNonNull(ram, "ram");
        Objects.requireNonNull(mine,"mine"); Objects.requireNonNull(napalm,"napalm"); Objects.requireNonNull(control,"control");
        if (homing.maximumDeltaSpeed > maximumCombinedDeltaSpeed || power.maximumDeltaSpeed > maximumCombinedDeltaSpeed
                || pulse.maximumDeltaSpeed > maximumCombinedDeltaSpeed) {
            throw new IllegalArgumentException("Individual impulses must not exceed the combined speed limit");
        }
    }

    public record MachineGun(float damage, float shotsPerSecond, float range, float spreadDegrees) {
        public MachineGun {
            positive("machineGun.damage", damage);
            CombatRules.range("machineGun.shotsPerSecond", shotsPerSecond, Float.MIN_NORMAL, MatchSession.TICKS_PER_SECOND);
            positive("machineGun.range", range);
            CombatRules.range("machineGun.spreadDegrees", spreadDegrees, 0, 90);
        }
        public int cooldownTicks() { return Math.max(1, Math.round(MatchSession.TICKS_PER_SECOND / shotsPerSecond)); }
    }

    public record Mine(int maximumActive,int initialAmmo,int maximumAmmo,float cooldownSeconds,
            float placementDistance,float supportDepth,float armSeconds,float triggerRadius,
            float explosionRadius,float damage,float lifetimeSeconds) {
        public Mine {
            if(maximumActive<1||maximumActive>10)throw new IllegalArgumentException("Mine cap must be 1..10");
            ammo(initialAmmo,maximumAmmo); positive("mine.cooldown",cooldownSeconds);
            positive("mine.placement",placementDistance);positive("mine.support",supportDepth);
            positive("mine.arm",armSeconds);positive("mine.trigger",triggerRadius);
            positive("mine.radius",explosionRadius);positive("mine.damage",damage);positive("mine.lifetime",lifetimeSeconds);
            if(armSeconds>=lifetimeSeconds)throw new IllegalArgumentException("Mine must arm before expiration");
        }
    }
    public record Napalm(int maximumZones,int initialAmmo,int maximumAmmo,float cooldownSeconds,
            float speed,float upwardSpeed,float gravity,float ttlSeconds,float impactDamage,
            float radius,float durationSeconds,float damagePerSecond,float intervalSeconds,float supportDepth) {
        public Napalm {
            if(maximumZones<1||maximumZones>6)throw new IllegalArgumentException("Fire-zone cap must be 1..6");
            ammo(initialAmmo,maximumAmmo);positive("napalm.cooldown",cooldownSeconds);
            positive("napalm.speed",speed);positive("napalm.upwardSpeed",upwardSpeed);positive("napalm.gravity",gravity);
            positive("napalm.ttl",ttlSeconds);positive("napalm.impact",impactDamage);positive("napalm.radius",radius);
            positive("napalm.duration",durationSeconds);positive("napalm.damage",damagePerSecond);
            positive("napalm.interval",intervalSeconds);positive("napalm.supportDepth",supportDepth);
            if(ticks(intervalSeconds)<1||intervalSeconds>durationSeconds)throw new IllegalArgumentException("Invalid fire interval");
        }
    }
    public record Control(float freezeSeconds,float freezeCooldownSeconds,float freezeSpeed,float freezeTtlSeconds,
            float freezeRadius,float stunSeconds,float stunCooldownSeconds,float stunRange,float stunHalfAngleDegrees,
            float shieldSeconds,float shieldCooldownSeconds,float shieldDamageMultiplier,float immunitySeconds) {
        public Control {
            positive("freeze.duration",freezeSeconds);positive("freeze.cooldown",freezeCooldownSeconds);
            positive("freeze.speed",freezeSpeed);positive("freeze.ttl",freezeTtlSeconds);positive("freeze.radius",freezeRadius);
            positive("stun.duration",stunSeconds);positive("stun.cooldown",stunCooldownSeconds);positive("stun.range",stunRange);
            range("stun.halfAngle",stunHalfAngleDegrees,1,90);positive("shield.duration",shieldSeconds);
            positive("shield.cooldown",shieldCooldownSeconds);range("shield.multiplier",shieldDamageMultiplier,0,1);
            positive("control.immunity",immunitySeconds);
        }
    }
    private static void ammo(int initial,int maximum) {
        if(initial<0||maximum<1||initial>maximum)throw new IllegalArgumentException("Invalid weapon ammo");
    }

    public record Rocket(float directDamage, float splashDamage, float cooldownSeconds,
            int initialAmmo, int maximumAmmo, float speed, float ttlSeconds,
            float explosionRadius, float maximumDeltaSpeed) {
        public Rocket {
            positive("rocket.directDamage", directDamage);
            positive("rocket.splashDamage", splashDamage);
            positive("rocket.cooldownSeconds", cooldownSeconds);
            if (initialAmmo < 0 || maximumAmmo < 1 || initialAmmo > maximumAmmo) throw new IllegalArgumentException("Invalid rocket ammo range");
            positive("rocket.speed", speed);
            positive("rocket.ttlSeconds", ttlSeconds);
            positive("rocket.explosionRadius", explosionRadius);
            positive("rocket.maximumDeltaSpeed", maximumDeltaSpeed);
        }
    }

    public record Pulse(float damage, float radius, float cooldownSeconds,
            float initialDelaySeconds, float maximumDeltaSpeed) {
        public Pulse {
            positive("pulse.damage", damage);
            positive("pulse.radius", radius);
            positive("pulse.cooldownSeconds", cooldownSeconds);
            CombatRules.range("pulse.initialDelaySeconds", initialDelaySeconds, 0, cooldownSeconds);
            positive("pulse.maximumDeltaSpeed", maximumDeltaSpeed);
        }
    }

    public record Targeting(float acquisitionConeDegrees, float acquisitionRange,
            float acquisitionSeconds, float retentionConeDegrees, float occlusionGraceSeconds,
            float turnDegreesPerSecond) {
        public Targeting {
            CombatRules.range("targeting.acquisitionConeDegrees", acquisitionConeDegrees, Float.MIN_NORMAL, 180);
            positive("targeting.acquisitionRange", acquisitionRange);
            positive("targeting.acquisitionSeconds", acquisitionSeconds);
            CombatRules.range("targeting.retentionConeDegrees", retentionConeDegrees, acquisitionConeDegrees, 180);
            positive("targeting.occlusionGraceSeconds", occlusionGraceSeconds);
            positive("targeting.turnDegreesPerSecond", turnDegreesPerSecond);
        }
    }

    public record Ram(float minimumClosingSpeed, float damagePerExcessSpeed,
            float maximumDamage, float cooldownSeconds) {
        public Ram {
            positive("ram.minimumClosingSpeed", minimumClosingSpeed);
            positive("ram.damagePerExcessSpeed", damagePerExcessSpeed);
            positive("ram.maximumDamage", maximumDamage);
            positive("ram.cooldownSeconds", cooldownSeconds);
        }
    }

    public static int ticks(float seconds) {
        return Math.max(0, Math.round(seconds * MatchSession.TICKS_PER_SECOND));
    }

    private static void positive(String field, float value) {
        if (!Float.isFinite(value) || value <= 0) throw new IllegalArgumentException(field + " must be finite and positive");
    }

    private static void range(String field, float value, float minimum, float maximum) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be within " + minimum + ".." + maximum);
        }
    }
}

