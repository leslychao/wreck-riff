package game.wreckriff.combat;

/** The complete selectable arsenal; the machine gun and Pulse have dedicated inputs. */
public enum WeaponType {
    HOMING("homing"), POWER("power"), MINE("mine"), NAPALM("napalm");
    private final String id;
    WeaponType(String id) { this.id=id; }
    public String id() { return id; }
    public WeaponType cycle(int delta) { return values()[Math.floorMod(ordinal()+delta,values().length)]; }
}
