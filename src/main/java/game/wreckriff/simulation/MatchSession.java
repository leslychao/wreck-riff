package game.wreckriff.simulation;

import java.util.List;
import java.util.UUID;

public final class MatchSession {
    public static final int TICKS_PER_SECOND = 120;
    public static final float DT = 1f / TICKS_PER_SECOND;
    public enum Outcome { NONE, VICTORY, DEFEAT, DRAW }
    public final UUID sessionId = UUID.randomUUID();
    public final long seed;
    public final List<VehicleState> vehicles;
    public long tick;
    public Outcome outcome = Outcome.NONE;
    public String outcomeReason = "";
    private final long maximumTicks;

    public MatchSession(long seed, int durationSeconds) {
        this.seed=seed;
        maximumTicks=(long) durationSeconds*TICKS_PER_SECOND;
        vehicles=List.of(new VehicleState(0,"Rivet",true), new VehicleState(1,"Static",false),
                new VehicleState(2,"Dent",false),new VehicleState(3,"Buzz",false),new VehicleState(4,"Fuse",false));
        var combatRules=game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class);
        vehicles.forEach(vehicle->vehicle.initializeArsenal(combatRules));
    }
    public VehicleState vehicle(int id) { return vehicles.get(id); }
    public void finishTick() {
        if (outcome != Outcome.NONE) return;
        tick++;
        long alive=vehicles.stream().filter(VehicleState::alive).count();
        if (alive==0) { outcome=Outcome.DRAW; outcomeReason="Simultaneous destruction"; }
        else if (!vehicle(0).alive()) { outcome=Outcome.DEFEAT; outcomeReason="Rivet destroyed"; }
        else if (alive==1) { outcome=Outcome.VICTORY; outcomeReason="Last machine standing"; }
        else if (tick>=maximumTicks) { outcome=Outcome.DRAW; outcomeReason="Time limit"; }
    }
    public float seconds() { return tick/(float)TICKS_PER_SECOND; }
}
