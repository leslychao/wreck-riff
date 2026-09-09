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
    private record ControlHit(int targetId,int sourceId,AbilityId ability,long eventId) {}
    public record MineView(long id,int ownerId,Vector3f position,Vector3f normal,boolean armed,float radius) {
        public MineView { position=position.clone();normal=normal.clone(); }
    }
    public record FireZoneView(long id,int ownerId,Vector3f position,Vector3f normal,float radius,int remainingTicks,List<Vector3f> surfacePoints) {
        public FireZoneView { position=position.clone();normal=normal.clone();surfacePoints=surfacePoints.stream().map(Vector3f::clone).toList(); }
    }
    private record Mine(long id,int ownerId,WorldQuery.Support support,long armedAt,long expiresAt) {}
    private record FireZone(long id,int ownerId,WorldQuery.Support support,long expiresAt,List<Vector3f> points) {}
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
    private final List<ControlHit> controlHits=new ArrayList<>();
    private final List<Mine> mines=new ArrayList<>();
    private final List<FireZone> fireZones=new ArrayList<>();
    private final Map<Integer,Integer> fireExposureTicks=new HashMap<>();
    private final Map<Integer, Lock> locks = new HashMap<>();
    private final Map<Integer, SplittableRandom> spreadRandom = new HashMap<>();
    private final Map<Integer, Long> emptyFeedbackAfter = new HashMap<>();
    private final Map<Pair, Float> ramSpeeds = new TreeMap<>();
    private final Map<Pair, Long> ramReadyAt = new HashMap<>();
    private final Map<Integer, Vector3f> deltaSpeeds = new TreeMap<>();
    private final Set<DamageKey> seenDamage = new HashSet<>();
    private final Set<Integer> destroyed = new HashSet<>();
    private long nextShotId = 1;
    private int reservedFireZones;
    private WorldQuery lastWorld;
    private long lastControlTimerTick=Long.MIN_VALUE;

    public CombatSystem(MatchSession session, CombatRules rules) {
        this.session = Objects.requireNonNull(session);
        this.rules = Objects.requireNonNull(rules);
        orderedVehicles = session.vehicles.stream().sorted(Comparator.comparingInt(v -> v.id)).toList();
        SplittableRandom weaponSeed = new SplittableRandom(session.seed ^ 0x575245434b524946L);
        for (VehicleState vehicle : orderedVehicles) {
            vehicle.pulseCooldown = ticks(rules.pulse().initialDelaySeconds());
            spreadRandom.put(vehicle.id, weaponSeed.split());
        }
    }

    /** Call once before the physics step; edges must already have been consumed by the input owner. */
    public void beginTick(Map<Integer, VehicleCommand> commands, WorldQuery world) {
        if (session.outcome != MatchSession.Outcome.NONE) return;
        lastWorld=world;
        // All defensive edges resolve before any participant can produce this tick's hits.
        for(VehicleState vehicle:orderedVehicles) {
            if(!vehicle.alive())continue;
            decrementTimers(vehicle,world);
            VehicleCommand command=commands.getOrDefault(vehicle.id,VehicleCommand.NONE);
            if(command.ability()==AbilityId.SHIELD && vehicle.abilityCooldown(AbilityId.SHIELD)==0) {
                endControl(vehicle,world);
                vehicle.shieldTicks=ticks(rules.control().shieldSeconds());
                vehicle.abilityCooldown(AbilityId.SHIELD,ticks(rules.control().shieldCooldownSeconds()));
                events.add(event(GameEvent.Type.SHIELD,nextShotId++,vehicle.id,vehicle.id,world.position(vehicle.id),"shield",rules.control().shieldSeconds()));
            }
        }
        for (VehicleState vehicle : orderedVehicles) {
            if (!vehicle.alive()) {
                locks.remove(vehicle.id);
                continue;
            }
            VehicleCommand command = commands.getOrDefault(vehicle.id, VehicleCommand.NONE);
            if (vehicle.protectionTicks > 0 || vehicle.stunnedTicks>0) command = command.withoutAttacks();
            updateLock(vehicle, world);
            if (command.weaponDelta() != 0 && vehicle.stunnedTicks==0) vehicle.selectedWeapon=vehicle.selectedWeapon.cycle(command.weaponDelta());
            if (vehicle.protectionTicks > 0 || vehicle.stunnedTicks>0) continue;
            if (command.machineGun() && vehicle.machineGunCooldown == 0) {
                SplittableRandom random = spreadRandom.get(vehicle.id);
                float spread = radians(rules.machineGun().spreadDegrees());
                float pitch = (float) ((random.nextDouble() * 2 - 1) * spread);
                float yaw = (float) ((random.nextDouble() * 2 - 1) * spread);
                intents.add(new ShotIntent(nextShotId++, vehicle.id, "machine-gun", -1, pitch, yaw));
                vehicle.machineGunCooldown = rules.machineGun().cooldownTicks();
            }
            if (command.selectedWeapon()) acceptWeapon(vehicle, world);
            if (command.special() && vehicle.pulseCooldown == 0) {
                intents.add(new ShotIntent(nextShotId++, vehicle.id, "pulse", -1, 0, 0));
                vehicle.pulseCooldown = ticks(rules.pulse().cooldownSeconds());
            }
            acceptAbility(vehicle,command.ability(),world);
        }
    }

    private void decrementTimers(VehicleState vehicle,WorldQuery world) {
        vehicle.machineGunCooldown = Math.max(0, vehicle.machineGunCooldown - 1);
        for(var slot:vehicle.weapons())slot.cooldownTicks=Math.max(0,slot.cooldownTicks-1);
        vehicle.pulseCooldown = Math.max(0, vehicle.pulseCooldown - 1);
        vehicle.advanceAbilityCooldowns();
        vehicle.shieldTicks=Math.max(0,vehicle.shieldTicks-1);
        vehicle.controlImmunityTicks=Math.max(0,vehicle.controlImmunityTicks-1);
    }
    private boolean projectileCapacity() {
        long reserved=intents.stream().filter(i->!i.kind.equals("machine-gun")&&!i.kind.equals("pulse")&&!i.kind.equals("stun")).count();
        return projectiles.size()+reserved<rules.maximumProjectiles();
    }
    private void denied(VehicleState vehicle,WorldQuery world,String reason) {
        if(session.tick<emptyFeedbackAfter.getOrDefault(vehicle.id,Long.MIN_VALUE))return;
        events.add(event(GameEvent.Type.EMPTY,nextShotId++,vehicle.id,vehicle.id,world.position(vehicle.id),reason,0));
        emptyFeedbackAfter.put(vehicle.id,session.tick+ticks(rules.emptyFeedbackSeconds()));
    }
    private void acceptWeapon(VehicleState vehicle,WorldQuery world) {
        WeaponType type=vehicle.selectedWeapon;WeaponSlot slot=vehicle.weapon(type);
        if(slot.cooldownTicks>0)return;
        if(slot.ammo<=0) {denied(vehicle,world,"empty-ammo");return;}
        if(type==WeaponType.MINE) {acceptMine(vehicle,world);return;}
        if(!projectileCapacity()) {denied(vehicle,world,"projectile-limit");return;}
        if(type==WeaponType.NAPALM && fireZones.size()+reservedFireZones>=rules.napalm().maximumZones()) {
            denied(vehicle,world,"fire-zone-limit");return;
        }
        intents.add(new ShotIntent(nextShotId++,vehicle.id,type.id(),type==WeaponType.HOMING?lockTarget(vehicle.id):-1,0,0));
        slot.ammo--;
        slot.cooldownTicks=ticks(switch(type) {
            case HOMING->rules.homing().cooldownSeconds();case POWER->rules.power().cooldownSeconds();
            case NAPALM->rules.napalm().cooldownSeconds();case MINE->throw new IllegalStateException();
        });
        if(type==WeaponType.NAPALM)reservedFireZones++;
    }
    private void acceptAbility(VehicleState vehicle,AbilityId ability,WorldQuery world) {
        if(ability==AbilityId.NONE||ability==AbilityId.SHIELD||vehicle.abilityCooldown(ability)>0)return;
        if(ability==AbilityId.FREEZE&&!projectileCapacity()) {denied(vehicle,world,"projectile-limit");return;}
        intents.add(new ShotIntent(nextShotId++,vehicle.id,ability==AbilityId.FREEZE?"freeze":"stun",-1,0,0));
        vehicle.abilityCooldown(ability,ticks(ability==AbilityId.FREEZE?rules.control().freezeCooldownSeconds():rules.control().stunCooldownSeconds()));
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
            if(projectile.kind().equals("freeze") || projectile.kind().equals("napalm")) {
                advanceUtilityProjectile(projectile,world);continue;
            }
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
        advanceMines(world);
        advanceFire(world);
    }

    private void executeIntent(ShotIntent intent, WorldQuery world) {
        if (intent.kind.equals("pulse")) {
            emitPulse(intent, world);
            return;
        }
        if(intent.kind.equals("stun")) {emitStun(intent,world);return;}
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
        float ttl=switch(intent.kind) {case "freeze"->rules.control().freezeTtlSeconds();case "napalm"->rules.napalm().ttlSeconds();default->rocketRules(intent.kind).ttlSeconds();};
        ProjectileState projectile = new ProjectileState(intent.id, intent.ownerId, intent.kind, muzzle,
                forward, Math.max(1, ticks(ttl)), intent.targetId);
        if(intent.kind.equals("napalm")) {
            forward.y=0;if(forward.lengthSquared()<.001f)forward.set(Vector3f.UNIT_Z);else forward.normalizeLocal();
            projectile.velocity.set(forward.mult(rules.napalm().speed())).addLocal(0,rules.napalm().upwardSpeed(),0);
        }
        events.add(event(GameEvent.Type.SHOT, intent.id, intent.ownerId, intent.ownerId, muzzle, intent.kind, 0));
        if (blocked == null) projectiles.add(projectile);
        else {
            projectile.position.set(blocked.point());
            if(intent.kind.equals("freeze")||intent.kind.equals("napalm"))utilityImpact(projectile,blocked,world);
            else explode(projectile, blocked, world);
        }
    }

    private void advanceUtilityProjectile(ProjectileState projectile,WorldQuery world) {
        boolean napalm=projectile.kind().equals("napalm");
        Vector3f displacement;
        if(napalm) {
            displacement=projectile.velocity.mult(MatchSession.DT).addLocal(0,-.5f*rules.napalm().gravity()*MatchSession.DT*MatchSession.DT,0);
            projectile.velocity.y-=rules.napalm().gravity()*MatchSession.DT;
            projectile.direction.set(projectile.velocity).normalizeLocal();
        } else displacement=projectile.direction.mult(rules.control().freezeSpeed()*MatchSession.DT);
        Vector3f end=projectile.position.add(displacement);
        WorldQuery.Hit hit=world.sweep(projectile.position,end,napalm?rules.projectileRadius():rules.control().freezeRadius(),projectile.ownerId());
        projectile.remainingTicks--;
        if(hit!=null) {projectile.position.set(hit.point());utilityImpact(projectile,hit,world);}
        else {
            projectile.position.set(end);
            if(projectile.remainingTicks<=0) {
                projectile.exploded=true;
                if(napalm)reservedFireZones--;
            }
        }
    }
    private void utilityImpact(ProjectileState projectile,WorldQuery.Hit hit,WorldQuery world) {
        if(projectile.exploded)return;projectile.exploded=true;
        if(projectile.kind().equals("freeze")) {
            if(hit.vehicleId()>=0&&hit.vehicleId()!=projectile.ownerId())controlHits.add(new ControlHit(hit.vehicleId(),projectile.ownerId(),AbilityId.FREEZE,projectile.id()));
            return;
        }
        reservedFireZones--;
        Vector3f center=hit.point().add(hit.normal().mult(.05f));
        radialDamage(projectile.id(),projectile.ownerId(),"napalm",center,rules.napalm().radius(),rules.napalm().impactDamage(),world);
        WorldQuery.Support support=world.support(center,rules.napalm().supportDepth());
        if(support==null||support.normal().y<.6f)return;
        List<Vector3f> points=fireSurface(support,world);
        if(points.isEmpty())return;
        FireZone zone=new FireZone(projectile.id(),projectile.ownerId(),support,session.tick+ticks(rules.napalm().durationSeconds()),points);
        fireZones.add(zone);
        events.add(event(GameEvent.Type.FIRE_STARTED,zone.id,zone.ownerId,zone.ownerId,support.point(),"napalm-fire",rules.napalm().radius()));
    }
    private void emitStun(ShotIntent intent,WorldQuery world) {
        Vector3f center=world.position(intent.ownerId),forward=world.forward(intent.ownerId);
        float cosine=(float)Math.cos(radians(rules.control().stunHalfAngleDegrees()));
        for(VehicleState target:orderedVehicles) {
            if(!target.alive()||target.id==intent.ownerId)continue;
            Vector3f offset=world.position(target.id).subtract(center);
            if(world.distanceToHull(target.id,center)>rules.control().stunRange()||offset.lengthSquared()<.0001f)continue;
            if(forward.dot(offset.normalizeLocal())<cosine||!exposed(center,target.id,world))continue;
            controlHits.add(new ControlHit(target.id,intent.ownerId,AbilityId.STUN,intent.id));
        }
    }
    private void acceptMine(VehicleState vehicle,WorldQuery world) {
        if(mines.size()>=rules.mine().maximumActive()||mines.stream().filter(m->m.ownerId==vehicle.id).count()>=2) {
            denied(vehicle,world,"mine-limit");return;
        }
        Vector3f forward=world.forward(vehicle.id);forward.y=0;
        if(forward.lengthSquared()<.001f) {denied(vehicle,world,"invalid-placement");return;}
        Vector3f origin=world.position(vehicle.id).add(0,.4f,0);
        Vector3f behind=origin.subtract(forward.normalizeLocal().mult(rules.mine().placementDistance()));
        WorldQuery.Hit path=world.sweep(origin,behind,.2f,vehicle.id);
        WorldQuery.Support support=world.support(behind,rules.mine().supportDepth());
        if(path!=null||support==null||support.normal().y<.6f) {denied(vehicle,world,"invalid-placement");return;}
        Vector3f place=support.point().add(support.normal().mult(.2f));
        if(world.sweep(behind,place,.15f,vehicle.id)!=null) {denied(vehicle,world,"invalid-placement");return;}
        if(mines.stream().anyMatch(m->m.support.point().distance(place)<.6f)) {denied(vehicle,world,"invalid-placement");return;}
        long id=nextShotId++;
        mines.add(new Mine(id,vehicle.id,support,session.tick+ticks(rules.mine().armSeconds()),session.tick+ticks(rules.mine().lifetimeSeconds())));
        WeaponSlot slot=vehicle.weapon(WeaponType.MINE);slot.ammo--;slot.cooldownTicks=ticks(rules.mine().cooldownSeconds());
        events.add(event(GameEvent.Type.MINE_PLACED,id,vehicle.id,vehicle.id,place,"mine",rules.mine().triggerRadius()));
    }
    private void advanceMines(WorldQuery world) {
        Iterator<Mine> iterator=mines.iterator();
        while(iterator.hasNext()) {
            Mine mine=iterator.next();
            if(session.tick>=mine.expiresAt) {iterator.remove();continue;}
            if(session.tick<mine.armedAt)continue;
            Vector3f center=mine.support.point().add(mine.support.normal().mult(.2f));
            boolean triggered=orderedVehicles.stream().anyMatch(v->v.alive()&&v.id!=mine.ownerId
                    &&world.distanceToHull(v.id,center)<=rules.mine().triggerRadius()&&exposed(center,v.id,world));
            if(triggered) {radialDamage(mine.id,mine.ownerId,"mine",center,rules.mine().explosionRadius(),rules.mine().damage(),world);iterator.remove();}
        }
    }
    private void radialDamage(long id,int owner,String kind,Vector3f center,float radius,float maximum,WorldQuery world) {
        events.add(event(GameEvent.Type.EXPLOSION,id,-1,owner,center,kind,radius));
        for(VehicleState target:orderedVehicles) {
            if(!target.alive())continue;
            float falloff=Math.max(0,1-world.distanceToHull(target.id,center)/radius);
            if(falloff<=0||!exposed(center,target.id,world))continue;
            queueDamage(target.id,owner,maximum*falloff*(target.id==owner?rules.ownerSplashMultiplier():1),kind,id);
        }
    }
    /** A bounded 1m support grid clips a fire patch to its original connected static surface. */
    private List<Vector3f> fireSurface(WorldQuery.Support center,WorldQuery world) {
        int radius=(int)Math.ceil(rules.napalm().radius());
        Map<Long,Vector3f> candidates=new HashMap<>();
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            if(x*x+z*z>rules.napalm().radius()*rules.napalm().radius())continue;
            Vector3f probe=center.point().add(x,1.1f,z);
            WorldQuery.Support support=world.support(probe,2.2f);
            if(support!=null&&support.surfaceId()==center.surfaceId()&&support.normal().dot(center.normal())>.9f
                    &&support.normal().y>=.6f)candidates.put(gridKey(x,z),support.point());
        }
        long first=gridKey(0,0);if(!candidates.containsKey(first))return List.of();
        Set<Long> seen=new HashSet<>();Deque<Long> queue=new ArrayDeque<>();queue.add(first);seen.add(first);
        List<Vector3f> points=new ArrayList<>();
        while(!queue.isEmpty()) {
            long key=queue.removeFirst();Vector3f point=candidates.get(key);points.add(point);
            int x=(int)(key>>32),z=(int)key;
            for(int[] delta:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                long neighbor=gridKey(x+delta[0],z+delta[1]);Vector3f next=candidates.get(neighbor);
                if(next==null||seen.contains(neighbor)||Math.abs(next.y-point.y)>1)continue;
                WorldQuery.Hit barrier=world.ray(point.add(0,.25f,0),next.add(0,.25f,0),-1);
                if(barrier!=null&&barrier.fraction()<.99f)continue;
                seen.add(neighbor);queue.add(neighbor);
            }
        }
        return List.copyOf(points);
    }
    private static long gridKey(int x,int z) {return ((long)x<<32)|(z&0xffffffffL);}
    private void advanceFire(WorldQuery world) {
        for(FireZone zone:fireZones)if(session.tick>=zone.expiresAt)events.add(event(GameEvent.Type.FIRE_ENDED,zone.id,zone.ownerId,zone.ownerId,zone.support.point(),"napalm-fire",0));
        fireZones.removeIf(zone->session.tick>=zone.expiresAt);
        int interval=ticks(rules.napalm().intervalSeconds());
        float baseAmount=rules.napalm().damagePerSecond()*interval*MatchSession.DT;
        for(VehicleState target:orderedVehicles) {
            FireZone selected=null;
            float maximumAmount=0;
            if(target.alive())for(FireZone zone:fireZones) {
                if(!inFire(target.id,zone,world))continue;
                if(selected==null||zone.id<selected.id)selected=zone;
                float amount=baseAmount*(target.id==zone.ownerId?rules.ownerSplashMultiplier():1);
                maximumAmount=Math.max(maximumAmount,amount);
            }
            if(selected==null||target.protectionTicks>0) {fireExposureTicks.remove(target.id);continue;}
            int elapsed=fireExposureTicks.getOrDefault(target.id,0)+1;
            if(elapsed>=interval) {
                queueDamage(target.id,selected.ownerId,maximumAmount,"napalm-fire",nextShotId++);elapsed=0;
            }
            fireExposureTicks.put(target.id,elapsed);
        }
    }
    private boolean inFire(int id,FireZone zone,WorldQuery world) {
        Vector3f position=world.position(id);
        if(world.distanceToHull(id,zone.support.point())>rules.napalm().radius())return false;
        WorldQuery.Support support=world.support(position.add(0,.2f,0),2);
        if(support==null||support.surfaceId()!=zone.support.surfaceId())return false;
        for(Vector3f point:zone.points) {
            if(Math.abs(point.y-support.point().y)>.7f)continue;
            if(Math.abs(point.x-support.point().x)>.75f||Math.abs(point.z-support.point().z)>.75f)continue;
            if(exposed(point.add(0,.25f,0),id,world))return true;
        }
        return false;
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
            controlHits.clear();
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
        resolveControl(world);
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
            world.immobilize(target.id,false);
            target.frozenTicks=target.stunnedTicks=target.shieldTicks=target.controlImmunityTicks=0;
            int killer = target.lastAttacker >= 0 && session.tick - target.lastAttackTick <= ticks(rules.killCreditSeconds())
                    ? target.lastAttacker : -1;
            if (killer >= 0 && killer != target.id) session.vehicle(killer).eliminations++;
            events.add(event(GameEvent.Type.DESTROYED, target.id, target.id, killer, world.position(target.id), "destroyed", 0));
            locks.remove(target.id);
        }
    }

    private void applyDamage(int targetId, List<Damage> requests, WorldQuery world) {
        VehicleState target = session.vehicle(targetId);
        if(target.shieldTicks>0)requests=requests.stream().map(request->
                request.cause.equals("recovery")||request.cause.equals("out-of-bounds")?request:
                        new Damage(request.targetId,request.sourceId,request.amount*rules.control().shieldDamageMultiplier(),request.cause,request.eventId)).toList();
        double total = requests.stream().mapToDouble(Damage::amount).sum();
        if(total<=0)return;
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

    private void resolveControl(WorldQuery world) {
        // Existing CC lasts through this complete physics step. Fresh hits below start
        // their duration afterwards, avoiding a one-step-short immobilization.
        if(lastControlTimerTick!=session.tick) {
            lastControlTimerTick=session.tick;
            for(VehicleState target:orderedVehicles) {
                boolean controlled=target.controlled();
                target.frozenTicks=Math.max(0,target.frozenTicks-1);target.stunnedTicks=Math.max(0,target.stunnedTicks-1);
                if(controlled&&!target.controlled()) {
                    target.controlImmunityTicks=ticks(rules.control().immunitySeconds());world.immobilize(target.id,false);
                    events.add(event(GameEvent.Type.CONTROL_ENDED,nextShotId++,target.id,target.id,world.position(target.id),"control-ended",0));
                }
            }
        }
        controlHits.sort(Comparator.comparingInt((ControlHit hit)->hit.ability==AbilityId.STUN?0:1).thenComparingLong(ControlHit::eventId));
        for(ControlHit hit:controlHits) {
            VehicleState target=session.vehicle(hit.targetId);
            if(!target.alive()||target.protectionTicks>0||target.shieldTicks>0||target.controlled()||target.controlImmunityTicks>0)continue;
            boolean freeze=hit.ability==AbilityId.FREEZE;
            int duration=ticks(freeze?rules.control().freezeSeconds():rules.control().stunSeconds());
            if(freeze) {target.frozenTicks=duration;world.immobilize(target.id,true);}
            else target.stunnedTicks=duration;
            events.add(event(freeze?GameEvent.Type.FREEZE:GameEvent.Type.STUN,hit.eventId,target.id,hit.sourceId,world.position(target.id),freeze?"freeze":"stun",duration/120f));
        }
        controlHits.clear();
    }
    public void endControl(VehicleState target,WorldQuery world) {
        if(target.controlled()) {
            target.frozenTicks=target.stunnedTicks=0;target.controlImmunityTicks=ticks(rules.control().immunitySeconds());
            events.add(event(GameEvent.Type.CONTROL_ENDED,nextShotId++,target.id,target.id,world.position(target.id),"control-ended",0));
        }
        world.immobilize(target.id,false);
    }

    public List<ProjectileState> projectiles() { return List.copyOf(projectiles); }
    public List<MineView> mines() {
        return mines.stream().map(m->new MineView(m.id,m.ownerId,m.support.point(),m.support.normal(),session.tick>=m.armedAt,rules.mine().triggerRadius())).toList();
    }
    public List<FireZoneView> fireZones() {
        return fireZones.stream().map(z->new FireZoneView(z.id,z.ownerId,z.support.point(),z.support.normal(),rules.napalm().radius(),(int)Math.max(0,z.expiresAt-session.tick),z.points)).toList();
    }
    public int reservedFireZones() {return reservedFireZones;}
    public int pendingDamageCount() { return damage.size(); }
    public List<GameEvent> drainEvents() {
        List<GameEvent> result = List.copyOf(events);
        events.clear();
        return result;
    }

    public void clear() {
        if(lastWorld!=null)for(var vehicle:orderedVehicles)lastWorld.immobilize(vehicle.id,false);
        for(var vehicle:orderedVehicles)vehicle.frozenTicks=vehicle.stunnedTicks=vehicle.shieldTicks=vehicle.controlImmunityTicks=0;
        mines.clear();fireZones.clear();controlHits.clear();fireExposureTicks.clear();reservedFireZones=0;
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
