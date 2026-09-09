package game.wreckriff.combat;

import game.wreckriff.simulation.MatchSession;
import java.util.Objects;

/** All weapon balance numbers are supplied by the strictly validated bundled combat.json. */
public record CombatRules(int maximumProjectiles, float projectileRadius, float explosionSurfaceOffset,
        float ownerSplashMultiplier, BlastLimits blastLimits, float killCreditSeconds,
        float emptyFeedbackSeconds, MachineGun machineGun, Rocket homing, Rocket power,
        Targeting targeting, Ram ram, Mine mine, Napalm napalm, Control control,Health health,
        Cannon cannon,Ballistic ballistic,BlastLimits heavyBlastLimits) {
    public CombatRules {
        if (maximumProjectiles < 1 || maximumProjectiles > 64) throw new IllegalArgumentException("maximumProjectiles must be 1..64");
        positive("projectileRadius", projectileRadius);
        positive("explosionSurfaceOffset", explosionSurfaceOffset);
        range("ownerSplashMultiplier", ownerSplashMultiplier, 0, 1);
        Objects.requireNonNull(blastLimits,"blastLimits");
        positive("killCreditSeconds", killCreditSeconds);
        positive("emptyFeedbackSeconds", emptyFeedbackSeconds);
        Objects.requireNonNull(machineGun, "machineGun");
        Objects.requireNonNull(homing, "homing");
        Objects.requireNonNull(power, "power");
        Objects.requireNonNull(targeting, "targeting");
        Objects.requireNonNull(ram, "ram");
        Objects.requireNonNull(mine,"mine"); Objects.requireNonNull(napalm,"napalm"); Objects.requireNonNull(control,"control");
        Objects.requireNonNull(health,"health");
        Objects.requireNonNull(cannon,"cannon");Objects.requireNonNull(ballistic,"ballistic");Objects.requireNonNull(heavyBlastLimits,"heavyBlastLimits");
    }
    public record BlastLimits(float horizontalDeltaSpeed,float upwardDeltaSpeed,float angularDeltaSpeed) {
        public BlastLimits { positive("blast.horizontalLimit",horizontalDeltaSpeed);positive("blast.upwardLimit",upwardDeltaSpeed);positive("blast.angularLimit",angularDeltaSpeed); }
    }
    public record Blast(float horizontalDeltaSpeed,float upwardDeltaSpeed) {
        public Blast { positive("blast.horizontal",horizontalDeltaSpeed);positive("blast.upward",upwardDeltaSpeed); }
    }
    public record Health(float playerMaximumHp,float botMaximumHp,float repairFraction) {
        public Health {
            positive("health.playerMaximumHp",playerMaximumHp);positive("health.botMaximumHp",botMaximumHp);
            range("health.repairFraction",repairFraction,Float.MIN_NORMAL,1);
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
            float explosionRadius,float damage,float lifetimeSeconds,Blast blast) {
        public Mine {
            Objects.requireNonNull(blast,"mine.blast");
            if(maximumActive<1||maximumActive>10)throw new IllegalArgumentException("Mine cap must be 1..10");
            ammo(initialAmmo,maximumAmmo); positive("mine.cooldown",cooldownSeconds);
            positive("mine.placement",placementDistance);positive("mine.support",supportDepth);
            positive("mine.arm",armSeconds);positive("mine.trigger",triggerRadius);
            positive("mine.radius",explosionRadius);positive("mine.damage",damage);positive("mine.lifetime",lifetimeSeconds);
            if(armSeconds>=lifetimeSeconds)throw new IllegalArgumentException("Mine must arm before expiration");
        }
    }
    public record Napalm(int maximumZones,int initialAmmo,int maximumAmmo,float cooldownSeconds,
            float speed,float gravity,float ttlSeconds,float impactDamage,
            float radius,float durationSeconds,float damagePerSecond,float intervalSeconds,float supportDepth,Blast blast, NapalmAssist assist) {
        public Napalm {
            Objects.requireNonNull(blast,"napalm.blast");
            Objects.requireNonNull(assist,"napalm.assist");
            if(maximumZones<1||maximumZones>6)throw new IllegalArgumentException("Fire-zone cap must be 1..6");
            ammo(initialAmmo,maximumAmmo);positive("napalm.cooldown",cooldownSeconds);
            positive("napalm.speed",speed);positive("napalm.gravity",gravity);
            positive("napalm.ttl",ttlSeconds);positive("napalm.impact",impactDamage);range("napalm.radius",radius,.5f,10);
            positive("napalm.duration",durationSeconds);positive("napalm.damage",damagePerSecond);
            positive("napalm.interval",intervalSeconds);positive("napalm.supportDepth",supportDepth);
            if(ticks(intervalSeconds)<1||intervalSeconds>durationSeconds)throw new IllegalArgumentException("Invalid fire interval");
        }
    }
    public record NapalmAssist(float minimumRange,float maximumRange,float coneDegrees,float leadSeconds,
            float maximumLead,float turnDegreesPerSecond,float maximumDeviation,float occlusionSeconds,
            float minimumFlightSeconds,float maximumFlightSeconds,float fallbackRange) {
        public NapalmAssist {
            positive("napalm.assist.minimumRange",minimumRange);positive("napalm.assist.maximumRange",maximumRange);
            if(maximumRange<minimumRange)throw new IllegalArgumentException("Invalid napalm assist range");
            range("napalm.assist.cone",coneDegrees,0,90);positive("napalm.assist.lead",leadSeconds);
            positive("napalm.assist.maximumLead",maximumLead);positive("napalm.assist.turn",turnDegreesPerSecond);
            positive("napalm.assist.deviation",maximumDeviation);positive("napalm.assist.occlusion",occlusionSeconds);
            positive("napalm.assist.minimumFlight",minimumFlightSeconds);positive("napalm.assist.maximumFlight",maximumFlightSeconds);
            if(maximumFlightSeconds<minimumFlightSeconds)throw new IllegalArgumentException("Invalid napalm flight time");
            positive("napalm.assist.fallback",fallbackRange);
        }
    }
    public record Cannon(int initialAmmo,int maximumAmmo,float cooldownSeconds,float speed,float radius,
            float upwardSpeed,float gravity,float ttlSeconds,int ricochets,float normalRestitution,
            float tangentRetention,float minimumSpeed,float directDamage,float ricochetDamage,float ricochetRadius,
            float splashDamage,float splashRadius,float horizontalImpulse,float upwardImpulse,Blast splashBlast) {
        public Cannon {
            ammo(initialAmmo,maximumAmmo);positive("cannon.cooldown",cooldownSeconds);positive("cannon.speed",speed);
            positive("cannon.radius",radius);positive("cannon.upwardSpeed",upwardSpeed);positive("cannon.gravity",gravity);
            positive("cannon.ttl",ttlSeconds);if(ricochets<0||ricochets>2)throw new IllegalArgumentException("Cannon supports at most two ricochets");
            range("cannon.restitution",normalRestitution,0,1);range("cannon.retention",tangentRetention,0,1);
            positive("cannon.minimumSpeed",minimumSpeed);positive("cannon.directDamage",directDamage);
            positive("cannon.ricochetDamage",ricochetDamage);positive("cannon.ricochetRadius",ricochetRadius);
            positive("cannon.splashDamage",splashDamage);positive("cannon.splashRadius",splashRadius);
            positive("cannon.horizontalImpulse",horizontalImpulse);positive("cannon.upwardImpulse",upwardImpulse);
            Objects.requireNonNull(splashBlast,"cannon.splashBlast");
        }
    }
    public record Ballistic(int initialAmmo,int maximumAmmo,float cooldownSeconds,int charges,float releaseIntervalSeconds,
            float minimumWarningSeconds,float maximumRange,float fallbackRange,
            float carrierHeight,float carrierSpeed,float minimumCarrierSeconds,float maximumCarrierSeconds,
            float gravity,float turnDegreesPerSecond,float maximumLeadSeconds,float maximumLead,
            float spread,float damage,float radius,Blast blast) {
        public Ballistic {
            ammo(initialAmmo,maximumAmmo);positive("ballistic.cooldown",cooldownSeconds);
            if(charges!=4)throw new IllegalArgumentException("Ballistic salvo must contain four charges");
            positive("ballistic.interval",releaseIntervalSeconds);range("ballistic.warning",minimumWarningSeconds,.5f,3);
            positive("ballistic.maximumRange",maximumRange);
            positive("ballistic.fallback",fallbackRange);positive("ballistic.height",carrierHeight);positive("ballistic.speed",carrierSpeed);
            positive("ballistic.minimumCarrierTime",minimumCarrierSeconds);positive("ballistic.maximumCarrierTime",maximumCarrierSeconds);
            if(maximumCarrierSeconds<minimumCarrierSeconds)throw new IllegalArgumentException("Invalid carrier time");
            positive("ballistic.gravity",gravity);range("ballistic.turn",turnDegreesPerSecond,Float.MIN_NORMAL,180);
            positive("ballistic.maximumLeadSeconds",maximumLeadSeconds);positive("ballistic.maximumLead",maximumLead);
            positive("ballistic.spread",spread);
            positive("ballistic.damage",damage);positive("ballistic.radius",radius);Objects.requireNonNull(blast,"ballistic.blast");
        }
    }
    public record Control(float freezeSeconds,float freezeCooldownSeconds,float freezeSpeed,float freezeTtlSeconds,
            float freezeRadius,
            float shieldSeconds,float shieldCooldownSeconds,float shieldDamageMultiplier,float immunitySeconds) {
        public Control {
            positive("freeze.duration",freezeSeconds);positive("freeze.cooldown",freezeCooldownSeconds);
            positive("freeze.speed",freezeSpeed);positive("freeze.ttl",freezeTtlSeconds);positive("freeze.radius",freezeRadius);
            positive("shield.duration",shieldSeconds);
            positive("shield.cooldown",shieldCooldownSeconds);range("shield.multiplier",shieldDamageMultiplier,0,1);
            positive("control.immunity",immunitySeconds);
        }
    }
    private static void ammo(int initial,int maximum) {
        if(initial<0||maximum<1||initial>maximum)throw new IllegalArgumentException("Invalid weapon ammo");
    }

    public record Rocket(float directDamage, float splashDamage, float cooldownSeconds,
            int initialAmmo, int maximumAmmo, float speed, float ttlSeconds,
            float explosionRadius, Blast blast) {
        public Rocket {
            positive("rocket.directDamage", directDamage);
            positive("rocket.splashDamage", splashDamage);
            positive("rocket.cooldownSeconds", cooldownSeconds);
            if (initialAmmo < 0 || maximumAmmo < 1 || initialAmmo > maximumAmmo) throw new IllegalArgumentException("Invalid rocket ammo range");
            positive("rocket.speed", speed);
            positive("rocket.ttlSeconds", ttlSeconds);
            positive("rocket.explosionRadius", explosionRadius);
            Objects.requireNonNull(blast,"rocket.blast");
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

