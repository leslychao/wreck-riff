package game.wreckriff.simulation;

import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import java.util.*;

/** The complete match tick shared by the graphical game and native batch acceptance. */
public final class MatchRuntime implements AutoCloseable {
    private final MatchSession session;
    private final PhysicsWorld world;
    private final Map<Integer,VehicleController> drivers=new LinkedHashMap<>();
    private final ArenaSystems arenaSystems;
    private final BotController bots;
    private final CombatSystem combat;
    private boolean closed;

    public MatchRuntime(MatchSession session,PhysicsWorld world,ArenaDefinition arena,
            NavGraph graph,VehicleRules vehicleRules) {
        this.session=Objects.requireNonNull(session); this.world=Objects.requireNonNull(world);
        for (var state:session.vehicles) {
            if (!world.containsVehicle(state.id)) throw new IllegalArgumentException("Missing participant body " + state.id);
            drivers.put(state.id,new VehicleController(world,state,vehicleRules));
        }
        arenaSystems=new ArenaSystems(session,arena);
        bots=new BotController(session,arena,graph,AiRules.load(),arenaSystems::activePickups);
        combat=new CombatSystem(session,session.combatRules);
        bots.observeProjectiles(combat::projectiles);
    }
    public List<GameEvent> tick(VehicleCommand player,boolean aiPlayer) {
        if (closed) throw new IllegalStateException("Match runtime is closed");
        Objects.requireNonNull(player);
        if (session.outcome!=MatchSession.Outcome.NONE) return List.of();
        Map<Integer,VehicleCommand> commands=new HashMap<>(bots.commands(world));
        if (!aiPlayer) commands.put(0,player);
        for (var state:session.vehicles) {
            if (!state.alive()) continue;
            VehicleCommand command=commands.getOrDefault(state.id,VehicleCommand.NONE);
            var recovery=drivers.get(state.id).prepare(command,session.tick);
            if(recovery.recovered()||recovery.fatal())combat.endControl(state,world);
            if (recovery.recovered() || recovery.fatal()) combat.queueDamage(state.id,-1,recovery.cost(),recovery.cause(),
                    Long.MIN_VALUE/2+session.tick*16+state.id);
            if (state.protectionTicks>0 || recovery.fatal()) commands.put(state.id,command.withoutAttacks());
        }
        combat.beginTick(commands,world);
        for(var state:session.vehicles) {
            if(!state.controlled())continue;
            VehicleCommand command=commands.getOrDefault(state.id,VehicleCommand.NONE);
            boolean stunned=state.stunnedTicks>0;
            commands.put(state.id,new VehicleCommand(0,0,0,false,false,!stunned&&command.machineGun(),
                    !stunned&&command.selectedWeapon(),!stunned&&command.special(),stunned?0:command.weaponDelta(),
                    command.rearView(),false,command.ability()));
        }
        for (var state:session.vehicles) if (state.alive()) drivers.get(state.id).drive(commands.getOrDefault(state.id,VehicleCommand.NONE));
        world.step();
        combat.advanceProjectiles(world);
        for (var ram:world.rams()) combat.queueRam(ram.first(),ram.second(),ram.closingSpeed());
        arenaSystems.updateHazard(world,(target,amount,cause,event)->combat.queueDamage(target,-1,amount,cause,event));
        combat.resolveDamage(world);
        List<GameEvent> events=new ArrayList<>(combat.drainEvents());
        for (var event:events) if (event.type()==GameEvent.Type.DESTROYED) world.removeVehicle(event.subjectId());
        arenaSystems.collectPickups(world); events.addAll(arenaSystems.drainEvents());
        for (var state:session.vehicles) if (state.alive()) drivers.get(state.id).recordSafePose(session.tick);
        session.finishTick();
        if (session.outcome!=MatchSession.Outcome.NONE) events.add(new GameEvent(GameEvent.Type.MATCH_FINISHED,
                Long.MAX_VALUE,0,-1,world.position(0),session.outcome.name().toLowerCase(Locale.ROOT),0));
        return List.copyOf(events);
    }
    public Map<Integer,VehicleController> drivers() { return Collections.unmodifiableMap(drivers); }
    public ArenaSystems arenaSystems() { return arenaSystems; }
    public BotController bots() { return bots; }
    public CombatSystem combat() { return combat; }
    @Override public void close() {
        if (closed) return;
        closed=true; combat.clear(); world.close();
    }
}
