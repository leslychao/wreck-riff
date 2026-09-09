package game.wreckriff.arena;
import game.wreckriff.combat.WeaponType;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.*;
import java.util.*;

/** Tick-driven hazard and atomic, surface-aware resource collection. */
public final class ArenaSystems {
    public enum HazardPhase { OFF, WARNING, ACTIVE }
    @FunctionalInterface public interface DamageSink {
        void damage(int targetId,float amount,String cause,long eventId);
    }
    private final MatchSession session;
    private final ArenaDefinition definition;
    private final Map<String,Long> returnsAt=new HashMap<>();
    private final int[] hazardExposure=new int[5];
    private final List<GameEvent> events=new ArrayList<>();
    private long lastHazardTick=Long.MIN_VALUE,lastPickupTick=Long.MIN_VALUE;

    public ArenaSystems(MatchSession session,ArenaDefinition definition) {
        this.session=session; this.definition=definition;
    }
    public HazardPhase hazardPhase() {
        var hazard=definition.hazard();
        long phase=session.tick%hazard.periodTicks();
        if (phase<hazard.offTicks()) return HazardPhase.OFF;
        return phase<hazard.offTicks()+hazard.warningTicks() ? HazardPhase.WARNING : HazardPhase.ACTIVE;
    }
    public void updateHazard(WorldQuery world,DamageSink sink) {
        if (lastHazardTick==session.tick || session.outcome!=MatchSession.Outcome.NONE) return;
        lastHazardTick=session.tick;
        var hazard=definition.hazard();
        for (VehicleState vehicle:session.vehicles) {
            if (!vehicle.alive() || hazardPhase()!=HazardPhase.ACTIVE || !hazard.contains(world.position(vehicle.id))) {
                hazardExposure[vehicle.id]=0; continue;
            }
            if (++hazardExposure[vehicle.id]>=hazard.damageIntervalTicks()) {
                hazardExposure[vehicle.id]=0;
                if (vehicle.protectionTicks==0) sink.damage(vehicle.id,hazard.damage(),"hazard",Long.MIN_VALUE+session.tick*16+vehicle.id);
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
                        winner.id,winner.id,surface,pickupKind(pickup.type()),amount));
            }
        }
    }
    private static float apply(VehicleState vehicle,ArenaDefinition.PickupType type) {
        return switch (type) {
            case REPAIR -> { float old=vehicle.hp; vehicle.hp=Math.min(vehicle.maximumHp,vehicle.hp+vehicle.maximumHp*.25f); yield vehicle.hp-old; }
            case HOMING_AMMO -> vehicle.weapon(WeaponType.HOMING).refill(3);
            case POWER_AMMO -> vehicle.weapon(WeaponType.POWER).refill(2);
            case MINE_AMMO -> vehicle.weapon(WeaponType.MINE).refill(2);
            case NAPALM_AMMO -> vehicle.weapon(WeaponType.NAPALM).refill(2);
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
            case TURBO_CELL -> "turbo";
        };
    }
    public List<ArenaDefinition.Pickup> activePickups() {
        return definition.pickups().stream().filter(p->active(p.id())).toList();
    }
    public List<GameEvent> drainEvents() {
        List<GameEvent> result=List.copyOf(events); events.clear(); return result;
    }
}
