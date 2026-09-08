package game.wreckriff.combat;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;

import java.util.*;

import static game.wreckriff.combat.CombatRules.ticks;

/** Fixed-tick weapon acceptance, geometric attacks, and one simultaneous damage phase. */
public final class CombatSystem {
    private record ShotIntent(long id, int ownerId, String kind, int targetId, float pitch, float yaw) {}
    private record DamageKey(long eventId, int targetId) {}
    private record Damage(int targetId, int sourceId, float amount, String cause, long eventId) {}
    private record Pair(int first, int second) implements Comparable<Pair> {
        static Pair of(int first, int second) { return new Pair(Math.min(first, second), Math.max(first, second)); }
        @Override public int compareTo(Pair other) {
            int order = Integer.compare(first, other.first);
            return order != 0 ? order : Integer.compare(second, other.second);
        }
    }
    private static final class Lock {
        int candidate = -1;
        int continuousTicks;
    }

    private final MatchSession session;
    private final CombatRules rules;
    private final List<VehicleState> orderedVehicles;
    private final List<ProjectileState> projectiles = new ArrayList<>();
    private final List<ShotIntent> intents = new ArrayList<>();
    private final List<Damage> damage = new ArrayList<>();
    private final List<GameEvent> events = new ArrayList<>();
    private final Map<Integer, Lock> locks = new HashMap<>();
    private final Map<Integer, SplittableRandom> spreadRandom = new HashMap<>();
    private final Map<Integer, Long> emptyFeedbackAfter = new HashMap<>();
    private final Map<Pair, Float> ramSpeeds = new TreeMap<>();
    private final Map<Pair, Long> ramReadyAt = new HashMap<>();
    private final Map<Integer, Vector3f> deltaSpeeds = new TreeMap<>();
    private final Set<DamageKey> seenDamage = new HashSet<>();
    private final Set<Integer> destroyed = new HashSet<>();
    private long nextShotId = 1;

    public CombatSystem(MatchSession session, CombatRules rules) {
        this.session = Objects.requireNonNull(session);
        this.rules = Objects.requireNonNull(rules);
        orderedVehicles = session.vehicles.stream().sorted(Comparator.comparingInt(v -> v.id)).toList();
        SplittableRandom weaponSeed = new SplittableRandom(session.seed ^ 0x575245434b524946L);
        for (VehicleState vehicle : orderedVehicles) {
            vehicle.homingAmmo = rules.homing().initialAmmo();
            vehicle.powerAmmo = rules.power().initialAmmo();
            vehicle.pulseCooldown = ticks(rules.pulse().initialDelaySeconds());
            spreadRandom.put(vehicle.id, weaponSeed.split());
        }
    }

    /** Call once before the physics step; edges must already have been consumed by the input owner. */
    public void beginTick(Map<Integer, VehicleCommand> commands, WorldQuery world) {
        if (session.outcome != MatchSession.Outcome.NONE) return;
        for (VehicleState vehicle : orderedVehicles) {
            if (!vehicle.alive()) {
                locks.remove(vehicle.id);
                continue;
            }
            VehicleCommand command = commands.getOrDefault(vehicle.id, VehicleCommand.NONE);
            if (vehicle.protectionTicks > 0) command = command.withoutAttacks();
            decrementTimers(vehicle);
            updateHeat(vehicle, command.machineGun(), world);
            updateLock(vehicle, world);
            if (command.weaponDelta() != 0) vehicle.selectedWeapon = Math.floorMod(vehicle.selectedWeapon + command.weaponDelta(), 2);
            if (vehicle.protectionTicks > 0) continue;
            if (command.machineGun() && !vehicle.overheated && vehicle.machineGunCooldown == 0) {
                SplittableRandom random = spreadRandom.get(vehicle.id);
                float spread = radians(rules.machineGun().spreadDegrees());
                float pitch = (float) ((random.nextDouble() * 2 - 1) * spread);
                float yaw = (float) ((random.nextDouble() * 2 - 1) * spread);
                intents.add(new ShotIntent(nextShotId++, vehicle.id, "machine-gun", -1, pitch, yaw));
                vehicle.machineGunCooldown = rules.machineGun().cooldownTicks();
            }
            if (command.selectedWeapon()) acceptRocket(vehicle, world);
            if (command.special() && vehicle.pulseCooldown == 0) {
                intents.add(new ShotIntent(nextShotId++, vehicle.id, "pulse", -1, 0, 0));
                vehicle.pulseCooldown = ticks(rules.pulse().cooldownSeconds());
            }
        }
    }

