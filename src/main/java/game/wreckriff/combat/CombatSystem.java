package game.wreckriff.combat;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import game.wreckriff.config.VehicleDefinition;

import java.util.*;
import java.util.function.Supplier;

import static game.wreckriff.combat.CombatRules.ticks;

/** Fixed-tick weapon acceptance, geometric attacks, and one simultaneous damage phase. */
public final class CombatSystem {
    public record ArenaTarget(String geometryId,Vector3f min,Vector3f max) {
        public ArenaTarget {
            if(geometryId==null||geometryId.isBlank()||min==null||max==null
                    ||!finite(min)||!finite(max)||min.x>=max.x||min.y>=max.y||min.z>=max.z)
                throw new IllegalArgumentException("Invalid arena damage target");
            min=min.clone();max=max.clone();
        }
        @Override public Vector3f min(){return min.clone();}
        @Override public Vector3f max(){return max.clone();}
        private static boolean finite(Vector3f value) {return Float.isFinite(value.x)&&Float.isFinite(value.y)&&Float.isFinite(value.z);}
        Vector3f closest(Vector3f point) {
            return new Vector3f(Math.clamp(point.x,min.x,max.x),Math.clamp(point.y,min.y,max.y),Math.clamp(point.z,min.z,max.z));
        }
    }
    @FunctionalInterface public interface ArenaDamageSink {void damage(String geometryId,int sourceId,float amount,String cause,long eventId);}
    private record ArenaDamageKey(long eventId,String geometryId) {}
    private record ArenaDamage(String geometryId,int sourceId,float amount,String cause,long eventId) {}
    private record ArenaRamPair(String geometryId,int vehicleId) implements Comparable<ArenaRamPair> {
        public int compareTo(ArenaRamPair other) {
            int order=geometryId.compareTo(other.geometryId);return order==0?Integer.compare(vehicleId,other.vehicleId):order;
        }
    }
    private static final int MAX_ARENA_TARGETS=128,MAX_ARENA_DAMAGE_PER_TICK=16384;
    private Supplier<List<ArenaTarget>> arenaTargetSource=List::of;
    private ArenaDamageSink arenaDamageSink=(geometry,source,amount,cause,event)->{};
    private Map<String,ArenaTarget> arenaTargets=Map.of();
    private long arenaTargetTick=Long.MIN_VALUE,arenaDamageTick=Long.MIN_VALUE;
    private final LinkedHashMap<ArenaDamageKey,ArenaDamage> arenaDamage=new LinkedHashMap<>();
    private final Set<ArenaDamageKey> seenArenaDamage=new HashSet<>();
    private final Map<ArenaRamPair,Float> arenaRamSpeeds=new TreeMap<>();
    private final Map<ArenaRamPair,Long> arenaRamReadyAt=new HashMap<>();

    public void configureArenaDamage(Supplier<List<ArenaTarget>> targets,ArenaDamageSink sink) {
        if(!arenaDamage.isEmpty()||!arenaRamSpeeds.isEmpty())throw new IllegalStateException("Cannot replace pending arena damage owner");
        arenaTargetSource=Objects.requireNonNull(targets);arenaDamageSink=Objects.requireNonNull(sink);
        arenaTargetTick=Long.MIN_VALUE;arenaTargets=Map.of();
    }
    private Map<String,ArenaTarget> arenaTargets() {
        if(arenaTargetTick==session.tick)return arenaTargets;
        List<ArenaTarget> supplied=Objects.requireNonNull(arenaTargetSource.get(),"Arena target catalogue");
        if(supplied.size()>MAX_ARENA_TARGETS)throw new IllegalStateException("Arena target catalogue exceeds "+MAX_ARENA_TARGETS);
        Map<String,ArenaTarget> result=new LinkedHashMap<>();
        for(var target:supplied)if(target==null||result.putIfAbsent(target.geometryId(),target)!=null)
            throw new IllegalStateException("Duplicate/null arena target");
        arenaTargets=Collections.unmodifiableMap(result);arenaTargetTick=session.tick;return arenaTargets;
    }
    private boolean arenaDamageEnabled(int source) {
        return session.outcome==MatchSession.Outcome.NONE
                &&!(session.phase==MatchSession.Phase.BOSS_ENTRY&&source==session.bossParticipantId&&source>=0);
    }
    private void queueArenaDamage(String geometry,int source,float amount,String cause,long event) {
        if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Arena damage must be finite and nonnegative");
        if(geometry==null||amount==0||!arenaDamageEnabled(source)||!arenaTargets().containsKey(geometry))return;
        if(arenaDamageTick!=session.tick){seenArenaDamage.clear();arenaDamageTick=session.tick;}
        var key=new ArenaDamageKey(event,geometry);if(seenArenaDamage.contains(key))return;
        if(seenArenaDamage.size()>=MAX_ARENA_DAMAGE_PER_TICK)throw new IllegalStateException("Arena damage exceeded the per-tick budget");
        seenArenaDamage.add(key);arenaDamage.putIfAbsent(key,new ArenaDamage(geometry,source,amount,cause,event));
    }
    private void directArenaDamage(WorldQuery.Hit hit,int owner,float amount,String cause,long event) {
        if(hit!=null&&hit.vehicleId()<0)queueArenaDamage(hit.objectId(),owner,amount,cause,event);
    }
    private void radialArenaDamage(long eventId,int owner,String cause,Vector3f center,float radius,float maximum,
                                   String directGeometry,WorldQuery world) {
        if(!arenaDamageEnabled(owner))return;
        for(var target:arenaTargets().values()) {
            if(target.geometryId().equals(directGeometry))continue;
            Vector3f point=target.closest(center);float distance=center.distance(point);
            if(distance>=radius)continue;
            var blocker=distance<.00001f?null:world.ray(center,point,-1);
            if(blocker!=null&&!target.geometryId().equals(blocker.objectId()))continue;
            queueArenaDamage(target.geometryId(),owner,maximum*(1-distance/radius),cause,eventId);
        }
    }
    public void queueArenaRam(String geometryId,int vehicleId,float closingSpeed) {
        if(!Float.isFinite(closingSpeed)||closingSpeed<0)throw new IllegalArgumentException("Invalid arena closing speed");
        if(geometryId==null||!session.containsParticipant(vehicleId)||!session.vehicle(vehicleId).alive()
                ||!arenaDamageEnabled(vehicleId)||closingSpeed<=rules.ram().minimumClosingSpeed()||!arenaTargets().containsKey(geometryId))return;
        var pair=new ArenaRamPair(geometryId,vehicleId);
        if(session.tick<arenaRamReadyAt.getOrDefault(pair,Long.MIN_VALUE))return;
        arenaRamSpeeds.merge(pair,closingSpeed,Math::max);
    }
    /** Resolve due special payloads before the environment chooses this tick's hazard damage. */
    public void prepareArenaDamage(WorldQuery world) {
        advanceSpecials(Objects.requireNonNull(world));resolveArenaDamage();
    }
    /** May run both before hazards and after late specials in the same tick; dedup survives both calls. */
    public void resolveArenaDamage() {
        if(session.outcome!=MatchSession.Outcome.NONE){arenaDamage.clear();arenaRamSpeeds.clear();return;}
        for(var entry:arenaRamSpeeds.entrySet()) {
            var pair=entry.getKey();if(!session.vehicle(pair.vehicleId()).alive())continue;
            float amount=Math.min(rules.ram().maximumDamage(),rules.ram().damagePerExcessSpeed()
                    *(entry.getValue()-rules.ram().minimumClosingSpeed()));
            queueArenaDamage(pair.geometryId(),pair.vehicleId(),amount,"ram",nextShotId++);
            arenaRamReadyAt.put(pair,session.tick+ticks(rules.ram().cooldownSeconds()));
        }
        arenaRamSpeeds.clear();
        for(var iterator=arenaDamage.entrySet().iterator();iterator.hasNext();) {
            var request=iterator.next().getValue();
            arenaDamageSink.damage(request.geometryId(),request.sourceId(),request.amount(),request.cause(),request.eventId());
            iterator.remove();
        }
    }
    @FunctionalInterface public interface DirectDamageMultiplier {float multiplier(int targetId,Vector3f localPoint);}
    private DirectDamageMultiplier directDamageMultiplier=(target,point)->1;
    public void directDamageMultiplier(DirectDamageMultiplier multiplier) {directDamageMultiplier=Objects.requireNonNull(multiplier);}
    private float directDamage(int targetId,Vector3f point,float amount,WorldQuery world) {
        Vector3f local=world.rotation(targetId).inverse().mult(point.subtract(world.position(targetId)));
        float factor=directDamageMultiplier.multiplier(targetId,local);
        if(factor!=1&&factor!=1.25f)throw new IllegalStateException("Unsupported target damage multiplier");
        return amount*factor;
    }
    private record ShotIntent(long id, int ownerId, String kind, int targetId, float pitch, float yaw, int barrel) {}
    private record DamageKey(long eventId, int targetId) {}
    private record Damage(int targetId, int sourceId, float amount, String cause, long eventId, Vector3f point, Vector3f normal,Vector3f origin) {}
    private record ControlHit(int targetId,int sourceId,long eventId,Vector3f point,Vector3f normal) {}
    private static final class BlastSum { final Vector3f linear=new Vector3f(),torque=new Vector3f();boolean heavy; }
    public record BallisticWarningView(long id,int ownerId,Vector3f point,Vector3f normal,float radius,int remainingTicks) {
        public BallisticWarningView {point=point.clone();normal=normal.clone();}
        @Override public Vector3f point() {return point.clone();}
        @Override public Vector3f normal() {return normal.clone();}
    }
    private static final class Salvo {
        final ProjectileState carrier;
        final Vector3f area,station;
        final int arrivalTicks;
        int targetId,planned,released;
        long nextPlanTick=Long.MAX_VALUE;
        Salvo(ProjectileState carrier,Vector3f area,int arrivalTicks) {
            this.carrier=carrier;this.area=area.clone();station=new Vector3f();
            this.arrivalTicks=arrivalTicks;targetId=carrier.targetId;
        }
    }
    private static final class FallingCharge {
        final long id,carrierId,releaseTick;
        final int ownerId,targetId,lifeTicks;
        final Vector3f origin,velocity,offset,point,normal;
        long impactTick;
        FallingCharge(long id,int ownerId,long carrierId,int targetId,Vector3f origin,Vector3f velocity,
                Vector3f offset,Prediction prediction,long releaseTick,int lifeTicks) {
            this.id=id;this.ownerId=ownerId;this.carrierId=carrierId;this.targetId=targetId;
            this.origin=origin;this.velocity=velocity;this.offset=offset;
            this.point=prediction.hit.point().clone();this.normal=prediction.hit.normal().clone();
            this.releaseTick=releaseTick;this.impactTick=releaseTick+prediction.ticks;this.lifeTicks=lifeTicks;
        }
    }
    public record MineView(long id,int ownerId,Vector3f position,Vector3f normal,boolean armed,float radius) {
        public MineView { position=position.clone();normal=normal.clone(); }
    }
    public record FireZoneView(long id,int ownerId,Vector3f position,Vector3f normal,float radius,int remainingTicks,List<Vector3f> surfacePoints) {
        public FireZoneView { position=position.clone();normal=normal.clone();surfacePoints=surfacePoints.stream().map(Vector3f::clone).toList(); }
    }
    private record Mine(long id,int ownerId,WorldQuery.Support support,long armedAt,long expiresAt) {}
    private record FireZone(long id,int ownerId,WorldQuery.Support support,long expiresAt,List<Vector3f> points) {}
    private record SpecialBomb(long id,int ownerId,WorldQuery.Support support,long explodeAt) {}
    public record SpecialBombView(long id,int ownerId,Vector3f position,Vector3f normal,int remainingTicks,float radius) {
        public SpecialBombView {position=position.clone();normal=normal.clone();}
        @Override public Vector3f position() {return position.clone();}
        @Override public Vector3f normal() {return normal.clone();}
    }
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
    private final List<VehicleState> orderedVehicles=new ArrayList<>();
    private final SplittableRandom weaponSeed;
    private final List<ProjectileState> projectiles = new ArrayList<>();
    private final List<ShotIntent> intents = new ArrayList<>();
    private final List<Damage> damage = new ArrayList<>();
    private final List<GameEvent> events = new ArrayList<>();
    private final List<ControlHit> controlHits=new ArrayList<>();
    private final List<Mine> mines=new ArrayList<>();
    private final List<FireZone> fireZones=new ArrayList<>();
    private final List<SpecialBomb> specialBombs=new ArrayList<>();
    private final Map<Long,Salvo> salvos=new LinkedHashMap<>();
    private final List<FallingCharge> pendingCharges=new ArrayList<>();
    private final Map<Long,FallingCharge> warnings=new LinkedHashMap<>();
    private final Map<Integer,Integer> napalmTargets=new HashMap<>();
    private final Map<Integer,Integer> fireExposureTicks=new HashMap<>();
    private final Map<Integer, Lock> locks = new HashMap<>();
    private final Map<Integer, SplittableRandom> spreadRandom = new HashMap<>();
    private final Map<Integer, Long> emptyFeedbackAfter = new HashMap<>();
    private final Map<Pair, Float> ramSpeeds = new TreeMap<>();
    private final Map<Pair, Long> ramReadyAt = new HashMap<>();
    private final Map<Pair, Long> ramFeedbackAt=new HashMap<>();
    private final Map<Integer, BlastSum> blasts = new TreeMap<>();
    private final Map<Integer,Integer> nextBarrels=new HashMap<>();
    private final Map<Integer,Long> shieldFeedbackAfter=new HashMap<>();
    private final Set<Integer> expiredShields=new TreeSet<>();
    private final Set<DamageKey> seenDamage = new HashSet<>();
    private final Set<Integer> destroyed = new HashSet<>();
    private long nextShotId = 1;
    private int reservedFireZones;
    private int reservedCharges;
    private WorldQuery lastWorld;
    private long lastControlTimerTick=Long.MIN_VALUE;
    private long lastSpecialTick=Long.MIN_VALUE;

