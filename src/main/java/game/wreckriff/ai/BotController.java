package game.wreckriff.ai;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** 10 Hz perception/decisions, 120 Hz ordinary driver commands. No transform writes. */
public final class BotController {
    public enum State { SEEK_TARGET, ATTACK, SEEK_PICKUP, EVADE_HAZARD, RECOVER, DESTROYED }
    public record Metrics(int vehicleId,State state,int targetId,int reverseAttempts,int recoveries,
            long aliveTicks,long maximumUnplannedStationaryTicks,Vector3f destination) {}
    private static final Logger LOG=Logger.getLogger(BotController.class.getName());
    private static final class Brain {
        final Random random;
        final Map<Integer,BotObservation.Opponent> memory=new HashMap<>();
        final Map<String,Long> pickupUnavailableUntil=new HashMap<>();
        BotObservation observation=new BotObservation(0,List.of(),List.of());
        State state=State.SEEK_TARGET;
        int target=-1,goalNode=-1,pathIndex,reverseAttempts,recoveriesSeen,lockTicks;
        long targetChanged=Long.MIN_VALUE/2,reactionUntil,replanAt,reverseUntil,stuckSince=-1,
                progressWindowStart,aliveTicks,stationaryTicks,maximumStationaryTicks;
        float progress;
        List<Integer> path=List.of();
        Vector3f destination,lastPosition,progressDirection=new Vector3f();
        String pickup;
        boolean healing,requiredMovement;
        Brain(long seed) { random=new Random(seed); }
    }
    private final MatchSession session;
    private final ArenaDefinition arena;
    private final NavGraph graph;
    private final AiRules rules;
    private final Supplier<List<ArenaDefinition.Pickup>> activePickups;
    private final Brain[] brains=new Brain[5];
    private long lastCommandTick=Long.MIN_VALUE;
    private Map<Integer,VehicleCommand> cached=Map.of();