    private void decrementTimers(VehicleState vehicle) {
        vehicle.machineGunCooldown = Math.max(0, vehicle.machineGunCooldown - 1);
        vehicle.homingCooldown = Math.max(0, vehicle.homingCooldown - 1);
        vehicle.powerCooldown = Math.max(0, vehicle.powerCooldown - 1);
        vehicle.pulseCooldown = Math.max(0, vehicle.pulseCooldown - 1);
    }

    private void updateHeat(VehicleState vehicle, boolean firing, WorldQuery world) {
        CombatRules.MachineGun gun = rules.machineGun();
        if (vehicle.overheated) {
            vehicle.heat = Math.max(0, vehicle.heat - gun.coolingPerSecond() * MatchSession.DT);
            vehicle.heatQuietTicks = 0;
            if (vehicle.heat <= gun.resumeHeat()) vehicle.overheated = false;
        } else if (firing) {
            vehicle.heatQuietTicks = 0;
            vehicle.heat = Math.min(gun.maximumHeat(), vehicle.heat + gun.heatPerSecond() * MatchSession.DT);
            if (vehicle.heat >= gun.maximumHeat()) {
                vehicle.overheated = true;
                events.add(event(GameEvent.Type.OVERHEAT, nextShotId++, vehicle.id, vehicle.id,
                        world.position(vehicle.id), "machine-gun", vehicle.heat));
            }
        } else {
            vehicle.heatQuietTicks = Math.min(ticks(gun.coolingDelaySeconds()), vehicle.heatQuietTicks + 1);
            if (vehicle.heatQuietTicks >= ticks(gun.coolingDelaySeconds())) {
                vehicle.heat = Math.max(0, vehicle.heat - gun.coolingPerSecond() * MatchSession.DT);
            }
        }
    }

    private void acceptRocket(VehicleState vehicle, WorldQuery world) {
        boolean homing = vehicle.selectedWeapon == 0;
        if ((homing ? vehicle.homingCooldown : vehicle.powerCooldown) > 0) return;
        int ammo = homing ? vehicle.homingAmmo : vehicle.powerAmmo;
        long reserved = intents.stream().filter(i -> i.kind.equals("homing") || i.kind.equals("power")).count();
        if (ammo <= 0 || projectiles.size() + reserved >= rules.maximumProjectiles()) {
            if (session.tick >= emptyFeedbackAfter.getOrDefault(vehicle.id, Long.MIN_VALUE)) {
                events.add(event(GameEvent.Type.EMPTY, nextShotId++, vehicle.id, vehicle.id,
                        world.position(vehicle.id), ammo <= 0 ? "empty-ammo" : "projectile-limit", 0));
                emptyFeedbackAfter.put(vehicle.id, session.tick + ticks(rules.emptyFeedbackSeconds()));
            }
            return;
        }
        String kind = homing ? "homing" : "power";
        intents.add(new ShotIntent(nextShotId++, vehicle.id, kind, homing ? lockTarget(vehicle.id) : -1, 0, 0));
        if (homing) {
            vehicle.homingAmmo--;
            vehicle.homingCooldown = ticks(rules.homing().cooldownSeconds());
        } else {
            vehicle.powerAmmo--;
            vehicle.powerCooldown = ticks(rules.power().cooldownSeconds());
        }
    }

