package game.wreckriff.input;

/** Shared driver contract. Pulse and weaponDelta are edges, other controls are held. */
public record VehicleCommand(float throttle, float brakeReverse, float steer,
        boolean handbrake, boolean turbo, boolean machineGun, boolean selectedWeapon,
        boolean special, int weaponDelta, boolean rearView, boolean recover) {
    public static final VehicleCommand NONE = new VehicleCommand(0,0,0,false,false,false,false,false,0,false,false);
    public VehicleCommand {
        if (!Float.isFinite(throttle) || !Float.isFinite(brakeReverse) || !Float.isFinite(steer)) {
            throw new IllegalArgumentException("Non-finite driver command");
        }
        throttle = Math.clamp(throttle, 0, 1);
        brakeReverse = Math.clamp(brakeReverse, 0, 1);
        steer = Math.clamp(steer, -1, 1);
        weaponDelta = Integer.signum(weaponDelta);
    }
    public VehicleCommand withoutEdges() {
        return new VehicleCommand(throttle,brakeReverse,steer,handbrake,turbo,machineGun,selectedWeapon,false,0,rearView,recover);
    }
    public VehicleCommand withoutAttacks() {
        return new VehicleCommand(throttle,brakeReverse,steer,handbrake,turbo,false,false,false,0,rearView,recover);
    }
}
