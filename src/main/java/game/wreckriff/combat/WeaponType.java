package game.wreckriff.combat;

/** The complete selectable arsenal; the machine gun has its own input. */
public enum WeaponType {
    HOMING("homing"), POWER("power"), MINE("mine"), NAPALM("napalm"), BALLISTIC("ballistic"), CANNON("cannon");
    private final String id;
    WeaponType(String id) { this.id=id; }
    public String id() { return id; }
    public WeaponType cycle(int delta) { return values()[Math.floorMod(ordinal()+delta,values().length)]; }
}