    public CombatSystem(MatchSession session, CombatRules rules) {
        this.session = Objects.requireNonNull(session);
        this.rules = Objects.requireNonNull(rules);
        weaponSeed = new SplittableRandom(session.seed ^ 0x575245434b524946L);
        for(var vehicle:session.vehicles)registerParticipant(vehicle);
    }
    public void registerParticipant(VehicleState vehicle) {
        if(orderedVehicles.stream().anyMatch(v->v.id==vehicle.id))throw new IllegalArgumentException("Duplicate combat participant: "+vehicle.id);
        if(!session.containsParticipant(vehicle.id)||session.vehicle(vehicle.id)!=vehicle)throw new IllegalArgumentException("Unregistered participant");
        orderedVehicles.add(vehicle);orderedVehicles.sort(Comparator.comparingInt(v->v.id));
        spreadRandom.put(vehicle.id,weaponSeed.split());
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
            if (vehicle.protectionTicks > 0) command = command.withoutAttacks();
            updateLock(vehicle, world);
            napalmTargets.put(vehicle.id,assistTarget(vehicle.id,world));
            if(command.directWeapon()!=null)vehicle.selectedWeapon=command.directWeapon();
            else if(command.weaponDelta()!=0)vehicle.selectedWeapon=vehicle.selectedWeapon.cycle(command.weaponDelta());
            if (vehicle.protectionTicks > 0) continue;
            if (command.machineGun() && vehicle.machineGunCooldown == 0) {
                SplittableRandom random = spreadRandom.get(vehicle.id);
                float spread = radians(rules.machineGun().spreadDegrees());
                float pitch = (float) ((random.nextDouble() * 2 - 1) * spread);
                float yaw = (float) ((random.nextDouble() * 2 - 1) * spread);
                int barrel=nextBarrels.getOrDefault(vehicle.id,0);nextBarrels.put(vehicle.id,1-barrel);
                intents.add(new ShotIntent(nextShotId++, vehicle.id, "machine-gun", -1, pitch, yaw,barrel));
                vehicle.machineGunCooldown = rules.machineGun().cooldownTicks();
            }
            if (command.selectedWeapon()) acceptWeapon(vehicle, world);
            if(command.ability()==AbilityId.SPECIAL)acceptSpecial(vehicle,command,world);
            else acceptAbility(vehicle,command.ability(),world);
        }
    }

    private void decrementTimers(VehicleState vehicle,WorldQuery world) {
        vehicle.machineGunCooldown = Math.max(0, vehicle.machineGunCooldown - 1);
        for(var slot:vehicle.weapons())slot.cooldownTicks=Math.max(0,slot.cooldownTicks-1);
        vehicle.advanceAbilityCooldowns();
        if(vehicle.shieldTicks==1)expiredShields.add(vehicle.id);
        vehicle.shieldTicks=Math.max(0,vehicle.shieldTicks-1);
        vehicle.controlImmunityTicks=Math.max(0,vehicle.controlImmunityTicks-1);
    }
    private boolean projectileCapacity(int required) {
        long reserved=intents.stream().filter(i->!i.kind.equals("machine-gun")).count();
        return projectiles.size()+reserved+reservedCharges+required<=rules.maximumProjectiles();
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
        if(!projectileCapacity(type==WeaponType.BALLISTIC?1+rules.ballistic().charges():1)) {denied(vehicle,world,"projectile-limit");return;}
        if(type==WeaponType.NAPALM && fireZones.size()+reservedFireZones>=rules.napalm().maximumZones()) {
            denied(vehicle,world,"fire-zone-limit");return;
        }
        int target=switch(type) {
            case HOMING->lockTarget(vehicle.id);case NAPALM->napalmAssistTarget(vehicle.id);
            case BALLISTIC->selectBallisticTarget(session,vehicle.id,world);default->-1;
        };
        intents.add(new ShotIntent(nextShotId++,vehicle.id,type.id(),target,0,0,0));
        slot.ammo--;
        slot.cooldownTicks=ticks(switch(type) {
            case HOMING->rules.homing().cooldownSeconds();case POWER->rules.power().cooldownSeconds();
            case NAPALM->rules.napalm().cooldownSeconds();case MINE->throw new IllegalStateException();
            case BALLISTIC->rules.ballistic().cooldownSeconds();case CANNON->rules.cannon().cooldownSeconds();
        });
        if(type==WeaponType.NAPALM)reservedFireZones++;
        if(type==WeaponType.BALLISTIC)reservedCharges+=rules.ballistic().charges();
    }
    private void acceptAbility(VehicleState vehicle,AbilityId ability,WorldQuery world) {
        if(ability!=AbilityId.FREEZE||vehicle.abilityCooldown(ability)>0)return;
        if(ability==AbilityId.FREEZE&&!projectileCapacity(1)) {denied(vehicle,world,"projectile-limit");return;}
        intents.add(new ShotIntent(nextShotId++,vehicle.id,"freeze",-1,0,0,0));
        vehicle.abilityCooldown(ability,ticks(rules.control().freezeCooldownSeconds()));
    }

    private void acceptSpecial(VehicleState owner,VehicleCommand command,WorldQuery world) {
        if(owner.boss||owner.controlled()||owner.specialActive()||owner.abilityCooldown(AbilityId.SPECIAL)>0)return;
        var definition=VehicleDefinition.forId(owner.profileId);
        WorldQuery.Support support=null;
        if(definition==VehicleDefinition.SPARK) {
            if(!world.grounded(owner.id)||specialBombs.size()>=SpecialRules.MAXIMUM_BOMBS) {denied(owner,world,"special-unavailable");return;}
            support=world.support(world.position(owner.id).add(0,.25f,0),world.profile(owner.id).roadOffset()+1);
            if(support==null||support.normal().y<.5f) {denied(owner,world,"special-needs-road");return;}
        }
        owner.specialDamage=0;owner.specialTargetId=-1;
        owner.abilityCooldown(AbilityId.SPECIAL,ticks(SpecialRules.cooldownSeconds(owner.profileId)));
        switch(definition) {
            case RIVET->{owner.specialPhase=VehicleState.SpecialPhase.PULSE_WINDUP;owner.specialTicks=ticks(SpecialRules.PULSE_WINDUP);}
            case GRINDER->{owner.specialPhase=VehicleState.SpecialPhase.GRINDER_WINDUP;owner.specialTicks=ticks(SpecialRules.GRINDER_WINDUP);}
            case SPARK->{
                owner.specialPhase=VehicleState.SpecialPhase.DASH;owner.specialTicks=ticks(SpecialRules.DASH_SECONDS);
                // Positive steering is driver's right: the camera/driver convention is local -X.
                owner.dashDirection.set(world.rotation(owner.id).mult(new Vector3f(command.steer()<0?1:-1,0,0)));
                owner.dashDirection.y=0;owner.dashDirection.normalizeLocal();
                long id=nextShotId++;
                specialBombs.add(new SpecialBomb(id,owner.id,support,session.tick+ticks(SpecialRules.BOMB_FUSE)));
                events.add(new GameEvent(GameEvent.Type.BOMB_PLACED,id,owner.id,owner.id,support.point(),"special-bomb",
                        SpecialRules.BOMB_RADIUS,world.position(owner.id),support.normal()));
            }
        }
        events.add(event(GameEvent.Type.SPECIAL_STARTED,nextShotId++,owner.id,owner.id,world.position(owner.id),owner.profileId,
                owner.specialTicks/(float)MatchSession.TICKS_PER_SECOND));
    }

    /** After the native step, using actual contacts, before the common simultaneous damage resolution. */
    public void advanceSpecials(WorldQuery world) {
        if(session.outcome!=MatchSession.Outcome.NONE||lastSpecialTick==session.tick)return;
        lastSpecialTick=session.tick;
        for(var owner:orderedVehicles) {
            if(!owner.specialActive())continue;
            if(!owner.alive()||owner.controlled()) {cancelSpecial(owner,world);continue;}
            switch(owner.specialPhase) {
                case PULSE_WINDUP->{if(--owner.specialTicks==0) {pulse(owner,world);cancelSpecial(owner,world);}}
                case GRINDER_WINDUP->{if(--owner.specialTicks==0) {
                    owner.specialPhase=VehicleState.SpecialPhase.GRINDER_SEARCH;owner.specialTicks=ticks(SpecialRules.GRINDER_SEARCH);
                }}
                case GRINDER_SEARCH->{
                    int targetId=grinderTarget(owner,world);
                    if(targetId>=0) {
                        owner.specialTargetId=targetId;owner.specialPhase=VehicleState.SpecialPhase.GRINDER_CONTACT;
                        owner.specialTicks=ticks(SpecialRules.GRINDER_CONTACT);
                        var target=session.vehicle(targetId);
                        if(!target.boss&&!target.controlled()&&target.controlImmunityTicks==0&&target.shieldTicks==0
                                &&world.beginGrab(owner.id,target.id)) {
                            // Cancel an in-progress dodge or windup as soon as the physical capture succeeds.
                            cancelSpecial(target,world);target.grabbedBy=owner.id;
                            events.add(event(GameEvent.Type.GRAB_STARTED,nextShotId++,target.id,owner.id,world.position(target.id),"grinder",SpecialRules.GRINDER_CONTACT));
                        }
                        grind(owner,world);
                    } else if(--owner.specialTicks==0)cancelSpecial(owner,world);
                }
                case GRINDER_CONTACT->grind(owner,world);
                case DASH->{if(--owner.specialTicks==0||!world.grounded(owner.id)||!world.dashActive(owner.id))cancelSpecial(owner,world);}
                case READY->{ }
            }
        }
        for(var iterator=specialBombs.iterator();iterator.hasNext();) {
            var bomb=iterator.next();if(session.tick+1<bomb.explodeAt)continue;
            Vector3f center=bomb.support.point().add(bomb.support.normal().mult(.08f));
            events.add(new GameEvent(GameEvent.Type.EXPLOSION,bomb.id,-1,bomb.ownerId,center,"special-bomb",SpecialRules.BOMB_RADIUS,
                    center,bomb.support.normal()));
            for(var target:orderedVehicles) {
                if(!target.alive())continue;
                float falloff=Math.max(0,1-world.distanceToHull(target.id,center)/SpecialRules.BOMB_RADIUS);
                if(falloff<=0||!exposed(center,target.id,world))continue;
                float amount=SpecialRules.BOMB_DAMAGE*falloff*(target.id==bomb.ownerId?rules.ownerSplashMultiplier():1);
                queueContactDamage(target.id,bomb.ownerId,amount,"special-bomb",bomb.id,
                        world.closestHullPoint(target.id,center),bomb.support.normal(),center);
            }
            radialArenaDamage(bomb.id,bomb.ownerId,"special-bomb",center,SpecialRules.BOMB_RADIUS,SpecialRules.BOMB_DAMAGE,null,world);
            iterator.remove();
        }
    }

    private void pulse(VehicleState owner,WorldQuery world) {
        Vector3f origin=world.position(owner.id).add(world.rotation(owner.id).mult(world.profile(owner.id).grinderIntake()));
        Vector3f forward=world.forward(owner.id).setY(0).normalizeLocal();
        VehicleState chosen=null;float nearest=Float.POSITIVE_INFINITY;
        for(var target:orderedVehicles) {
            if(target.id==owner.id||!target.alive()||target.protectionTicks>0)continue;
            Vector3f point=world.closestHullPoint(target.id,origin),offset=point.subtract(origin);
            float distance=offset.length();
            if(distance>SpecialRules.PULSE_RANGE||distance>=nearest||!specialExposed(owner.id,origin,target.id,world))continue;
            // Use the full direction for the cone: a car on another deck is not in a flat forward pulse.
            if(distance>.001f&&forward.dot(offset.divide(distance))<Math.cos(radians(SpecialRules.PULSE_HALF_ANGLE)))continue;
            nearest=distance;chosen=target;
        }
        long id=nextShotId++;
        Vector3f end=chosen==null?origin.add(forward.mult(SpecialRules.PULSE_RANGE)):world.closestHullPoint(chosen.id,origin);
        events.add(new GameEvent(GameEvent.Type.SPECIAL_HIT,id,chosen==null?-1:chosen.id,owner.id,end,"pulse",SpecialRules.PULSE_DAMAGE,origin,forward));
        if(chosen==null)return;
        queueContactDamage(chosen.id,owner.id,SpecialRules.PULSE_DAMAGE,"pulse",id,end,forward.negate(),origin);
        Vector3f direction=world.position(chosen.id).subtract(world.position(owner.id)).setY(0);
        if(direction.lengthSquared()<.0001f)direction.set(forward);else direction.normalizeLocal();
        float magnitude=Math.min(SpecialRules.PULSE_IMPULSE,SpecialRules.PULSE_MAX_DELTA_SPEED*world.mass(chosen.id));
        if(chosen.shieldTicks>0)magnitude*=rules.control().shieldDamageMultiplier();
        blasts.computeIfAbsent(chosen.id,ignored->new BlastSum()).linear.addLocal(direction.mult(magnitude));
    }

    private int grinderTarget(VehicleState owner,WorldQuery world) {
        if(!world.grounded(owner.id))return -1;
        Vector3f intake=world.position(owner.id).add(world.rotation(owner.id).mult(world.profile(owner.id).grinderIntake()));
        int candidate=-1;float nearest=Float.POSITIVE_INFINITY;
        for(var target:orderedVehicles) {
            if(target.id==owner.id||!target.alive()||target.protectionTicks>0||!grinderContact(owner,target,world))continue;
            float distance=world.distanceToHull(target.id,intake);
            if(distance<nearest) {nearest=distance;candidate=target.id;}
        }
        return candidate;
    }
    private boolean grinderContact(VehicleState owner,VehicleState target,WorldQuery world) {
        if(!world.grounded(owner.id)||!world.grounded(target.id)||!world.touchingVehicles(owner.id,target.id))return false;
        Vector3f intake=world.position(owner.id).add(world.rotation(owner.id).mult(world.profile(owner.id).grinderIntake()));
        Vector3f point=world.closestHullPoint(target.id,intake);
        Vector3f local=world.rotation(owner.id).inverse().mult(point.subtract(world.position(owner.id)));
        Vector3f socket=world.profile(owner.id).grinderIntake();
        return local.z>=socket.z-.65f&&Math.abs(local.x)<=world.profile(owner.id).width()*.48f
                &&point.distance(intake)<=world.profile(owner.id).width()*.6f&&specialExposed(owner.id,intake,target.id,world);
    }
    private boolean specialExposed(int ownerId,Vector3f origin,int targetId,WorldQuery world) {
        for(var sample:world.hullVisibilityPoints(targetId)) {
            var hit=world.ray(origin,sample,ownerId);
            if(hit==null||hit.vehicleId()==targetId||hit.fraction()>=.999f)return true;
        }
        return false;
    }
    private void grind(VehicleState owner,WorldQuery world) {
        var target=session.vehicle(owner.specialTargetId);
        boolean held=target.grabbedBy==owner.id;
        if(!target.alive()||target.protectionTicks>0||target.shieldTicks>0||!grinderContact(owner,target,world)
                ||held&&!world.grabIntact(owner.id,target.id)) {
            cancelSpecial(owner,world);return;
        }
        float amount=Math.min(SpecialRules.GRINDER_DPS*MatchSession.DT,SpecialRules.GRINDER_DAMAGE_CAP-owner.specialDamage);
        if(amount>0) {
            owner.specialDamage+=amount;
            Vector3f intake=world.position(owner.id).add(world.rotation(owner.id).mult(world.profile(owner.id).grinderIntake()));
            queueContactDamage(target.id,owner.id,amount,"grinder",nextShotId++,world.closestHullPoint(target.id,intake),world.forward(owner.id).negate(),intake);
        }
        if(--owner.specialTicks==0)cancelSpecial(owner,world);
    }
    public void cancelSpecial(VehicleState owner,WorldQuery world) {
        if(owner.specialTargetId>=0&&session.containsParticipant(owner.specialTargetId)) {
            var target=session.vehicle(owner.specialTargetId);
            if(target.grabbedBy==owner.id) {
                target.grabbedBy=-1;target.controlImmunityTicks=ticks(rules.control().immunitySeconds());
                events.add(event(GameEvent.Type.CONTROL_ENDED,nextShotId++,target.id,owner.id,world.position(target.id),"grinder",0));
            }
        }
        world.endGrab(owner.id);
        if(owner.dashing())world.endDash(owner.id);
        if(owner.specialActive())events.add(event(GameEvent.Type.SPECIAL_ENDED,nextShotId++,owner.id,owner.id,world.position(owner.id),owner.profileId,0));
        owner.clearSpecial();
    }
    public List<SpecialBombView> specialBombs() {
        return specialBombs.stream().map(b->new SpecialBombView(b.id,b.ownerId,b.support.point(),b.support.normal(),
                (int)Math.max(0,b.explodeAt-session.tick),SpecialRules.BOMB_RADIUS)).toList();
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
        for (ProjectileState projectile : List.copyOf(projectiles)) {
            projectile.previousPosition.set(projectile.position);
            if(projectile.kind().equals("cannon")) {advanceCannon(projectile,world);continue;}
            if(projectile.kind().equals("ballistic")) {advanceCarrier(projectile,world);continue;}
            if(projectile.kind().equals("ballistic-fall")) {advanceCharge(projectile,world);continue;}
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
        releaseCharges(world);
        projectiles.removeIf(projectile -> projectile.exploded);
        advanceMines(world);
        advanceFire(world);
    }

    private void executeIntent(ShotIntent intent, WorldQuery world) {
        Vector3f muzzle = intent.kind.equals("machine-gun")?world.machineGunMuzzle(intent.ownerId,intent.barrel):world.muzzle(intent.ownerId);
        WorldQuery.Hit blocked = world.ray(world.weaponBase(intent.ownerId), muzzle, intent.ownerId);
        Vector3f forward = world.forward(intent.ownerId).normalizeLocal();
        var owner=session.vehicle(intent.ownerId);
        boolean aimedIntoGrinder=owner.grinding()&&owner.specialTargetId>=0
                &&session.vehicle(owner.specialTargetId).grabbedBy==owner.id;
        if(aimedIntoGrinder) {
            int targetId=owner.specialTargetId;
            Vector3f point=world.position(targetId).add(world.rotation(targetId).mult(world.profile(targetId).hullBoxes().getFirst().center()));
            forward=point.subtract(muzzle).normalizeLocal();
        }
        if (intent.kind.equals("machine-gun")) {
            Quaternion spread = new Quaternion().fromAngles(intent.pitch, intent.yaw, 0);
            Quaternion orientation=aimedIntoGrinder?new Quaternion().lookAt(forward,Vector3f.UNIT_Y):world.rotation(intent.ownerId);
            Vector3f direction = orientation.mult(spread.mult(Vector3f.UNIT_Z)).normalizeLocal();
            Vector3f end = muzzle.add(direction.mult(rules.machineGun().range()));
            WorldQuery.Hit hit = blocked == null ? world.ray(muzzle, end, intent.ownerId) : blocked;
            events.add(new GameEvent(GameEvent.Type.SHOT,intent.id,intent.ownerId,intent.ownerId,
                    hit==null?end:hit.point(),intent.kind,rules.machineGun().damage(),muzzle,Vector3f.ZERO));
            if(hit!=null)impact(intent.id,intent.ownerId,intent.kind,hit,muzzle);
            directArenaDamage(hit,intent.ownerId,rules.machineGun().damage(),intent.kind,intent.id);
            if (hit != null && hit.vehicleId() >= 0 && hit.vehicleId() != intent.ownerId) {
                queueContactDamage(hit.vehicleId(),intent.ownerId,directDamage(hit.vehicleId(),hit.point(),rules.machineGun().damage(),world),intent.kind,intent.id,hit.point(),hit.normal(),muzzle);
            }
            return;
        }
        float ttl=switch(intent.kind) {
            case "freeze"->rules.control().freezeTtlSeconds();case "napalm"->rules.napalm().ttlSeconds();
            case "cannon"->rules.cannon().ttlSeconds();case "ballistic"->8;
            default->rocketRules(intent.kind).ttlSeconds();
        };
        ProjectileState projectile = new ProjectileState(intent.id, intent.ownerId, intent.kind, muzzle,
                forward, Math.max(1, ticks(ttl)), intent.targetId);
        if(intent.kind.equals("napalm")) {
            launchNapalm(projectile,world);
        }
        if(intent.kind.equals("cannon"))projectile.velocity.set((aimedIntoGrinder?forward:forwardXZ(intent.ownerId,world)).mult(rules.cannon().speed())).addLocal(0,rules.cannon().upwardSpeed(),0);
        if(intent.kind.equals("ballistic"))launchCarrier(projectile,world);
        events.add(new GameEvent(GameEvent.Type.SHOT,intent.id,intent.ownerId,intent.ownerId,
                blocked==null?muzzle:blocked.point(),intent.kind,0,muzzle,Vector3f.ZERO));
        if (blocked == null) projectiles.add(projectile);
        else {
            projectile.position.set(blocked.point());
            if(intent.kind.equals("cannon")) {
                processCannonContact(projectile,blocked,world);
                if(!projectile.exploded)projectiles.add(projectile);
            }
            else if(intent.kind.equals("ballistic"))ballisticImpact(projectile,blocked,world);
            else if(intent.kind.equals("freeze")||intent.kind.equals("napalm"))utilityImpact(projectile,blocked,world);
            else explode(projectile, blocked, world);
        }
    }

    private void advanceUtilityProjectile(ProjectileState projectile,WorldQuery world) {
        boolean napalm=projectile.kind().equals("napalm");
        Vector3f displacement;
        if(napalm) {
            guideNapalm(projectile,world);
            displacement=projectile.velocity.mult(MatchSession.DT).addLocal(0,-.5f*rules.napalm().gravity()*MatchSession.DT*MatchSession.DT,0);
            projectile.velocity.y-=rules.napalm().gravity()*MatchSession.DT;
            projectile.direction.set(projectile.velocity).normalizeLocal();
        } else displacement=projectile.direction.mult(rules.control().freezeSpeed()*MatchSession.DT);
        Vector3f end=projectile.position.add(displacement);
        WorldQuery.Hit hit=world.sweep(projectile.position,end,napalm?rules.projectileRadius():rules.control().freezeRadius(),projectile.ownerId());
        projectile.remainingTicks--;
        projectile.ageTicks++;
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
        impact(projectile.id(),projectile.ownerId(),projectile.kind(),hit,projectile.previousPosition);
        if(projectile.kind().equals("freeze")) {
            if(hit.vehicleId()>=0&&hit.vehicleId()!=projectile.ownerId())controlHits.add(new ControlHit(hit.vehicleId(),projectile.ownerId(),projectile.id(),hit.point(),hit.normal()));
            return;
        }
        reservedFireZones--;
        Vector3f center=hit.point().add(hit.normal().mult(.05f));
        radialDamage(projectile.id(),projectile.ownerId(),"napalm",center,rules.napalm().radius(),rules.napalm().impactDamage(),rules.napalm().blast(),hit.normal(),world);
        WorldQuery.Support support=world.support(center,rules.napalm().supportDepth());
        if(support==null||support.normal().y<.6f)return;
        List<Vector3f> points=fireSurface(support,world);
        if(points.isEmpty())return;
        FireZone zone=new FireZone(projectile.id(),projectile.ownerId(),support,session.tick+ticks(rules.napalm().durationSeconds()),points);
        fireZones.add(zone);
        events.add(event(GameEvent.Type.FIRE_STARTED,zone.id,zone.ownerId,zone.ownerId,support.point(),"napalm-fire",rules.napalm().radius()));
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
            if(triggered) {radialDamage(mine.id,mine.ownerId,"mine",center,rules.mine().explosionRadius(),rules.mine().damage(),rules.mine().blast(),mine.support.normal(),world);iterator.remove();}
        }
    }
    private void radialDamage(long id,int owner,String kind,Vector3f center,float radius,float maximum,CombatRules.Blast blast,Vector3f normal,WorldQuery world) {
        events.add(new GameEvent(GameEvent.Type.EXPLOSION,id,-1,owner,center,kind,radius,Vector3f.ZERO,normal));
        radialArenaDamage(id,owner,kind,center,radius,maximum,null,world);
        for(VehicleState target:orderedVehicles) {
            if(!target.alive())continue;
            float falloff=Math.max(0,1-world.distanceToHull(target.id,center)/radius);
            if(falloff<=0||!exposed(center,target.id,world))continue;
            Vector3f point=world.closestHullPoint(target.id,center);
            queueContactDamage(target.id,owner,maximum*falloff*(target.id==owner?rules.ownerSplashMultiplier():1),kind,id,point,center.subtract(point).normalizeLocal());
            addBlast(target.id,owner,center,point,blast,falloff,world);
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
                Vector3f point=world.closestHullPoint(target.id,selected.support.point());
                queueContactDamage(target.id,selected.ownerId,maximumAmount,"napalm-fire",nextShotId++,point,selected.support.normal());elapsed=0;
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

    public int napalmAssistTarget(int vehicleId) {return napalmTargets.getOrDefault(vehicleId,-1);}
    private int assistTarget(int owner,WorldQuery world) {
        var assist=rules.napalm().assist();Vector3f origin=world.muzzle(owner),forward=world.forward(owner).normalizeLocal();
        float bestCosine=(float)Math.cos(radians(assist.coneDegrees())),bestDistance=Float.POSITIVE_INFINITY;int best=-1;
        for(var target:orderedVehicles) {
            if(!target.alive()||target.id==owner)continue;
            Vector3f delta=world.position(target.id).subtract(origin);float distance=delta.length();
            if(distance<assist.minimumRange()||distance>assist.maximumRange())continue;
            float cosine=forward.dot(delta)/distance;
            if(cosine<bestCosine||!world.visible(origin,world.position(target.id),target.id))continue;
            if(best<0||cosine>bestCosine||(Float.compare(cosine,bestCosine)==0&&distance<bestDistance)) {
                best=target.id;bestCosine=cosine;bestDistance=distance;
            }
        }
        return best;
    }
    public int ballisticTarget(int owner) {
        return lastWorld==null||!session.vehicle(owner).alive()?-1:selectBallisticTarget(session,owner,lastWorld);
    }
    /** Shared immediate selection for shot acceptance, HUD and AI; no Homing lock timer. */
    public static int selectBallisticTarget(MatchSession session,int owner,WorldQuery world) {
        Vector3f origin=world.position(owner),muzzle=world.muzzle(owner),forward=forwardXZ(owner,world);
        float bestDistance=Float.POSITIVE_INFINITY,range=session.combatRules.ballistic().maximumRange();int best=-1;
        for(var target:session.vehicles) {
            if(!target.alive()||target.id==owner)continue;
            Vector3f position=world.position(target.id),delta=position.subtract(origin);float distance=delta.lengthSquared();
            if(distance>range*range||forward.dot(delta)<=0||!world.visible(muzzle,position,target.id))continue;
            if(distance<bestDistance||(Float.compare(distance,bestDistance)==0&&(best<0||target.id<best))) {
                best=target.id;bestDistance=distance;
            }
        }
        return best;
    }
    private static Vector3f horizontal(Vector3f value) {Vector3f result=value.clone();result.y=0;return result;}
    private static Vector3f forwardXZ(int owner,WorldQuery world) {
        Vector3f forward=horizontal(world.forward(owner));
        if(forward.lengthSquared()<1e-6f)forward.set(Vector3f.UNIT_Z);
        return forward.normalizeLocal();
    }
    private Vector3f napalmAim(int target,WorldQuery world) {
        Vector3f lead=horizontal(world.velocity(target)).multLocal(rules.napalm().assist().leadSeconds());
        if(lead.length()>rules.napalm().assist().maximumLead())lead.normalizeLocal().multLocal(rules.napalm().assist().maximumLead());
        return world.position(target).add(lead);
    }
    private static Vector3f groundPoint(Vector3f point,WorldQuery world) {
        WorldQuery.Support support=world.support(point.add(0,35,0),80);
        return support==null?point.clone():support.point().clone();
    }
    private void launchNapalm(ProjectileState projectile,WorldQuery world) {
        var assist=rules.napalm().assist();
        Vector3f aim=projectile.targetId>=0?napalmAim(projectile.targetId,world):groundPoint(
                projectile.position.add(forwardXZ(projectile.ownerId(),world).mult(assist.fallbackRange())),world);
        Vector3f displacement=aim.subtract(projectile.position);
        float time=Math.clamp(horizontal(displacement).length()/rules.napalm().speed(),assist.minimumFlightSeconds(),assist.maximumFlightSeconds());
        projectile.velocity.set(displacement.divide(time));projectile.velocity.y+=.5f*rules.napalm().gravity()*time;
        projectile.originalVelocity.set(projectile.velocity);projectile.direction.set(projectile.velocity).normalizeLocal();
    }
    private void guideNapalm(ProjectileState projectile,WorldQuery world) {
        var assist=rules.napalm().assist();
        if(projectile.targetId>=0) {
            if(!session.vehicle(projectile.targetId).alive())projectile.targetId=-1;
            else {
                if(world.visible(projectile.position,world.position(projectile.targetId),projectile.targetId))projectile.occludedTicks=0;
                else projectile.occludedTicks++;
                if(projectile.occludedTicks>ticks(assist.occlusionSeconds()))projectile.targetId=-1;
            }
        }
        Vector3f base=horizontal(projectile.originalVelocity);float speed=base.length();if(speed<1e-5f)return;
        float baseline=(float)Math.atan2(base.x,base.z),heading=(float)Math.atan2(projectile.velocity.x,projectile.velocity.z);
        float desired=baseline;
        if(projectile.targetId>=0) {
            Vector3f aim=napalmAim(projectile.targetId,world).subtract(projectile.position);
            if(horizontal(aim).lengthSquared()>1e-6f)desired=(float)Math.atan2(aim.x,aim.z);
        }
        float rate=radians(assist.turnDegreesPerSecond()),turn=rate*MatchSession.DT;
        float candidate=heading+Math.clamp(angleDifference(desired,heading),-turn,turn);
        float returning=heading+Math.clamp(angleDifference(baseline,heading),-turn,turn);
        // Reserve enough of the 3m envelope to turn back at the same bounded rate.
        // Losing the target disables tracking permanently; this bound still holds.
        if(!napalmHeadingFits(projectile,candidate,baseline,speed,rate,assist.maximumDeviation())) {
            float low=0,high=1;
            for(int i=0;i<12;i++) {
                float middle=(low+high)*.5f;
                float trial=returning+angleDifference(candidate,returning)*middle;
                if(napalmHeadingFits(projectile,trial,baseline,speed,rate,assist.maximumDeviation()))low=middle;else high=middle;
            }
            candidate=returning+angleDifference(candidate,returning)*low;
        }
        projectile.velocity.x=(float)Math.sin(candidate)*speed;projectile.velocity.z=(float)Math.cos(candidate)*speed;
    }
    private static float angleDifference(float target,float source) {return (float)Math.atan2(Math.sin(target-source),Math.cos(target-source));}
    private static boolean napalmHeadingFits(ProjectileState p,float heading,float baseline,float speed,float rate,float maximum) {
        Vector3f next=p.position.add((float)Math.sin(heading)*speed*MatchSession.DT,0,(float)Math.cos(heading)*speed*MatchSession.DT);
        Vector3f original=p.launchPosition.add(p.originalVelocity.mult((p.ageTicks+1)*MatchSession.DT));
        float offset=horizontal(next.subtract(original)).length(),angle=Math.abs(angleDifference(heading,baseline));
        float side=speed/rate*(1-(float)Math.cos(angle)),back=speed/rate*(angle-(float)Math.sin(angle));
        return offset+(float)Math.sqrt(side*side+back*back)<=maximum-.02f;
    }

    private void advanceCannon(ProjectileState projectile,WorldQuery world) {
        var cannon=rules.cannon();float remaining=MatchSession.DT;
        Vector3f lastContact=null;
        for(int contacts=0;contacts<3&&remaining>1e-7f&&!projectile.exploded;contacts++) {
            Vector3f end=projectile.position.add(projectile.velocity.mult(remaining)).addLocal(0,-.5f*cannon.gravity()*remaining*remaining,0);
            WorldQuery.Hit hit=world.sweep(projectile.position,end,cannon.radius(),projectile.ricochets==0?projectile.ownerId():-1,
                    1-remaining/MatchSession.DT,1);
            if(hit==null) {
                projectile.position.set(end);projectile.velocity.y-=cannon.gravity()*remaining;remaining=0;break;
            }
            float elapsed=remaining*Math.clamp(hit.fraction(),0,1);projectile.velocity.y-=cannon.gravity()*elapsed;
            remaining-=elapsed;
            Vector3f normal=hit.normal().normalize();
            if(lastContact!=null&&hit.point().distanceSquared(lastContact)<1e-5f&&hit.fraction()<1e-5f) {
                // A numerical zero-distance re-contact must never produce another blast.
                projectile.position.set(hit.point().add(normal.mult(cannon.radius()+.01f)));
                if(contacts==2)projectile.exploded=true;
                continue;
            }
            if(processCannonContact(projectile,hit,world))lastContact=hit.point().clone();
        }
        projectile.remainingTicks--;projectile.ageTicks++;
        projectile.direction.set(projectile.velocity).normalizeLocal();
        if(!projectile.exploded&&projectile.remainingTicks<=0)cannonImpact(projectile,null,false,world);
    }
    private boolean processCannonContact(ProjectileState projectile,WorldQuery.Hit hit,WorldQuery world) {
        var cannon=rules.cannon();Vector3f normal=hit.normal().normalize();
        Vector3f reflected=projectile.velocity.subtract(normal.mult(projectile.velocity.dot(normal))).multLocal(cannon.tangentRetention())
                .subtractLocal(normal.mult(projectile.velocity.dot(normal)*cannon.normalRestitution()));
        boolean bounce=hit.vehicleId()<0&&normal.lengthSquared()>.5f&&projectile.ricochets<cannon.ricochets()
                &&reflected.length()>=cannon.minimumSpeed();
        cannonImpact(projectile,hit,bounce,world);
        if(bounce) {
            projectile.ricochets++;projectile.velocity.set(reflected);
            projectile.position.set(hit.point().add(normal.mult(cannon.radius()+.005f)));
        }
        return bounce;
    }
    private void cannonImpact(ProjectileState projectile,WorldQuery.Hit hit,boolean ricochet,WorldQuery world) {
        if(projectile.exploded)return;
        var cannon=rules.cannon();long id=nextShotId++;
        Vector3f point=hit==null?projectile.position.clone():hit.point();
        Vector3f normal=hit==null?new Vector3f():hit.normal();
        Vector3f center=point.add(normal.mult(rules.explosionSurfaceOffset()));
        int direct=hit==null?-1:hit.vehicleId();String kind=ricochet?"cannon-ricochet":"cannon";
        if(hit!=null)impact(id,projectile.ownerId(),kind,hit,projectile.previousPosition);
        float radius=ricochet?cannon.ricochetRadius():cannon.splashRadius();
        events.add(new GameEvent(GameEvent.Type.EXPLOSION,id,direct,projectile.ownerId(),center,kind,radius,projectile.previousPosition,normal));
        directArenaDamage(hit,projectile.ownerId(),cannon.directDamage(),kind,id);
        radialArenaDamage(id,projectile.ownerId(),kind,center,radius,ricochet?cannon.ricochetDamage():cannon.splashDamage(),
                hit==null?null:hit.objectId(),world);
        for(var target:orderedVehicles) {
            if(!target.alive())continue;
            if(target.id==direct) {
                float owner=target.id==projectile.ownerId()?rules.ownerSplashMultiplier():1;
                queueContactDamage(target.id,projectile.ownerId(),directDamage(target.id,point,cannon.directDamage()*owner,world),"cannon",id,point,normal);
                if(target.protectionTicks==0) {
                    float multiplier=owner*(target.shieldTicks>0?rules.control().shieldDamageMultiplier():1)
                            *projectile.velocity.length()/cannon.speed();
                    Vector3f linear=horizontal(projectile.velocity);
                    if(linear.lengthSquared()>0)linear.normalizeLocal().multLocal(cannon.horizontalImpulse()*multiplier);
                    linear.y=cannon.upwardImpulse()*multiplier;
                    BlastSum sum=blasts.computeIfAbsent(target.id,ignored->new BlastSum());sum.heavy=true;
                    sum.linear.addLocal(linear);sum.torque.addLocal(point.subtract(world.position(target.id)).cross(linear));
                    target.heavyImpactPending=true;
                }
                continue;
            }
            float falloff=Math.max(0,1-world.distanceToHull(target.id,center)/radius);
            if(falloff<=0||!exposed(center,target.id,world))continue;
            Vector3f hullPoint=world.closestHullPoint(target.id,center);
            float amount=(ricochet?cannon.ricochetDamage():cannon.splashDamage())*falloff
                    *(target.id==projectile.ownerId()?rules.ownerSplashMultiplier():1);
            queueContactDamage(target.id,projectile.ownerId(),amount,"cannon",id,hullPoint,center.subtract(hullPoint).normalizeLocal());
            addBlast(target.id,projectile.ownerId(),center,hullPoint,cannon.splashBlast(),falloff,world);
        }
        if(!ricochet)projectile.exploded=true;
    }

    private void launchCarrier(ProjectileState carrier,WorldQuery world) {
        var ballistic=rules.ballistic();
        Vector3f area=groundPoint(carrier.targetId>=0?world.position(carrier.targetId):
                carrier.position.add(forwardXZ(carrier.ownerId(),world).mult(ballistic.fallbackRange())),world);
        float duration=Math.clamp(horizontal(area.subtract(carrier.position)).length()/ballistic.carrierSpeed(),
                ballistic.minimumCarrierSeconds(),ballistic.maximumCarrierSeconds());
        int flightTicks=Math.max(1,ticks(duration));duration=flightTicks*MatchSession.DT;
        Salvo salvo=new Salvo(carrier,area,flightTicks);salvo.station.set(area).addLocal(0,ballistic.carrierHeight(),0);
        carrier.velocity.set(salvo.station.subtract(carrier.position).divide(duration)).addLocal(0,.5f*ballistic.gravity()*duration,0);
        carrier.direction.set(carrier.velocity).normalizeLocal();salvos.put(carrier.id(),salvo);
    }
    private void advanceCarrier(ProjectileState carrier,WorldQuery world) {
        Salvo salvo=salvos.get(carrier.id());if(salvo==null) {carrier.exploded=true;return;}
        if(carrier.ageTicks<salvo.arrivalTicks) {
            Vector3f end=carrier.position.add(carrier.velocity.mult(MatchSession.DT)).addLocal(0,-.5f*rules.ballistic().gravity()*MatchSession.DT*MatchSession.DT,0);
            WorldQuery.Hit hit=world.sweep(carrier.position,end,rules.projectileRadius(),carrier.ownerId());
            carrier.ageTicks++;carrier.remainingTicks--;carrier.velocity.y-=rules.ballistic().gravity()*MatchSession.DT;
            if(hit!=null) {ballisticImpact(carrier,hit,world);return;}
            carrier.position.set(end);carrier.direction.set(carrier.velocity).normalizeLocal();
            if(carrier.ageTicks<salvo.arrivalTicks)return;
            carrier.position.set(salvo.station);carrier.velocity.zero();
            salvo.nextPlanTick=session.tick;
        }
        if(salvo.planned<rules.ballistic().charges()&&session.tick>=salvo.nextPlanTick) {
            planCharge(salvo,world);salvo.planned++;
            salvo.nextPlanTick=session.tick+ticks(rules.ballistic().releaseIntervalSeconds());
        }
        if(salvo.released==rules.ballistic().charges()) {carrier.exploded=true;salvos.remove(carrier.id());}
    }
    private void planCharge(Salvo salvo,WorldQuery world) {
        var ballistic=rules.ballistic();
        if(salvo.targetId>=0) {
            if(!session.vehicle(salvo.targetId).alive()||!world.visible(salvo.carrier.position,world.position(salvo.targetId),salvo.targetId))salvo.targetId=-1;
            else salvo.area.set(groundPoint(world.position(salvo.targetId),world));
        }
        float spread=ballistic.spread();Vector3f offset=switch(salvo.planned) {
            case 0->new Vector3f(-spread,0,0);case 1->new Vector3f(0,0,spread);
            case 2->new Vector3f(spread,0,0);default->new Vector3f(0,0,-spread);
        };
        Vector3f origin=salvo.carrier.position.clone();
        Vector3f aim=groundPoint(salvo.targetId<0?salvo.area.add(offset)
                :ballisticAim(salvo.targetId,origin,0,ballistic.minimumWarningSeconds(),offset,world),world)
                .addLocal(0,rules.projectileRadius(),0);
        float duration=Math.max(.5f,(float)Math.sqrt(2*Math.max(.1f,origin.y-aim.y)/ballistic.gravity()));
        Vector3f velocity=aim.subtract(origin).divide(duration).addLocal(0,.5f*ballistic.gravity()*duration,0);
        int lifeTicks=Math.min(720,ticks(duration+1));
        Prediction prediction=predictCharge(origin,velocity,lifeTicks,world);
        if(prediction==null) {reservedCharges--;salvo.released++;return;}
        long id=nextShotId++,release=session.tick+ticks(ballistic.minimumWarningSeconds());
        FallingCharge charge=new FallingCharge(id,salvo.carrier.ownerId(),salvo.carrier.id(),salvo.targetId,
                origin,velocity,offset,prediction,release,lifeTicks);
        pendingCharges.add(charge);warnings.put(id,charge);
    }
    private record Prediction(WorldQuery.Hit hit,int ticks) {}
    private Prediction predictCharge(Vector3f origin,Vector3f initialVelocity,int lifeTicks,WorldQuery world) {
        Vector3f position=origin.clone(),velocity=initialVelocity.clone();
        // Preview the current ballistic path, not a promise of a fixed impact point.
        // Six-tick sweeps bound preview cost; real contacts still sweep every native step.
        for(int tick=0;tick<lifeTicks;) {
            int steps=Math.min(6,lifeTicks-tick);float dt=steps*MatchSession.DT;
            Vector3f end=position.add(velocity.mult(dt)).addLocal(0,-.5f*rules.ballistic().gravity()*dt*dt,0);
            WorldQuery.Hit hit=world.staticSweep(position,end,rules.projectileRadius());
            if(hit!=null)return new Prediction(hit,tick+Math.max(1,Math.round(steps*hit.fraction())));
            position.set(end);velocity.y-=rules.ballistic().gravity()*dt;tick+=steps;
        }
        return null;
    }
    private void releaseCharges(WorldQuery world) {
        for(var iterator=pendingCharges.iterator();iterator.hasNext();) {
            FallingCharge charge=iterator.next();if(session.tick<charge.releaseTick)continue;
            Salvo salvo=salvos.get(charge.carrierId);
            if(salvo==null)throw new IllegalStateException("Orphaned ballistic reservation");
            int target=salvo.targetId<0?-1:charge.targetId;
            if(target>=0&&(!session.vehicle(target).alive()||!world.visible(charge.origin,world.position(target),target)))target=-1;
            ProjectileState projectile=new ProjectileState(charge.id,charge.ownerId,"ballistic-fall",charge.origin,
                    charge.velocity,charge.lifeTicks,target);
            projectile.velocity.set(charge.velocity);projectiles.add(projectile);
            events.add(new GameEvent(GameEvent.Type.SHOT,charge.id,charge.ownerId,charge.ownerId,charge.origin,
                    "ballistic-fall",0,charge.origin,Vector3f.ZERO));
            reservedCharges--;salvo.released++;iterator.remove();
            if(salvo.released==rules.ballistic().charges()) {salvo.carrier.exploded=true;salvos.remove(charge.carrierId);}
        }
    }
    private void advanceCharge(ProjectileState charge,WorldQuery world) {
        FallingCharge warning=warnings.get(charge.id());
        guideCharge(charge,warning.offset,world);
        Vector3f end=charge.position.add(charge.velocity.mult(MatchSession.DT)).addLocal(0,-.5f*rules.ballistic().gravity()*MatchSession.DT*MatchSession.DT,0);
        WorldQuery.Hit hit=world.sweep(charge.position,end,rules.projectileRadius(),charge.ownerId());
        charge.remainingTicks--;charge.ageTicks++;charge.velocity.y-=rules.ballistic().gravity()*MatchSession.DT;
        charge.direction.set(charge.velocity).normalizeLocal();
        if(hit!=null)ballisticImpact(charge,hit,world);
        else {
            charge.position.set(end);
            if(charge.remainingTicks<=0) {charge.exploded=true;warnings.remove(charge.id());}
            else if(charge.ageTicks%12==1||(warning.impactTick>session.tick&&warning.impactTick-session.tick<=12)) {
                Prediction prediction=predictCharge(charge.position,charge.velocity,charge.remainingTicks,world);
                if(prediction!=null) {
                    warning.point.set(prediction.hit.point());warning.normal.set(prediction.hit.normal());
                    warning.impactTick=session.tick+1+prediction.ticks;
                } else warning.impactTick=session.tick;
            }
        }
    }
    private void guideCharge(ProjectileState charge,Vector3f offset,WorldQuery world) {
        if(charge.targetId<0)return;
        int target=charge.targetId;
        if(!session.vehicle(target).alive()||!world.visible(charge.position,world.position(target),target)) {
            charge.targetId=-1;return;
        }
        Vector3f aim=ballisticAim(target,charge.position,charge.velocity.y,0,offset,world);
        // It remains a falling charge: no climbing back to a target that has jumped over it.
        aim.y=Math.min(aim.y,charge.position.y-.1f);
        Vector3f desired=aim.subtract(charge.position).normalizeLocal();
        float speed=charge.velocity.length();if(speed<.0001f)return;
        Vector3f direction=charge.velocity.divide(speed);
        float angle=(float)Math.acos(Math.clamp(direction.dot(desired),-1,1));
        float permitted=radians(rules.ballistic().turnDegreesPerSecond())*MatchSession.DT;
        if(angle<=permitted)charge.velocity.set(desired).multLocal(speed);
        else {
            Vector3f axis=direction.cross(desired).normalizeLocal();
            if(axis.lengthSquared()>0)new Quaternion().fromAngleAxis(permitted,axis).mult(charge.velocity,charge.velocity);
        }
    }
    private Vector3f ballisticAim(int target,Vector3f origin,float verticalSpeed,float delay,Vector3f offset,WorldQuery world) {
        var ballistic=rules.ballistic();Vector3f position=world.position(target);
        float height=Math.max(.1f,origin.y-position.y),gravity=ballistic.gravity();
        float flight=(verticalSpeed+(float)Math.sqrt(verticalSpeed*verticalSpeed+2*gravity*height))/gravity;
        Vector3f lead=horizontal(world.velocity(target)).multLocal(Math.min(ballistic.maximumLeadSeconds(),flight+delay));
        if(lead.length()>ballistic.maximumLead())lead.normalizeLocal().multLocal(ballistic.maximumLead());
        return position.add(lead).addLocal(offset);
    }
    private void ballisticImpact(ProjectileState projectile,WorldQuery.Hit hit,WorldQuery world) {
        if(projectile.exploded)return;projectile.exploded=true;projectile.position.set(hit.point());
        Salvo salvo=salvos.remove(projectile.id());
        if(salvo!=null) {
            reservedCharges-=rules.ballistic().charges()-salvo.released;
            pendingCharges.removeIf(charge->{if(charge.carrierId!=projectile.id())return false;warnings.remove(charge.id);return true;});
        }
        warnings.remove(projectile.id());
        impact(projectile.id(),projectile.ownerId(),projectile.kind(),hit,projectile.previousPosition);
        radialDamage(projectile.id(),projectile.ownerId(),"ballistic",hit.point().add(hit.normal().mult(rules.explosionSurfaceOffset())),
                rules.ballistic().radius(),rules.ballistic().damage(),rules.ballistic().blast(),hit.normal(),world);
    }
    public List<BallisticWarningView> ballisticWarnings() {
        return warnings.values().stream().filter(warning->warning.impactTick>session.tick)
                .map(warning->new BallisticWarningView(warning.id,warning.ownerId,warning.point,warning.normal,
                rules.ballistic().radius(),(int)Math.max(0,warning.impactTick-session.tick))).toList();
    }
    public int reservedBallisticCharges() {return reservedCharges;}
    public int occupiedProjectileSlots() {
        return projectiles.size()+reservedCharges+(int)intents.stream().filter(intent->!intent.kind.equals("machine-gun")).count();
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
        if(hit!=null)impact(projectile.id(),projectile.ownerId(),projectile.kind(),hit,projectile.previousPosition);
        Vector3f center = projectile.position.clone();
        if (hit != null && hit.normal() != null && hit.normal().lengthSquared() > 0) {
            center.addLocal(hit.normal().normalize().mult(rules.explosionSurfaceOffset()));
        }
        CombatRules.Rocket rocket = rocketRules(projectile.kind());
        int directTarget = hit == null ? -1 : hit.vehicleId();
        events.add(event(GameEvent.Type.EXPLOSION, projectile.id(), directTarget, projectile.ownerId(),
                center, projectile.kind(), rocket.explosionRadius()));
        directArenaDamage(hit,projectile.ownerId(),rocket.directDamage(),projectile.kind(),projectile.id());
        radialArenaDamage(projectile.id(),projectile.ownerId(),projectile.kind(),center,rocket.explosionRadius(),rocket.splashDamage(),
                hit==null?null:hit.objectId(),world);
        for (VehicleState target : orderedVehicles) {
            if (!target.alive()) continue;
            boolean direct = target.id == directTarget && target.id != projectile.ownerId();
            float distance = world.distanceToHull(target.id, center);
            float falloff = Math.max(0, 1 - distance / rocket.explosionRadius());
            if (!direct && (falloff <= 0 || !exposed(center, target.id, world))) continue;
            float amount = direct ? rocket.directDamage() : rocket.splashDamage() * falloff;
            if (target.id == projectile.ownerId()) amount *= rules.ownerSplashMultiplier();
            Vector3f point=direct?hit.point():world.closestHullPoint(target.id,center);
            Vector3f normal=direct?hit.normal():center.subtract(point).normalizeLocal();
            queueContactDamage(target.id,projectile.ownerId(),direct?directDamage(target.id,point,amount,world):amount,projectile.kind(),projectile.id(),point,normal);
            addBlast(target.id,projectile.ownerId(),center,point,rocket.blast(),direct?1:falloff,world);
        }
    }

    private boolean exposed(Vector3f center, int targetId, WorldQuery world) {
        for (Vector3f sample : world.hullVisibilityPoints(targetId)) {
            if (world.visible(center, sample, targetId)) return true;
        }
        return false;
    }

    private void addBlast(int targetId,int sourceId,Vector3f center,Vector3f point,CombatRules.Blast blast,float falloff,WorldQuery world) {
        VehicleState target=session.vehicle(targetId);
        if(target.protectionTicks>0||falloff<=0)return;
        float multiplier=falloff*(targetId==sourceId?rules.ownerSplashMultiplier():1)
                *(target.shieldTicks>0?rules.control().shieldDamageMultiplier():1);
        Vector3f delta=world.position(targetId).subtract(center);delta.y=0;
        if(delta.lengthSquared()>0)delta.normalizeLocal().multLocal(blast.horizontalDeltaSpeed()*multiplier);
        delta.y=blast.upwardDeltaSpeed()*multiplier;
        Vector3f linear=delta.mult(world.mass(targetId));
        BlastSum sum=blasts.computeIfAbsent(targetId,ignored->new BlastSum());
        sum.linear.addLocal(linear);sum.torque.addLocal(point.subtract(world.position(targetId)).cross(linear));
    }
    private void impact(long id,int owner,String kind,WorldQuery.Hit hit,Vector3f origin) {
        var event=new GameEvent(GameEvent.Type.IMPACT,id,hit.vehicleId(),owner,hit.point(),kind,0,origin,hit.normal());
        events.add(hit.objectId()==null?event:event.forObject(hit.objectId()));
    }
    private void shieldHit(int target,int source,long id,Vector3f point,Vector3f normal,Vector3f origin,String kind,float incoming) {
        if(session.tick<shieldFeedbackAfter.getOrDefault(target,Long.MIN_VALUE))return;
        shieldFeedbackAfter.put(target,session.tick+12);
        events.add(new GameEvent(GameEvent.Type.SHIELD_HIT,id,target,source,point,kind,incoming,origin,normal));
    }

    /** External event ids must be unique for the session and not collide with positive combat shot ids. */
    public void queueDamage(int targetId, int sourceId, float amount, String cause, long sourceEvent) {
        queueContactDamage(targetId,sourceId,amount,cause,sourceEvent,null,null);
    }
    private void queueContactDamage(int targetId,int sourceId,float amount,String cause,long sourceEvent,Vector3f point,Vector3f normal) {
        queueContactDamage(targetId,sourceId,amount,cause,sourceEvent,point,normal,Vector3f.ZERO);
    }
    private void queueContactDamage(int targetId,int sourceId,float amount,String cause,long sourceEvent,Vector3f point,Vector3f normal,Vector3f origin) {
        if (!Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Damage must be finite and nonnegative");
        if (targetId < 0 || targetId >= session.vehicles.size() || sourceId < -1 || sourceId >= session.vehicles.size()) {
            throw new IllegalArgumentException("Invalid damage participant");
        }
        Objects.requireNonNull(cause, "cause");
        if(session.phase==MatchSession.Phase.BOSS_ENTRY&&sourceId==session.bossParticipantId&&sourceId>=0)return;
        if (amount == 0 || !seenDamage.add(new DamageKey(sourceEvent, targetId))) return;
        damage.add(new Damage(targetId, sourceId, amount, cause, sourceEvent,point,normal,origin));
    }

    public void queueRam(int first, int second, float closingSpeed,Vector3f point,Vector3f normal) {
        if (!Float.isFinite(closingSpeed) || closingSpeed < 0) throw new IllegalArgumentException("Closing speed must be finite and nonnegative");
        if (first == second || first < 0 || second < 0 || first >= session.vehicles.size() || second >= session.vehicles.size()) return;
        // Audible body contact is independent of the higher gameplay damage threshold.
        if (closingSpeed < 1.25f) return;
        Pair pair = Pair.of(first, second);
        if(session.tick>=ramFeedbackAt.getOrDefault(pair,Long.MIN_VALUE)) {
            events.add(new GameEvent(GameEvent.Type.RAM,nextShotId++,pair.first,pair.second,point,"ram",closingSpeed,
                    Vector3f.ZERO,first==pair.first?normal:normal.negate()));
            ramFeedbackAt.put(pair,session.tick+ticks(.15f));
        }
        if (closingSpeed <= rules.ram().minimumClosingSpeed()) return;
        if (session.tick < ramReadyAt.getOrDefault(pair, Long.MIN_VALUE)) return;
        ramSpeeds.merge(pair, closingSpeed, Math::max);
    }

    /** One resolution after projectiles, contacts, recovery penalties, and hazards have queued all damage. */
    public void resolveDamage(WorldQuery world) {
        if (session.outcome != MatchSession.Outcome.NONE) {
            damage.clear();
            arenaDamage.clear();arenaRamSpeeds.clear();
            controlHits.clear();
            expiredShields.clear();
            ramSpeeds.clear();
            blasts.clear();
            return;
        }
        advanceSpecials(world);
        resolveArenaDamage();
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
        for(int id:expiredShields)if(session.vehicle(id).alive())
            events.add(event(GameEvent.Type.SHIELD_ENDED,nextShotId++,id,id,world.position(id),"shield",0));
        expiredShields.clear();
        resolveControl(world);
        for(var entry:blasts.entrySet()) {
            VehicleState target=session.vehicle(entry.getKey());
            if(destroyed.contains(target.id)||target.protectionTicks>0)continue;
            if(!target.alive())world.immobilize(target.id,false);
            BlastSum blast=entry.getValue();float mass=world.mass(target.id);
            CombatRules.BlastLimits limits=blast.heavy?rules.heavyBlastLimits():rules.blastLimits();
            float horizontal=(float)Math.sqrt(blast.linear.x*blast.linear.x+blast.linear.z*blast.linear.z);
            float maximumHorizontal=limits.horizontalDeltaSpeed()*mass;
            if(horizontal>maximumHorizontal) {blast.linear.x*=maximumHorizontal/horizontal;blast.linear.z*=maximumHorizontal/horizontal;}
            blast.linear.y=Math.min(blast.linear.y,limits.upwardDeltaSpeed()*mass);
            world.impulse(target.id,blast.linear,blast.torque,limits.angularDeltaSpeed());
        }
        blasts.clear();
        for (VehicleState target : orderedVehicles) {
            if (target.alive() || !destroyed.add(target.id)) continue;
            releaseControl(target,world,false);
            cancelSpecial(target,world);
            world.immobilize(target.id,false);
            target.frozenTicks=target.shieldTicks=target.controlImmunityTicks=0;
            target.heavyImpactPending=false;target.impactStabilizerTicks=0;
            int killer = target.lastAttacker >= 0 && session.tick - target.lastAttackTick <= ticks(rules.killCreditSeconds())
                    ? target.lastAttacker : -1;
            if (killer >= 0 && killer != target.id) session.vehicle(killer).eliminations++;
            events.add(event(GameEvent.Type.DESTROYED, target.id, target.id, killer, world.position(target.id), "destroyed", 0));
            locks.remove(target.id);
        }
    }

    private void applyDamage(int targetId, List<Damage> requests, WorldQuery world) {
        VehicleState target = session.vehicle(targetId);
        if(target.shieldTicks>0)for(Damage request:requests)if(request.point!=null)
            shieldHit(targetId,request.sourceId,request.eventId,request.point,request.normal,request.origin,request.cause,request.amount);
        if(target.shieldTicks>0)requests=requests.stream().map(request->
                request.cause.equals("recovery")||request.cause.equals("out-of-bounds")?request:
                        new Damage(request.targetId,request.sourceId,request.amount*rules.control().shieldDamageMultiplier(),request.cause,request.eventId,request.point,request.normal,request.origin)).toList();
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
                if(!target.alive())continue;
                boolean controlled=target.frozenTicks>0;
                target.frozenTicks=Math.max(0,target.frozenTicks-1);
                if(controlled&&target.frozenTicks==0) {
                    target.controlImmunityTicks=ticks(rules.control().immunitySeconds());world.immobilize(target.id,false);
                    events.add(event(GameEvent.Type.CONTROL_ENDED,nextShotId++,target.id,target.id,world.position(target.id),"freeze",0));
                }
            }
        }
        controlHits.sort(Comparator.comparingLong(ControlHit::eventId));
        for(ControlHit hit:controlHits) {
            VehicleState target=session.vehicle(hit.targetId);
            if(!target.alive()||target.protectionTicks>0)continue;
            if(target.shieldTicks>0) {shieldHit(target.id,hit.sourceId,hit.eventId,hit.point,hit.normal,Vector3f.ZERO,"freeze",0);continue;}
            if(target.controlled()||target.controlImmunityTicks>0)continue;
            cancelSpecial(target,world);
            target.frozenTicks=ticks(rules.control().freezeSeconds());world.immobilize(target.id,true);
            events.add(new GameEvent(GameEvent.Type.FREEZE,hit.eventId,target.id,hit.sourceId,hit.point,"freeze",rules.control().freezeSeconds(),Vector3f.ZERO,hit.normal));
        }
        controlHits.clear();
    }
    public void endControl(VehicleState target,WorldQuery world) { releaseControl(target,world,true); }
    public void cancelControl(VehicleState target,WorldQuery world) { releaseControl(target,world,false);cancelSpecial(target,world); }
    private void releaseControl(VehicleState target,WorldQuery world,boolean feedback) {
        if(target.grabbedBy>=0)cancelSpecial(session.vehicle(target.grabbedBy),world);
        if(target.frozenTicks>0) {
            target.frozenTicks=0;target.controlImmunityTicks=ticks(rules.control().immunitySeconds());
            if(feedback)events.add(event(GameEvent.Type.CONTROL_ENDED,nextShotId++,target.id,target.id,world.position(target.id),"freeze",0));
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
        List<GameEvent> result = events.stream().map(e->e.inSession(session.sessionId)).toList();
        events.clear();
        return result;
    }

    public void clear() {
        if(lastWorld!=null)for(var vehicle:orderedVehicles) {
            cancelSpecial(vehicle,lastWorld);lastWorld.immobilize(vehicle.id,false);
        }
        for(var vehicle:orderedVehicles) {
            vehicle.frozenTicks=vehicle.shieldTicks=vehicle.controlImmunityTicks=vehicle.impactStabilizerTicks=0;
            vehicle.clearSpecial();vehicle.grabbedBy=-1;
            vehicle.heavyImpactPending=false;
        }
        mines.clear();fireZones.clear();specialBombs.clear();controlHits.clear();fireExposureTicks.clear();reservedFireZones=0;
        projectiles.clear();
        salvos.clear();pendingCharges.clear();warnings.clear();napalmTargets.clear();reservedCharges=0;
        intents.clear();
        damage.clear();
        events.clear();
        locks.clear();
        spreadRandom.clear();
        emptyFeedbackAfter.clear();nextBarrels.clear();shieldFeedbackAfter.clear();expiredShields.clear();
        ramSpeeds.clear();
        ramReadyAt.clear();
        ramFeedbackAt.clear();
        blasts.clear();
        seenDamage.clear();
        arenaDamage.clear();seenArenaDamage.clear();arenaRamSpeeds.clear();arenaRamReadyAt.clear();
        arenaTargetSource=List::of;arenaDamageSink=(geometry,source,amount,cause,event)->{};arenaTargets=Map.of();
        arenaTargetTick=arenaDamageTick=Long.MIN_VALUE;
        destroyed.clear();
    }

    private CombatRules.Rocket rocketRules(String kind) {
        return switch(kind) {case "homing"->rules.homing();case "power"->rules.power();default->throw new IllegalArgumentException("Not a rocket: "+kind);};
    }
    private static float radians(float degrees) { return (float) Math.toRadians(degrees); }
    private static GameEvent event(GameEvent.Type type, long id, int subject, int source, Vector3f position, String kind, float value) {
        return new GameEvent(type, id, subject, source, position, kind, value);
    }
}