    private void updateLock(VehicleState owner, WorldQuery world) {
        Lock lock = locks.computeIfAbsent(owner.id, ignored -> new Lock());
        Vector3f origin = world.muzzle(owner.id);
        Vector3f forward = world.forward(owner.id).normalizeLocal();
        int candidate = -1;
        float bestCosine = -1;
        float bestDistance = Float.POSITIVE_INFINITY;
        float minimumCosine = (float) Math.cos(radians(rules.targeting().acquisitionConeDegrees()));
        for (VehicleState target : orderedVehicles) {
            if (!target.alive() || target.id == owner.id) continue;
            Vector3f displacement = world.position(target.id).subtract(origin);
            float distance = displacement.length();
            if (distance == 0 || distance > rules.targeting().acquisitionRange()) continue;
            float cosine = forward.dot(displacement) / distance;
            if (cosine < minimumCosine || !world.visible(origin, world.position(target.id), target.id)) continue;
            if (cosine > bestCosine || (Float.compare(cosine, bestCosine) == 0 && distance < bestDistance)) {
                candidate = target.id;
                bestCosine = cosine;
                bestDistance = distance;
            }
        }
        if (candidate < 0) {
            lock.candidate = -1;
            lock.continuousTicks = 0;
        } else if (candidate != lock.candidate) {
            lock.candidate = candidate;
            lock.continuousTicks = 1;
        } else {
            lock.continuousTicks = Math.min(ticks(rules.targeting().acquisitionSeconds()), lock.continuousTicks + 1);
        }
    }

    public int lockTarget(int vehicleId) {
        Lock lock = locks.get(vehicleId);
        return lock != null && lock.continuousTicks >= ticks(rules.targeting().acquisitionSeconds()) ? lock.candidate : -1;
    }

    public float lockProgress(int vehicleId) {
        Lock lock = locks.get(vehicleId);
        return lock == null ? 0 : Math.min(1, lock.continuousTicks / (float) Math.max(1, ticks(rules.targeting().acquisitionSeconds())));
    }

    /** Call after the physics step, before damage resolution, even if a source has pending lethal damage. */
    public void advanceProjectiles(WorldQuery world) {
        if (session.outcome != MatchSession.Outcome.NONE) return;
        for (ShotIntent intent : intents) executeIntent(intent, world);
        intents.clear();
        for (ProjectileState projectile : projectiles) {
            projectile.previousPosition.set(projectile.position);
            guide(projectile, world);
            CombatRules.Rocket rocket = rocketRules(projectile.kind());
            Vector3f end = projectile.position.add(projectile.direction.mult(rocket.speed() * MatchSession.DT));
            WorldQuery.Hit hit = world.sweep(projectile.position, end, rules.projectileRadius(), projectile.ownerId());
            projectile.remainingTicks--;
            if (hit != null) {
                projectile.position.set(hit.point());
                explode(projectile, hit, world);
            } else {
                projectile.position.set(end);
                if (projectile.remainingTicks <= 0) explode(projectile, null, world);
            }
        }
        projectiles.removeIf(projectile -> projectile.exploded);
    }

    private void executeIntent(ShotIntent intent, WorldQuery world) {
        if (intent.kind.equals("pulse")) {
            emitPulse(intent, world);
            return;
        }
        Vector3f muzzle = world.muzzle(intent.ownerId);
        WorldQuery.Hit blocked = world.ray(world.weaponBase(intent.ownerId), muzzle, intent.ownerId);
        Vector3f forward = world.forward(intent.ownerId).normalizeLocal();
        if (intent.kind.equals("machine-gun")) {
            Quaternion spread = new Quaternion().fromAngles(intent.pitch, intent.yaw, 0);
            Vector3f direction = world.rotation(intent.ownerId).mult(spread.mult(Vector3f.UNIT_Z)).normalizeLocal();
            Vector3f end = muzzle.add(direction.mult(rules.machineGun().range()));
            WorldQuery.Hit hit = blocked == null ? world.ray(muzzle, end, intent.ownerId) : blocked;
            events.add(event(GameEvent.Type.SHOT, intent.id, intent.ownerId, intent.ownerId,
                    hit == null ? end : hit.point(), intent.kind, rules.machineGun().damage()));
            if (hit != null && hit.vehicleId() >= 0 && hit.vehicleId() != intent.ownerId) {
                queueDamage(hit.vehicleId(), intent.ownerId, rules.machineGun().damage(), intent.kind, intent.id);
            }
            return;
        }
        ProjectileState projectile = new ProjectileState(intent.id, intent.ownerId, intent.kind, muzzle,
                forward, Math.max(1, ticks(rocketRules(intent.kind).ttlSeconds())), intent.targetId);
        events.add(event(GameEvent.Type.SHOT, intent.id, intent.ownerId, intent.ownerId, muzzle, intent.kind, 0));
        if (blocked == null) projectiles.add(projectile);
        else {
            projectile.position.set(blocked.point());
            explode(projectile, blocked, world);
        }
    }

