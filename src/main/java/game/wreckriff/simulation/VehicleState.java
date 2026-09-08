package game.wreckriff.simulation;

/** One authoritative resource state per participant; positions belong to PhysicsWorld. */
public final class VehicleState {
    public final int id;
    public final String name;
    public final boolean player;
    public float hp = 200;
    public int homingAmmo = 6;
    public int powerAmmo = 4;
    public float turbo = 100;
    public float heat;
    public boolean overheated;
    public int machineGunCooldown, homingCooldown, powerCooldown, pulseCooldown = 480;
    public int heatQuietTicks, turboQuietTicks, recoveryCooldown, protectionTicks;
    public int selectedWeapon; // 0 homing, 1 power
    public float damageDealt;
    public int eliminations, recoveries;
    public int lastAttacker = -1;
    public long lastAttackTick = Long.MIN_VALUE;
    public VehicleState(int id, String name, boolean player) {
        this.id=id; this.name=name; this.player=player;
    }
    public boolean alive() { return hp > 0; }
}