    public BotController(MatchSession session,ArenaDefinition arena,AiRules rules) {
        this(session,arena,new NavGraph(arena),rules,arena::pickups);
    }
    public BotController(MatchSession session,ArenaDefinition arena,NavGraph graph,AiRules rules) {
        this(session,arena,graph,rules,arena::pickups);
    }
    public BotController(MatchSession session,ArenaDefinition arena,AiRules rules,
            Supplier<List<ArenaDefinition.Pickup>> activePickups) {
        this(session,arena,new NavGraph(arena),rules,activePickups);
    }
    public BotController(MatchSession session,ArenaDefinition arena,NavGraph graph,AiRules rules,
            Supplier<List<ArenaDefinition.Pickup>> activePickups) {
        this.session=session; this.arena=arena; this.graph=graph; this.rules=rules; this.activePickups=activePickups;
        for (int id=0;id<brains.length;id++) brains[id]=new Brain(session.seed ^ (0x9E3779B97F4A7C15L*(id+1)));
    }
    public Map<Integer,VehicleCommand> commands(WorldQuery world) {
        if (lastCommandTick==session.tick) return cached;
        lastCommandTick=session.tick;
        Map<Integer,VehicleCommand> result=new LinkedHashMap<>();
        Set<String> active=new HashSet<>();
        for (var pickup:activePickups.get()) active.add(pickup.id());
        for (VehicleState vehicle:session.vehicles) {
            Brain brain=brains[vehicle.id];
            if (!vehicle.alive() || session.outcome!=MatchSession.Outcome.NONE) {
                brain.state=State.DESTROYED; result.put(vehicle.id,VehicleCommand.NONE); continue;
            }
            brain.aliveTicks++;
            Vector3f position=world.position(vehicle.id);
            trackProgress(vehicle,brain,position);
            if (session.tick%rules.decisionTicks()==0 || brain.destination==null) {
                observe(vehicle,brain,world,active);
                decide(vehicle,brain,world);
            }
            result.put(vehicle.id,drive(vehicle,brain,world));
        }
        cached=Collections.unmodifiableMap(result); return cached;
    }
    private void observe(VehicleState self,Brain brain,WorldQuery world,Set<String> active) {
        Vector3f eye=world.position(self.id).add(0,.6f,0),forward=world.forward(self.id);
        List<BotObservation.Opponent> visible=new ArrayList<>();
        for (VehicleState other:session.vehicles) {
            if (other.id==self.id || !other.alive()) continue;
            Vector3f position=world.position(other.id),offset=position.subtract(eye);
            if (offset.length()>rules.sightRange() || angleDegrees(forward,offset)>rules.sightHalfAngleDegrees()
                    || !lineOfSight(world,eye,position,self.id,other.id)) continue;
            var observation=new BotObservation.Opponent(other.id,position,world.velocity(other.id),session.tick);
            visible.add(observation); brain.memory.put(other.id,observation);
        }
        brain.memory.values().removeIf(e->session.tick-e.observedTick()>rules.memoryTicks() || !session.vehicle(e.id()).alive());
        List<BotObservation.Opponent> remembered=brain.memory.values().stream()
                .filter(e->visible.stream().noneMatch(v->v.id()==e.id())).sorted(Comparator.comparingInt(BotObservation.Opponent::id)).toList();
        brain.observation=new BotObservation(session.tick,visible,remembered);
        for (var pickup:arena.pickups()) {
            Vector3f target=pickup.position().vector().add(0,.6f,0);
            if (target.distance(eye)>rules.sightRange() || world.ray(eye,target,self.id)!=null) continue;
            if (active.contains(pickup.id())) brain.pickupUnavailableUntil.remove(pickup.id());
            else brain.pickupUnavailableUntil.put(pickup.id(),session.tick+pickup.respawnTicks());
        }
    }
    private void decide(VehicleState self,Brain brain,WorldQuery world) {
        if (brain.state==State.RECOVER && (brain.reverseUntil>session.tick || needsRecovery(brain))) return;
        Vector3f position=world.position(self.id);
        boolean hazardActive=visibleActiveHazard(self.id,world);
        if (hazardActive && arena.hazard().contains(position)) {
            brain.state=State.EVADE_HAZARD; brain.pickup=null;
            int escape=graph.nodes().stream().filter(n->!arena.hazard().contains(n.position().vector()))
                    .min(Comparator.comparingDouble(n->n.position().vector().distanceSquared(position))).orElseThrow().id();
            route(brain,position,graph.position(escape),true); return;
        }
        if (self.hp<200*rules.repairThreshold()) brain.healing=true;
        else if (self.hp>=200*rules.repairReleaseThreshold()) brain.healing=false;
        ArenaDefinition.PickupType wanted=brain.healing ? ArenaDefinition.PickupType.REPAIR
                : self.homingAmmo<2 ? ArenaDefinition.PickupType.HOMING_AMMO
                : self.powerAmmo<1 ? ArenaDefinition.PickupType.POWER_AMMO : null;
        if (wanted!=null) {
            ArenaDefinition.Pickup selected=choosePickup(brain,position,wanted,hazardActive);
            if (selected!=null) {
                brain.state=State.SEEK_PICKUP; brain.pickup=selected.id();
                route(brain,position,selected.position().vector(),hazardActive);
                chooseTarget(self,brain,world); return;
            }
        }
        brain.pickup=null;
        chooseTarget(self,brain,world);
        var target=brain.observation.visible(brain.target);
        if (target!=null) {
            brain.state=State.ATTACK;
            Vector3f destination=target.position();
            boolean sameFloor=Math.abs(destination.y-position.y)<2.2f;
            WorldQuery.Hit obstacle=world.sweep(position,destination,1.15f,self.id);
            if (sameFloor && (obstacle==null || obstacle.vehicleId()==target.id())
                    && !(hazardActive && graph.crossesHazard(position,destination))) {
                brain.destination=destination.clone(); brain.path=List.of(); brain.goalNode=-1;
            } else route(brain,position,destination,hazardActive);
            return;
        }
        brain.state=State.SEEK_TARGET;
        var remembered=brain.observation.known(brain.target);
        if (remembered!=null && position.distance(remembered.position())>5) {
            route(brain,position,remembered.position(),hazardActive); return;
        }
        if (brain.destination==null || horizontalDistance(position,brain.destination)<6 || brain.path.isEmpty()) {
            List<ArenaDefinition.NavNode> nodes=new ArrayList<>(graph.nodes());
            int current=graph.nearest(position);
            int index=brain.random.nextInt(nodes.size());
            int goal=nodes.get(index).id();
            if (goal==current) goal=nodes.get((index+1)%nodes.size()).id();
            route(brain,position,graph.position(goal),hazardActive);
        } else if (session.tick>=brain.replanAt) route(brain,position,brain.destination,hazardActive);
    }
    private void chooseTarget(VehicleState self,Brain brain,WorldQuery world) {
        boolean hasVisible=brain.observation.visible(brain.target)!=null;
        if (hasVisible && session.tick-brain.targetChanged<rules.targetChangeTicks()) return;
        int selected=-1; float best=Float.POSITIVE_INFINITY;
        Vector3f position=world.position(self.id),forward=world.forward(self.id);
        for (var opponent:brain.observation.visible()) {
            Vector3f offset=opponent.position().subtract(position);
            float score=offset.length()+angleDegrees(forward,offset)*.20f;
            if (self.lastAttacker==opponent.id() && session.tick-self.lastAttackTick<600) score-=10;
            if (score<best-.001f || (Math.abs(score-best)<.001f && brain.random.nextBoolean())) {
                selected=opponent.id(); best=score;
            }
        }
        if (selected<0 && brain.observation.known(brain.target)!=null) return;
        if (selected!=brain.target) {
            brain.target=selected; brain.targetChanged=session.tick; brain.lockTicks=0;
            brain.reactionUntil=session.tick+rules.reactionMinTicks()
                    +brain.random.nextInt(rules.reactionMaxTicks()-rules.reactionMinTicks()+1);
        }
    }
    private ArenaDefinition.Pickup choosePickup(Brain brain,Vector3f position,ArenaDefinition.PickupType type,boolean activeHazard) {
        ArenaDefinition.Pickup result=null; float best=Float.POSITIVE_INFINITY;
        int start=graph.nearest(position);
        for (var pickup:arena.pickups()) {
            if (pickup.type()!=type || session.tick<brain.pickupUnavailableUntil.getOrDefault(pickup.id(),0L)
                    || (activeHazard && arena.hazard().contains(pickup.position().vector()))) continue;
            List<Integer> path=graph.path(start,graph.nearest(pickup.position().vector()),activeHazard,
                    rules.turnPenalty(),rules.activeHazardPenalty());
            if (path.isEmpty()) continue;
            float distance=graph.pathLength(path)+position.distance(graph.position(start));
            if (distance>rules.maximumPickupPath()) continue;
            float score=distance-(pickup.id().equals(brain.pickup)?15:0);
            if (score<best) { best=score; result=pickup; }
        }
        return result;
    }
    private void route(Brain brain,Vector3f position,Vector3f destination,boolean hazardActive) {
        int goal=graph.nearest(destination);
        if (goal==brain.goalNode && !brain.path.isEmpty() && session.tick<brain.replanAt) {
            brain.destination=destination.clone(); return;
        }
        brain.destination=destination.clone(); brain.goalNode=goal;
        brain.path=graph.path(graph.nearest(position),goal,hazardActive,rules.turnPenalty(),rules.activeHazardPenalty());
        brain.pathIndex=0;
        if (brain.path.size()>1) {
            Vector3f start=graph.position(brain.path.getFirst()),next=graph.position(brain.path.get(1));
            Vector3f segment=next.subtract(start),offset=position.subtract(start);segment.y=0;offset.y=0;
            float projection=segment.lengthSquared()>0?offset.dot(segment)/segment.lengthSquared():0;
            float distance=offset.subtract(segment.mult(Math.clamp(projection,0,1))).length();
            float width=graph.links(brain.path.getFirst()).stream().filter(l->l.to()==brain.path.get(1)).findFirst().orElseThrow().width();
            // Replanning must not turn a moving car back toward a node it has already passed.
            if (horizontalDistance(position,start)<3 || (projection>0 && distance<Math.max(3,width*.4f))) brain.pathIndex=1;
        }
        brain.replanAt=session.tick+120;
    }
    private VehicleCommand drive(VehicleState self,Brain brain,WorldQuery world) {
        Vector3f position=world.position(self.id),velocity=world.velocity(self.id),forward=world.forward(self.id);
        float speed=velocity.length();
        if (needsRecovery(brain)) {
            brain.state=State.RECOVER; brain.requiredMovement=false;
            float longitudinal=velocity.dot(forward);
            return new VehicleCommand(longitudinal<-.5f?1:0,longitudinal>.5f?1:0,0,false,false,
                    false,false,false,0,false,true);
        }
        if (session.tick<brain.reverseUntil) {
            brain.state=State.RECOVER; brain.requiredMovement=false;
            float steer=brain.progressDirection.lengthSquared()>0 ? signedAngle(forward,brain.progressDirection) : .7f;
            return new VehicleCommand(0,.8f,-Math.signum(steer),false,false,false,false,false,0,false,false);
        }
        Vector3f destination=steeringTarget(brain,position,speed);
        Vector3f direction=destination.subtract(position); direction.y=0;
        if (direction.lengthSquared()<.01f) direction=forward.clone();
        brain.progressDirection=direction.normalize();
        float error=signedAngle(forward,direction);
        float steer=Math.clamp(error*rules.steeringGain(),-1,1);
        float desiredSpeed=rules.cruiseSpeed()*(1-.75f*Math.min(1,Math.abs(error)/1.5f));
        if (Math.abs(error)>2) desiredSpeed=6;
        if (brain.state==State.ATTACK && direction.length()<rules.attackDistance()) desiredSpeed=Math.min(desiredSpeed,12);
        if (brain.state==State.SEEK_PICKUP && horizontalDistance(position,brain.destination)<8) desiredSpeed=Math.min(desiredSpeed,7);
        // Three hull-width probes; only static blockers and other cars affect avoidance.
        float probeLength=Math.clamp(4+speed*.38f,4,14);
        Vector3f origin=position.add(0,.85f,0);
        Vector3f right=new Vector3f(forward.z,0,-forward.x).normalizeLocal();
        WorldQuery.Hit center=world.sweep(origin,origin.add(forward.mult(probeLength)),.8f,self.id);
        WorldQuery.Hit left=world.sweep(origin.subtract(right.mult(1.1f)),origin.subtract(right.mult(1.1f)).add(forward.mult(probeLength)),.45f,self.id);
        WorldQuery.Hit rightHit=world.sweep(origin.add(right.mult(1.1f)),origin.add(right.mult(1.1f)).add(forward.mult(probeLength)),.45f,self.id);
        // Ignore only the individual drivable surface hit, not a wall on another probe.
        if (center!=null && center.normal().y>.65f) center=null;
        if (left!=null && left.normal().y>.65f) left=null;
        if (rightHit!=null && rightHit.normal().y>.65f) rightHit=null;
        float clearance=center==null?1:center.fraction();
        if (clearance<1 || left!=null || rightHit!=null) {
            float leftClear=left==null?1:left.fraction(),rightClear=rightHit==null?1:rightHit.fraction();
            float avoid=Math.abs(rightClear-leftClear)>.04f ? Math.signum(rightClear-leftClear) : Math.signum(steer==0?1:steer);
            steer=Math.clamp(steer+avoid*.8f,-1,1);
            desiredSpeed=Math.min(desiredSpeed,Math.max(0,clearance*10-1));
        }
        boolean brake=speed>desiredSpeed+2;
        float throttle=brake?0:Math.clamp((desiredSpeed-speed)*.30f+.25f,0,1);
        float braking=brake?Math.clamp((speed-desiredSpeed)*.25f,0,1):0;
        if (desiredSpeed<.5f) { throttle=0; braking=1; }
        boolean handbrake=Math.abs(error)>1.15f && speed>10 && world.grounded(self.id);
        boolean turbo=brain.state==State.SEEK_TARGET && Math.abs(error)<.12f && clearance>=1
                && direction.length()>40 && self.turbo>30 && world.grounded(self.id);
        var target=brain.observation.visible(brain.target);
        boolean machineGun=false,rocket=false,pulse=false;
        int weaponDelta=0;
        if (target!=null && session.tick>=brain.reactionUntil && self.protectionTicks==0) {
            Vector3f toTarget=target.position().subtract(world.muzzle(self.id));
            float distance=toTarget.length(),angle=angleDegrees(forward,toTarget);
            boolean visible=lineOfSight(world,world.muzzle(self.id),target.position(),self.id,target.id());
            machineGun=visible && distance<=rules.machineGunRange() && angle<=rules.machineGunAngleDegrees() && !self.overheated;
            if (visible && distance<=70 && angle<=18) brain.lockTicks++; else brain.lockTicks=0;
            boolean safePower=visible && distance>10 && distance<65 && angle<=rules.powerAngleDegrees()
                    && world.sweep(world.muzzle(self.id),world.muzzle(self.id).add(forward.mult(9)),.2f,self.id)==null;
            int selected=safePower && self.powerAmmo>0 ? 1 : 0;
            if (selected!=self.selectedWeapon) weaponDelta=selected>self.selectedWeapon?1:-1;
            rocket=selected==1 ? safePower && self.powerAmmo>0 : brain.lockTicks>=18 && self.homingAmmo>0;
            pulse=visible && distance<=8 && self.pulseCooldown==0;
        } else brain.lockTicks=0;
        // Braking forever in front of a blocker is still a failed request to follow a route.
        brain.requiredMovement=horizontalDistance(position,brain.destination)>2;
        return new VehicleCommand(throttle,braking,steer,handbrake,turbo,machineGun,rocket,pulse,weaponDelta,false,false);
    }
    private Vector3f steeringTarget(Brain brain,Vector3f position,float speed) {
        if (brain.destination==null) return position.add(0,0,10);
        if (brain.path.isEmpty()) return brain.destination;
        float lookAhead=Math.clamp(rules.minimumLookAhead()+speed*.25f,rules.minimumLookAhead(),rules.maximumLookAhead());
        while (brain.pathIndex<brain.path.size()-1) {
            Vector3f current=graph.position(brain.path.get(brain.pathIndex));
            Vector3f next=graph.position(brain.path.get(brain.pathIndex+1));
            Vector3f onwards=next.subtract(current); onwards.y=0;
            Vector3f past=position.subtract(current); past.y=0;
            if (horizontalDistance(position,current)<Math.min(lookAhead,5)
                    || (past.dot(onwards)>0 && horizontalDistance(position,current)<8)) brain.pathIndex++;
            else break;
        }
        if (brain.pathIndex==brain.path.size()-1 && horizontalDistance(position,graph.position(brain.path.getLast()))<5)
            return brain.destination;
        Vector3f waypoint=graph.position(brain.path.get(brain.pathIndex));
        // A target beyond the corner is only blended close to that corner, preserving garage/ramp entrances.
        if (brain.pathIndex<brain.path.size()-1 && horizontalDistance(position,waypoint)<lookAhead) {
            Vector3f next=graph.position(brain.path.get(brain.pathIndex+1));
            float blend=Math.clamp((lookAhead-horizontalDistance(position,waypoint))/Math.max(1,waypoint.distance(next)),0,.4f);
            return waypoint.interpolateLocal(next,blend);
        }
        return waypoint;
    }
    private void trackProgress(VehicleState self,Brain brain,Vector3f position) {
        if (self.recoveries!=brain.recoveriesSeen) {
            brain.recoveriesSeen=self.recoveries; brain.reverseAttempts=0; brain.stuckSince=-1;
            brain.reverseUntil=0; brain.progress=0; brain.progressWindowStart=session.tick;
            brain.destination=null; brain.path=List.of(); brain.state=State.SEEK_TARGET;
        }
        if (brain.lastPosition!=null) {
            Vector3f displacement=position.subtract(brain.lastPosition); displacement.y=0;
            if (brain.requiredMovement) {
                brain.progress+=Math.max(0,displacement.dot(brain.progressDirection));
                if (displacement.lengthSquared()<.0001f) brain.stationaryTicks++; else brain.stationaryTicks=0;
                brain.maximumStationaryTicks=Math.max(brain.maximumStationaryTicks,brain.stationaryTicks);
            } else brain.stationaryTicks=0;
        }
        brain.lastPosition=position.clone();
        if (session.tick<brain.reverseUntil) { brain.progressWindowStart=session.tick; brain.progress=0; return; }
        if (session.tick-brain.progressWindowStart<rules.stuckCheckTicks()) return;
        if (brain.requiredMovement && brain.progress<rules.stuckMinimumProgress()) {
            if (brain.stuckSince<0) brain.stuckSince=brain.progressWindowStart;
            brain.reverseAttempts++; brain.reverseUntil=session.tick+rules.reverseTicks();
            brain.state=State.RECOVER; brain.replanAt=0; brain.goalNode=-1;
            LOG.warning("AI stuck id="+self.id+" tick="+session.tick+" position="+position+" destination="+brain.destination
                    +" target="+brain.target+" reverseAttempt="+brain.reverseAttempts);
        } else if (brain.progress>=rules.stuckMinimumProgress()) {
            brain.stuckSince=-1; brain.reverseAttempts=0;
        }
        brain.progress=0; brain.progressWindowStart=session.tick;
    }
    private boolean needsRecovery(Brain brain) {
        return brain.stuckSince>=0 && brain.reverseAttempts>=2 && session.tick-brain.stuckSince>rules.recoveryAfterTicks();
    }
    private boolean visibleActiveHazard(int vehicleId,WorldQuery world) {
        var hazard=arena.hazard();
        long phase=session.tick%hazard.periodTicks();
        if (phase<hazard.offTicks()+hazard.warningTicks()) return false;
        Vector3f center=new Vector3f((hazard.minX()+hazard.maxX())*.5f,.6f,(hazard.minZ()+hazard.maxZ())*.5f);
        Vector3f eye=world.position(vehicleId).add(0,.6f,0);
        return eye.distance(center)<=rules.sightRange() && world.ray(eye,center,vehicleId)==null;
    }
    public State state(int id) { return brains[id].state; }
    public int targetId(int id) { return brains[id].target; }
    public BotObservation observation(int id) { return brains[id].observation; }
    public List<Integer> route(int id) { return brains[id].path; }
    public Metrics metrics(int id) {
        Brain brain=brains[id];
        return new Metrics(id,brain.state,brain.target,brain.reverseAttempts,session.vehicle(id).recoveries,
                brain.aliveTicks,brain.maximumStationaryTicks,brain.destination==null?null:brain.destination.clone());
    }
    private static float horizontalDistance(Vector3f a,Vector3f b) {
        float x=a.x-b.x,z=a.z-b.z; return (float)Math.sqrt(x*x+z*z);
    }
    private static boolean lineOfSight(WorldQuery world,Vector3f from,Vector3f to,int self,int target) {
        WorldQuery.Hit hit=world.ray(from,to,self);
        return hit==null || hit.vehicleId()==target || hit.fraction()>=.999f;
    }
    private static float signedAngle(Vector3f forward,Vector3f target) {
        return (float)Math.atan2(forward.z*target.x-forward.x*target.z,forward.x*target.x+forward.z*target.z);
    }
    private static float angleDegrees(Vector3f a,Vector3f b) {
        float denominator=a.length()*b.length();
        if (denominator<1e-6f) return 0;
        return (float)Math.toDegrees(Math.acos(Math.clamp(a.dot(b)/denominator,-1,1)));
    }
}
