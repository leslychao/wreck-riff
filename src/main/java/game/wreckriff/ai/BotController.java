package game.wreckriff.ai;

import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.CombatSystem;
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
    public enum TransitionPhase { ROAD, APPROACH, ALIGN, FLIGHT, LANDING }
    public record NavigationView(long revision,List<Integer> nodes,NavGraph.Traversal transition,TransitionPhase phase) {
        public NavigationView { nodes=List.copyOf(nodes); }
    }
    public record PickupReservation(String pickupId,int vehicleId,long untilTick) {}
    public enum BossCommandKind { HEAVY_STRIKE, PROTOCOL, RITE, RAM_MISSED }
    public enum BossCommandStage { BEGIN, COMPLETE }
    public record BossCommand(BossCommandKind kind,BossCommandStage stage,String targetId,int bossId,
                              ArenaDefinition.Vec3 observedTarget,long tick) {}
    public record BossActionView(String phase,int mode,long beganTick,long untilTick,ArenaDefinition.Vec3 targetPoint) {}
    private static final Logger LOG=Logger.getLogger(BotController.class.getName());
    // Below the native four-wheel deceleration, including the driver's partial-brake range.
    private static final float OBSTACLE_BRAKING_DECELERATION=10;
    private record Warning(Vector3f point,float radius,long impactTick) {}
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
        List<NavGraph.Traversal> traversals=List.of();
        long routeRevision=-1,landedAt=Long.MIN_VALUE/2,lastRecoveryTick=Long.MIN_VALUE/2;
        NavGraph.Traversal transition;
        TransitionPhase transitionPhase=TransitionPhase.ROAD;
        boolean wasFlying,stuckReplanned;
        BossTactics boss;
        List<Warning> warnings=List.of();
        int weaponCursor;
        Vector3f destination,lastPosition,passingDestination,lastDrivingTarget,progressDirection=new Vector3f();
        long passingUntil,nextPassAttempt;
        String pickup,launchTask;
        boolean healing,requiredMovement,backingToRoute,recoveryDetourAttempted,ballisticEvading;
        Brain(long seed) { random=new Random(seed); }
    }
    private final MatchSession session;
    private final ArenaDefinition arena;
    private final NavGraph graph;
    private final AiRules rules;
    private final Supplier<List<ArenaDefinition.Pickup>> activePickups;
    private Supplier<List<ProjectileState>> observedProjectiles=List::of;
    private Supplier<List<CombatSystem.BallisticWarningView>> observedBallisticWarnings=List::of;
    private final Map<Integer,Brain> brains=new HashMap<>();
    private final Map<String,PickupReservation> reservations=new LinkedHashMap<>();
    private final ArrayDeque<BossCommand> bossCommands=new ArrayDeque<>();
    private Supplier<List<ArenaDefinition.Hazard>> activeHazards;
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
        activeHazards=()->arena.hazards().stream().filter(h->ArenaSystems.phaseAt(session.tick,arena.hazards(),h.id())==ArenaSystems.HazardPhase.ACTIVE).toList();
        for(var vehicle:session.vehicles)registerParticipant(vehicle.id);
    }
    public Map<Integer,VehicleCommand> commands(WorldQuery world) {
        if (lastCommandTick==session.tick) return cached;
        lastCommandTick=session.tick;
        reservations.values().removeIf(r->r.untilTick()<=session.tick||!session.vehicle(r.vehicleId()).alive());
        Map<Integer,VehicleCommand> result=new LinkedHashMap<>();
        Set<String> active=new HashSet<>();
        for (var pickup:activePickups.get()) active.add(pickup.id());
        for (VehicleState vehicle:session.vehicles) {
            Brain brain=brains.get(vehicle.id);
            if (!vehicle.alive() || session.outcome!=MatchSession.Outcome.NONE) {
                brain.state=State.DESTROYED; result.put(vehicle.id,VehicleCommand.NONE); continue;
            }
            brain.aliveTicks++;
            Vector3f position=world.position(vehicle.id);
            var road=world.roadContext(vehicle.id);
            if(road.known()&&road.flying()) {
                brain.wasFlying=true;brain.transitionPhase=TransitionPhase.FLIGHT;
                brain.requiredMovement=false;brain.lastPosition=position.clone();brain.progressWindowStart=session.tick;
                brain.progress=0;brain.reverseUntil=0;brain.stuckSince=-1;
                // The shared driver/launch owner controls airborne attitude. Never steer towards an apex node.
                result.put(vehicle.id,VehicleCommand.NONE);continue;
            }
            if(brain.wasFlying) {
                brain.wasFlying=false;brain.landedAt=session.tick;brain.transitionPhase=TransitionPhase.LANDING;
                brain.reverseAttempts=0;brain.replanAt=0;
                if(brain.launchTask!=null&&road.level()>0) {
                    brain.launchTask=null;
                    if(brain.boss!=null){brain.boss.launchIndex++;brain.boss.nextLaunchTick=session.tick+1440;}
                }
            }
            if(brain.boss!=null) {
                brain.boss.advance(session.tick,session.bossMode);
                if(brain.boss.completionPending) {
                    brain.boss.completionPending=false;
                    if(selfBossHeavy(vehicle))emitBoss(brain.boss,BossCommandKind.HEAVY_STRIKE,
                            BossCommandStage.COMPLETE,"",vehicle.id,brain.boss.targetPoint);
                    if(brain.boss.lastContactTick<brain.boss.chargeStartedTick)emitBoss(brain.boss,BossCommandKind.RAM_MISSED,
                            BossCommandStage.COMPLETE,"",vehicle.id,brain.boss.targetPoint);
                }
                observeBossTurn(vehicle,brain,world);
            }
            trackProgress(vehicle,brain,position,world);
            if (session.tick%rules.decisionTicks()==0 || brain.destination==null) {
                observe(vehicle,brain,world,active);
                decide(vehicle,brain,world);
            }
            VehicleCommand command=drive(vehicle,brain,world);
            AbilityId ability=chooseAbility(vehicle,brain,world);
            // Save explosive ammunition until the receiver has its target: launching it on
            // approach pushes that target away and makes the truck evade its own salvo.
            boolean preparingGrab=vehicle.profileId.equals("grinder")&&(ability==AbilityId.SPECIAL
                    ||vehicle.specialPhase==VehicleState.SpecialPhase.GRINDER_WINDUP
                    ||vehicle.specialPhase==VehicleState.SpecialPhase.GRINDER_SEARCH);
            result.put(vehicle.id,new VehicleCommand(command.throttle(),command.brakeReverse(),command.steer(),command.handbrake(),
                    command.turbo(),command.machineGun(),command.selectedWeapon()&&!preparingGrab,
                    preparingGrab?null:command.directWeapon(),command.weaponDelta(),
                    command.rearView(),command.recover(),ability));
        }
        cached=Collections.unmodifiableMap(result); return cached;
    }
    public void observeProjectiles(Supplier<List<ProjectileState>> projectiles) { observedProjectiles=Objects.requireNonNull(projectiles); }
    public void observeBallisticWarnings(Supplier<List<CombatSystem.BallisticWarningView>> warnings) {
        observedBallisticWarnings=Objects.requireNonNull(warnings);
    }
    private AbilityId chooseAbility(VehicleState self,Brain brain,WorldQuery world) {
        if(session.tick<brain.reactionUntil)return AbilityId.NONE;
        Vector3f position=world.position(self.id),forward=world.forward(self.id);
        if(self.controlled()&&self.abilityCooldown(AbilityId.SHIELD)==0)return AbilityId.SHIELD;
        if(self.abilityCooldown(AbilityId.SHIELD)==0)for(Warning warning:brain.warnings) {
            float seconds=(warning.impactTick-session.tick)/(float)MatchSession.TICKS_PER_SECOND;
            if(seconds<=0||seconds>.8f||!warningVisible(self.id,warning.point,world))continue;
            Vector3f predicted=position.add(world.velocity(self.id).mult(seconds));
            if(sameWarningFloor(position,warning)&&horizontalDistance(predicted,warning.point)<warning.radius+2
                    && (self.controlled()||seconds<(warning.radius+2-horizontalDistance(position,warning.point))/6f+.2f))
                return AbilityId.SHIELD;
        }
        if(self.abilityCooldown(AbilityId.SHIELD)==0)for(ProjectileState projectile:observedProjectiles.get()) {
            Vector3f offset=projectile.position().subtract(position);
            if(projectile.ownerId()==self.id||offset.length()>35||angleDegrees(forward,offset)>rules.sightHalfAngleDegrees())continue;
            if(projectile.direction().dot(offset.negate().normalizeLocal())<.85f)continue;
            WorldQuery.Hit blocked=world.ray(position.add(0,.6f,0),projectile.position(),self.id);
            if(blocked==null||blocked.fraction()>=.99f)return AbilityId.SHIELD;
        }
        if(self.protectionTicks>0)return AbilityId.NONE;
        for(var target:brain.observation.visible()) {
            Vector3f offset=target.position().subtract(position);
            if(!lineOfSight(world,position.add(0,.6f,0),target.position(),self.id,target.id()))continue;
            float distance=offset.length(),angle=angleDegrees(forward,offset);
            if(!self.boss&&!self.controlled()&&!self.specialActive()&&self.abilityCooldown(AbilityId.SPECIAL)==0) {
                boolean use=switch(self.profileId) {
                    case "rivet"->distance<=13&&angle<=30;
                    case "grinder"->world.grounded(self.id)&&distance<=20&&angle<=25;
                    case "spark"->world.grounded(self.id)&&distance<=9;
                    default->false;
                };
                if(use)return AbilityId.SPECIAL;
            }
            if(!self.specialActive()&&distance>=18&&distance<=50&&angle<=5&&self.abilityCooldown(AbilityId.FREEZE)==0)return AbilityId.FREEZE;
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
        brain.memory.values().removeIf(e->session.tick-e.observedTick()>(self.boss?720:rules.memoryTicks()) || !session.vehicle(e.id()).alive());
        List<BotObservation.Opponent> remembered=brain.memory.values().stream()
                .filter(e->visible.stream().noneMatch(v->v.id()==e.id())).sorted(Comparator.comparingInt(BotObservation.Opponent::id)).toList();
        brain.observation=new BotObservation(session.tick,visible,remembered);
        if(brain.boss!=null)brain.boss.observed(brain.observation.visible(brain.target));
        brain.warnings=observedBallisticWarnings.get().stream().filter(w->w.remainingTicks()>0&&warningVisible(self.id,w.point(),world))
                .map(w->new Warning(w.point(),w.radius(),session.tick+w.remainingTicks())).toList();
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
        if(evadeBallistic(self,brain,position,hazardActive,world))return;
        if (hazardActive && insideActiveHazard(position)) {
            if(brain.boss!=null)brain.boss.cancel(session.tick);
            brain.state=State.EVADE_HAZARD; brain.pickup=null;
            int escape=graph.nodes().stream().filter(n->!insideActiveHazard(n.position().vector()))
                    .min(Comparator.comparingDouble(n->n.position().vector().distanceSquared(position))).orElseThrow().id();
            route(brain,position,graph.position(escape),true,world,self.id); return;
        }
        if (self.hp<self.maximumHp*rules.repairThreshold()) brain.healing=true;
        else if (self.hp>=self.maximumHp*rules.repairReleaseThreshold()) brain.healing=false;
        ArenaDefinition.PickupType wanted=brain.healing ? ArenaDefinition.PickupType.REPAIR
                : self.boss ? bossSupply(self)
                : self.weapon(WeaponType.HOMING).ammo<Math.min(2,self.weapon(WeaponType.HOMING).maximumAmmo) ? ArenaDefinition.PickupType.HOMING_AMMO
                : self.weapon(WeaponType.POWER).ammo<Math.min(1,self.weapon(WeaponType.POWER).maximumAmmo) ? ArenaDefinition.PickupType.POWER_AMMO
                : self.weapon(WeaponType.MINE).ammo==0 ? ArenaDefinition.PickupType.MINE_AMMO
                : self.weapon(WeaponType.NAPALM).ammo==0 ? ArenaDefinition.PickupType.NAPALM_AMMO
                : self.weapon(WeaponType.BALLISTIC).ammo==0 ? ArenaDefinition.PickupType.BALLISTIC_AMMO
                : self.weapon(WeaponType.CANNON).ammo==0 ? ArenaDefinition.PickupType.CANNON_AMMO
                : self.turbo<rules.turboSeekThreshold() || continuingTurboRun(brain,self)
                    ? ArenaDefinition.PickupType.TURBO_CELL : null;
        if (wanted!=null) {
            ArenaDefinition.Pickup selected=choosePickup(brain,position,wanted,hazardActive,world,self.id);
            if (selected!=null) {
                brain.state=State.SEEK_PICKUP; brain.pickup=selected.id();
                reservations.put(selected.id(),new PickupReservation(selected.id(),self.id,session.tick+360));
                route(brain,position,selected.position().vector(),hazardActive,world,self.id);
                chooseTarget(self,brain,world); return;
            }
        }
        brain.pickup=null;
        chooseTarget(self,brain,world);
        if(self.boss&&decideBoss(self,brain,world,hazardActive))return;
        var target=brain.observation.visible(brain.target);
        if (target!=null) {
            brain.state=State.ATTACK;
            Vector3f destination=target.position();
            boolean sameFloor=sameObservedLevel(self.id,target.id(),position,destination,world);
            WorldQuery.Hit obstacle=world.sweep(position.add(0,1.2f,0),destination.add(0,1.2f,0),halfWidth(world,self.id),self.id);
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
    private ArenaDefinition.Boss bossDefinition(VehicleState self) {
        return arena.bosses().stream().filter(b->b.profileId().equals(self.profileId)).findFirst().orElseThrow();
    }
    private ArenaDefinition.PickupType bossSupply(VehicleState self) {
        var definition=bossDefinition(self);
        for(var weapon:List.of(definition.primary(),definition.secondary()))if(self.weapon(weapon).ammo<1)
            return ArenaDefinition.PickupType.valueOf(weapon.name()+"_AMMO");
        return self.turbo<rules.turboSeekThreshold()?ArenaDefinition.PickupType.TURBO_CELL:null;
    }
    private boolean decideBoss(VehicleState self,Brain brain,WorldQuery world,boolean avoidHazards) {
        var policy=brain.boss;var target=brain.observation.visible(brain.target);policy.observed(target);
        Vector3f position=world.position(self.id);
        if(session.phase!=MatchSession.Phase.BOSS_COMBAT)return false;
        if(brain.launchTask!=null&&!brain.path.isEmpty()&&brain.pathIndex<brain.path.size()&&brain.routeRevision==graph.revision()) {
            brain.state=State.SEEK_TARGET;return true;
        }
        if(policy.phase==BossTactics.Phase.TELEGRAPH||policy.phase==BossTactics.Phase.CHARGE) {
            brain.state=State.ATTACK;brain.destination=policy.targetPoint.clone();return true;
        }
        var known=brain.observation.known(brain.target);
        if(self.profileId.equals("boss_prefect")&&known!=null&&position.distance(known.position())>75) {
            if(policy.farSince<0)policy.farSince=session.tick;
            if(session.tick-policy.farSince>=600) {
                brain.state=State.SEEK_TARGET;route(brain,position,known.position(),avoidHazards,world,self.id);return true;
            }
        } else policy.farSince=-1;
        if(target==null) {
            if(policy.lastSeen!=null&&session.tick>=policy.nextSearchTick&&session.tick-policy.lastSeenTick>=720) {
                // Move to an authored exit adjacent to the last observed location, never the hidden live transform.
                int last=graph.nearest(policy.lastSeen);
                var exits=graph.links(last);int goal=exits.isEmpty()?last:exits.get(Math.floorMod(policy.side++,exits.size())).to();
                route(brain,position,graph.position(goal),avoidHazards,world,self.id);policy.nextSearchTick=session.tick+720;
                brain.state=State.SEEK_TARGET;return true;
            }
            return false;
        }
        boolean sameLevel=sameObservedLevel(self.id,target.id(),position,target.position(),world);
        boolean ramCorridor=policy.canRam(session.tick,session.bossMode)&&session.tick-brain.landedAt>=84&&sameLevel
                &&horizontalDistance(position,target.position())>=policy.timing.minimumRange()
                &&horizontalDistance(position,target.position())<=policy.timing.maximumRange()
                &&world.sweep(position.add(0,1.2f,0),target.position().add(0,1.2f,0),halfWidth(world,self.id),self.id)==null
                &&supportedRoadConnection(self.id,position,target.position(),world);
        if(ramCorridor&&angleDegrees(world.forward(self.id),target.position().subtract(position).setY(0))<=12) {
            policy.beginRam(session.tick,position,target.position());brain.state=State.ATTACK;
            brain.passingDestination=null;brain.destination=policy.targetPoint.clone();brain.path=List.of();brain.transition=null;
            if(selfBossHeavy(self))emitBoss(policy,BossCommandKind.HEAVY_STRIKE,BossCommandStage.BEGIN,"",self.id,target.position());
            return true;
        }
        if(ramCorridor) {
            // A real wheeled chassis must turn while approaching before the advertised warning begins.
            brain.state=State.ATTACK;brain.destination=target.position();brain.path=List.of();brain.transition=null;
            return true;
        }
        requestBossProtocol(self,brain,target,world);
        if(self.profileId.equals("boss_emcee")&&session.tick>=policy.nextLaunchTick&&session.tick-brain.landedAt>=84
                &&brain.transition==null&&!bossDefinition(self).launchPadIds().isEmpty()) {
            var ids=bossDefinition(self).launchPadIds();String pad=ids.get(policy.launchIndex%ids.size());
            var edge=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.LAUNCH&&e.objectId().equals(pad)).findFirst().orElseThrow();
            var mobility=mobility(self.id,world);
            var groundOnly=new NavGraph.Mobility(mobility.width(),mobility.height(),mobility.speed(),false,mobility.drop(),Set.of());
            var prefix=graph.route(navigationStart(position,world,self.id),edge.from(),groundOnly,Set.of(),rules.turnPenalty(),rules.activeHazardPenalty());
            if(prefix.found()) {
                var nodes=new ArrayList<>(prefix.nodes());nodes.add(edge.to());var traversals=new ArrayList<>(prefix.traversals());
                traversals.add(new NavGraph.Traversal(edge.from(),edge.to(),edge.id(),edge.type(),edge.objectId()));
                brain.path=List.copyOf(nodes);brain.traversals=List.copyOf(traversals);brain.routeRevision=graph.revision();
                brain.pathIndex=0;brain.goalNode=edge.to();brain.destination=graph.position(edge.to());brain.state=State.SEEK_TARGET;
                brain.launchTask=pad;policy.nextLaunchTick=session.tick+1440;return true;
            }
            policy.nextLaunchTick=session.tick+360;
        }
        brain.state=State.ATTACK;
        if(!sameLevel) {route(brain,position,target.position(),avoidHazards,world,self.id);return true;}
        Vector3f away=position.subtract(target.position());away.y=0;if(away.lengthSquared()<1)away=world.forward(self.id).negate();away.normalizeLocal();
        Vector3f sideways=new Vector3f(-away.z,0,away.x).mult(policy.side);
        float leadSeconds=self.profileId.equals("boss_prefect")?Math.min(2,position.distance(target.position())/45):0;
        Vector3f candidate=target.position().add(target.velocity().mult(leadSeconds)).addLocal(away.mult(policy.standOff()))
                .addLocal(sideways.mult(self.profileId.equals("boss_ash_shepherd")?24:16));
        int goal=graph.nearest(candidate,world.roadContext(self.id).known()?world.roadContext(self.id).surfaceId():null);
        route(brain,position,graph.position(goal),avoidHazards,world,self.id);return true;
    }
    private void requestBossProtocol(VehicleState self,Brain brain,BotObservation.Opponent target,WorldQuery world) {
        var policy=brain.boss;
        if(session.tick<policy.nextProtocolTick||policy.phase!=BossTactics.Phase.CRUISE)return;
        if((selfBossHeavy(self)||self.profileId.equals("boss_ash_shepherd"))&&session.bossMode<2)return;
        if(self.profileId.equals("boss_ash_shepherd")&&(world.roadContext(self.id).level()==0||world.roadContext(target.id()).level()==0))return;
        Vector3f predicted=target.position().add(target.velocity().mult(.6f));
        if(self.profileId.equals("boss_prefect")&&!arena.barriers().isEmpty()&&(session.bossMode==1||policy.protocolIndex%2==0)) {
            var barrier=arena.barriers().stream().min(Comparator.comparingDouble(b->arena.boxes().stream()
                    .filter(box->box.id().equals(b.geometryId())).findFirst().orElseThrow().center().vector().distanceSquared(predicted))).orElseThrow();
            emitBoss(policy,BossCommandKind.PROTOCOL,BossCommandStage.BEGIN,barrier.id(),self.id,target.position());
            policy.nextProtocolTick=session.tick+policy.protocolCooldown()*(session.bossMode==1?2:1);
            policy.protocolIndex++;policy.side=-policy.side;return;
        }
        var selected=arena.hazards().stream().filter(h->!selfBossHeavy(self)||h.type()==ArenaDefinition.HazardType.CRANE)
                .filter(h->!self.profileId.equals("boss_ash_shepherd")||h.type()==ArenaDefinition.HazardType.FIRE)
                .min(Comparator.comparingDouble(h->h.center().vector().distanceSquared(predicted))).orElse(null);
        if(selected==null)return;
        emitBoss(policy,self.profileId.equals("boss_ash_shepherd")?BossCommandKind.RITE:BossCommandKind.PROTOCOL,
                BossCommandStage.BEGIN,selected.id(),self.id,target.position());
        policy.nextProtocolTick=session.tick+policy.protocolCooldown();policy.protocolIndex++;policy.side=-policy.side;
    }
    private static boolean selfBossHeavy(VehicleState self) {return self.profileId.equals("boss_foreman");}
    private void emitBoss(BossTactics policy,BossCommandKind kind,BossCommandStage stage,String hazard,int id,Vector3f point) {
        if(bossCommands.size()>=64)throw new IllegalStateException("Boss command consumer did not drain its bounded queue");
        bossCommands.add(new BossCommand(kind,stage,hazard,id,new ArenaDefinition.Vec3(point.x,point.y,point.z),session.tick));
    }
    public List<BossCommand> drainBossCommands() {var commands=List.copyOf(bossCommands);bossCommands.clear();return commands;}
    public Optional<BossActionView> bossAction(int id) {
        var boss=brains.get(id).boss;
        return boss==null?Optional.empty():Optional.of(new BossActionView(boss.phase.name(),boss.mode,boss.beganTick,boss.untilTick,boss.point()));
    }
    public void confirmRamContact(int bossId,long tick) {
        var brain=brains.get(bossId);if(brain!=null&&brain.boss!=null)brain.boss.lastContactTick=Math.max(brain.boss.lastContactTick,tick);
    }
    private void observeBossTurn(VehicleState self,Brain brain,WorldQuery world) {
        var policy=brain.boss;if(!self.profileId.equals("boss_prefect"))return;
        Vector3f forward=world.forward(self.id);
        if(session.tick-policy.turnWindowTick>120){policy.turnWindowTick=session.tick;policy.turning=0;}
        if(policy.lastForward!=null&&world.velocity(self.id).length()>8&&world.grounded(self.id))
            policy.turning+=angleDegrees(policy.lastForward,forward);
        policy.lastForward=forward;
        if(policy.turning>=60&&session.tick>=policy.nextTurnTick&&session.phase==MatchSession.Phase.BOSS_COMBAT) {
            // The native chassis actually turned; this is the same exposed-side window as a completed city protocol.
            emitBoss(policy,BossCommandKind.PROTOCOL,BossCommandStage.COMPLETE,"",self.id,
                    policy.lastSeen==null?world.position(self.id):policy.lastSeen);
            policy.turning=0;policy.nextTurnTick=session.tick+216;
        }
    }
    private WeaponType chooseWeapon(VehicleState self,Brain brain,WorldQuery world,BotObservation.Opponent target,
            boolean visible,float distance,float angle) {
        EnumSet<WeaponType> eligible=EnumSet.noneOf(WeaponType.class);
        Vector3f muzzle=world.muzzle(self.id),forward=world.forward(self.id);
        var combat=session.combatRules;
        boolean locked=brain.lockTicks>=Math.round(combat.targeting().acquisitionSeconds()*MatchSession.TICKS_PER_SECOND);
        if(visible) {
            if(locked)eligible.add(WeaponType.HOMING);
            if(distance>10&&distance<65&&angle<=rules.powerAngleDegrees()
                    &&world.sweep(muzzle,muzzle.add(forward.mult(9)),.2f,self.id)==null)eligible.add(WeaponType.POWER);
            var assist=combat.napalm().assist();
            // CombatSystem owns the 0.25s/5m lead and guided arc; the driver uses its actual assist envelope.
            if(distance>=assist.minimumRange()&&distance<=assist.maximumRange()&&angle<=assist.coneDegrees())eligible.add(WeaponType.NAPALM);
            if(CombatSystem.selectBallisticTarget(session,self.id,world)>=0
                    &&world.staticSweep(muzzle,muzzle.add(0,combat.ballistic().carrierHeight(),0),.25f)==null)eligible.add(WeaponType.BALLISTIC);
            Vector3f cannonLead=target.position().add(target.velocity().mult(distance/combat.cannon().speed())).subtract(muzzle);
            if(distance>combat.cannon().splashRadius()+4&&distance<=Math.min(rules.machineGunRange(),combat.cannon().speed())
                    &&angleDegrees(forward,cannonLead)<=rules.powerAngleDegrees()
                    &&world.sweep(muzzle,muzzle.add(forward.mult(9)),combat.cannon().radius(),self.id)==null)eligible.add(WeaponType.CANNON);
        }
        for(var pursuer:brain.observation.visible()) {
            Vector3f behind=pursuer.position().subtract(world.position(self.id));
            if(behind.length()<=16&&angleDegrees(forward,behind)>=120&&pursuer.velocity().dot(behind.negate())>0
                    &&lineOfSight(world,muzzle,pursuer.position(),self.id,pursuer.id()))eligible.add(WeaponType.MINE);
        }
        // Rotate only among currently useful, ready weapons; a preferred cooling weapon cannot starve the rest.
        WeaponType[] weapons=self.boss?new WeaponType[]{bossDefinition(self).primary(),bossDefinition(self).secondary()}:WeaponType.values();
        for(int step=0;step<weapons.length;step++) {
            int index=(brain.weaponCursor+step)%weapons.length;WeaponType weapon=weapons[index];
            if(eligible.contains(weapon)&&self.weapon(weapon).ammo>0&&self.weapon(weapon).cooldownTicks==0) {
                brain.weaponCursor=(index+1)%weapons.length;return weapon;
            }
        }
        return null;
    }
    private boolean warningVisible(int id,Vector3f point,WorldQuery world) {
        Vector3f eye=world.position(id).add(0,.6f,0),marker=point.add(0,.15f,0),offset=marker.subtract(eye);
        WorldQuery.Hit blocker=world.ray(eye,marker,id);
        return offset.length()<=rules.sightRange()&&angleDegrees(world.forward(id),offset)<=rules.sightHalfAngleDegrees()
                &&(blocker==null||blocker.fraction()>=.999f);
    }
    private static boolean sameWarningFloor(Vector3f position,Warning warning) {
        return Math.abs(position.y-.45f-warning.point.y)<2.2f;
    }
    private boolean evadeBallistic(VehicleState self,Brain brain,Vector3f position,boolean hazardActive,WorldQuery world) {
        List<Warning> active=brain.warnings.stream().filter(w->w.impactTick>session.tick&&sameWarningFloor(position,w)).toList();
        boolean threat=active.stream().anyMatch(w->{
            float remaining=(w.impactTick-session.tick)/(float)MatchSession.TICKS_PER_SECOND;
            Vector3f next=position.add(world.velocity(self.id).mult(Math.min(remaining,1.2f)));
            return horizontalDistance(position,w.point)<w.radius+2||!safeWarningSegment(position,next,List.of(w))
                    ||brain.ballisticEvading&&horizontalDistance(position,w.point)<w.radius+10;
        });
        if(!threat) {brain.ballisticEvading=false;return false;}
        int start=navigationStart(position,world,self.id);
        List<ArenaDefinition.NavNode> candidates=graph.nodes().stream()
                .filter(n->world.roadContext(self.id).known()?arena.surfaces().stream()
                        .anyMatch(s->s.id().equals(n.surfaceId())&&s.level()==world.roadContext(self.id).level())
                        :Math.abs(n.position().y()+roadOffset(world,self.id)-position.y)<2.2f)
                .filter(n->active.stream().noneMatch(w->horizontalDistance(n.position().vector(),w.point)<w.radius+3))
                .filter(n->!hazardActive||!insideActiveHazard(n.position().vector()))
                .sorted(Comparator.comparingDouble((ArenaDefinition.NavNode n)->n.position().vector().distanceSquared(position))
                        .thenComparingInt(ArenaDefinition.NavNode::id)).limit(12).toList();
        for(var candidate:candidates) {
            List<Integer> path=plannedRoute(start,candidate.id(),self.id,world,hazardActive).nodes();
            if(path.isEmpty())continue;
            Vector3f previous=position;boolean safe=true;
            for(int node:path) {
                Vector3f next=graph.position(node).add(0,roadOffset(world,self.id),0);
                if(!safeWarningSegment(previous,next,active)) {safe=false;break;}
                previous=next;
            }
            if(!safe)continue;
            brain.state=State.EVADE_HAZARD;brain.pickup=null;brain.passingDestination=null;brain.ballisticEvading=true;
            brain.goalNode=-1;route(brain,position,candidate.position().vector(),hazardActive,world,self.id);return true;
        }
        // A blocked escape still permits ordinary obstacle recovery and the imminent-hit shield decision.
        return false;
    }
    private static boolean safeWarningSegment(Vector3f from,Vector3f to,List<Warning> warnings) {
        Vector3f direction=to.subtract(from);direction.y=0;
        for(Warning warning:warnings) {
            Vector3f relative=from.subtract(warning.point);relative.y=0;
            float fraction=direction.lengthSquared()<1e-6f?0:Math.clamp(-relative.dot(direction)/direction.lengthSquared(),0,1);
            Vector3f closest=from.clone().interpolateLocal(to,fraction);
            if(!sameWarningFloor(closest,warning)||horizontalDistance(closest,warning.point)>=warning.radius+2)continue;
            // A car already inside may leave monotonically; an outside segment may never cut through the circle.
            if(sameWarningFloor(from,warning)&&horizontalDistance(from,warning.point)<warning.radius+2
                    &&relative.dot(direction)>=-.001f&&horizontalDistance(to,warning.point)>=horizontalDistance(from,warning.point))continue;
            return false;
        }
        return true;
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
            var reservation=reservations.get(pickup.id());
            if (pickup.type()!=type || session.tick<brain.pickupUnavailableUntil.getOrDefault(pickup.id(),0L)
                    || (reservation!=null&&reservation.vehicleId()!=vehicleId&&reservation.untilTick()>session.tick)
                    || (activeHazard && insideActiveHazard(pickup.position().vector()))) continue;
            List<Integer> path=plannedRoute(start,graph.nearest(pickup.position().vector()),vehicleId,world,activeHazard).nodes();
            if (path.isEmpty()) continue;
            float distance=graph.pathLength(path)+position.distance(graph.position(start));
            if (distance>rules.maximumPickupPath()) continue;
            float score=distance-(pickup.id().equals(brain.pickup)?15:0);
            if (score<best) { best=score; result=pickup; }
        }
        return result;
    }
    private void route(Brain brain,Vector3f position,Vector3f destination,boolean hazardActive,WorldQuery world,int vehicleId) {
        if(brain.transition!=null&&brain.routeRevision==graph.revision()
                &&brain.transition.type()!=ArenaDefinition.Transition.ROAD
                &&!transitionReached(brain.transition,position,world,vehicleId)) {
            brain.destination=destination.clone();return;
        }
        // The final connector belongs to the route too. A nearby visible goal must not cause
        // a replan back to its nearest graph node after the car has already left that node.
        float offset=roadOffset(world,vehicleId);
        if (horizontalDistance(position,destination)<12 && Math.abs(position.y-offset-destination.y)<1.5f
                && !(hazardActive && graph.crossesHazard(position,destination))
                &&safeWarningSegment(position,destination.add(0,offset,0),brain.warnings)) {
            WorldQuery.Hit blocker=world.sweep(position.add(0,1.2f,0),destination.add(0,1.2f+offset,0),halfWidth(world,vehicleId),vehicleId);
            if ((blocker==null || drivableRampHit(blocker))
                    && supportedRoadConnection(vehicleId,position,destination.add(0,offset,0),world)) {
                brain.destination=destination.clone();brain.path=List.of();brain.goalNode=-1;return;
            }
        }
        int goal=graph.nearest(destination);
        if (goal==brain.goalNode && !brain.path.isEmpty() && session.tick<brain.replanAt&&brain.routeRevision==graph.revision()) {
            brain.destination=destination.clone(); return;
        }
        brain.destination=destination.clone(); brain.goalNode=goal;
        var planned=plannedRoute(navigationStart(position,world,vehicleId),goal,vehicleId,world,hazardActive);
        if(!planned.found()) {
            // An inaccessible secret/underside is intercepted at a reachable authored exit.
            // Never feed its unreachable coordinate to the ordinary direct driver.
            int start=navigationStart(position,world,vehicleId);
            for(var candidate:graph.nodes().stream().sorted(Comparator.comparingDouble(n->n.position().vector().distanceSquared(destination)))
                    .limit(16).toList()) {
                var alternative=plannedRoute(start,candidate.id(),vehicleId,world,hazardActive);
                if(alternative.found()) {planned=alternative;brain.destination=graph.position(candidate.id());break;}
            }
            if(!planned.found())brain.destination=position.clone();
        }
        brain.path=planned.nodes();brain.traversals=planned.traversals();brain.routeRevision=planned.revision();brain.transition=null;
        brain.pathIndex=0;
        if (brain.path.size()>1) {
            Vector3f start=graph.position(brain.path.getFirst()),next=graph.position(brain.path.get(1));
            Vector3f segment=next.subtract(start),relative=position.subtract(start);segment.y=0;relative.y=0;
            float projection=segment.lengthSquared()>0?relative.dot(segment)/segment.lengthSquared():0;
            float distance=relative.subtract(segment.mult(Math.clamp(projection,0,1))).length();
            float width=graph.links(brain.path.getFirst()).stream().filter(l->l.to()==brain.path.get(1)).findFirst().orElseThrow().width();
            // Replanning must not turn a moving car back toward a node it has already passed.
            if (horizontalDistance(position,start)<3 || (projection>0 && distance<Math.max(3,width*.4f))) brain.pathIndex=1;
            else {
                Vector3f heading=world.forward(vehicleId);
                float startAngle=Math.abs(signedAngle(heading,start.subtract(position)));
                float nextAngle=Math.abs(signedAngle(heading,next.subtract(position)));
                if (startAngle>1.2f && nextAngle+.35f<startAngle && nextAngle<1.2f
                        && Math.abs(next.y-(position.y-offset))<.75f
                        && !(hazardActive && graph.crossesHazard(position,next))
                        &&safeWarningSegment(position,next.add(0,offset,0),brain.warnings)
                        && world.sweep(position.add(0,1.2f,0),next.add(0,1.65f,0),1.2f,vehicleId)==null
                        && supportedRoadConnection(vehicleId,position,next.add(0,offset,0),world)) brain.pathIndex=1;
            }
        }
        brain.replanAt=session.tick+120;
    }
    private int navigationStart(Vector3f position,WorldQuery world,int vehicleId) {
        Vector3f surface=position.add(0,-roadOffset(world,vehicleId),0);
        var context=world.roadContext(vehicleId);
        List<ArenaDefinition.NavNode> candidates=graph.nodes().stream()
                .filter(n->context.known()?n.surfaceId().equals(context.surfaceId()):Math.abs(n.position().y()-surface.y)<2.2f)
                .sorted(Comparator.comparingDouble((ArenaDefinition.NavNode n)->n.position().vector().distanceSquared(surface))
                        .thenComparingInt(ArenaDefinition.NavNode::id)).toList();
        for (var node:candidates) {
            Vector3f endpoint=node.position().vector().add(0,roadOffset(world,vehicleId),0);
            if (position.distanceSquared(endpoint)<.1f) return node.id();
            WorldQuery.Hit blocker=world.sweep(position.add(0,1.2f,0),endpoint.add(0,1.2f,0),halfWidth(world,vehicleId),vehicleId);
            if ((blocker==null || drivableRampHit(blocker)) && supportedRoadConnection(vehicleId,position,endpoint,world)) return node.id();
        }
        // A temporarily surrounded car keeps its route and uses the normal reverse/recovery rules.
        return graph.nearest(surface,candidates.isEmpty()?null:context.known()?context.surfaceId():null);
    }
    private VehicleCommand drive(VehicleState self,Brain brain,WorldQuery world) {
        Vector3f position=world.position(self.id),velocity=world.velocity(self.id),forward=world.forward(self.id);
        float speed=velocity.length();
        boolean grinderApproach=self.profileId.equals("grinder")&&!self.controlled()
                &&(self.specialPhase==VehicleState.SpecialPhase.GRINDER_WINDUP||self.specialPhase==VehicleState.SpecialPhase.GRINDER_SEARCH);
        var meleeTarget=brain.observation.visible(self.grinding()?self.specialTargetId:brain.target);
        int contactGoal=(grinderApproach||self.grinding())&&brain.state!=State.EVADE_HAZARD&&meleeTarget!=null
                &&world.grounded(self.id)&&sameObservedLevel(self.id,meleeTarget.id(),position,meleeTarget.position(),world)
                &&horizontalDistance(position,meleeTarget.position())<22?meleeTarget.id():-1;
        if(contactGoal>=0)brain.passingDestination=null;
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
                    false,false,null,0,false,true,AbilityId.NONE);
        }
        if (session.tick<brain.reverseUntil) {
            brain.state=State.RECOVER; brain.requiredMovement=false;
            float steer=brain.progressDirection.lengthSquared()>0 ? signedAngle(forward,brain.progressDirection) : .7f;
            // A confined elevated road cannot use the open-floor turning circle:
            // keep an established reverse approach aligned with that road.
            float reverseSteer=brain.backingToRoute && (world.roadContext(self.id).known()
                    ?world.roadContext(self.id).level()>0||world.roadContext(self.id).motion()==RoadContext.Motion.RAMP:position.y>.8f)
                    ? Math.clamp(-signedAngle(forward.negate(),brain.progressDirection)*rules.steeringGain(),-1,1)
                    : -Math.signum(steer);
            float support=supportedDistance(self.id,position,forward.negate(),world);
            if (support<4) {
                // The timed attempt keeps its duration, but must brake before an edge.
                return new VehicleCommand(velocity.dot(forward)<-.5f?1:0,0,reverseSteer,
                        false,false,false,false,null,0,false,false,AbilityId.NONE);
            }
            return new VehicleCommand(0,.8f,reverseSteer,false,false,false,false,null,0,false,false,AbilityId.NONE);
        }
        Vector3f destination=steeringTarget(brain,position,speed,world,self.id);
        if(brain.passingDestination!=null && horizontalDistance(position,brain.passingDestination)<4) {
            // Reaching the verified detour is a successful escape. A later, unrelated
            // blocked route gets its own attempt; timeout alone never resets it.
            brain.passingDestination=null;brain.recoveryDetourAttempted=false;
        } else if(brain.passingDestination!=null && session.tick>=brain.passingUntil)brain.passingDestination=null;
        var closeOpponent=brain.observation.visible().stream().min(Comparator.comparingDouble(e->e.position().distanceSquared(position))).orElse(null);
        if (contactGoal<0 && brain.state!=State.EVADE_HAZARD && brain.passingDestination==null && session.tick>=brain.nextPassAttempt && closeOpponent!=null
                && closeOpponent.position().distance(position)<rules.passDistance()
                && sameObservedLevel(self.id,closeOpponent.id(),position,closeOpponent.position(),world)) {
            brain.passingDestination=passingDestination(self.id,position,forward,closeOpponent.position(),world);
            brain.passingUntil=session.tick+rules.passHoldTicks();
            brain.nextPassAttempt=session.tick+rules.decisionTicks();
        }
        if (brain.passingDestination!=null) destination=brain.passingDestination;
        if(contactGoal>=0)destination=meleeTarget.position();
        ArenaDefinition.Ramp ramp=rampAt(position,roadOffset(world,self.id));
        float halfWidth=halfWidth(world,self.id);
        if (ramp!=null && (ramp.axis()==ArenaDefinition.Axis.Z
                ?position.x<ramp.minX()+halfWidth||position.x>ramp.maxX()-halfWidth
                :position.z<ramp.minZ()+halfWidth||position.z>ramp.maxZ()-halfWidth)) ramp=null;
        if (ramp!=null) {
            // The ramp is a 12 m road without side barriers. Close combat still follows
            // its longitudinal corridor, with two passing lanes, rather than chasing a
            // moving enemy over its side and leaving the chassis suspended on the edge.
            boolean xAxis=ramp.axis()==ArenaDefinition.Axis.X;
            float along=xAxis?destination.x-position.x:destination.z-position.z;
            float travel=Math.abs(along)>4?Math.signum(along):((xAxis?forward.x:forward.z)>=0?1:-1);
            float width=xAxis?ramp.maxZ()-ramp.minZ():ramp.maxX()-ramp.minX();
            float laneOffset=closeOpponent!=null && closeOpponent.position().distance(position)<rules.passDistance()
                    ?-travel*Math.min(2,Math.max(0,width/2-halfWidth-1)):0;
            destination=xAxis?new Vector3f(Math.clamp(position.x+travel*12,ramp.minX()-4,ramp.maxX()+4),destination.y,
                    (ramp.minZ()+ramp.maxZ())*.5f-laneOffset)
                    :new Vector3f((ramp.minX()+ramp.maxX())*.5f+laneOffset,destination.y,
                    Math.clamp(position.z+travel*12,ramp.minZ()-4,ramp.maxZ()+4));
        }
        brain.lastDrivingTarget=destination.clone();
        Vector3f direction=destination.subtract(position); direction.y=0;
        if (direction.lengthSquared()<.01f) direction=forward.clone();
        brain.progressDirection=direction.normalize();
        float error=signedAngle(forward,direction);
        if(brain.boss!=null) {
            var policy=brain.boss;
            if(brain.state==State.EVADE_HAZARD||self.controlled())policy.cancel(session.tick);
            if(self.profileId.equals("boss_emcee")&&session.tick-brain.landedAt<84) {
                brain.requiredMovement=false;
                return new VehicleCommand(0,velocity.dot(forward)>.5f?.5f:0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
            }
            if(policy.phase==BossTactics.Phase.TELEGRAPH) {
                brain.requiredMovement=false;
                float aim=signedAngle(forward,policy.chargeDirection);
                return new VehicleCommand(0,velocity.dot(forward)>.5f?1:0,Math.clamp(aim*rules.steeringGain(),-.5f,.5f),false,false,
                        false,false,null,0,false,false,AbilityId.NONE);
            }
            if(policy.phase==BossTactics.Phase.CHARGE) {
                brain.requiredMovement=true;brain.progressDirection=policy.chargeDirection;
                float aim=signedAngle(forward,policy.chargeDirection);
                var blocked=world.sweep(position.add(0,1.2f,0),position.add(0,1.2f,0).add(forward.mult(8)),halfWidth,self.id);
                if(blocked!=null&&blocked.vehicleId()<0&&!drivableRampHit(blocked)||supportedDistance(self.id,position,forward,world)<3) {
                    policy.finishCharge(session.tick);
                    return new VehicleCommand(0,velocity.dot(forward)>.5f?1:0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
                }
                return new VehicleCommand(1,0,Math.clamp(aim*.45f,-.22f,.22f),false,false,false,false,null,0,false,false,AbilityId.NONE);
            }
        }
        VehicleCommand backing=backTowardRoute(self,brain,world,position,forward,velocity,direction,error);
        if (backing!=null) return backing;
        float steer=Math.clamp(error*rules.steeringGain(),-1,1);
        float desiredSpeed=rules.cruiseSpeed()*(1-.75f*Math.min(1,Math.abs(error)/1.5f));
        if (Math.abs(error)>2) desiredSpeed=6;
        if (brain.state==State.ATTACK && direction.length()<rules.attackDistance()&&!grinderApproach) desiredSpeed=Math.min(desiredSpeed,12);
        if (brain.state==State.SEEK_PICKUP && horizontalDistance(position,brain.destination)<8) desiredSpeed=Math.min(desiredSpeed,7);
        if (ramp!=null) desiredSpeed=Math.min(desiredSpeed,12);
        if(brain.boss!=null&&brain.boss.phase==BossTactics.Phase.RECOVERY)desiredSpeed=Math.min(desiredSpeed,8);
        desiredSpeed*=world.profile(self.id).speedMultiplier();
        if(brain.transition!=null&&brain.transition.type()==ArenaDefinition.Transition.LAUNCH
                &&brain.transitionPhase==TransitionPhase.ALIGN) {
            var pad=arena.launchPads().stream().filter(p->p.id().equals(brain.transition.objectId())).findFirst().orElseThrow();
            if(Math.abs(error)<Math.toRadians(pad.maximumEntryAngle()))desiredSpeed=Math.max(desiredSpeed,pad.minimumSpeed()+2);
        }
        // Three hull-width probes; only static blockers and other cars affect avoidance.
        float probeLength=obstacleProbeLength(speed,world.profile(self.id).length(),rules.sightRange());
        Vector3f origin=position.add(0,.85f,0);
        Vector3f right=new Vector3f(-forward.z,0,forward.x).normalizeLocal();
        WorldQuery.Hit center=world.sweep(origin,origin.add(forward.mult(probeLength)),halfWidth*.76f,self.id);
        WorldQuery.Hit left=world.sweep(origin.subtract(right.mult(halfWidth+.05f)),origin.subtract(right.mult(halfWidth+.05f)).add(forward.mult(probeLength)),.45f,self.id);
        WorldQuery.Hit rightHit=world.sweep(origin.add(right.mult(halfWidth+.05f)),origin.add(right.mult(halfWidth+.05f)).add(forward.mult(probeLength)),.45f,self.id);
        if(self.boss) {
            Vector3f roofProbe=position.add(0,world.profile(self.id).hullBounds().maxY()-.35f,0);
            var roof=world.sweep(roofProbe,roofProbe.add(forward.mult(probeLength)),.35f,self.id);
            if(roof!=null&&(center==null||roof.fraction()<center.fraction()))center=roof;
        }
        // Ignore only the individual drivable surface hit, not a wall on another probe.
        if (drivableRampHit(center)) center=null;
        if (drivableRampHit(left)) left=null;
        if (drivableRampHit(rightHit)) rightHit=null;
        // During this one melee attempt the chosen hull is the contact goal.
        // Static blockers, other cars and road-edge checks remain authoritative.
        if(contactGoal>=0) {
            if(center!=null&&center.vehicleId()==contactGoal)center=null;
            if(left!=null&&left.vehicleId()==contactGoal)left=null;
            if(rightHit!=null&&rightHit.vehicleId()==contactGoal)rightHit=null;
        }
        if (center!=null && center.vehicleId()>=0 && center.fraction()*probeLength<4
                && beginLocalTrafficEscape(brain,self.id,position,forward,center.point(),world)) return VehicleCommand.NONE;
        float clearance=center==null?1:center.fraction();
        if (clearance<1 || left!=null || rightHit!=null) {
            float leftClear=left==null?1:left.fraction(),rightClear=rightHit==null?1:rightHit.fraction();
            float avoid=Math.abs(rightClear-leftClear)>.04f ? Math.signum(rightClear-leftClear) : Math.signum(steer==0?1:steer);
            steer=Math.clamp(steer+avoid*.8f,-1,1);
            float minimumPassingSpeed=center!=null && center.vehicleId()>=0?4:0;
            float blockingDistance=center==null?Float.POSITIVE_INFINITY:center.fraction()*probeLength;
            // Parallel side walls still guide steering through a narrow corridor. A side
            // probe facing a wall, however, must get the same stopping room as the centre.
            for(var side:new WorldQuery.Hit[]{left,rightHit})if(side!=null&&side.normal().dot(forward)<-.3f)
                blockingDistance=Math.min(blockingDistance,side.fraction()*probeLength);
            if(Float.isFinite(blockingDistance)) {
                float remaining=Math.max(0,blockingDistance-world.profile(self.id).length()/2-1);
                // Reserve the existing 2 m/s brake deadband, so at least half braking
                // is applied when the car reaches the conservative stopping envelope.
                float safeSpeed=Math.max(0,(float)Math.sqrt(2*OBSTACLE_BRAKING_DECELERATION*remaining)-2);
                desiredSpeed=Math.min(desiredSpeed,Math.max(minimumPassingSpeed,safeSpeed));
            } else desiredSpeed=Math.min(desiredSpeed,9);
        }
        boolean brake=speed>desiredSpeed+2;
        float throttle=brake?0:Math.clamp((desiredSpeed-speed)*.30f+.25f,0,1);
        float braking=brake?Math.clamp((speed-desiredSpeed)*.25f,0,1):0;
        if (desiredSpeed<.5f) {
            float longitudinal=velocity.dot(forward);
            throttle=longitudinal<-.5f?1:0; braking=longitudinal>.5f?1:0;
        }
        boolean handbrake=Math.abs(error)>1.15f && speed>10 && world.grounded(self.id);
        boolean turbo=brain.state==State.SEEK_TARGET && Math.abs(error)<.12f && center==null && left==null && rightHit==null
                && direction.length()>40 && self.turbo>30 && world.grounded(self.id);
        var target=brain.observation.visible(brain.target);
        boolean machineGun=false,rocket=false;
        WeaponType directWeapon=null;
        if (target!=null && session.tick>=brain.reactionUntil && self.protectionTicks==0) {
            Vector3f toTarget=target.position().subtract(world.muzzle(self.id));
            float distance=toTarget.length(),angle=angleDegrees(forward,toTarget);
            boolean visible=lineOfSight(world,world.muzzle(self.id),target.position(),self.id,target.id());
            machineGun=visible && distance<=rules.machineGunRange() && angle<=rules.machineGunAngleDegrees()&&self.machineGunCooldown==0;
            var targeting=session.combatRules.targeting();
            if (visible && distance<=targeting.acquisitionRange() && angle<=targeting.acquisitionConeDegrees()) brain.lockTicks++; else brain.lockTicks=0;
            WeaponType selected=chooseWeapon(self,brain,world,target,visible,distance,angle);
            if(selected!=null) {rocket=true;if(selected!=self.selectedWeapon)directWeapon=selected;}
        } else brain.lockTicks=0;
        if(self.grinding()&&self.specialTargetId>=0&&session.vehicle(self.specialTargetId).grabbedBy==self.id) {
            // The elevated mounts tilt into the physical receiver in CombatSystem.
            machineGun=self.machineGunCooldown==0;
            if(self.weapon(WeaponType.POWER).ammo>0&&self.weapon(WeaponType.POWER).cooldownTicks==0) {
                rocket=true;directWeapon=WeaponType.POWER;
            }
        }
        // Braking forever in front of a blocker is still a failed request to follow a route.
        brain.requiredMovement=horizontalDistance(position,brain.destination)>2;
        return new VehicleCommand(throttle,braking,steer,handbrake,turbo,machineGun,rocket,directWeapon,0,false,false,AbilityId.NONE);
    }
    static float obstacleProbeLength(float speed,float hullLength,float sightRange) {
        float stoppingDistance=speed*speed/(2*OBSTACLE_BRAKING_DECELERATION);
        return Math.min(sightRange,Math.max(4,hullLength/2+1+speed*.2f+stoppingDistance));
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
        return new VehicleCommand(throttle,reverse,steer,false,false,false,false,null,0,false,false,AbilityId.NONE);
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
        if (arena.bosses().isEmpty()&&position.y<.8f && rampAt(position)==null) return 6;
        Vector3f heading=new Vector3f(direction.x,0,direction.z).normalizeLocal();
        Vector3f right=new Vector3f(-heading.z,0,heading.x);
        float previous=position.y-roadOffset(world,id);
        for (int distance=2;distance<=6;distance+=2) {
            Vector3f center=position.add(heading.mult(distance));
            float height=previous;
            for (int side:new int[]{0,-1,1}) {
                Vector3f point=center.add(right.mult(side*(halfWidth(world,id)+.05f)));point.y=previous+roadOffset(world,id);
                WorldQuery.Hit surface=world.ray(point.add(0,1.5f,0),point.add(0,-2,0),id);
                if (surface==null || surface.vehicleId()>=0 || surface.normal().y<.65f
                        || !roadSurface(surface.point(),true) || Math.abs(surface.point().y-previous)>.8f) return distance-1;
                if (side==0) height=surface.point().y;
            }
            previous=height;
        }
        return 6;
    }
    private Vector3f steeringTarget(Brain brain,Vector3f position,float speed,WorldQuery world,int id) {
        if (brain.destination==null) return position.add(0,0,10);
        if (brain.path.isEmpty()) {brain.transition=null;return brain.destination;}
        if(!arena.bosses().isEmpty()) {
            while(brain.pathIndex<brain.path.size()) {
                var transition=brain.pathIndex>0&&brain.pathIndex-1<brain.traversals.size()?brain.traversals.get(brain.pathIndex-1):null;
                if(transition!=null) {
                    if(transition.type()==ArenaDefinition.Transition.ROAD&&brain.pathIndex<brain.traversals.size()
                            &&brain.traversals.get(brain.pathIndex).type()==ArenaDefinition.Transition.LAUNCH
                            &&horizontalDistance(position,graph.position(transition.to()))<24) {
                        // Begin alignment on the authored run-up, before entering the compression rectangle.
                        brain.pathIndex++;continue;
                    }
                    boolean changed=!transition.equals(brain.transition);brain.transition=transition;
                    if(!transitionReached(transition,position,world,id)) {
                        if(changed)brain.transitionPhase=TransitionPhase.APPROACH;
                        if(transition.type()==ArenaDefinition.Transition.LAUNCH)return launchApproach(brain,transition,position,world,id);
                        return graph.position(transition.to());
                    }
                } else if(horizontalDistance(position,graph.position(brain.path.get(brain.pathIndex)))>3)
                    return graph.position(brain.path.get(brain.pathIndex));
                brain.pathIndex++;
            }
            brain.transition=null;brain.transitionPhase=TransitionPhase.ROAD;
            return brain.destination;
        }
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
    private boolean transitionReached(NavGraph.Traversal transition,Vector3f position,WorldQuery world,int id) {
        var context=world.roadContext(id);String targetSurface=graph.surfaceId(transition.to());
        if(context.known()&&(context.flying()||!context.surfaceId().equals(targetSurface))) {
            var target=arena.surfaces().stream().filter(s->s.id().equals(targetSurface)).findFirst().orElseThrow();
            if(context.flying())return false;
            if(target.level()!=context.level()) {
                // A long chassis can vote for the adjoining deck while its centre still
                // crosses the ramp's final node. Accept only the authored continuation
                // of this same ramp onto the surface actually confirmed by its wheels.
                boolean joinedRamp=transition.type()==ArenaDefinition.Transition.RAMP
                        &&graph.links(transition.to()).stream().anyMatch(link->link.type()==ArenaDefinition.Transition.RAMP
                        &&link.objectId().equals(transition.objectId())&&graph.surfaceId(link.to()).equals(context.surfaceId()));
                if(!joinedRamp)return false;
            }
        }
        return horizontalDistance(position,graph.position(transition.to()))<3.5f
                &&Math.abs(position.y-roadOffset(world,id)-graph.position(transition.to()).y)<2.2f;
    }
    private Vector3f launchApproach(Brain brain,NavGraph.Traversal transition,Vector3f position,WorldQuery world,int id) {
        var pad=arena.launchPads().stream().filter(p->p.id().equals(transition.objectId())).findFirst().orElseThrow();
        Vector3f direction=pad.direction(),source=pad.source().vector();
        Vector3f offset=position.subtract(source);offset.y=0;
        float along=offset.dot(direction),lateral=offset.subtract(direction.mult(along)).length();
        Vector3f approach=arena.edges().stream().filter(e->e.type()==ArenaDefinition.Transition.ROAD
                        &&(e.to()==transition.from()||e.bidirectional()&&e.from()==transition.from()))
                .map(e->graph.position(e.to()==transition.from()?e.from():e.to()))
                .filter(p->p.subtract(source).dot(direction)<-1)
                .min(Comparator.comparingDouble(p->p.distanceSquared(source))).orElseGet(()->source.subtract(direction.mult(pad.length()/2+8)));
        boolean aligned=along< -pad.length()/2&&lateral<Math.max(1,(pad.width()-mobility(id,world).width())*.4f)
                &&angleDegrees(world.forward(id),direction)<pad.maximumEntryAngle()*.7f;
        if(along<pad.length()/2+world.profile(id).length()&&(aligned||horizontalDistance(position,approach)<3
                ||brain.transitionPhase==TransitionPhase.ALIGN)) {
            brain.transitionPhase=TransitionPhase.ALIGN;
            return source.add(direction.mult(pad.length()/2+world.profile(id).length()));
        }
        brain.transitionPhase=TransitionPhase.APPROACH;
        return approach;
    }
    private NavGraph.Route plannedRoute(int start,int goal,int id,WorldQuery world,boolean avoidHazards) {
        Set<String> active=avoidHazards?activeHazards.get().stream().filter(h->hazardVisible(id,h,world)).map(ArenaDefinition.Hazard::id)
                .collect(java.util.stream.Collectors.toSet()):Set.of();
        return graph.route(start,goal,mobility(id,world),active,rules.turnPenalty(),rules.activeHazardPenalty());
    }
    public NavGraph.Mobility mobility(int id,WorldQuery world) {
        var profile=world.profile(id);var bounds=profile.fullBounds();var self=session.vehicle(id);
        Set<String> pads=self.boss?Set.copyOf(arena.bosses().stream().filter(b->b.profileId().equals(self.profileId))
                .findFirst().orElseThrow().launchPadIds()):Set.of();
        // Clearance is the free height above the road; fully extended wheels below that
        // plane do not increase the clearance needed above the roof.
        return new NavGraph.Mobility(bounds.maxX()-bounds.minX(),profile.roadOffset()+bounds.maxY(),
                rules.cruiseSpeed()*profile.speedMultiplier(),!self.boss||self.profileId.equals("boss_emcee"),true,pads);
    }
    private float roadOffset(WorldQuery world,int id) {
        // Yard navigation samples were authored against the compressed Rivet suspension.
        return arena.bosses().isEmpty()?.45f:world.profile(id).roadOffset();
    }
    private static float halfWidth(WorldQuery world,int id) {return world.profile(id).width()/2;}
    private boolean sameObservedLevel(int self,int target,Vector3f from,Vector3f to,WorldQuery world) {
        var a=world.roadContext(self);var b=world.roadContext(target);
        return a.known()&&b.known()?a.level()==b.level()&&!a.flying()&&!b.flying():Math.abs(from.y-to.y)<2.2f;
    }
    public NavigationView navigation(int id) {
        var brain=brains.get(id);return new NavigationView(brain.routeRevision,brain.path,brain.transition,brain.transitionPhase);
    }
    public List<PickupReservation> pickupReservations() {return List.copyOf(reservations.values());}
    private void trackProgress(VehicleState self,Brain brain,Vector3f position,WorldQuery world) {
        if(self.controlled()) {
            brain.lastPosition=position.clone();brain.progressWindowStart++;brain.stationaryTicks=0;
            if(brain.stuckSince>=0)brain.stuckSince++;
            if(brain.reverseUntil>session.tick)brain.reverseUntil++;
            return;
        }
        if(!arena.bosses().isEmpty()&&brain.stuckSince>=0&&session.tick-brain.stuckSince>=360&&!brain.stuckReplanned) {
            brain.replanAt=0;brain.goalNode=-1;brain.stuckReplanned=true;
        }
        if (self.recoveries!=brain.recoveriesSeen) {
            if(!arena.bosses().isEmpty()&&session.tick-brain.lastRecoveryTick<1800)
                LOG.severe("AI repeated recovery arena="+arena.id()+" seed="+session.seed+" id="+self.id+" tick="+session.tick
                        +" prior="+brain.lastRecoveryTick+" position="+position+" route="+brain.path);
            brain.lastRecoveryTick=session.tick;brain.stuckReplanned=false;
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
        // Successful movement ends this stuck episode immediately. Waiting for the
        // full sample window could let its old recovery deadline stop a car that
        // has already resumed following the route after reversing.
        if (brain.progress>=rules.stuckMinimumProgress()) {
            brain.stuckSince=-1;brain.reverseAttempts=0;brain.stuckReplanned=false;
        }
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
        }
        brain.progress=0; brain.progressWindowStart=session.tick;
    }
    private boolean needsRecovery(Brain brain) {
        return brain.stuckSince>=0 && brain.reverseAttempts>=2 && session.tick-brain.stuckSince>
                (arena.bosses().isEmpty()?rules.recoveryAfterTicks():960);
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
            // Sample the current driving level. A ray above the garage roof would
            // see its non-drivable top and reject the clear passing floor below it.
            WorldQuery.Hit ground=world.ray(candidate.add(0,2,0),candidate.add(0,-3,0),id);
            if (ground==null || ground.vehicleId()>=0 || ground.normal().y<.65f || !roadSurface(ground.point())) continue;
            candidate.y=ground.point().y+roadOffset(world,id);
            WorldQuery.Hit obstacle=world.sweep(position.add(0,1.2f,0),candidate.add(0,1.2f,0),halfWidth(world,id),id);
            if (obstacle!=null && !drivableRampHit(obstacle)) continue;
            if (!supportedRoadConnection(id,position,candidate,world)) continue;
            return candidate;
        }
        return null;
    }
    private boolean supportedRoadConnection(int id,Vector3f position,Vector3f candidate,WorldQuery world) {
        int samples=(int)Math.ceil(position.distance(candidate)/4);
        float previousHeight=position.y-roadOffset(world,id);
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
        return rampAt(position,.45f);
    }
    private ArenaDefinition.Ramp rampAt(Vector3f position,float offset) {
        for (var ramp:arena.ramps()) if (position.x>=ramp.minX()-.5f && position.x<=ramp.maxX()+.5f
                && position.z>=ramp.minZ() && position.z<=ramp.maxZ()) {
            float height=ramp.heightAt(position.x,position.z);
            if (Math.abs(position.y-height-offset)<.8f) return ramp;
        }
        return null;
    }
    private boolean roadSurface(Vector3f point) {
        return roadSurface(point,false);
    }
    private boolean roadSurface(Vector3f point,boolean hullEdgeSample) {
        var surface=arena.surfaceAt(point,0,.15f);
        if(surface.isEmpty())return false;
        if(surface.get().level()==0)return true;
        float margin=hullEdgeSample?.2f:2.5f;
        if(arena.surfaceAt(point,margin,.15f).isPresent())return true;
        // The ramp/plate seam is part of the same connected road; do not inset it twice.
        return arena.ramps().stream().anyMatch(r->r.containsXZ(point.x,point.z,-.3f)
                &&Math.abs(r.heightAt(point.x,point.z)-point.y)<.2f);
    }
    public void registerParticipant(int id) {
        brains.computeIfAbsent(id,key->{
            var brain=new Brain(session.seed ^ (0x9E3779B97F4A7C15L*(key+1)));
            if(session.vehicle(key).boss)brain.boss=new BossTactics(session.vehicle(key).profileId);
            return brain;
        });
    }
    public void observeHazards(Supplier<List<ArenaDefinition.Hazard>> source) { activeHazards=Objects.requireNonNull(source); }
    private boolean insideActiveHazard(Vector3f point) {
        return activeHazards.get().stream().anyMatch(h->h.contains(point));
    }
    private boolean visibleActiveHazard(int vehicleId,WorldQuery world) {
        return activeHazards.get().stream().anyMatch(h->hazardVisible(vehicleId,h,world));
    }
    private boolean hazardVisible(int vehicleId,ArenaDefinition.Hazard hazard,WorldQuery world) {
        Vector3f eye=world.position(vehicleId).add(0,.6f,0);
        Vector3f center=hazard.center().vector();
        return eye.distance(center)<=rules.sightRange()&&world.ray(eye,center,vehicleId)==null;
    }
    public State state(int id) { return brains.get(id).state; }
    public int targetId(int id) { return brains.get(id).target; }
    public BotObservation observation(int id) { return brains.get(id).observation; }
    public List<Integer> route(int id) { return brains.get(id).path; }
    public Metrics metrics(int id) {
        Brain brain=brains.get(id);
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
