package game.wreckriff.simulation;

import game.wreckriff.combat.*;
import java.util.*;

/** One authoritative resource state per participant; positions belong to PhysicsWorld. */
public final class VehicleState {
    public final int id;
    public final String name;
    public final boolean player;
    public final float maximumHp;
    public final float repairFraction;
    public float hp;
    private final EnumMap<WeaponType,WeaponSlot> weapons=new EnumMap<>(WeaponType.class);
    private final EnumMap<AbilityId,Integer> abilityCooldowns=new EnumMap<>(AbilityId.class);
    public float turbo = 100;
    public int machineGunCooldown;
    public int turboQuietTicks, recoveryCooldown, protectionTicks;
    public int frozenTicks,shieldTicks,controlImmunityTicks;
    public int impactStabilizerTicks;
    public boolean heavyImpactPending;
    public WeaponType selectedWeapon=WeaponType.HOMING;
    public float damageDealt;
    public int eliminations, recoveries;
    public int lastAttacker = -1;
    public long lastAttackTick = Long.MIN_VALUE;
    public VehicleState(int id, String name, boolean player,CombatRules rules) {
        this.id=id; this.name=name; this.player=player;
        maximumHp=player?rules.health().playerMaximumHp():rules.health().botMaximumHp();hp=maximumHp;
        repairFraction=rules.health().repairFraction();initializeArsenal(rules);
    }
    public void initializeWeapon(WeaponType type,int ammunition,int maximum) { weapons.put(type,new WeaponSlot(ammunition,maximum)); }
    public void initializeArsenal(CombatRules rules) {
        initializeWeapon(WeaponType.HOMING,rules.homing().initialAmmo(),rules.homing().maximumAmmo());
        initializeWeapon(WeaponType.POWER,rules.power().initialAmmo(),rules.power().maximumAmmo());
        initializeWeapon(WeaponType.MINE,rules.mine().initialAmmo(),rules.mine().maximumAmmo());
        initializeWeapon(WeaponType.NAPALM,rules.napalm().initialAmmo(),rules.napalm().maximumAmmo());
        initializeWeapon(WeaponType.BALLISTIC,rules.ballistic().initialAmmo(),rules.ballistic().maximumAmmo());
        initializeWeapon(WeaponType.CANNON,rules.cannon().initialAmmo(),rules.cannon().maximumAmmo());
    }
    public WeaponSlot weapon(WeaponType type) {
        WeaponSlot result=weapons.get(type);
        if(result==null) throw new IllegalStateException("Weapons must be initialized before simulation: "+type);
        return result;
    }
    public Collection<WeaponSlot> weapons() { return Collections.unmodifiableCollection(weapons.values()); }
    public int abilityCooldown(AbilityId ability) { return abilityCooldowns.getOrDefault(ability,0); }
    public void abilityCooldown(AbilityId ability,int ticks) { abilityCooldowns.put(ability,Math.max(0,ticks)); }
    public void advanceAbilityCooldowns() { abilityCooldowns.replaceAll((ability,ticks)->Math.max(0,ticks-1)); }
    public boolean controlled() { return frozenTicks>0; }
    public boolean alive() { return hp > 0; }
}
