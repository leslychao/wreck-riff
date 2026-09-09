package game.wreckriff.combat;

/** Session-owned ammunition and timer for a single weapon type. */
public final class WeaponSlot {
    public int ammo;
    public final int maximumAmmo;
    public int cooldownTicks;
    public WeaponSlot(int ammo,int maximumAmmo) {
        if(ammo<0 || maximumAmmo<1 || ammo>maximumAmmo) throw new IllegalArgumentException("Invalid ammunition");
        this.ammo=ammo; this.maximumAmmo=maximumAmmo;
    }
    public int refill(int amount) {
        int accepted=Math.min(amount,maximumAmmo-ammo); ammo+=accepted; return accepted;
    }
}
