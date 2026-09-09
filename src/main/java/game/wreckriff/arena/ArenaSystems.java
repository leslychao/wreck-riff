package game.wreckriff.arena;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.ProgressStore;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.combat.CombatSystem;

import com.jme3.math.Vector3f;
import com.jme3.math.Quaternion;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import java.util.*;

/** Tick-driven hazard and atomic, surface-aware resource collection. */
public final class ArenaSystems {
    public enum HazardPhase { OFF, WARNING, ACTIVE }
    public enum BossAction { HEAVY_STRIKE, PROTOCOL, RITE, RAM_MISSED, LANDED }
    @FunctionalInterface public interface DamageSink {
        void damage(int targetId,float amount,String cause,long eventId);
    }
    private final MatchSession session;
    private final ArenaDefinition definition;
    private final ArenaLaunches launches;
    private final ArenaHazardSchedule schedule;
    private final Map<String,ArenaDefinition.Destructible> objectsByGeometry=new LinkedHashMap<>();
    private final Map<String,ArenaDefinition.BoxPart> boxesById=new HashMap<>();
    private final Set<String> removedGeometry=new HashSet<>();
    public record MechanismView(String id,String hazardId,ArenaDefinition.HazardType type,Vector3f halfExtents,Vector3f position,Quaternion rotation) {
        public MechanismView {halfExtents=halfExtents.clone();position=position.clone();rotation=rotation.clone();}
        @Override public Vector3f halfExtents(){return halfExtents.clone();}
        @Override public Vector3f position(){return position.clone();}
        @Override public Quaternion rotation(){return rotation.clone();}
    }
    private final Map<String,MechanismView> mechanisms=new LinkedHashMap<>();
    private final Map<String,Map<Integer,Long>> mechanicalReadyAt=new HashMap<>();
    private final Set<String> raisedBarriers=new HashSet<>();
    private final Map<String,HazardPhase> announcedPhases=new HashMap<>();
    private final Map<String,Long> returnsAt=new HashMap<>();
    private final Map<String,ProgressStore.ObjectState> objects=new LinkedHashMap<>();
    private long hazardTicks;
    private long bossVulnerableUntil;
    private VehicleProfile bossProfile;
    private final Map<String,Map<Integer,Integer>> hazardExposure=new HashMap<>();
    private final List<GameEvent> events=new ArrayList<>();
    private long lastHazardTick=Long.MIN_VALUE,lastPickupTick=Long.MIN_VALUE;

