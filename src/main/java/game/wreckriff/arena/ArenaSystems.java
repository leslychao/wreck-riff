package game.wreckriff.arena;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.ProgressStore;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import java.util.*;

/** Tick-driven hazard and atomic, surface-aware resource collection. */
public final class ArenaSystems {
    public enum HazardPhase { OFF, WARNING, ACTIVE }
    @FunctionalInterface public interface DamageSink {
        void damage(int targetId,float amount,String cause,long eventId);
    }
    private final MatchSession session;
    private final ArenaDefinition definition;
    private final ArenaLaunches launches;
    private final Map<String,Long> returnsAt=new HashMap<>();
    private final Map<String,ProgressStore.ObjectState> objects=new LinkedHashMap<>();
    private long hazardTicks;
    private final Map<String,Map<Integer,Integer>> hazardExposure=new HashMap<>();
    private final List<GameEvent> events=new ArrayList<>();
    private long lastHazardTick=Long.MIN_VALUE,lastPickupTick=Long.MIN_VALUE;

    public ArenaSystems(MatchSession session,ArenaDefinition definition) {
        this.session=session; this.definition=definition;
        launches=new ArenaLaunches(session,definition);
        definition.destructibles().forEach(object->objects.put(object.id(),new ProgressStore.ObjectState(object.maximumHp(),false,false)));
    }
    public ArenaLaunches launches() { return launches; }
    public void beforePhysics(PhysicsWorld world,Map<Integer,VehicleController> drivers) { launches.beforePhysics(world,drivers); }
    public void afterPhysics(PhysicsWorld world,Map<Integer,VehicleController> drivers) { launches.afterPhysics(world,drivers); }
    public HazardPhase hazardPhase() {
        return definition.hazards().stream().map(h->hazardPhase(h.id())).max(Comparator.naturalOrder()).orElse(HazardPhase.OFF);
    }
    public HazardPhase hazardPhase(String id) {
        return phaseAt(hazardClock(),definition.hazards(),id);
    }
    private long hazardClock() {return session.mode==MatchSession.Mode.LEGACY?session.tick:hazardTicks;}
    public static HazardPhase phaseAt(long tick,List<ArenaDefinition.Hazard> hazards,String id) {
        long period=hazards.stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum();
        long phase=period==0?0:tick%period;
        ArenaDefinition.Hazard hazard=null;
        for(var candidate:hazards) {
            if(candidate.id().equals(id)) {hazard=candidate;break;}
            phase-=candidate.periodTicks();
        }
        if(hazard==null)throw new IllegalArgumentException("Unknown hazard: "+id);
        if(phase<0||phase>=hazard.periodTicks())return HazardPhase.OFF;
        if (phase<hazard.offTicks()) return HazardPhase.OFF;
        return phase<hazard.offTicks()+hazard.warningTicks() ? HazardPhase.WARNING : HazardPhase.ACTIVE;
    }
    public List<ArenaDefinition.Hazard> activeHazards() {
        return definition.hazards().stream().filter(h->hazardPhase(h.id())==HazardPhase.ACTIVE).toList();
    }
    public float warningProgress(String id) {
        long period=definition.hazards().stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum();
        long phase=period==0?0:hazardClock()%period;
        for(var hazard:definition.hazards()) {
            if(hazard.id().equals(id))return Math.clamp((phase-hazard.offTicks())/(float)hazard.warningTicks(),0,1);
            phase-=hazard.periodTicks();
        }
        throw new IllegalArgumentException("Unknown hazard: "+id);
    }
    public void updateHazard(WorldQuery world,DamageSink sink) {
        if (lastHazardTick==session.tick || session.outcome!=MatchSession.Outcome.NONE) return;
        lastHazardTick=session.tick;
        int hazardIndex=0;
        for(var hazard:definition.hazards()) {
        int sequence=hazardIndex++;
        Map<Integer,Integer> exposure=hazardExposure.computeIfAbsent(hazard.id(),key->new HashMap<>());
        for (VehicleState vehicle:session.vehicles) {
            if (!vehicle.alive() || hazardPhase(hazard.id())!=HazardPhase.ACTIVE || !hazard.contains(world.position(vehicle.id))) {
                exposure.remove(vehicle.id); continue;
            }
            int ticks=exposure.getOrDefault(vehicle.id,0)+1;
            if (ticks>=hazard.damageIntervalTicks()) {
                exposure.remove(vehicle.id);
                if (vehicle.protectionTicks==0) sink.damage(vehicle.id,hazard.damage(),"hazard",
                        Long.MIN_VALUE+session.tick*16+vehicle.id+(long)sequence*(1L<<48));
            } else {
                exposure.put(vehicle.id,ticks);
            }
        }
        }
        if(session.mode!=MatchSession.Mode.LEGACY)hazardTicks++;
    }
    public ProgressStore.ArenaState snapshot() {
        Map<String,ProgressStore.PickupState> pickups=new LinkedHashMap<>();
        definition.pickups().forEach(p->pickups.put(p.id(),new ProgressStore.PickupState(Math.max(0,returnsAt.getOrDefault(p.id(),0L)-session.tick))));
        return new ProgressStore.ArenaState(pickups,objects,hazardSnapshot(hazardClock()),0,session.seed);
    }
    private Map<String,ProgressStore.HazardState> hazardSnapshot(long clock) {
        Map<String,ProgressStore.HazardState> result=new LinkedHashMap<>();
        long total=definition.hazards().stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum(),offset=0;
        for(var hazard:definition.hazards()) {
            long local=Math.floorMod(clock-offset,total),remaining;ProgressStore.HazardPhase phase;
            if(local<hazard.offTicks()) {phase=ProgressStore.HazardPhase.READY;remaining=hazard.offTicks()-local;}
            else if(local<hazard.offTicks()+hazard.warningTicks()) {phase=ProgressStore.HazardPhase.WARNING;remaining=hazard.offTicks()+hazard.warningTicks()-local;}
            else if(local<hazard.periodTicks()) {phase=ProgressStore.HazardPhase.ACTIVE;remaining=hazard.periodTicks()-local;}
            else {phase=ProgressStore.HazardPhase.COOLDOWN;remaining=total-local;}
            result.put(hazard.id(),new ProgressStore.HazardState(phase,remaining,0,clock/total));offset+=hazard.periodTicks();
        }
        return result;
    }
    public void restore(ProgressStore.ArenaState state,PhysicsWorld world,NavGraph graph) {
        long restored=0;
        if(!definition.hazards().isEmpty()) {
            var first=definition.hazards().getFirst();var stored=Objects.requireNonNull(state.hazards().get(first.id()));
            long total=definition.hazards().stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum();
            long end=switch(stored.phase()) {
                case READY -> first.offTicks();case WARNING -> first.offTicks()+first.warningTicks();
                case ACTIVE -> first.periodTicks();case COOLDOWN -> total;
                default -> throw new IllegalArgumentException("Invalid saved hazard schedule");
            };
            if(stored.remainingTicks()>end)throw new IllegalArgumentException("Invalid saved hazard duration");
            restored=Math.addExact(Math.multiplyExact(stored.cycle(),total),end-stored.remainingTicks());
            if(!hazardSnapshot(restored).equals(state.hazards()))throw new IllegalArgumentException("Inconsistent saved hazard schedule");
        }
        returnsAt.clear();state.pickups().forEach((id,p)->returnsAt.put(id,Math.addExact(session.tick,p.respawnTicks())));
        objects.clear();objects.putAll(state.objects());hazardTicks=restored;hazardExposure.clear();
        restoreGeometry(state,world,graph,definition);
    }
    /** Initial collider restoration happens before placing participants in the restored road network. */
    public static void restoreGeometry(ProgressStore.ArenaState state,PhysicsWorld world,NavGraph graph,ArenaDefinition definition) {
        for(var object:definition.destructibles()) {
            var saved=Objects.requireNonNull(state.objects().get(object.id()));
            if(saved.destroyed()||saved.open()) {
                world.removeStatic(object.geometryId());
                if(definition.edges().stream().anyMatch(edge->edge.type()==ArenaDefinition.Transition.OPENABLE&&object.id().equals(edge.objectId())))
                    graph.setOpen(object.id(),true);
            }
        }
    }
    public void collectPickups(WorldQuery world) {
        if (lastPickupTick==session.tick || session.outcome!=MatchSession.Outcome.NONE) return;
        lastPickupTick=session.tick;
        int index=0;
        for (var pickup:definition.pickups()) {
            int pickupIndex=index++;
            if (!active(pickup.id())) continue;
            VehicleState winner=null; float nearest=Float.POSITIVE_INFINITY;
            Vector3f surface=pickup.position().vector();
            for (VehicleState vehicle:session.vehicles) {
                if (!vehicle.alive() || vehicle.protectionTicks>0 || !needs(vehicle,pickup.type())) continue;
                Vector3f position=world.position(vehicle.id);
                float dx=position.x-surface.x,dz=position.z-surface.z,distance=dx*dx+dz*dz;
                if (distance>4 || Math.abs(position.y-surface.y)>2) continue;
                if (!world.visible(surface.add(0,.5f,0),position,vehicle.id)) continue;
                if (distance<nearest || (distance==nearest && (winner==null || vehicle.id<winner.id))) {
                    winner=vehicle; nearest=distance;
                }
            }
            if (winner!=null) {
                float amount=apply(winner,pickup.type());
                returnsAt.put(pickup.id(),session.tick+pickup.respawnTicks());
                events.add(new GameEvent(GameEvent.Type.PICKUP,-(1L<<60)-session.tick*16-pickupIndex,
                        winner.id,winner.id,surface,pickupKind(pickup.type()),amount).forObject(pickup.id()));
            }
        }
    }
    private static float apply(VehicleState vehicle,ArenaDefinition.PickupType type) {
        return switch (type) {
            case REPAIR -> { float old=vehicle.hp; vehicle.hp=Math.min(vehicle.maximumHp,vehicle.hp+vehicle.maximumHp*vehicle.repairFraction); yield vehicle.hp-old; }
            case HOMING_AMMO -> vehicle.weapon(WeaponType.HOMING).refill(3);
            case POWER_AMMO -> vehicle.weapon(WeaponType.POWER).refill(2);
            case MINE_AMMO -> vehicle.weapon(WeaponType.MINE).refill(2);
            case NAPALM_AMMO -> vehicle.weapon(WeaponType.NAPALM).refill(2);
            case BALLISTIC_AMMO -> vehicle.weapon(WeaponType.BALLISTIC).refill(1);
            case CANNON_AMMO -> vehicle.weapon(WeaponType.CANNON).refill(2);
            case TURBO_CELL -> { float old=vehicle.turbo; vehicle.turbo=Math.min(100,vehicle.turbo+50); yield vehicle.turbo-old; }
        };
    }
    public static boolean needs(VehicleState vehicle,ArenaDefinition.PickupType type) {
        return switch (type) {
            case REPAIR -> vehicle.hp<vehicle.maximumHp;
            case HOMING_AMMO -> vehicle.weapon(WeaponType.HOMING).ammo<vehicle.weapon(WeaponType.HOMING).maximumAmmo;
            case POWER_AMMO -> vehicle.weapon(WeaponType.POWER).ammo<vehicle.weapon(WeaponType.POWER).maximumAmmo;
            case MINE_AMMO -> vehicle.weapon(WeaponType.MINE).ammo<vehicle.weapon(WeaponType.MINE).maximumAmmo;
            case NAPALM_AMMO -> vehicle.weapon(WeaponType.NAPALM).ammo<vehicle.weapon(WeaponType.NAPALM).maximumAmmo;
            case BALLISTIC_AMMO -> vehicle.weapon(WeaponType.BALLISTIC).ammo<vehicle.weapon(WeaponType.BALLISTIC).maximumAmmo;
            case CANNON_AMMO -> vehicle.weapon(WeaponType.CANNON).ammo<vehicle.weapon(WeaponType.CANNON).maximumAmmo;
            case TURBO_CELL -> vehicle.turbo<100;
        };
    }
    public boolean active(String id) { return session.tick>=returnsAt.getOrDefault(id,0L); }
    private static String pickupKind(ArenaDefinition.PickupType type) {
        return switch(type) {
            case REPAIR -> "repair";
            case HOMING_AMMO -> "homing-ammo";
            case POWER_AMMO -> "power-ammo";
            case MINE_AMMO -> "mine-ammo";
            case NAPALM_AMMO -> "napalm-ammo";
            case BALLISTIC_AMMO -> "ballistic-ammo";
            case CANNON_AMMO -> "cannon-ammo";
            case TURBO_CELL -> "turbo";
        };
    }
    public List<ArenaDefinition.Pickup> activePickups() {
        return definition.pickups().stream().filter(p->active(p.id())).toList();
    }
    public List<GameEvent> drainEvents() {
        events.addAll(launches.drainEvents());
        List<GameEvent> result=events.stream().map(e->e.inSession(session.sessionId)).toList(); events.clear(); return result;
    }
}
