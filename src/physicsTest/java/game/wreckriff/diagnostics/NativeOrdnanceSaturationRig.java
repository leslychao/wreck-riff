package game.wreckriff.diagnostics;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Native command-only load fixture. No projectile injection, ammo replenishment, timer or rule overrides. */
public final class NativeOrdnanceSaturationRig implements AutoCloseable {
    public static final float FIRING_PLATFORM_HEIGHT=6;
    public final ArenaDefinition rosterArena=ArenaRegistry.load().definition("euphoria_park");
    public final CombatRules rules=Configs.load("combat",CombatRules.class);
    public final MatchSession session=new MatchSession(913,rosterArena,MatchSession.Mode.ARENA,rules);
    public final PhysicsWorld world=new PhysicsWorld(VehicleRules.load());
    public final CombatSystem combat=new CombatSystem(session,rules);
    private final Set<Long> shots=new HashSet<>(),placedMines=new HashSet<>();
    private int scriptTicks,maximumProjectiles,maximumMines,projectileDenials,mineDenials;
    private long capTick=-1;
    private boolean closed;

    public NativeOrdnanceSaturationRig() {
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
    }
    public List<GameEvent> step(boolean commandsEnabled) {
        Map<Integer,VehicleCommand> commands=new LinkedHashMap<>();
        if(commandsEnabled)for(var participant:session.vehicles)commands.put(participant.id,new VehicleCommand(0,0,0,false,false,false,true,
                scriptTicks==0?WeaponType.MINE:WeaponType.HOMING,0,false,false,AbilityId.NONE));
        List<GameEvent> events=advance(commands);scriptTicks++;
        maximumProjectiles=Math.max(maximumProjectiles,combat.projectiles().size());maximumMines=Math.max(maximumMines,combat.mines().size());
        if(combat.projectiles().size()>rules.maximumProjectiles()||combat.mines().size()>rules.mine().maximumActive())
            throw new IllegalStateException("CombatSystem exceeded its configured limit");
        if(saturated()&&capTick<0)capTick=session.tick;
        for(var event:events) {
            if(event.type()==GameEvent.Type.SHOT&&event.kind().equals("homing"))shots.add(event.eventId());
            if(event.type()==GameEvent.Type.MINE_PLACED)placedMines.add(event.eventId());
            if(event.type()==GameEvent.Type.EMPTY&&event.kind().equals("projectile-limit"))projectileDenials++;
            if(event.type()==GameEvent.Type.EMPTY&&event.kind().equals("mine-limit"))mineDenials++;
        }
        return events;
    }
    private List<GameEvent> advance(Map<Integer,VehicleCommand> commands) {
        combat.beginTick(commands,world);world.step();combat.advanceProjectiles(world);combat.resolveDamage(world);
        MatchRuntime.finishTick(session);return combat.drainEvents();
    }
    public boolean saturated(){return combat.projectiles().size()==rules.maximumProjectiles()&&combat.mines().size()==rules.mine().maximumActive();}
    public int scriptTicks(){return scriptTicks;}
    public int projectileDenials(){return projectileDenials;}
    public int mineDenials(){return mineDenials;}
    public Map<String,Object> evidence() {
        var result=new LinkedHashMap<String,Object>();
        result.put("method","Existing carnival roster on a staged native firing range with a 6m raised firing apron. Only VehicleCommand.MINE/HOMING; unmodified CombatSystem, PhysicsWorld and bundled combat.json. No authoritative-array injection, timer changes or resource refill.");
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
