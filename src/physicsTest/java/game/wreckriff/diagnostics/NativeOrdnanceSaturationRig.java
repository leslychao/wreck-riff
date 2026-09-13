package game.wreckriff.diagnostics;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Staged native supply-and-fire fixture. Actual pickups and commands; no projectile, timer or rule overrides. */
public final class NativeOrdnanceSaturationRig implements AutoCloseable {
    public static final float FIRING_PLATFORM_HEIGHT=6;
    public final ArenaDefinition rosterArena=Configs.load("arena-euphoria-park",ArenaDefinition.class);
    public final CombatRules rules=Configs.load("combat",CombatRules.class);
    public final MatchSession session=new MatchSession(913,rosterArena,MatchSession.Mode.ARENA,rules);
    public final PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
    public final CombatSystem combat=new CombatSystem(session,rules);
    private final ArenaSystems supplies;
    private final boolean emptyAtStart;
    private final Map<String,Integer> collected=new LinkedHashMap<>(),launched=new LinkedHashMap<>();
    private final Set<Long> shots=new HashSet<>(),placedMines=new HashSet<>();
    private int scriptTicks,maximumProjectiles,maximumMines,projectileDenials,mineDenials;
    private long capTick=-1;
    private boolean closed;

    public NativeOrdnanceSaturationRig() {
        emptyAtStart=session.vehicles.stream().allMatch(vehicle->vehicle.weapons().stream().allMatch(slot->slot.ammo==0));
        if(!emptyAtStart)throw new IllegalStateException("Saturation participants must start with the live empty arsenal");
        world.addStatic("saturation-floor",new BoxCollisionShape(new Vector3f(100,.5f,260)),new Vector3f(0,-.5f,100),new Quaternion());
        // A real raised firing apron leaves clear air beyond the muzzle even with small settled chassis pitch.
        world.addStatic("saturation-firing-apron",new BoxCollisionShape(new Vector3f(100,FIRING_PLATFORM_HEIGHT/2,6)),
                new Vector3f(0,FIRING_PLATFORM_HEIGHT/2,0),new Quaternion());
        var vehicleRules=VehicleRules.load();
        for(var participant:session.vehicles) {
            var profile=VehicleDefinition.forId(participant.profileId).profile(vehicleRules);
            world.addVehicle(participant.id,new Vector3f((participant.id-6)*8,FIRING_PLATFORM_HEIGHT+profile.roadOffset()+.01f,0),new Quaternion(),profile);
        }
        // The ordinary intro advances through the same finishTick transition; only initial poses are staged.
        for(int i=0;i<360;i++)advance(Map.of());
        if(session.phase!=MatchSession.Phase.ARENA_COMBAT)throw new IllegalStateException("Native fixture intro did not finish");
        for(var participant:session.vehicles)if(!world.grounded(participant.id))throw new IllegalStateException("Unsupported fixture chassis "+participant.id);
        var pickups=new ArrayList<ArenaDefinition.Pickup>();
        for(var participant:session.vehicles)for(var type:List.of(ArenaDefinition.PickupType.HOMING_AMMO,ArenaDefinition.PickupType.POWER_AMMO,
                ArenaDefinition.PickupType.BALLISTIC_AMMO,ArenaDefinition.PickupType.MINE_AMMO))for(int packageIndex=0;packageIndex<2;packageIndex++) {
            var position=world.position(participant.id);
            pickups.add(new ArenaDefinition.Pickup("range-supply-"+participant.id+"-"+type+"-"+packageIndex,type,
                    new ArenaDefinition.Vec3(position.x,FIRING_PLATFORM_HEIGHT,position.z),3000));
        }
        // The compact fixture definition bounds cover the staged range; its geometry is not installed.
        supplies=new ArenaSystems(session,ArenaDefinition.load().withPickups(pickups));
    }
    public List<GameEvent> step(boolean commandsEnabled) {
        return step(commandsEnabled,null);
    }
    public List<GameEvent> step(boolean commandsEnabled,StageProfiler profiler) {
        List<GameEvent> collectedEvents=List.of();
        if(commandsEnabled&&scriptTicks==0) {
            supplies.collectPickups(world);collectedEvents=supplies.drainEvents();
            for(var event:collectedEvents)if(event.type()==GameEvent.Type.PICKUP)
                collected.merge(event.kind().replace("-ammo",""),Math.round(event.value()),Integer::sum);
        }
        Map<Integer,VehicleCommand> commands=new LinkedHashMap<>();
        WeaponType[] rotation={WeaponType.BALLISTIC,WeaponType.HOMING,WeaponType.POWER};
        if(commandsEnabled)for(var participant:session.vehicles)commands.put(participant.id,new VehicleCommand(0,0,0,false,false,false,true,
                scriptTicks==0?WeaponType.MINE:rotation[(scriptTicks-1)%rotation.length],0,false,false,AbilityId.NONE));
        List<GameEvent> events=new ArrayList<>(collectedEvents);events.addAll(advance(commands,profiler));scriptTicks++;
        maximumProjectiles=Math.max(maximumProjectiles,combat.projectiles().size());maximumMines=Math.max(maximumMines,combat.mines().size());
        if(combat.projectiles().size()>rules.maximumProjectiles()||combat.mines().size()>rules.mine().maximumActive())
            throw new IllegalStateException("CombatSystem exceeded its configured limit");
        if(saturated()&&capTick<0)capTick=session.tick;
        for(var event:events) {
            if(event.type()==GameEvent.Type.SHOT&&event.kind().equals("homing"))shots.add(event.eventId());
            if(event.type()==GameEvent.Type.MINE_PLACED)placedMines.add(event.eventId());
            if(event.type()==GameEvent.Type.SHOT||event.type()==GameEvent.Type.MINE_PLACED)
                for(var weapon:WeaponType.values())if(weapon.id().equals(event.kind()))launched.merge(weapon.id(),1,Integer::sum);
            if(event.type()==GameEvent.Type.EMPTY&&event.kind().equals("projectile-limit"))projectileDenials++;
            if(event.type()==GameEvent.Type.EMPTY&&event.kind().equals("mine-limit"))mineDenials++;
        }
        return events;
    }
    private List<GameEvent> advance(Map<Integer,VehicleCommand> commands) {
        return advance(commands,null);
    }
    private List<GameEvent> advance(Map<Integer,VehicleCommand> commands,StageProfiler profiler) {
        long started=profiler==null?0:System.nanoTime();combat.beginTick(commands,world);
        long combatNanos=profiler==null?0:System.nanoTime()-started;
        started=profiler==null?0:System.nanoTime();world.step();
        if(profiler!=null)profiler.record(StageProfiler.Stage.BULLET,System.nanoTime()-started);
        started=profiler==null?0:System.nanoTime();combat.advanceProjectiles(world);combat.resolveDamage(world);
        MatchRuntime.finishTick(session);var events=combat.drainEvents();
        if(profiler!=null)profiler.record(StageProfiler.Stage.COMBAT,combatNanos+System.nanoTime()-started);
        return events;
    }
    public boolean saturated(){return combat.projectiles().size()==rules.maximumProjectiles()&&combat.mines().size()==rules.mine().maximumActive();}
    public int scriptTicks(){return scriptTicks;}
    public int projectileDenials(){return projectileDenials;}
    public int mineDenials(){return mineDenials;}
    public boolean emptyAtStart(){return emptyAtStart;}
    public int ammunitionCollected(WeaponType type){return collected.getOrDefault(type.id(),0);}
    public int launches(WeaponType type){return launched.getOrDefault(type.id(),0);}
    public Map<String,Object> evidence() {
        var result=new LinkedHashMap<String,Object>();
        result.put("method","Existing carnival roster starts empty on a staged native firing range with a 6m raised firing apron. Each driver collects two real packages each of Homing, Power, Ballistic and Mine through ArenaSystems before a mixed command salvo. Unmodified CombatSystem, PhysicsWorld and bundled combat.json; no projectile injection, timer overrides or subsequent ammunition grants.");
        result.put("emptyArsenalAtStart",emptyAtStart);result.put("collectedAmmunition",Map.copyOf(collected));result.put("weaponLaunches",Map.copyOf(launched));
        result.put("participants",session.vehicles.size());result.put("configuredProjectiles",rules.maximumProjectiles());result.put("configuredMines",rules.mine().maximumActive());
        result.put("maximumProjectiles",maximumProjectiles);result.put("maximumMines",maximumMines);result.put("firstSimultaneousCapTick",capTick);
        result.put("scriptTicks",scriptTicks);result.put("homingShotEvents",shots.size());result.put("minePlacedEvents",placedMines.size());
        result.put("projectileLimitDenials",projectileDenials);result.put("mineLimitDenials",mineDenials);
        result.put("homingAmmoRemaining",session.vehicles.stream().mapToInt(v->v.weapon(WeaponType.HOMING).ammo).sum());
        result.put("mineAmmoRemaining",session.vehicles.stream().mapToInt(v->v.weapon(WeaponType.MINE).ammo).sum());
        result.put("nativeBodies",world.bodyCount());result.put("outcome",session.outcome.name());return result;
    }
    @Override public void close(){if(closed)return;closed=true;combat.clear();world.close();}
}
