package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import java.util.*;

/** Per-participant launch state inside the ordinary arena tick, with no independent clock or physics. */
public final class ArenaLaunches {
    private record Key(String pad,int participant) {}
    private static final class PadState {
        long compressionStart=-1,compressionGeneration=-1,blockedUntil=Long.MIN_VALUE;
        boolean exitedSinceLaunch=true,rejected;
    }
    private static final class Flight {
        final String pad;
        final long launchedAt,generation;
        boolean airborne;
        Flight(String pad,long launchedAt,long generation) {this.pad=pad;this.launchedAt=launchedAt;this.generation=generation;}
    }
    public record FlightView(String launchId,long launchTick,boolean airborne) {}
    private final MatchSession session;
    private final ArenaDefinition arena;
    private final Map<Key,PadState> pairs=new HashMap<>();
    private final Map<Integer,Flight> flights=new HashMap<>();
    private final List<GameEvent> events=new ArrayList<>();
    private long lastBefore=Long.MIN_VALUE,lastAfter=Long.MIN_VALUE,eventSequence;

    public ArenaLaunches(MatchSession session,ArenaDefinition arena) {
        this.session=Objects.requireNonNull(session);this.arena=Objects.requireNonNull(arena);
    }
    public Optional<FlightView> flight(int participant) {
        Flight state=flights.get(participant);
        return state==null?Optional.empty():Optional.of(new FlightView(state.pad,state.launchedAt,state.airborne));
    }
    public float compression(String pad,int participant) {
        var state=pairs.get(new Key(pad,participant));
        if(state==null||state.compressionStart<0)return 0;
        var definition=arena.launchPads().stream().filter(p->p.id().equals(pad)).findFirst().orElseThrow();
        return Math.clamp((session.tick-state.compressionStart)/(float)definition.compressionTicks(),0,1);
    }
    public void beforePhysics(PhysicsWorld world,Map<Integer,VehicleController> drivers) {
        if(lastBefore==session.tick||session.outcome!=MatchSession.Outcome.NONE)return;
        lastBefore=session.tick;
        for(var vehicle:session.vehicles) {
            int id=vehicle.id;
            if(!world.containsVehicle(id)||!vehicle.alive()) {cancel(id,world,drivers);continue;}
            Flight flight=flights.get(id);
            if(flight!=null&&world.teleportGeneration(id)!=flight.generation)cancel(id,world,drivers);
            for(var pad:arena.launchPads()) {
                PadState state=pairs.computeIfAbsent(new Key(pad.id(),id),key->new PadState());
                if(state.compressionStart>=0&&state.compressionGeneration!=world.teleportGeneration(id))state.compressionStart=-1;
                Vector3f position=world.position(id);
                boolean inside=inside(pad,position);
                if(!inside) {state.exitedSinceLaunch=true;state.rejected=false;state.compressionStart=-1;continue;}
                if(!allowed(vehicle,pad)||flights.containsKey(id)||session.tick<state.blockedUntil||!state.exitedSinceLaunch)continue;
                var driver=drivers.get(id);
                if(driver==null)throw new IllegalStateException("Missing launch driver "+id);
                Vector3f velocity=world.velocity(id).setY(0);
                float speed=velocity.length();
                Vector3f forward=world.forward(id).setY(0);
                Vector3f direction=pad.direction();
                float cosine=(float)Math.cos(Math.toRadians(pad.maximumEntryAngle()));
                boolean directionValid=forward.lengthSquared()>.01f&&forward.normalizeLocal().dot(direction)>=cosine
                        &&speed>.01f&&velocity.divide(speed).dot(direction)>=cosine;
                float sourceOffset=position.y-pad.source().y();
                boolean onSource=world.grounded(id)&&world.rotation(id).mult(Vector3f.UNIT_Y).y>.65f
                        &&sourceOffset>0&&sourceOffset<world.profile(id).roadOffset()+.6f;
                if(vehicle.controlled()||!onSource||!directionValid||speed<.5f) {
                    state.compressionStart=-1;
                    if(onSource&&speed>=pad.minimumSpeed()&&!directionValid&&!state.rejected) {
                        emit(GameEvent.Type.LAUNCH_REJECTED,id,pad,position,0);state.rejected=true;
                    }
                    continue;
                }
                if(state.compressionStart<0) {
                    if(speed+.0001f<pad.minimumSpeed())continue;
                    state.compressionStart=session.tick;
                    state.compressionGeneration=world.teleportGeneration(id);
                    emit(GameEvent.Type.LAUNCH_COMPRESS,id,pad,position,pad.compressionTicks()*MatchSession.DT);
                }
                if(session.tick-state.compressionStart<pad.compressionTicks())continue;
                // Use the actual source COM offset after suspension compression,
                // not the profile's unloaded wheel extension as a guessed spawn Y.
                Vector3f destination=pad.target().vector().addLocal(0,sourceOffset,0);
                Vector3f required=world.launchVelocity(id,destination,pad.flightSeconds());
                world.impulse(id,required.subtract(world.velocity(id)).multLocal(world.mass(id)),Vector3f.ZERO,0);
                world.beginLaunch(id,pad.id(),pad.sourceSurfaceId(),pad.landingSurfaceId());
                driver.beginLaunch(direction);
                flights.put(id,new Flight(pad.id(),session.tick,world.teleportGeneration(id)));
                state.compressionStart=-1;state.blockedUntil=session.tick+pad.rearmTicks();state.exitedSinceLaunch=false;
                emit(GameEvent.Type.LAUNCHED,id,pad,position,pad.flightSeconds());
            }
        }
    }
    public void afterPhysics(PhysicsWorld world,Map<Integer,VehicleController> drivers) {
        if(lastAfter==session.tick)return;lastAfter=session.tick;
        for(int id:List.copyOf(flights.keySet())) {
            Flight flight=flights.get(id);
            if(!world.containsVehicle(id)||!session.vehicle(id).alive()||world.teleportGeneration(id)!=flight.generation) {
                cancel(id,world,drivers);continue;
            }
            int wheels=world.wheelContacts(id);
            if(wheels==0)flight.airborne=true;
            if(flight.airborne&&wheels>0) {
                var pad=arena.launchPads().stream().filter(p->p.id().equals(flight.pad)).findFirst().orElseThrow();
                emit(GameEvent.Type.LANDED,id,pad,world.position(id),(session.tick-flight.launchedAt+1)*MatchSession.DT);
                cancel(id,world,drivers);
            } else if(session.tick-flight.launchedAt>6L*MatchSession.TICKS_PER_SECOND) {
                // A collision can wedge a car away from all road contacts. Return
                // normal recovery/control; never relocate it to rescue a trajectory.
                cancel(id,world,drivers);
            }
        }
    }
    private boolean allowed(VehicleState vehicle,ArenaDefinition.LaunchPad pad) {
        if(!vehicle.boss)return true;
        return "boss_emcee".equals(vehicle.profileId)&&arena.bosses().stream()
                .anyMatch(b->b.profileId().equals(vehicle.profileId)&&b.launchPadIds().contains(pad.id()));
    }
    private boolean inside(ArenaDefinition.LaunchPad pad,Vector3f position) {
        Vector3f relative=position.subtract(pad.source().vector());Vector3f direction=pad.direction();
        float along=relative.dot(direction),across=Math.abs(relative.x*direction.z-relative.z*direction.x);
        return along>=-pad.length()/2-3&&along<=pad.length()/2&&across<=pad.width()/2;
    }
    private void cancel(int id,PhysicsWorld world,Map<Integer,VehicleController> drivers) {
        if(flights.remove(id)==null)return;
        var driver=drivers.get(id);if(driver!=null)driver.endLaunch();
        if(world.containsVehicle(id))world.endLaunch(id);
    }
    private void emit(GameEvent.Type type,int id,ArenaDefinition.LaunchPad pad,Vector3f position,float value) {
        events.add(new GameEvent(type,-(1L<<57)+eventSequence++,id,id,position,pad.id(),value));
    }
    public List<GameEvent> drainEvents() {var result=List.copyOf(events);events.clear();return result;}
}