    private void guide(ProjectileState projectile, WorldQuery world) {
        if (projectile.targetId < 0) return;
        VehicleState target = session.vehicle(projectile.targetId);
        if (!target.alive()) {
            projectile.targetId = -1;
            return;
        }
        Vector3f desired = world.position(target.id).subtract(projectile.position);
        if (desired.lengthSquared() == 0) return;
        desired.normalizeLocal();
        float angle = (float) Math.acos(Math.clamp(projectile.direction.dot(desired), -1, 1));
        if (angle > radians(rules.targeting().retentionConeDegrees())) {
            projectile.targetId = -1;
            return;
        }
        if (world.visible(projectile.position, world.position(target.id), target.id)) projectile.occludedTicks = 0;
        else projectile.occludedTicks++;
        if (projectile.occludedTicks > ticks(rules.targeting().occlusionGraceSeconds())) {
            projectile.targetId = -1;
            return;
        }
        float permitted = radians(rules.targeting().turnDegreesPerSecond()) * MatchSession.DT;
        if (angle <= permitted) projectile.direction.set(desired);
        else {
            Vector3f axis = projectile.direction.cross(desired).normalizeLocal();
            if (axis.lengthSquared() > 0) {
                new Quaternion().fromAngleAxis(permitted, axis).mult(projectile.direction, projectile.direction);
                projectile.direction.normalizeLocal();
            }
        }
    }

    private void explode(ProjectileState projectile, WorldQuery.Hit hit, WorldQuery world) {
        if (projectile.exploded) return;
        projectile.exploded = true;
        Vector3f center = projectile.position.clone();
        if (hit != null && hit.normal() != null && hit.normal().lengthSquared() > 0) {
            center.addLocal(hit.normal().normalize().mult(rules.explosionSurfaceOffset()));
        }
        CombatRules.Rocket rocket = rocketRules(projectile.kind());
        int directTarget = hit == null ? -1 : hit.vehicleId();
        events.add(event(GameEvent.Type.EXPLOSION, projectile.id(), directTarget, projectile.ownerId(),
                center, projectile.kind(), rocket.explosionRadius()));
        for (VehicleState target : orderedVehicles) {
            if (!target.alive()) continue;
            boolean direct = target.id == directTarget && target.id != projectile.ownerId();
            float distance = world.distanceToHull(target.id, center);
            float falloff = Math.max(0, 1 - distance / rocket.explosionRadius());
            if (!direct && (falloff <= 0 || !exposed(center, target.id, world))) continue;
            float amount = direct ? rocket.directDamage() : rocket.splashDamage() * falloff;
            if (target.id == projectile.ownerId()) amount *= rules.ownerSplashMultiplier();
            queueDamage(target.id, projectile.ownerId(), amount, projectile.kind(), projectile.id());
            addPush(target.id, center, rocket.maximumDeltaSpeed() * (direct ? 1 : falloff), world);
        }
    }

