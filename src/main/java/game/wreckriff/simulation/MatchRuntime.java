package game.wreckriff.simulation;

import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import com.jme3.math.*;
import java.util.*;

/** The complete match tick shared by the graphical game and native batch acceptance. */
public final class MatchRuntime implements AutoCloseable {
    private final MatchSession session;
    private final PhysicsWorld world;
    private final ArenaDefinition arena;
    private final VehicleRules vehicleRules;
    private final VehicleProfile bossProfile;
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
        this.arena=Objects.requireNonNull(arena);this.vehicleRules=Objects.requireNonNull(vehicleRules);
        world.configureArena(arena);
        bossProfile=arena.bosses().isEmpty()?null:VehicleProfile.boss(arena.bosses().getFirst().profileId(),vehicleRules);
        for (var state:session.vehicles) {
            if (!world.containsVehicle(state.id)) throw new IllegalArgumentException("Missing participant body " + state.id);
            drivers.put(state.id,new VehicleController(world,state,vehicleRules,arena.bounds(),arena.metadata().recoveryCost()));
        }
        arenaSystems=new ArenaSystems(session,arena);
        bots=new BotController(session,arena,graph,AiRules.load(),arenaSystems::activePickups);
        bots.observeHazards(arenaSystems::activeHazards);
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
        if(session.phase==MatchSession.Phase.ERROR)return List.of();
        if(session.phase==MatchSession.Phase.INTRO) {
            finishTick(session);
            return List.of();
        }
        if(session.phase==MatchSession.Phase.BOSS_ENTRY&&session.bossParticipantId<0) {
            if(trySpawnBoss())session.phaseTicks=0;
            else if(session.phaseTicks>=3L*MatchSession.TICKS_PER_SECOND) {
                session.outcomeReason="Не удалось безопасно разместить босса. Повторите от контрольной точки.";
                session.transition(MatchSession.Phase.ERROR);return List.of();
            }
        }
        Map<Integer,VehicleCommand> commands=runAi?new HashMap<>(bots.commands(world)):new HashMap<>();
        for(var entry:overrides.entrySet()) {
            if(entry.getKey()==null||entry.getKey()<0||entry.getKey()>=session.vehicles.size())throw new IllegalArgumentException("Unknown driver override");
            commands.put(entry.getKey(),Objects.requireNonNull(entry.getValue()));
        }
        if(session.phase==MatchSession.Phase.BOSS_ENTRY&&session.bossParticipantId>=0)
            commands.computeIfPresent(session.bossParticipantId,(id,command)->command.withoutAttacks());
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
        arenaSystems.beforePhysics(world,drivers);
        for (var state:session.vehicles) if (state.alive()) drivers.get(state.id).drive(commands.getOrDefault(state.id,VehicleCommand.NONE));
        stepPhysics();
        arenaSystems.afterPhysics(world,drivers);
        combat.advanceProjectiles(world);
        for (var ram:world.rams()) combat.queueRam(ram.first(),ram.second(),ram.closingSpeed(),ram.point(),ram.normal());
        if(session.combatPhase())arenaSystems.updateHazard(world,(target,amount,cause,event)->combat.queueDamage(target,-1,amount,cause,event));
        combat.resolveDamage(world);
        List<GameEvent> events=new ArrayList<>(combat.drainEvents());
        for (var event:events) if (event.type()==GameEvent.Type.DESTROYED) {
            world.makeWreck(event.subjectId());wreckTicks.put(event.subjectId(),3*MatchSession.TICKS_PER_SECOND);
        }
        arenaSystems.collectPickups(world); events.addAll(arenaSystems.drainEvents());
        for (var state:session.vehicles) if (state.alive()) drivers.get(state.id).recordSafePose(session.tick);
        finishTick(session);
        if (session.outcome!=MatchSession.Outcome.NONE) events.add(new GameEvent(GameEvent.Type.MATCH_FINISHED,
                Long.MAX_VALUE,0,-1,world.position(0),session.outcome.name().toLowerCase(Locale.ROOT),0));
        return events.stream().map(e->e.inSession(session.sessionId)).toList();
    }
    public void skipIntro() { if(session.phase==MatchSession.Phase.INTRO)session.transition(MatchSession.Phase.ARENA_COMBAT); }
    /** Whole-tick outcome priority is shared by native execution and pure transition tests. */
    public static void finishTick(MatchSession session) {
        if(session.outcome!=MatchSession.Outcome.NONE||session.phase==MatchSession.Phase.ERROR)return;
        session.tick++;session.phaseTicks++;
        if(session.combatPhase())session.activeTicks++;
        if(session.mode==MatchSession.Mode.LEGACY) {
            long alive=session.vehicles.stream().filter(VehicleState::alive).count();
            if(alive==0)finish(session,MatchSession.Outcome.DRAW,"Simultaneous destruction");
            else if(!session.vehicle(0).alive())finish(session,MatchSession.Outcome.DEFEAT,"Rivet destroyed");
            else if(alive==1)finish(session,MatchSession.Outcome.VICTORY,"Last machine standing");
            else if(session.tick>=session.maximumTicks())finish(session,MatchSession.Outcome.DRAW,"Time limit");
            return;
        }
        if(session.bossParticipantId>=0&&!session.vehicle(session.bossParticipantId).alive()) {
            finish(session,MatchSession.Outcome.VICTORY,session.vehicle(0).alive()?"Босс уничтожен":"Босс уничтожен. Машина уничтожена");
        } else if(!session.vehicle(0).alive()) {
            finish(session,MatchSession.Outcome.DEFEAT,"Машина уничтожена");
        } else if(session.phase==MatchSession.Phase.INTRO&&session.phaseTicks>=3L*MatchSession.TICKS_PER_SECOND) {
            session.transition(MatchSession.Phase.ARENA_COMBAT);
        } else if(session.phase==MatchSession.Phase.ARENA_COMBAT&&session.normalRivalsAlive()==0) {
            if(!session.preBossRepairApplied) {
                var player=session.vehicle(0);player.hp=Math.max(player.hp,player.maximumHp*.65f);
                session.preBossRepairApplied=true;session.checkpointRequested=true;
            }
            session.transition(MatchSession.Phase.BOSS_ENTRY);
        } else if(session.phase==MatchSession.Phase.BOSS_ENTRY&&session.bossParticipantId>=0
                &&session.phaseTicks>=3L*MatchSession.TICKS_PER_SECOND) {
            session.transition(MatchSession.Phase.BOSS_COMBAT);
        }
        if(session.bossParticipantId>=0&&session.vehicle(session.bossParticipantId).alive()) {
            var boss=session.vehicle(session.bossParticipantId);float hp=boss.hp/boss.maximumHp;
            session.bossMode=Math.max(session.bossMode,hp<=.3f?3:hp<=.65f?2:1);
        }
    }
    private static void finish(MatchSession session,MatchSession.Outcome outcome,String reason) {
        session.outcome=outcome;session.outcomeReason=reason;session.transition(MatchSession.Phase.RESULT);
    }
    private boolean trySpawnBoss() {
        if(bossProfile==null)throw new IllegalStateException("Missing arena boss");
        var boss=arena.bosses().getFirst();
        List<ArenaDefinition.Spawn> candidates=new ArrayList<>(boss.entrances());candidates.addAll(arena.spawns());
        Vector3f player=world.position(0);
        for(var spawn:candidates) {
            Vector3f surface=spawn.position().vector();
            if(surface.y!=0||surface.subtract(player).setY(0).length()<30)continue;
            Quaternion rotation=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
            Vector3f position=surface.add(0,bossProfile.roadOffset(),0);
            boolean supported=true;
            for(int wheel=0;wheel<4;wheel++) {
                var connection=bossProfile.wheelConnection(wheel);connection.y=0;
                Vector3f point=surface.add(rotation.mult(connection));
                var support=world.support(point.add(0,1,0),2);
                if(support==null||Math.abs(support.point().y-surface.y)>.15f||support.normal().y<.9f
                        ||!arena.bounds().contains(point)) {supported=false;break;}
            }
            if(!supported||!world.freeSpawnPose(bossProfile,position,rotation))continue;
            int id=session.vehicles.size();world.addVehicle(id,position,rotation,bossProfile);
            var state=session.registerBoss(boss);combat.registerParticipant(state);bots.registerParticipant(id);
            drivers.put(id,new VehicleController(world,state,vehicleRules,arena.bounds(),arena.metadata().recoveryCost()));
            return true;
        }
        return false;
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
            for(var state:session.vehicles)if(world.containsVehicle(state.id))world.stopDriving(state.id);
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
