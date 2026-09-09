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
    private final Map<Integer,Integer> wreckTicks=new LinkedHashMap<>();
    private boolean physicsTailStarted;
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
        bots.observeBallisticWarnings(combat::ballisticWarnings);
    }
    public List<GameEvent> tick(VehicleCommand player,boolean aiPlayer) {
        Objects.requireNonNull(player);
        return tick(aiPlayer?Map.of():Map.of(0,player),true);
    }
    /** Deterministic demonstrations use the same physics/combat pipeline with explicit driver commands. */
    public List<GameEvent> tick(Map<Integer,VehicleCommand> overrides,boolean runAi) {
        if (closed) throw new IllegalStateException("Match runtime is closed");
        Objects.requireNonNull(overrides);
        if (session.outcome!=MatchSession.Outcome.NONE) return List.of();
        Map<Integer,VehicleCommand> commands=runAi?new HashMap<>(bots.commands(world)):new HashMap<>();
        for(var entry:overrides.entrySet()) {
            if(entry.getKey()==null||entry.getKey()<0||entry.getKey()>=session.vehicles.size())throw new IllegalArgumentException("Unknown driver override");
            commands.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        for (var state:session.vehicles) {
            if (!state.alive()) continue;
            VehicleCommand command=commands.getOrDefault(state.id,VehicleCommand.NONE);
            var recovery=drivers.get(state.id).prepare(command,session.tick);
            if(recovery.recovered()||recovery.fatal())combat.cancelControl(state,world);
            if (recovery.recovered() || recovery.fatal()) combat.queueDamage(state.id,-1,recovery.cost(),recovery.cause(),
                    Long.MIN_VALUE/2+session.tick*16+state.id);
            if (state.protectionTicks>0 || recovery.fatal()) commands.put(state.id,command.withoutAttacks());
        }
        combat.beginTick(commands,world);
        for(var state:session.vehicles) {
            if(!state.controlled())continue;
            VehicleCommand command=commands.getOrDefault(state.id,VehicleCommand.NONE);
            commands.put(state.id,new VehicleCommand(0,0,0,false,false,command.machineGun(),
                    command.selectedWeapon(),command.directWeapon(),command.weaponDelta(),
                    command.rearView(),false,command.ability()));
        }
        for (var state:session.vehicles) if (state.alive()) drivers.get(state.id).drive(commands.getOrDefault(state.id,VehicleCommand.NONE));
        stepPhysics();
        combat.advanceProjectiles(world);
        for (var ram:world.rams()) combat.queueRam(ram.first(),ram.second(),ram.closingSpeed(),ram.point(),ram.normal());
        arenaSystems.updateHazard(world,(target,amount,cause,event)->combat.queueDamage(target,-1,amount,cause,event));
        combat.resolveDamage(world);
        List<GameEvent> events=new ArrayList<>(combat.drainEvents());
        for (var event:events) if (event.type()==GameEvent.Type.DESTROYED) {
            world.makeWreck(event.subjectId());wreckTicks.put(event.subjectId(),3*MatchSession.TICKS_PER_SECOND);
        }
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
    public int wreckRemainingTicks(int id) { return wreckTicks.getOrDefault(id,0); }
    public boolean hasWrecks() { return !wreckTicks.isEmpty(); }
    /** Results advances the same space without advancing match time, attacks or AI. */
    public void tickPhysicsTail() {
        if(closed)throw new IllegalStateException("Match runtime is closed");
        if(session.outcome==MatchSession.Outcome.NONE)throw new IllegalStateException("Physics tail requires a finished match");
        if(!hasWrecks())return;
        if(!physicsTailStarted) {
            for(var state:session.vehicles)if(world.containsVehicle(state.id))world.makeWreck(state.id);
            physicsTailStarted=true;
        }
        stepPhysics();
    }
    private void stepPhysics() {
        world.step();
        for(var iterator=wreckTicks.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();int remaining=entry.getValue()-1;
            if(remaining==0) {world.removeVehicle(entry.getKey());iterator.remove();}
            else entry.setValue(remaining);
        }
    }
    @Override public void close() {
        if (closed) return;
        closed=true; wreckTicks.clear();combat.clear(); world.close();
    }
}