    private void emitPulse(ShotIntent intent, WorldQuery world) {
        Vector3f center = world.position(intent.ownerId);
        events.add(event(GameEvent.Type.PULSE, intent.id, intent.ownerId, intent.ownerId,
                center, "pulse", rules.pulse().radius()));
        for (VehicleState target : orderedVehicles) {
            if (!target.alive() || target.id == intent.ownerId) continue;
            float distance = world.distanceToHull(target.id, center);
            if (distance > rules.pulse().radius() || !exposed(center, target.id, world)) continue;
            queueDamage(target.id, intent.ownerId, rules.pulse().damage(), "pulse", intent.id);
            addPush(target.id, center, rules.pulse().maximumDeltaSpeed() * Math.max(0, 1 - distance / rules.pulse().radius()), world);
        }
    }

    private boolean exposed(Vector3f center, int targetId, WorldQuery world) {
        for (Vector3f sample : world.hullVisibilityPoints(targetId)) {
            if (world.visible(center, sample, targetId)) return true;
        }
        return false;
    }

    private void addPush(int targetId, Vector3f center, float deltaSpeed, WorldQuery world) {
        if (deltaSpeed <= 0) return;
        Vector3f direction = world.position(targetId).subtract(center);
        direction.y = 0;
        if (direction.lengthSquared() == 0) direction.set(Vector3f.UNIT_X);
        direction.normalizeLocal().multLocal(deltaSpeed);
        deltaSpeeds.merge(targetId, direction, Vector3f::add);
    }