    public ArenaSystems(MatchSession session,ArenaDefinition definition) {
        this.session=session; this.definition=definition;
        launches=new ArenaLaunches(session,definition);
        schedule=new ArenaHazardSchedule(session,definition);
        definition.boxes().forEach(box->boxesById.put(box.id(),box));
        definition.destructibles().forEach(object->objectsByGeometry.put(object.geometryId(),object));
        definition.destructibles().forEach(object->objects.put(object.id(),new ProgressStore.ObjectState(object.maximumHp(),false,false)));
    }
    public ArenaLaunches launches() { return launches; }
    public void configureBoss(VehicleProfile profile) {bossProfile=profile;}
    public boolean bossVulnerable() {
        return session.phase==MatchSession.Phase.BOSS_COMBAT&&session.bossParticipantId>=0
                &&session.vehicle(session.bossParticipantId).alive()&&session.tick<bossVulnerableUntil;
    }
    public void completeBossAction(BossAction action) {
        if(bossProfile==null||session.phase!=MatchSession.Phase.BOSS_COMBAT)return;
        float seconds=switch(bossProfile.id()) {
            case "boss_foreman" -> action==BossAction.RAM_MISSED?2:0;
            case "boss_prefect" -> action==BossAction.PROTOCOL?1.8f:0;
            case "boss_emcee" -> action==BossAction.RAM_MISSED||action==BossAction.LANDED?1.6f:0;
            case "boss_ash_shepherd" -> action==BossAction.RITE?2:0;
            case "boss_director" -> action==BossAction.RAM_MISSED?2.2f:0;
            default -> 0;
        };
        if(seconds>0)bossVulnerableUntil=Math.max(bossVulnerableUntil,session.tick+Math.round(seconds*MatchSession.TICKS_PER_SECOND));
    }
    /** Receives a genuine local collision point. Splash and fire never call this method. */
    public float directHitMultiplier(int targetId,Vector3f localPoint) {
        if(targetId!=session.bossParticipantId||!bossVulnerable()||localPoint==null)return 1;
        var hull=bossProfile.hullBoxes().getFirst();
        float scaleY=bossProfile.height()/1.07f;
        boolean prefect=bossProfile.id().equals("boss_prefect");
        boolean height=Math.abs(localPoint.y-(prefect?.25f:.26f)*scaleY)<=(prefect?.11f:.10f)*scaleY;
        boolean panel=prefect?
                localPoint.x>=hull.halfWidth()-.08f&&localPoint.x<=hull.halfWidth()+.08f
                        &&Math.abs(localPoint.z-bossProfile.length()*.19f)<=bossProfile.length()*.09f:
                Math.abs(localPoint.x)<=hull.halfWidth()*.30f&&localPoint.z<=-hull.halfLength()+.08f
                        &&localPoint.z>=-hull.halfLength()-.08f;
        return height&&panel?1.25f:1;
    }
    public void beforePhysics(PhysicsWorld world,Map<Integer,VehicleController> drivers) {
        advanceSchedule(world);syncObjects(world);syncMechanisms(world);launches.beforePhysics(world,drivers);
    }
    public void afterPhysics(PhysicsWorld world,Map<Integer,VehicleController> drivers) { launches.afterPhysics(world,drivers); }
    public HazardPhase hazardPhase() {
        return definition.hazards().stream().map(h->hazardPhase(h.id())).max(Comparator.naturalOrder()).orElse(HazardPhase.OFF);
    }
    public HazardPhase hazardPhase(String id) {
        if(session.mode==MatchSession.Mode.LEGACY)return phaseAt(hazardClock(),definition.hazards(),id);
        return switch(schedule.state(id).phase) {case WARNING->HazardPhase.WARNING;case ACTIVE->HazardPhase.ACTIVE;default->HazardPhase.OFF;};
    }
    private long hazardClock() {return session.mode==MatchSession.Mode.LEGACY?session.tick:hazardTicks;}
    public static HazardPhase phaseAt(long tick,List<ArenaDefinition.Hazard> hazards,String id) {
        long period=hazards.stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum();
        long phase=period==0?0:tick%period;
        ArenaDefinition.Hazard hazard=null;
        for(var candidate:hazards) {
            if(candidate.id().equals(id)) {hazard=candidate;break;}
            phase-=candidate.periodTicks();
        }
        if(hazard==null)throw new IllegalArgumentException("Unknown hazard: "+id);
        if(phase<0||phase>=hazard.periodTicks())return HazardPhase.OFF;
        if (phase<hazard.offTicks()) return HazardPhase.OFF;
        return phase<hazard.offTicks()+hazard.warningTicks() ? HazardPhase.WARNING : HazardPhase.ACTIVE;
    }
    public List<ArenaDefinition.Hazard> activeHazards() {
        return definition.hazards().stream().filter(h->hazardPhase(h.id())==HazardPhase.ACTIVE).toList();
    }
    public float warningProgress(String id) {
        if(session.mode!=MatchSession.Mode.LEGACY) {
            var state=schedule.state(id);
            return state.phase==ProgressStore.HazardPhase.WARNING?1-state.remaining/(float)schedule.timing(id).warning():state.phase==ProgressStore.HazardPhase.ACTIVE?1:0;
        }
        long period=definition.hazards().stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum();
        long phase=period==0?0:hazardClock()%period;
        for(var hazard:definition.hazards()) {
            if(hazard.id().equals(id))return Math.clamp((phase-hazard.offTicks())/(float)hazard.warningTicks(),0,1);
            phase-=hazard.periodTicks();
        }
        throw new IllegalArgumentException("Unknown hazard: "+id);
    }
    public void updateHazard(WorldQuery world,DamageSink sink) {
        if (lastHazardTick==session.tick || session.outcome!=MatchSession.Outcome.NONE) return;
        lastHazardTick=session.tick;
        advanceSchedule(world);
        int hazardIndex=0;
        for(var hazard:definition.hazards()) {
        int sequence=hazardIndex++;
        if(session.mode!=MatchSession.Mode.LEGACY&&hazard.type()!=ArenaDefinition.HazardType.ELECTRIC&&hazard.type()!=ArenaDefinition.HazardType.FIRE) {
            if(world instanceof PhysicsWorld physics&&hazardPhase(hazard.id())==HazardPhase.ACTIVE) {
                var ready=mechanicalReadyAt.computeIfAbsent(hazard.id(),key->new HashMap<>());
                for(var contact:physics.arenaContacts()) {
                    var mechanism=mechanisms.get(contact.objectId());
                    if(mechanism==null||!mechanism.hazardId().equals(hazard.id()))continue;
                    int id=contact.vehicleId();if(!session.vehicle(id).alive()||session.tick<ready.getOrDefault(id,Long.MIN_VALUE))continue;
                    ready.put(id,session.tick+hazard.damageIntervalTicks());
                    if(session.vehicle(id).protectionTicks==0)sink.damage(id,hazard.damage(),"hazard",Long.MIN_VALUE+session.tick*16+id+(long)sequence*(1L<<48));
                }
            }
            continue;
        }
        Map<Integer,Integer> exposure=hazardExposure.computeIfAbsent(hazard.id(),key->new HashMap<>());
        for (VehicleState vehicle:session.vehicles) {
            if (!vehicle.alive() || hazardPhase(hazard.id())!=HazardPhase.ACTIVE || !hazard.contains(world.position(vehicle.id))) {
                exposure.remove(vehicle.id); continue;
            }
            int ticks=exposure.getOrDefault(vehicle.id,0)+1;
            if (ticks>=hazard.damageIntervalTicks()) {
                exposure.remove(vehicle.id);
                if (vehicle.protectionTicks==0) sink.damage(vehicle.id,hazard.damage(),"hazard",
                        Long.MIN_VALUE+session.tick*16+vehicle.id+(long)sequence*(1L<<48));
            } else {
                exposure.put(vehicle.id,ticks);
            }
        }
        }
        if(session.mode!=MatchSession.Mode.LEGACY)hazardTicks++;
    }
    public ProgressStore.ArenaState snapshot() {
        Map<String,ProgressStore.PickupState> pickups=new LinkedHashMap<>();
        definition.pickups().forEach(p->pickups.put(p.id(),new ProgressStore.PickupState(Math.max(0,returnsAt.getOrDefault(p.id(),0L)-session.tick))));
        return new ProgressStore.ArenaState(pickups,objects,session.mode==MatchSession.Mode.LEGACY?hazardSnapshot(hazardClock()):schedule.snapshot(),
                session.mode==MatchSession.Mode.LEGACY?0:schedule.cooldown(),session.mode==MatchSession.Mode.LEGACY?session.seed:schedule.cursor());
    }
    private Map<String,ProgressStore.HazardState> hazardSnapshot(long clock) {
        Map<String,ProgressStore.HazardState> result=new LinkedHashMap<>();
        long total=definition.hazards().stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum(),offset=0;
        for(var hazard:definition.hazards()) {
            long local=Math.floorMod(clock-offset,total),remaining;ProgressStore.HazardPhase phase;
            if(local<hazard.offTicks()) {phase=ProgressStore.HazardPhase.READY;remaining=hazard.offTicks()-local;}
            else if(local<hazard.offTicks()+hazard.warningTicks()) {phase=ProgressStore.HazardPhase.WARNING;remaining=hazard.offTicks()+hazard.warningTicks()-local;}
            else if(local<hazard.periodTicks()) {phase=ProgressStore.HazardPhase.ACTIVE;remaining=hazard.periodTicks()-local;}
            else {phase=ProgressStore.HazardPhase.COOLDOWN;remaining=total-local;}
            result.put(hazard.id(),new ProgressStore.HazardState(phase,remaining,0,clock/total));offset+=hazard.periodTicks();
        }
        return result;
    }
    public void restore(ProgressStore.ArenaState state,PhysicsWorld world,NavGraph graph) {
        if(session.mode!=MatchSession.Mode.LEGACY) {
            schedule.restore(state);
            returnsAt.clear();state.pickups().forEach((id,p)->returnsAt.put(id,Math.addExact(session.tick,p.respawnTicks())));
            objects.clear();objects.putAll(state.objects());hazardExposure.clear();removedGeometry.clear();
            restoreGeometry(state,world,graph,definition);syncMechanisms(world);schedule.snapshot().keySet().forEach(id->announcedPhases.put(id,hazardPhase(id)));return;
        }
        long restored=0;
        if(!definition.hazards().isEmpty()) {
            var first=definition.hazards().getFirst();var stored=Objects.requireNonNull(state.hazards().get(first.id()));
            long total=definition.hazards().stream().mapToLong(ArenaDefinition.Hazard::periodTicks).sum();
            long end=switch(stored.phase()) {
                case READY -> first.offTicks();case WARNING -> first.offTicks()+first.warningTicks();
                case ACTIVE -> first.periodTicks();case COOLDOWN -> total;
                default -> throw new IllegalArgumentException("Invalid saved hazard schedule");
            };
            if(stored.remainingTicks()>end)throw new IllegalArgumentException("Invalid saved hazard duration");
            restored=Math.addExact(Math.multiplyExact(stored.cycle(),total),end-stored.remainingTicks());
            if(!hazardSnapshot(restored).equals(state.hazards()))throw new IllegalArgumentException("Inconsistent saved hazard schedule");
        }
        returnsAt.clear();state.pickups().forEach((id,p)->returnsAt.put(id,Math.addExact(session.tick,p.respawnTicks())));
        objects.clear();objects.putAll(state.objects());hazardTicks=restored;hazardExposure.clear();
        restoreGeometry(state,world,graph,definition);
    }
    /** Initial collider restoration happens before placing participants in the restored road network. */
    public static void restoreGeometry(ProgressStore.ArenaState state,PhysicsWorld world,NavGraph graph,ArenaDefinition definition) {
        for(var object:definition.destructibles()) {
            var saved=Objects.requireNonNull(state.objects().get(object.id()));
            if(saved.open()||(saved.destroyed()&&object.effect()!=ArenaDefinition.ObjectEffect.STATUE)) {
                world.removeStatic(object.geometryId());
                if(definition.edges().stream().anyMatch(edge->edge.type()==ArenaDefinition.Transition.OPENABLE&&object.id().equals(edge.objectId())))
                    graph.setOpen(object.id(),true);
            }
        }
        for(var barrier:definition.barriers()) {
            var saved=state.hazards().get(barrier.id());
            if(saved==null||saved.phase()!=ProgressStore.HazardPhase.ACTIVE)world.removeStatic(barrier.geometryId());
            else if(!world.containsArenaBody(barrier.geometryId())) {
                var box=definition.boxes().stream().filter(b->b.id().equals(barrier.geometryId())).findFirst().orElseThrow();
                world.addMovingBox(barrier.geometryId(),box.size().vector().mult(.5f),box.center().vector(),new Quaternion());
            }
            if(definition.edges().stream().anyMatch(edge->edge.type()==ArenaDefinition.Transition.OPENABLE&&barrier.id().equals(edge.objectId())))
                graph.setOpen(barrier.id(),saved==null||saved.phase()!=ProgressStore.HazardPhase.ACTIVE);
        }
        for(var hazard:definition.hazards()) {
            var saved=state.hazards().get(hazard.id());if(saved==null)continue;
            var mechanism=mechanismView(hazard,saved.phase(),saved.remainingTicks());
            if(mechanism!=null&&!world.containsArenaBody(mechanism.id()))
                world.addMovingBox(mechanism.id(),mechanism.halfExtents(),mechanism.position(),mechanism.rotation());
        }
    }
    private void advanceSchedule(WorldQuery world) {
        if(session.mode==MatchSession.Mode.LEGACY)return;
        schedule.advance(id->{var barrier=definition.barriers().stream().filter(b->b.id().equals(id)).findFirst().orElseThrow();return volumeClear(boxesById.get(barrier.geometryId()),world);});
        for(String id:schedule.drainCompleted()) {
            var object=definition.destructibles().stream().filter(o->o.id().equals(id)).findFirst();
            if(object.isPresent()&&object.get().effect()==ArenaDefinition.ObjectEffect.STATUE)objects.put(id,new ProgressStore.ObjectState(0,true,true));
        }
        schedule.drainActions().forEach(this::completeBossAction);
        announceHazards();
    }
    private void announceHazards() {
        int index=0;
        for(var entry:schedule.snapshot().entrySet()) {
            String id=entry.getKey();HazardPhase phase=hazardPhase(id),previous=announcedPhases.getOrDefault(id,HazardPhase.OFF);
            announcedPhases.put(id,phase);index++;if(phase==previous)continue;
            GameEvent.Type type=phase==HazardPhase.WARNING?GameEvent.Type.ARENA_HAZARD_WARNING:
                    phase==HazardPhase.ACTIVE?GameEvent.Type.ARENA_HAZARD_ACTIVE:previous==HazardPhase.WARNING?GameEvent.Type.ARENA_HAZARD_CANCELLED:null;
            if(type==null)continue;
            Vector3f position=definition.hazards().stream().filter(h->h.id().equals(id)).findFirst().map(h->h.center().vector()).orElseGet(()->{
                var barrier=definition.barriers().stream().filter(b->b.id().equals(id)).findFirst();
                String geometry=barrier.isPresent()?barrier.get().geometryId():definition.destructibles().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow().geometryId();
                return boxesById.get(geometry).center().vector();
            });
            String kind=definition.hazards().stream().filter(h->h.id().equals(id)).findFirst().map(h->h.type().name().toLowerCase(Locale.ROOT))
                    .orElseGet(()->definition.barriers().stream().anyMatch(b->b.id().equals(id))?"barrier":"statue");
            events.add(new GameEvent(type,Long.MIN_VALUE/4+session.tick*64+index,-1,schedule.state(id).completion==null?-1:session.bossParticipantId,
                    position,kind,entry.getValue().remainingTicks()*MatchSession.DT).forObject(id));
        }
    }
    private boolean volumeClear(ArenaDefinition.BoxPart box,WorldQuery world) {
        Vector3f center=box.center().vector(),extent=box.size().vector().mult(.5f);
        for(var vehicle:session.vehicles)if(vehicle.alive()) {
            var bounds=world.profile(vehicle.id).fullBounds();var rotation=world.rotation(vehicle.id);var position=world.position(vehicle.id);
            Vector3f min=new Vector3f(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY),max=min.negate();
            for(float x:new float[]{bounds.minX(),bounds.maxX()})for(float y:new float[]{bounds.minY(),bounds.maxY()})for(float z:new float[]{bounds.minZ(),bounds.maxZ()}) {
                Vector3f point=position.add(rotation.mult(new Vector3f(x,y,z)));min.minLocal(point);max.maxLocal(point);
            }
            if(min.x<center.x+extent.x+.15f&&max.x>center.x-extent.x-.15f&&min.y<center.y+extent.y+.15f&&max.y>center.y-extent.y-.15f
                    &&min.z<center.z+extent.z+.15f&&max.z>center.z-extent.z-.15f)return false;
        }
        return true;
    }
    public boolean requestHazard(String id,BossAction completion) {return schedule.request(id,completion);}
    public ProgressStore.ObjectState objectState(String id) {return Objects.requireNonNull(objects.get(id),"Unknown arena object: "+id);}
    public List<CombatSystem.ArenaTarget> damageTargets() {
        List<CombatSystem.ArenaTarget> result=new ArrayList<>();
        for(var object:definition.destructibles())if(!objects.get(object.id()).destroyed()) {
            var box=boxesById.get(object.geometryId());var center=box.center().vector();var extent=box.size().vector().mult(.5f);
            result.add(new CombatSystem.ArenaTarget(object.geometryId(),center.subtract(extent),center.add(extent)));
        }
        return List.copyOf(result);
    }
    public void damageObject(String geometryId,int sourceId,float amount,String cause,long eventId) {
        if(!Float.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid object damage");
        var object=objectsByGeometry.get(geometryId);if(object==null||session.outcome!=MatchSession.Outcome.NONE)return;
        var state=objects.get(object.id());if(state.destroyed()||amount==0)return;
        float hp=Math.max(0,state.hp()-amount);boolean destroyed=hp==0;
        objects.put(object.id(),new ProgressStore.ObjectState(hp,destroyed,destroyed&&object.effect()!=ArenaDefinition.ObjectEffect.STATUE));
        if(destroyed) {
            if(object.effect()==ArenaDefinition.ObjectEffect.SHIELD)schedule.cancelPreparedAndActive(object.blockTicks());
            if(object.effect()==ArenaDefinition.ObjectEffect.STATUE)schedule.statue(object.id());
            events.add(new GameEvent(GameEvent.Type.ARENA_OBJECT_DESTROYED,eventId,-1,sourceId,boxesById.get(geometryId).center().vector(),cause,amount).forObject(object.id()));
        }
    }
    /** Called after combat object resolution and before environment damage, always outside a native step. */
    public void synchronizeGeometry(PhysicsWorld world,NavGraph graph) {
        syncObjects(world);syncMechanisms(world);if(session.mode!=MatchSession.Mode.LEGACY)announceHazards();
        for(var object:definition.destructibles())if(objects.get(object.id()).open()&&definition.edges().stream().anyMatch(e->e.type()==ArenaDefinition.Transition.OPENABLE&&object.id().equals(e.objectId())))graph.setOpen(object.id(),true);
        for(var barrier:definition.barriers())if(definition.edges().stream().anyMatch(e->e.type()==ArenaDefinition.Transition.OPENABLE&&barrier.id().equals(e.objectId())))
            graph.setOpen(barrier.id(),hazardPhase(barrier.id())!=HazardPhase.ACTIVE);
    }
    private void syncObjects(PhysicsWorld world) {
        for(var object:definition.destructibles())if(objects.get(object.id()).open()&&removedGeometry.add(object.geometryId()))world.removeStatic(object.geometryId());
    }
    public List<MechanismView> mechanisms() {return List.copyOf(mechanisms.values());}
    public HazardPhase barrierPhase(String id) {return hazardPhase(id);}
    private static MechanismView mechanismView(ArenaDefinition.Hazard hazard,ProgressStore.HazardPhase phase,long remaining) {
        boolean active=phase==ProgressStore.HazardPhase.ACTIVE,warning=phase==ProgressStore.HazardPhase.WARNING;
        float elapsed=active?(hazard.activeTicks()-remaining)*MatchSession.DT:0;
        float cx=(hazard.minX()+hazard.maxX())/2,cz=(hazard.minZ()+hazard.maxZ())/2,road=hazard.minY()+.1f;
        Vector3f half,position;Quaternion rotation=new Quaternion();
        switch(hazard.type()) {
            case CRANE -> {
                if(!active&&!warning)return null;
                half=new Vector3f(4,1.5f,4);position=new Vector3f(cx,road+1.5f+10.5f*(1-Math.clamp(elapsed/.25f,0,1)),cz);
            }
            case CAROUSEL -> {
                half=new Vector3f((hazard.maxX()-hazard.minX())/2-1,.65f,.65f);position=new Vector3f(cx,road+1.2f,cz);
                rotation.fromAngleAxis(elapsed*(float)Math.PI,Vector3f.UNIT_Y);
            }
            case TRAFFIC -> {
                if(!active&&!warning)return null;
                half=new Vector3f(2.3f,1.1f,1.1f);boolean alongX=hazard.maxX()-hazard.minX()>=hazard.maxZ()-hazard.minZ();
                float distance=Math.min(64,(alongX?hazard.maxX()-hazard.minX():hazard.maxZ()-hazard.minZ())-8);
                position=new Vector3f(cx+(alongX?-distance/2+elapsed*16:0),road+1.1f,cz+(alongX?0:-distance/2+elapsed*16));
                if(!alongX)rotation.fromAngleAxis((float)Math.PI/2,Vector3f.UNIT_Y);
            }
            default -> {return null;}
        }
        return new MechanismView("mechanism-"+hazard.id(),hazard.id(),hazard.type(),half,position,rotation);
    }
    private void syncMechanisms(PhysicsWorld world) {
        if(session.mode==MatchSession.Mode.LEGACY)return;
        Map<String,MechanismView> desired=new LinkedHashMap<>();
        for(var hazard:definition.hazards()) {
            var state=schedule.state(hazard.id());var view=mechanismView(hazard,state.phase,state.remaining);if(view==null)continue;
            if(hazard.type()==ArenaDefinition.HazardType.TRAFFIC&&!world.containsArenaBody(view.id())) {
                boolean alongX=hazard.maxX()-hazard.minX()>=hazard.maxZ()-hazard.minZ();var position=view.position();
                var envelope=new ArenaDefinition.BoxPart("traffic-spawn",new ArenaDefinition.Vec3(position.x,position.y,position.z),
                        new ArenaDefinition.Vec3(alongX?4.6f:2.2f,2.2f,alongX?2.2f:4.6f),"",true);
                if(!volumeClear(envelope,world)) {schedule.cancel(hazard.id());continue;}
            }
            desired.put(view.id(),view);
        }
        for(String id:List.copyOf(mechanisms.keySet()))if(!desired.containsKey(id))world.removeArenaBody(id);
        for(var entry:desired.entrySet()) {
            var value=entry.getValue();
            if(world.containsArenaBody(entry.getKey()))world.moveArenaBody(value.id(),value.position(),value.rotation());
            else world.addMovingBox(value.id(),value.halfExtents(),value.position(),value.rotation());
        }
        mechanisms.clear();mechanisms.putAll(desired);
        for(var barrier:definition.barriers()) {
            boolean active=hazardPhase(barrier.id())==HazardPhase.ACTIVE;
            if(active&&raisedBarriers.add(barrier.id())) {
                var box=boxesById.get(barrier.geometryId());
                if(!world.containsArenaBody(barrier.geometryId()))world.addMovingBox(barrier.geometryId(),box.size().vector().mult(.5f),box.center().vector(),new Quaternion());
            } else if(!active&&raisedBarriers.remove(barrier.id()))world.removeArenaBody(barrier.geometryId());
        }
    }
    public void stop(PhysicsWorld world) {
        for(String id:mechanisms.keySet())world.removeArenaBody(id);mechanisms.clear();
        for(var barrier:definition.barriers())world.removeArenaBody(barrier.geometryId());raisedBarriers.clear();
        schedule.cancelPreparedAndActive(0);hazardExposure.clear();mechanicalReadyAt.clear();
    }
    public void collectPickups(WorldQuery world) {
        if (lastPickupTick==session.tick || session.outcome!=MatchSession.Outcome.NONE) return;
        lastPickupTick=session.tick;
        int index=0;
        for (var pickup:definition.pickups()) {
            int pickupIndex=index++;
            if (!active(pickup.id())) continue;
            VehicleState winner=null; float nearest=Float.POSITIVE_INFINITY;
            Vector3f surface=pickup.position().vector();
            for (VehicleState vehicle:session.vehicles) {
                if (!vehicle.alive() || vehicle.protectionTicks>0 || !needs(vehicle,pickup.type())) continue;
                Vector3f position=world.position(vehicle.id);
                float dx=position.x-surface.x,dz=position.z-surface.z,distance=dx*dx+dz*dz;
                if (distance>4 || Math.abs(position.y-surface.y)>2) continue;
                if (!world.visible(surface.add(0,.5f,0),position,vehicle.id)) continue;
                if (distance<nearest || (distance==nearest && (winner==null || vehicle.id<winner.id))) {
                    winner=vehicle; nearest=distance;
                }
            }
            if (winner!=null) {
                float amount=apply(winner,pickup.type());
                returnsAt.put(pickup.id(),session.tick+pickup.respawnTicks());
                events.add(new GameEvent(GameEvent.Type.PICKUP,-(1L<<60)-session.tick*16-pickupIndex,
                        winner.id,winner.id,surface,pickupKind(pickup.type()),amount).forObject(pickup.id()));
            }
        }
    }
    private static float apply(VehicleState vehicle,ArenaDefinition.PickupType type) {
        return switch (type) {
            case REPAIR -> { float old=vehicle.hp; vehicle.hp=Math.min(vehicle.maximumHp,vehicle.hp+vehicle.maximumHp*vehicle.repairFraction); yield vehicle.hp-old; }
            case HOMING_AMMO -> vehicle.weapon(WeaponType.HOMING).refill(3);
            case POWER_AMMO -> vehicle.weapon(WeaponType.POWER).refill(2);
            case MINE_AMMO -> vehicle.weapon(WeaponType.MINE).refill(2);
            case NAPALM_AMMO -> vehicle.weapon(WeaponType.NAPALM).refill(2);
            case BALLISTIC_AMMO -> vehicle.weapon(WeaponType.BALLISTIC).refill(1);
            case CANNON_AMMO -> vehicle.weapon(WeaponType.CANNON).refill(2);
            case TURBO_CELL -> { float old=vehicle.turbo; vehicle.turbo=Math.min(100,vehicle.turbo+50); yield vehicle.turbo-old; }
        };
    }
    public static boolean needs(VehicleState vehicle,ArenaDefinition.PickupType type) {
        return switch (type) {
            case REPAIR -> vehicle.hp<vehicle.maximumHp;
            case HOMING_AMMO -> vehicle.weapon(WeaponType.HOMING).ammo<vehicle.weapon(WeaponType.HOMING).maximumAmmo;
            case POWER_AMMO -> vehicle.weapon(WeaponType.POWER).ammo<vehicle.weapon(WeaponType.POWER).maximumAmmo;
            case MINE_AMMO -> vehicle.weapon(WeaponType.MINE).ammo<vehicle.weapon(WeaponType.MINE).maximumAmmo;
            case NAPALM_AMMO -> vehicle.weapon(WeaponType.NAPALM).ammo<vehicle.weapon(WeaponType.NAPALM).maximumAmmo;
            case BALLISTIC_AMMO -> vehicle.weapon(WeaponType.BALLISTIC).ammo<vehicle.weapon(WeaponType.BALLISTIC).maximumAmmo;
            case CANNON_AMMO -> vehicle.weapon(WeaponType.CANNON).ammo<vehicle.weapon(WeaponType.CANNON).maximumAmmo;
            case TURBO_CELL -> vehicle.turbo<100;
        };
    }
    public boolean active(String id) { return session.tick>=returnsAt.getOrDefault(id,0L); }
    private static String pickupKind(ArenaDefinition.PickupType type) {
        return switch(type) {
            case REPAIR -> "repair";
            case HOMING_AMMO -> "homing-ammo";
            case POWER_AMMO -> "power-ammo";
            case MINE_AMMO -> "mine-ammo";
            case NAPALM_AMMO -> "napalm-ammo";
            case BALLISTIC_AMMO -> "ballistic-ammo";
            case CANNON_AMMO -> "cannon-ammo";
            case TURBO_CELL -> "turbo";
        };
    }
    public List<ArenaDefinition.Pickup> activePickups() {
        return definition.pickups().stream().filter(p->active(p.id())).toList();
    }
    public List<GameEvent> drainEvents() {
        events.addAll(launches.drainEvents());
        List<GameEvent> result=events.stream().map(e->e.inSession(session.sessionId)).toList(); events.clear(); return result;
    }
}
