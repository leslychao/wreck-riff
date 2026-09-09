package game.wreckriff.ai;

import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.ProjectileState;

import game.wreckriff.combat.WeaponType;

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
        Vector3f destination,lastPosition,passingDestination,lastDrivingTarget,progressDirection=new Vector3f();
        long passingUntil,nextPassAttempt;
        String pickup;
        boolean healing,requiredMovement,backingToRoute,recoveryDetourAttempted;
        Brain(long seed) { random=new Random(seed); }
    }
    private final MatchSession session;
    private final ArenaDefinition arena;
    private final NavGraph graph;
    private final AiRules rules;
    private final Supplier<List<ArenaDefinition.Pickup>> activePickups;
    private Supplier<List<ProjectileState>> observedProjectiles=List::of;
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
            trackProgress(vehicle,brain,position,world);
            if (session.tick%rules.decisionTicks()==0 || brain.destination==null) {
                observe(vehicle,brain,world,active);
                decide(vehicle,brain,world);
            }
            VehicleCommand command=drive(vehicle,brain,world);
            AbilityId ability=chooseAbility(vehicle,brain,world);
            result.put(vehicle.id,new VehicleCommand(command.throttle(),command.brakeReverse(),command.steer(),command.handbrake(),
                    command.turbo(),command.machineGun(),command.selectedWeapon(),command.special(),command.weaponDelta(),
                    command.rearView(),command.recover(),ability));
        }
        cached=Collections.unmodifiableMap(result); return cached;
    }
    public void observeProjectiles(Supplier<List<ProjectileState>> projectiles) { observedProjectiles=Objects.requireNonNull(projectiles); }
    private AbilityId chooseAbility(VehicleState self,Brain brain,WorldQuery world) {
        if(session.tick<brain.reactionUntil)return AbilityId.NONE;
        Vector3f position=world.position(self.id),forward=world.forward(self.id);
        if(self.abilityCooldown(AbilityId.SHIELD)==0)for(ProjectileState projectile:observedProjectiles.get()) {
            Vector3f offset=projectile.position().subtract(position);
            if(projectile.ownerId()==self.id||offset.length()>35||angleDegrees(forward,offset)>rules.sightHalfAngleDegrees())continue;
            if(projectile.direction().dot(offset.negate().normalizeLocal())<.85f)continue;
            WorldQuery.Hit blocked=world.ray(position.add(0,.6f,0),projectile.position(),self.id);
            if(blocked==null||blocked.fraction()>=.99f)return AbilityId.SHIELD;
        }
        if(self.stunnedTicks>0||self.protectionTicks>0)return AbilityId.NONE;
        for(var target:brain.observation.visible()) {
            Vector3f offset=target.position().subtract(position);
            if(!lineOfSight(world,position.add(0,.6f,0),target.position(),self.id,target.id()))continue;
            float distance=offset.length(),angle=angleDegrees(forward,offset);
            if(distance<=14&&angle<=30&&self.abilityCooldown(AbilityId.STUN)==0)return AbilityId.STUN;
            if(distance>=18&&distance<=50&&angle<=5&&self.abilityCooldown(AbilityId.FREEZE)==0)return AbilityId.FREEZE;
        }
        return AbilityId.NONE;
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
            route(brain,position,graph.position(escape),true,world,self.id); return;
        }
        if (self.hp<self.maximumHp*rules.repairThreshold()) brain.healing=true;
        else if (self.hp>=self.maximumHp*rules.repairReleaseThreshold()) brain.healing=false;
        ArenaDefinition.PickupType wanted=brain.healing ? ArenaDefinition.PickupType.REPAIR
                : self.weapon(WeaponType.HOMING).ammo<Math.min(2,self.weapon(WeaponType.HOMING).maximumAmmo) ? ArenaDefinition.PickupType.HOMING_AMMO
                : self.weapon(WeaponType.POWER).ammo<Math.min(1,self.weapon(WeaponType.POWER).maximumAmmo) ? ArenaDefinition.PickupType.POWER_AMMO
                : self.weapon(WeaponType.MINE).ammo==0 ? ArenaDefinition.PickupType.MINE_AMMO
                : self.weapon(WeaponType.NAPALM).ammo==0 ? ArenaDefinition.PickupType.NAPALM_AMMO
                : self.turbo<rules.turboSeekThreshold() || continuingTurboRun(brain,self)
                    ? ArenaDefinition.PickupType.TURBO_CELL : null;
        if (wanted!=null) {
            ArenaDefinition.Pickup selected=choosePickup(brain,position,wanted,hazardActive,world,self.id);
            if (selected!=null) {
                brain.state=State.SEEK_PICKUP; brain.pickup=selected.id();
                route(brain,position,selected.position().vector(),hazardActive,world,self.id);
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
            WorldQuery.Hit obstacle=world.sweep(position.add(0,1.2f,0),destination.add(0,1.2f,0),1.05f,self.id);
            if (sameFloor && (obstacle==null || obstacle.vehicleId()==target.id())
                    && !(hazardActive && graph.crossesHazard(position,destination))) {
                brain.destination=destination.clone(); brain.path=List.of(); brain.goalNode=-1;
            } else route(brain,position,destination,hazardActive,world,self.id);
            return;
        }
        brain.state=State.SEEK_TARGET;
        var remembered=brain.observation.known(brain.target);
        if (remembered!=null && position.distance(remembered.position())>5) {
            route(brain,position,remembered.position(),hazardActive,world,self.id); return;
        }
        if (brain.destination==null || horizontalDistance(position,brain.destination)<6 || brain.path.isEmpty()) {
            List<ArenaDefinition.NavNode> nodes=new ArrayList<>(graph.nodes());
            int current=graph.nearest(position);
            int index=brain.random.nextInt(nodes.size());
            int goal=nodes.get(index).id();
            if (goal==current) goal=nodes.get((index+1)%nodes.size()).id();
            route(brain,position,graph.position(goal),hazardActive,world,self.id);
        } else if (session.tick>=brain.replanAt) route(brain,position,brain.destination,hazardActive,world,self.id);
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
    private boolean continuingTurboRun(Brain brain,VehicleState self) {
        // Passive regeneration crossing 20 must not cancel an already selected useful route.
        return brain.pickup!=null && self.turbo<100
                && session.tick>=brain.pickupUnavailableUntil.getOrDefault(brain.pickup,0L)
                && arena.pickups().stream().anyMatch(p->p.id().equals(brain.pickup) && p.type()==ArenaDefinition.PickupType.TURBO_CELL);
    }
    private ArenaDefinition.Pickup choosePickup(Brain brain,Vector3f position,ArenaDefinition.PickupType type,boolean activeHazard,
            WorldQuery world,int vehicleId) {
        ArenaDefinition.Pickup result=null; float best=Float.POSITIVE_INFINITY;
        int start=navigationStart(position,world,vehicleId);
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
    private void route(Brain brain,Vector3f position,Vector3f destination,boolean hazardActive,WorldQuery world,int vehicleId) {
        // The final connector belongs to the route too. A nearby visible goal must not cause
        // a replan back to its nearest graph node after the car has already left that node.
        if (horizontalDistance(position,destination)<12 && Math.abs(position.y-.45f-destination.y)<1.5f
                && !(hazardActive && graph.crossesHazard(position,destination))) {
            WorldQuery.Hit blocker=world.sweep(position.add(0,1.2f,0),destination.add(0,1.65f,0),1.05f,vehicleId);
            if ((blocker==null || drivableRampHit(blocker))
                    && supportedRoadConnection(vehicleId,position,destination.add(0,.45f,0),world)) {
                brain.destination=destination.clone();brain.path=List.of();brain.goalNode=-1;return;
            }
        }
        int goal=graph.nearest(destination);
        if (goal==brain.goalNode && !brain.path.isEmpty() && session.tick<brain.replanAt) {
            brain.destination=destination.clone(); return;
        }
        brain.destination=destination.clone(); brain.goalNode=goal;
        brain.path=graph.path(navigationStart(position,world,vehicleId),goal,hazardActive,rules.turnPenalty(),rules.activeHazardPenalty());
        brain.pathIndex=0;
        if (brain.path.size()>1) {
            Vector3f start=graph.position(brain.path.getFirst()),next=graph.position(brain.path.get(1));
            Vector3f segment=next.subtract(start),offset=position.subtract(start);segment.y=0;offset.y=0;
            float projection=segment.lengthSquared()>0?offset.dot(segment)/segment.lengthSquared():0;
            float distance=offset.subtract(segment.mult(Math.clamp(projection,0,1))).length();
            float width=graph.links(brain.path.getFirst()).stream().filter(l->l.to()==brain.path.get(1)).findFirst().orElseThrow().width();
            // Replanning must not turn a moving car back toward a node it has already passed.
            if (horizontalDistance(position,start)<3 || (projection>0 && distance<Math.max(3,width*.4f))) brain.pathIndex=1;
            else {
                Vector3f heading=world.forward(vehicleId);
                float startAngle=Math.abs(signedAngle(heading,start.subtract(position)));
                float nextAngle=Math.abs(signedAngle(heading,next.subtract(position)));
                if (startAngle>1.2f && nextAngle+.35f<startAngle && nextAngle<1.2f
                        && Math.abs(next.y-(position.y-.45f))<.75f
                        && !(hazardActive && graph.crossesHazard(position,next))
                        && world.sweep(position.add(0,1.2f,0),next.add(0,1.65f,0),1.2f,vehicleId)==null
                        && supportedRoadConnection(vehicleId,position,next.add(0,.45f,0),world)) brain.pathIndex=1;
            }
        }
        brain.replanAt=session.tick+120;
    }
    private int navigationStart(Vector3f position,WorldQuery world,int vehicleId) {
        Vector3f surface=position.add(0,-.45f,0);
        List<ArenaDefinition.NavNode> candidates=graph.nodes().stream()
                .filter(n->Math.abs(n.position().y()-surface.y)<2.2f)
                .sorted(Comparator.comparingDouble((ArenaDefinition.NavNode n)->n.position().vector().distanceSquared(surface))
                        .thenComparingInt(ArenaDefinition.NavNode::id)).toList();
        for (var node:candidates) {
            Vector3f endpoint=node.position().vector().add(0,.45f,0);
            if (position.distanceSquared(endpoint)<.1f) return node.id();
            WorldQuery.Hit blocker=world.sweep(position.add(0,1.2f,0),endpoint.add(0,1.2f,0),1.05f,vehicleId);
            if ((blocker==null || drivableRampHit(blocker)) && supportedRoadConnection(vehicleId,position,endpoint,world)) return node.id();
        }
        // A temporarily surrounded car keeps its route and uses the normal reverse/recovery rules.
        return graph.nearest(position);
    }
    private VehicleCommand drive(VehicleState self,Brain brain,WorldQuery world) {
        Vector3f position=world.position(self.id),velocity=world.velocity(self.id),forward=world.forward(self.id);
        float speed=velocity.length();
        if(needsRecovery(brain) && !brain.recoveryDetourAttempted && world.grounded(self.id)
                && world.rotation(self.id).mult(Vector3f.UNIT_Y).y>.8f) {
            // An upright car can fail a short turning circle while a clear longer
            // corridor remains. Try one observed, supported driving detour before
            // requesting a paid recovery; a failed detour still reaches recovery.
            brain.recoveryDetourAttempted=true;
            Vector3f obstruction=brain.lastDrivingTarget==null?position.add(forward):brain.lastDrivingTarget;
            Vector3f escape=passingDestination(self.id,position,forward,obstruction,world);
            if(escape!=null) {
                brain.passingDestination=escape;brain.passingUntil=session.tick+rules.recoveryAfterTicks();
                brain.reverseUntil=0;brain.stuckSince=-1;brain.reverseAttempts=0;
                brain.progress=0;brain.progressWindowStart=session.tick;brain.backingToRoute=false;
            }
        }
        if (needsRecovery(brain)) {
            brain.state=State.RECOVER; brain.requiredMovement=false;
            float longitudinal=velocity.dot(forward);
            return new VehicleCommand(longitudinal<-.5f?1:0,longitudinal>.5f?1:0,0,false,false,
                    false,false,false,0,false,true,AbilityId.NONE);
        }
        if (session.tick<brain.reverseUntil) {
            brain.state=State.RECOVER; brain.requiredMovement=false;
            float steer=brain.progressDirection.lengthSquared()>0 ? signedAngle(forward,brain.progressDirection) : .7f;
            // A confined elevated road cannot use the open-floor turning circle:
            // keep an established reverse approach aligned with that road.
            float reverseSteer=brain.backingToRoute && position.y>.8f
                    ? Math.clamp(-signedAngle(forward.negate(),brain.progressDirection)*rules.steeringGain(),-1,1)
                    : -Math.signum(steer);
            float support=supportedDistance(self.id,position,forward.negate(),world);
            if (support<4) {
                // The timed attempt keeps its duration, but must brake before an edge.
                return new VehicleCommand(velocity.dot(forward)<-.5f?1:0,0,reverseSteer,
                        false,false,false,false,false,0,false,false,AbilityId.NONE);
            }
            return new VehicleCommand(0,.8f,reverseSteer,false,false,false,false,false,0,false,false,AbilityId.NONE);
        }
        Vector3f destination=steeringTarget(brain,position,speed);
        if(brain.passingDestination!=null && horizontalDistance(position,brain.passingDestination)<4) {
            // Reaching the verified detour is a successful escape. A later, unrelated
            // blocked route gets its own attempt; timeout alone never resets it.
            brain.passingDestination=null;brain.recoveryDetourAttempted=false;
        } else if(brain.passingDestination!=null && session.tick>=brain.passingUntil)brain.passingDestination=null;
        var closeOpponent=brain.observation.visible().stream().min(Comparator.comparingDouble(e->e.position().distanceSquared(position))).orElse(null);
        if (brain.passingDestination==null && session.tick>=brain.nextPassAttempt && closeOpponent!=null
                && closeOpponent.position().distance(position)<rules.passDistance()
                && Math.abs(closeOpponent.position().y-position.y)<1.5f) {
            brain.passingDestination=passingDestination(self.id,position,forward,closeOpponent.position(),world);
            brain.passingUntil=session.tick+rules.passHoldTicks();
            brain.nextPassAttempt=session.tick+rules.decisionTicks();
        }
        if (brain.passingDestination!=null) destination=brain.passingDestination;
        ArenaDefinition.Ramp ramp=rampAt(position);
        if (ramp!=null && (position.x<ramp.minX()+1.1f || position.x>ramp.maxX()-1.1f)) ramp=null;
        if (ramp!=null) {
            // The ramp is a 12 m road without side barriers. Close combat still follows
            // its longitudinal corridor, with two passing lanes, rather than chasing a
            // moving enemy over its side and leaving the chassis suspended on the edge.
            float along=destination.z-position.z;
            float travel=Math.abs(along)>4?Math.signum(along):(forward.z>=0?1:-1);
            float laneOffset=closeOpponent!=null && closeOpponent.position().distance(position)<rules.passDistance()?-travel*2:0;
            destination=new Vector3f((ramp.minX()+ramp.maxX())*.5f+laneOffset,destination.y,
                    Math.clamp(position.z+travel*12,ramp.minZ()-4,ramp.maxZ()+4));
        }
        brain.lastDrivingTarget=destination.clone();
        Vector3f direction=destination.subtract(position); direction.y=0;
        if (direction.lengthSquared()<.01f) direction=forward.clone();
        brain.progressDirection=direction.normalize();
        float error=signedAngle(forward,direction);
        VehicleCommand backing=backTowardRoute(self,brain,world,position,forward,velocity,direction,error);
        if (backing!=null) return backing;
        float steer=Math.clamp(error*rules.steeringGain(),-1,1);
        float desiredSpeed=rules.cruiseSpeed()*(1-.75f*Math.min(1,Math.abs(error)/1.5f));
        if (Math.abs(error)>2) desiredSpeed=6;
        if (brain.state==State.ATTACK && direction.length()<rules.attackDistance()) desiredSpeed=Math.min(desiredSpeed,12);
        if (brain.state==State.SEEK_PICKUP && horizontalDistance(position,brain.destination)<8) desiredSpeed=Math.min(desiredSpeed,7);
        if (ramp!=null) desiredSpeed=Math.min(desiredSpeed,12);
        // Three hull-width probes; only static blockers and other cars affect avoidance.
        float probeLength=Math.clamp(4+speed*.38f,4,14);
        Vector3f origin=position.add(0,.85f,0);
        Vector3f right=new Vector3f(-forward.z,0,forward.x).normalizeLocal();
        WorldQuery.Hit center=world.sweep(origin,origin.add(forward.mult(probeLength)),.8f,self.id);
        WorldQuery.Hit left=world.sweep(origin.subtract(right.mult(1.1f)),origin.subtract(right.mult(1.1f)).add(forward.mult(probeLength)),.45f,self.id);
        WorldQuery.Hit rightHit=world.sweep(origin.add(right.mult(1.1f)),origin.add(right.mult(1.1f)).add(forward.mult(probeLength)),.45f,self.id);
        // Ignore only the individual drivable surface hit, not a wall on another probe.
        if (drivableRampHit(center)) center=null;
        if (drivableRampHit(left)) left=null;
        if (drivableRampHit(rightHit)) rightHit=null;
        if (center!=null && center.vehicleId()>=0 && center.fraction()*probeLength<4
                && beginLocalTrafficEscape(brain,self.id,position,forward,center.point(),world)) return VehicleCommand.NONE;
        float clearance=center==null?1:center.fraction();
        if (clearance<1 || left!=null || rightHit!=null) {
            float leftClear=left==null?1:left.fraction(),rightClear=rightHit==null?1:rightHit.fraction();
            float avoid=Math.abs(rightClear-leftClear)>.04f ? Math.signum(rightClear-leftClear) : Math.signum(steer==0?1:steer);
            steer=Math.clamp(steer+avoid*.8f,-1,1);
            float minimumPassingSpeed=center!=null && center.vehicleId()>=0?4:0;
            desiredSpeed=Math.min(desiredSpeed,Math.max(minimumPassingSpeed,clearance*10-1));
        }
        boolean brake=speed>desiredSpeed+2;
        float throttle=brake?0:Math.clamp((desiredSpeed-speed)*.30f+.25f,0,1);
        float braking=brake?Math.clamp((speed-desiredSpeed)*.25f,0,1):0;
        if (desiredSpeed<.5f) {
            float longitudinal=velocity.dot(forward);
            throttle=longitudinal<-.5f?1:0; braking=longitudinal>.5f?1:0;
        }
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
            machineGun=visible && distance<=rules.machineGunRange() && angle<=rules.machineGunAngleDegrees();
            if (visible && distance<=70 && angle<=18) brain.lockTicks++; else brain.lockTicks=0;
            boolean safePower=visible && distance>10 && distance<65 && angle<=rules.powerAngleDegrees()
                    && world.sweep(world.muzzle(self.id),world.muzzle(self.id).add(forward.mult(9)),.2f,self.id)==null;
            WeaponType selected=safePower && self.weapon(WeaponType.POWER).ammo>0 ? WeaponType.POWER:WeaponType.HOMING;
            rocket=selected==WeaponType.POWER?safePower&&self.weapon(selected).ammo>0:brain.lockTicks>=18&&self.weapon(selected).ammo>0;
            Vector3f lead=target.position().add(target.velocity().mult(1.1f)).subtract(world.muzzle(self.id));
            if(visible&&world.grounded(target.id())&&lead.length()>=24&&lead.length()<=38
                    &&angleDegrees(forward,lead)<7&&self.weapon(WeaponType.NAPALM).ammo>0) {
                selected=WeaponType.NAPALM;rocket=true;
            }
            for(var pursuer:brain.observation.visible()) {
                Vector3f behind=pursuer.position().subtract(position);
                if(behind.length()<=16&&angleDegrees(forward,behind)>=120&&self.weapon(WeaponType.MINE).ammo>0
                        &&pursuer.velocity().dot(behind.negate())>0) {selected=WeaponType.MINE;rocket=true;break;}
            }
            if(selected!=self.selectedWeapon)weaponDelta=Math.floorMod(selected.ordinal()-self.selectedWeapon.ordinal(),WeaponType.values().length)<=2?1:-1;
            rocket&=self.selectedWeapon.cycle(weaponDelta)==selected;
            pulse=visible && distance<=10 && self.pulseCooldown==0;
        } else brain.lockTicks=0;
        // Braking forever in front of a blocker is still a failed request to follow a route.
        brain.requiredMovement=horizontalDistance(position,brain.destination)>2;
        return new VehicleCommand(throttle,braking,steer,handbrake,turbo,machineGun,rocket,pulse,weaponDelta,false,false,AbilityId.NONE);
    }
    private VehicleCommand backTowardRoute(VehicleState self,Brain brain,WorldQuery world,Vector3f position,
            Vector3f forward,Vector3f velocity,Vector3f direction,float error) {
        // A waypoint behind a slow car requires room for a turning circle. Track it in reverse
        // through a clear rear corridor instead of repeatedly driving the nose into the same wall.
        // This is ordinary route driving; the separate timed stuck/recovery policy stays unchanged.
        if (!brain.backingToRoute) {
            if (velocity.length()>6 || !world.grounded(self.id) || Math.abs(error)<1.3f) return null;
            if (Math.abs(error)<1.8f) {
                WorldQuery.Hit front=world.sweep(position.add(0,.85f,0),position.add(0,.85f,0).add(forward.mult(5)),.8f,self.id);
                if (front==null || drivableRampHit(front) || front.fraction()*5>3.5f) return null;
            }
        }
        Vector3f backward=forward.negate();
        Vector3f origin=position.add(0,.85f,0);
        WorldQuery.Hit rear=world.sweep(origin,origin.add(backward.mult(6)),1.0f,self.id);
        float clearance=rear==null || drivableRampHit(rear)?6:rear.fraction()*6;
        float support=supportedDistance(self.id,position,backward,world);
        // On a ramp a shorter reverse corridor calls for a slower turn, not a
        // passing target uphill that reverses the chosen longitudinal route.
        if (support<4 && rampAt(position)==null && beginLocalTrafficEscape(brain,self.id,position,forward,position.add(backward.mult(4)),world)) {
            brain.backingToRoute=false;return VehicleCommand.NONE;
        }
        clearance=Math.min(clearance,support);
        if (rear!=null && rear.vehicleId()>=0 && clearance<3.5f
                && beginLocalTrafficEscape(brain,self.id,position,forward,rear.point(),world)) {
            brain.backingToRoute=false;return VehicleCommand.NONE;
        }
        if (Math.abs(error)<1.2f || direction.length()<.75f || clearance<2.5f) {
            brain.backingToRoute=false; return null;
        }
        brain.backingToRoute=true;
        float rearError=signedAngle(backward,direction);
        float steer=Math.clamp(-rearError*rules.steeringGain(),-1,1);
        float desired=Math.min(5,Math.max(1,Math.min(clearance-2,direction.length()*.7f)));
        float rearSpeed=velocity.dot(backward);
        float throttle=rearSpeed>desired+1?Math.clamp((rearSpeed-desired)*.3f,0,1):0;
        float reverse=throttle>0?0:Math.clamp((desired-rearSpeed)*.35f+.25f,0,1);
        brain.requiredMovement=true;
        return new VehicleCommand(throttle,reverse,steer,false,false,false,false,false,0,false,false,AbilityId.NONE);
    }
    private boolean beginLocalTrafficEscape(Brain brain,int id,Vector3f position,Vector3f forward,
            Vector3f contact,WorldQuery world) {
        if (brain.passingDestination!=null || session.tick<brain.nextPassAttempt) return false;
        brain.nextPassAttempt=session.tick+rules.decisionTicks();
        brain.passingDestination=passingDestination(id,position,forward,contact,world);
        brain.passingUntil=session.tick+rules.passHoldTicks();
        return brain.passingDestination!=null;
    }
    private float supportedDistance(int id,Vector3f position,Vector3f direction,WorldQuery world) {
        if (position.y<.8f && rampAt(position)==null) return 6;
        Vector3f heading=new Vector3f(direction.x,0,direction.z).normalizeLocal();
        Vector3f right=new Vector3f(-heading.z,0,heading.x);
        float previous=position.y-.45f;
        for (int distance=2;distance<=6;distance+=2) {
            Vector3f center=position.add(heading.mult(distance));
            float height=previous;
            for (int side:new int[]{0,-1,1}) {
                Vector3f point=center.add(right.mult(side*1.1f));point.y=previous+.45f;
                WorldQuery.Hit surface=world.ray(point.add(0,1.5f,0),point.add(0,-2,0),id);
                if (surface==null || surface.vehicleId()>=0 || surface.normal().y<.65f
                        || !roadSurface(surface.point(),true) || Math.abs(surface.point().y-previous)>.8f) return distance-1;
                if (side==0) height=surface.point().y;
            }
            previous=height;
        }
        return 6;
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
    private void trackProgress(VehicleState self,Brain brain,Vector3f position,WorldQuery world) {
        if(self.controlled()) {
            brain.lastPosition=position.clone();brain.progressWindowStart++;brain.stationaryTicks=0;
            if(brain.stuckSince>=0)brain.stuckSince++;
            if(brain.reverseUntil>session.tick)brain.reverseUntil++;
            return;
        }
        if (self.recoveries!=brain.recoveriesSeen) {
            brain.recoveriesSeen=self.recoveries; brain.reverseAttempts=0; brain.stuckSince=-1;
            brain.reverseUntil=0; brain.progress=0; brain.progressWindowStart=session.tick;
            brain.destination=null; brain.path=List.of(); brain.state=State.SEEK_TARGET;
            brain.passingDestination=null;
            brain.backingToRoute=false;brain.recoveryDetourAttempted=false;
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
            LOG.warning("AI stuck seed="+session.seed+" id="+self.id+" tick="+session.tick+" position="+position+" destination="+brain.destination
                    +" steeringTarget="+brain.lastDrivingTarget+" passingTarget="+brain.passingDestination
                    +" path="+brain.path+" pathIndex="+brain.pathIndex
                    +" forward="+world.forward(self.id)+" velocity="+world.velocity(self.id)+" backing="+brain.backingToRoute
                    +" target="+brain.target+" reverseAttempt="+brain.reverseAttempts);
        } else if (brain.progress>=rules.stuckMinimumProgress()) {
            brain.stuckSince=-1; brain.reverseAttempts=0;
        }
        brain.progress=0; brain.progressWindowStart=session.tick;
    }
    private boolean needsRecovery(Brain brain) {
        return brain.stuckSince>=0 && brain.reverseAttempts>=2 && session.tick-brain.stuckSince>rules.recoveryAfterTicks();
    }
    private Vector3f passingDestination(int id,Vector3f position,Vector3f forward,Vector3f opponent,WorldQuery world) {
        Vector3f heading=new Vector3f(forward.x,0,forward.z).normalizeLocal();
        Vector3f right=new Vector3f(-heading.z,0,heading.x);
        // Hold a clear drive-by corridor instead of braking nose-to-nose or continually
        // chasing a nearby opponent's moving center. Both drivers initially pass right.
        List<Vector3f> candidates=new ArrayList<>();
        for (float length:new float[]{rules.passForwardDistance(),12,8}) for (int side:new int[]{1,-1}) {
            float offset=length==rules.passForwardDistance()?rules.passSideOffset():4;
            candidates.add(position.add(heading.mult(length)).addLocal(right.mult(side*offset)));
        }
        // At the end of a narrow platform a forward pass can be impossible. Open a gap
        // along an observed clear route instead of repeatedly driving into the other hull.
        Vector3f away=position.subtract(opponent);away.y=0;away.normalizeLocal();
        for (float distance:new float[]{12,8}) candidates.add(position.add(away.mult(distance)));
        for (Vector3f candidate:candidates) {
            var bounds=arena.bounds();
            if (candidate.x<bounds.minX()+3 || candidate.x>bounds.maxX()-3
                    || candidate.z<bounds.minZ()+3 || candidate.z>bounds.maxZ()-3) continue;
            WorldQuery.Hit ground=world.ray(candidate.add(0,6,0),candidate.add(0,-7,0),id);
            if (ground==null || ground.vehicleId()>=0 || ground.normal().y<.65f || !roadSurface(ground.point())) continue;
            candidate.y=ground.point().y+.45f;
            WorldQuery.Hit obstacle=world.sweep(position.add(0,1.2f,0),candidate.add(0,1.2f,0),1.05f,id);
            if (obstacle!=null && !drivableRampHit(obstacle)) continue;
            if (!supportedRoadConnection(id,position,candidate,world)) continue;
            return candidate;
        }
        return null;
    }
    private boolean supportedRoadConnection(int id,Vector3f position,Vector3f candidate,WorldQuery world) {
        int samples=(int)Math.ceil(position.distance(candidate)/4);
        float previousHeight=position.y-.45f;
        for (int step=1;step<=samples;step++) {
            Vector3f point=position.clone().interpolateLocal(candidate,step/(float)samples);
            WorldQuery.Hit surface=world.ray(point.add(0,2,0),point.add(0,-3,0),id);
            if (surface==null || surface.vehicleId()>=0 || surface.normal().y<.65f
                    || !roadSurface(surface.point()) || Math.abs(surface.point().y-previousHeight)>1.3f) return false;
            previousHeight=surface.point().y;
        }
        return true;
    }
    private boolean drivableRampHit(WorldQuery.Hit hit) {
        if (hit==null || hit.vehicleId()>=0 || hit.normal().y<.65f) return false;
        // An upward normal also occurs on the top edge of a low guardrail. Only the
        // authored ramp corridor may rise into an elevated driving probe.
        Vector3f point=hit.point();
        return arena.ramps().stream().anyMatch(r->point.x>r.minX()-.4f && point.x<r.maxX()+.4f
                && point.z>=r.minZ()-1.2f && point.z<=r.maxZ()+1.2f);
    }
    private ArenaDefinition.Ramp rampAt(Vector3f position) {
        for (var ramp:arena.ramps()) if (position.x>=ramp.minX()-.5f && position.x<=ramp.maxX()+.5f
                && position.z>=ramp.minZ() && position.z<=ramp.maxZ()) {
            float height=ramp.startY()+(ramp.endY()-ramp.startY())*(position.z-ramp.minZ())/(ramp.maxZ()-ramp.minZ());
            if (Math.abs(position.y-height-.45f)<.8f) return ramp;
        }
        return null;
    }
    private boolean roadSurface(Vector3f point) {
        return roadSurface(point,false);
    }
    private boolean roadSurface(Vector3f point,boolean hullEdgeSample) {
        // Lateral hull probes already include the body width. Applying the centerline
        // clearance to them again would falsely block reverse exits beside a railing.
        float rampMargin=1.2f,deckMargin=hullEdgeSample?.2f:2.5f;
        if (Math.abs(point.y)<.15f) return true;
        for (var ramp:arena.ramps()) if (point.x>=ramp.minX()+rampMargin && point.x<=ramp.maxX()-rampMargin
                && point.z>=ramp.minZ() && point.z<=ramp.maxZ()) {
            float height=ramp.startY()+(ramp.endY()-ramp.startY())*(point.z-ramp.minZ())/(ramp.maxZ()-ramp.minZ());
            if (Math.abs(height-point.y)<.15f) return true;
        }
        var deck=arena.boxes().stream().filter(b->b.id().equals("upper-deck")).findFirst().orElseThrow();
        boolean rampOpening=arena.ramps().stream().anyMatch(r->point.x>=r.minX()+rampMargin && point.x<=r.maxX()-rampMargin
                && (Math.abs(r.minZ()-point.z)<3 || Math.abs(r.maxZ()-point.z)<3));
        return Math.abs(point.y-(deck.center().y()+deck.size().y()/2))<.15f
                && Math.abs(point.x-deck.center().x())<deck.size().x()/2-deckMargin
                && (Math.abs(point.z-deck.center().z())<deck.size().z()/2-deckMargin
                    || rampOpening && Math.abs(point.z-deck.center().z())<=deck.size().z()/2);
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
        return (float)Math.atan2(forward.x*target.z-forward.z*target.x,forward.x*target.x+forward.z*target.z);
    }
    private static float angleDegrees(Vector3f a,Vector3f b) {
        float denominator=a.length()*b.length();
        if (denominator<1e-6f) return 0;
        return (float)Math.toDegrees(Math.acos(Math.clamp(a.dot(b)/denominator,-1,1)));
    }
}