    /** External event ids must be unique for the session and not collide with positive combat shot ids. */
    public void queueDamage(int targetId, int sourceId, float amount, String cause, long sourceEvent) {
        if (!Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Damage must be finite and nonnegative");
        if (targetId < 0 || targetId >= session.vehicles.size() || sourceId < -1 || sourceId >= session.vehicles.size()) {
            throw new IllegalArgumentException("Invalid damage participant");
        }
        Objects.requireNonNull(cause, "cause");
        if (amount == 0 || !seenDamage.add(new DamageKey(sourceEvent, targetId))) return;
        damage.add(new Damage(targetId, sourceId, amount, cause, sourceEvent));
    }

    public void queueRam(int first, int second, float closingSpeed) {
        if (!Float.isFinite(closingSpeed) || closingSpeed < 0) throw new IllegalArgumentException("Closing speed must be finite and nonnegative");
        if (first == second || first < 0 || second < 0 || first >= session.vehicles.size() || second >= session.vehicles.size()) return;
        if (closingSpeed <= rules.ram().minimumClosingSpeed()) return;
        Pair pair = Pair.of(first, second);
        if (session.tick < ramReadyAt.getOrDefault(pair, Long.MIN_VALUE)) return;
        ramSpeeds.merge(pair, closingSpeed, Math::max);
    }

    /** One resolution after projectiles, contacts, recovery penalties, and hazards have queued all damage. */
    public void resolveDamage(WorldQuery world) {
        if (session.outcome != MatchSession.Outcome.NONE) {
            damage.clear();
            ramSpeeds.clear();
            deltaSpeeds.clear();
            return;
        }
        for (Map.Entry<Pair, Float> entry : ramSpeeds.entrySet()) {
            Pair pair = entry.getKey();
            if (!session.vehicle(pair.first).alive() || !session.vehicle(pair.second).alive()) continue;
            float amount = Math.min(rules.ram().maximumDamage(), rules.ram().damagePerExcessSpeed()
                    * (entry.getValue() - rules.ram().minimumClosingSpeed()));
            long eventId = nextShotId++;
            queueDamage(pair.first, pair.second, amount, "ram", eventId);
            queueDamage(pair.second, pair.first, amount, "ram", eventId);
            ramReadyAt.put(pair, session.tick + ticks(rules.ram().cooldownSeconds()));
        }
        ramSpeeds.clear();
        damage.sort(Comparator.comparingInt(Damage::targetId).thenComparingInt(Damage::sourceId).thenComparingLong(Damage::eventId));
        Map<Integer, List<Damage>> grouped = new TreeMap<>();
        for (Damage request : damage) {
            VehicleState target = session.vehicle(request.targetId);
            if (!target.alive()) continue;
            if (target.protectionTicks > 0 && !request.cause.equals("recovery") && !request.cause.equals("out-of-bounds")) continue;
            grouped.computeIfAbsent(request.targetId, ignored -> new ArrayList<>()).add(request);
        }
        for (Map.Entry<Integer, List<Damage>> entry : grouped.entrySet()) applyDamage(entry.getKey(), entry.getValue(), world);
        damage.clear();
        for (Map.Entry<Integer, Vector3f> entry : deltaSpeeds.entrySet()) {
            VehicleState target = session.vehicle(entry.getKey());
            if (!target.alive() || target.protectionTicks > 0) continue;
            Vector3f speed = entry.getValue();
            if (speed.length() > rules.maximumCombinedDeltaSpeed()) speed.normalizeLocal().multLocal(rules.maximumCombinedDeltaSpeed());
            world.impulse(target.id, speed.mult(world.mass(target.id)));
        }
        deltaSpeeds.clear();
        for (VehicleState target : orderedVehicles) {
            if (target.alive() || !destroyed.add(target.id)) continue;
            int killer = target.lastAttacker >= 0 && session.tick - target.lastAttackTick <= ticks(rules.killCreditSeconds())
                    ? target.lastAttacker : -1;
            if (killer >= 0 && killer != target.id) session.vehicle(killer).eliminations++;
            events.add(event(GameEvent.Type.DESTROYED, target.id, target.id, killer, world.position(target.id), "destroyed", 0));
            locks.remove(target.id);
        }
    }

    private void applyDamage(int targetId, List<Damage> requests, WorldQuery world) {
        VehicleState target = session.vehicle(targetId);
        double total = requests.stream().mapToDouble(Damage::amount).sum();
        double loss = Math.min(target.hp, total);
        Map<Integer, Double> externalAmounts = new TreeMap<>();
        for (Damage request : requests) {
            float actual = (float) (loss * request.amount / total);
            if (request.sourceId >= 0 && request.sourceId != targetId) {
                // Aggregate before proportional rounding: splitting one source into several
                // equal-hit events must not turn a true tie into a different kill credit.
                externalAmounts.merge(request.sourceId, (double) request.amount, Double::sum);
            }
            events.add(event(GameEvent.Type.DAMAGE, request.eventId, targetId, request.sourceId,
                    world.position(targetId), request.cause, actual));
        }
        target.hp = Math.max(0, target.hp - (float) loss);
        double greatest = -1;
        for (Map.Entry<Integer, Double> external : externalAmounts.entrySet()) {
            session.vehicle(external.getKey()).damageDealt += (float) (loss * external.getValue() / total);
            if (external.getValue() > greatest) {
                target.lastAttacker = external.getKey();
                target.lastAttackTick = session.tick;
                greatest = external.getValue();
            }
        }
    }

    public List<ProjectileState> projectiles() { return List.copyOf(projectiles); }
    public int pendingDamageCount() { return damage.size(); }
    public List<GameEvent> drainEvents() {
        List<GameEvent> result = List.copyOf(events);
        events.clear();
        return result;
    }

    public void clear() {
        projectiles.clear();
        intents.clear();
        damage.clear();
        events.clear();
        locks.clear();
        spreadRandom.clear();
        emptyFeedbackAfter.clear();
        ramSpeeds.clear();
        ramReadyAt.clear();
        deltaSpeeds.clear();
        seenDamage.clear();
        destroyed.clear();
    }

    private CombatRules.Rocket rocketRules(String kind) { return kind.equals("homing") ? rules.homing() : rules.power(); }
    private static float radians(float degrees) { return (float) Math.toRadians(degrees); }
    private static GameEvent event(GameEvent.Type type, long id, int subject, int source, Vector3f position, String kind, float value) {
        return new GameEvent(type, id, subject, source, position, kind, value);
    }
}
