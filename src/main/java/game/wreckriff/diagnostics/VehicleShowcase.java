package game.wreckriff.diagnostics;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.combat.*;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Bounded roster evidence. Positions/resources are staged; every attack and hit comes from MatchRuntime. */
public final class VehicleShowcase {
    public static final int SECONDS=24;
    private static final int STAGE_TICKS=8*MatchSession.TICKS_PER_SECOND,SETTLE_TICKS=84;
    private final MatchSession session;
    private final PhysicsWorld world;
    private final MatchRuntime runtime;
    private final ArenaDefinition arena;
    private final int[] actors;
    private final int targetId;
    private final long startTick;
    private final Set<String> captures=new LinkedHashSet<>(),started=new LinkedHashSet<>();
    private final List<String> failures=new ArrayList<>();
    private final List<Map<String,Object>> staging=new ArrayList<>(),timeline=new ArrayList<>();
    private int stage=-1,nativeGrabs;
    private boolean specialSent,powerSent,positioned;
    private Vector3f direction=Vector3f.UNIT_Z.clone(),sparkStart;
    private float pulseDamage,grinderDamage,truckWeaponDamage,bombDamage,sparkMotion;

    public VehicleShowcase(MatchSession session,PhysicsWorld world,MatchRuntime runtime,ArenaDefinition arena) {
        this.session=Objects.requireNonNull(session);this.world=Objects.requireNonNull(world);
        this.runtime=Objects.requireNonNull(runtime);this.arena=Objects.requireNonNull(arena);
        if(!session.arenaId.equals(arena.id())||!arena.id().equals("dead-air-yard"))throw new IllegalArgumentException("Vehicle showcase requires Dead Air Yard");
        actors=new int[]{find("rivet",true),find("grinder",false),find("spark",false)};
        targetId=session.vehicles.stream().filter(v->!v.boss&&Arrays.stream(actors).noneMatch(id->id==v.id))
                .mapToInt(v->v.id).findFirst().orElseThrow(()->new IllegalArgumentException("Vehicle showcase needs a separate target"));
        runtime.skipIntro();startTick=session.tick;
    }
    private int find(String profile,boolean player) {
        return session.vehicles.stream().filter(v->v.profileId.equals(profile)&&!v.boss&&(!player||v.player))
                .mapToInt(v->v.id).findFirst().orElseThrow(()->new IllegalArgumentException("Missing showcase profile "+profile));
    }
    public float seconds(){return(session.tick-startTick)/(float)MatchSession.TICKS_PER_SECOND;}
    public Map<Integer,VehicleCommand> commands() {
        if(complete())return Map.of();
        int next=(int)((session.tick-startTick)/STAGE_TICKS);
        if(next!=stage)prepare(next);
        if(!positioned)return Map.of();
        int within=(int)((session.tick-startTick)%STAGE_TICKS),actor=actors[stage];
        VehicleState state=session.vehicle(actor);
        if(within<SETTLE_TICKS)return Map.of();
        if(!specialSent) {
            if(!world.grounded(actor)||!world.grounded(targetId)) {fail("Unsupported staged actor or target: "+state.profileId);return Map.of();}
            specialSent=true;if(stage==2)sparkStart=world.position(actor);
            return Map.of(actor,command(0,false,false,false,AbilityId.SPECIAL));
        }
        if(stage==1) {
            boolean searching=state.specialPhase==VehicleState.SpecialPhase.GRINDER_SEARCH;
            boolean held=state.grinding()&&session.vehicle(targetId).grabbedBy==actor;
            boolean power=held&&state.specialTicks<=12&&!powerSent;if(power)powerSent=true;
            return Map.of(actor,command(searching||held?1:0,!searching&&!held,held,power,AbilityId.NONE));
        }
        return Map.of();
    }
    private static VehicleCommand command(float throttle,boolean handbrake,boolean mg,boolean power,AbilityId ability) {
        return new VehicleCommand(throttle,0,0,handbrake,false,mg,power,power?WeaponType.POWER:null,0,false,false,ability);
    }
    private void prepare(int next) {
        stage=next;specialSent=powerSent=positioned=false;
        for(var state:session.vehicles) {
            if(!state.alive()||!world.containsVehicle(state.id)) {fail("Participant lost before stage: "+state.id);return;}
            runtime.combat().cancelControl(state,world);
            state.hp=state.maximumHp;state.turbo=100;state.protectionTicks=(SECONDS+2)*MatchSession.TICKS_PER_SECOND;
            state.shieldTicks=state.controlImmunityTicks=state.machineGunCooldown=0;
            for(var ability:List.of(AbilityId.FREEZE,AbilityId.SHIELD,AbilityId.SPECIAL))state.abilityCooldown(ability,0);
            for(var slot:state.weapons()){slot.ammo=slot.maximumAmmo;slot.cooldownTicks=0;}
        }
        // Return all cars to supported, free parking before searching a clear demonstration lane.
        for(var state:session.vehicles)if(!park(state.id)) {fail("No free supported parking for "+state.id);return;}
        int actor=actors[stage];
        for(float x=arena.bounds().minX()+18;x<=arena.bounds().maxX()-18;x+=6)
            for(float z=arena.bounds().minZ()+18;z<=arena.bounds().maxZ()-18;z+=6)
                for(int heading=0;heading<4;heading++) {
                    float yaw=heading*FastMath.HALF_PI;Quaternion rotation=new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y);
                    Vector3f forward=rotation.mult(Vector3f.UNIT_Z),side=rotation.mult(Vector3f.UNIT_X);
                    var support=world.support(new Vector3f(x,30,z),60);if(support==null||support.normal().y<.98f)continue;
                    Vector3f surface=support.point();
                    if(!lane(actor,surface,rotation,forward,stage==1?24:stage==0?18:0))continue;
                    if(stage==2&&!lane(actor,surface,rotation,side.negate(),8))continue;
                    Vector3f targetSurface=stage==2?surface.add(side.mult(2.9f)):surface.add(forward.mult(stage==0?9:6.5f));
                    if(!pose(targetId,targetSurface,rotation))continue;
                    // The two staged poses are checked independently before committing either teleport.
                    float pairDistance=surface.distance(targetSurface);
                    float required=stage==2?(world.profile(actor).width()+world.profile(targetId).width())/2:
                            (world.profile(actor).length()+world.profile(targetId).length())/2;
                    if(pairDistance<required+.4f)continue;
                    var actorPosition=surface.add(0,world.profile(actor).roadOffset(),0);
                    var targetPosition=targetSurface.add(0,world.profile(targetId).roadOffset(),0);
                    world.teleport(actor,actorPosition,rotation);world.teleport(targetId,targetPosition,rotation);
                    session.vehicle(actor).protectionTicks=0;session.vehicle(targetId).protectionTicks=0;
                    direction=forward;positioned=true;
                    staging.add(Map.of("seconds",seconds(),"profile",session.vehicle(actor).profileId,"actorId",actor,"targetId",targetId,
                            "actorPosition",actorPosition.toString(),"targetPosition",targetPosition.toString(),
                            "action","free supported positions and full resource preparation; no attack event injection"));
                    return;
                }
        fail("No clear supported demonstration lane for "+session.vehicle(actor).profileId);
    }
    private boolean park(int id) {
        for(var spawn:arena.spawns()) {
            Quaternion rotation=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
            Vector3f surface=spawn.position().vector();
            if(!pose(id,surface,rotation))continue;
            world.teleport(id,surface.add(0,world.profile(id).roadOffset(),0),rotation);return true;
        }
        return false;
    }
    private boolean lane(int actor,Vector3f surface,Quaternion rotation,Vector3f travel,float length) {
        for(float distance=0;distance<=length;distance+=1)if(!pose(actor,surface.add(travel.mult(distance)),rotation))return false;
        for(var state:session.vehicles)if(state.id!=actor&&state.id!=targetId) {
            Vector3f relative=world.position(state.id).subtract(surface);
            float along=Math.clamp(relative.dot(travel),0,length);
            if(relative.subtract(travel.mult(along)).setY(0).length()<12)return false;
        }
        return true;
    }
    private boolean pose(int id,Vector3f surface,Quaternion rotation) {
        var profile=world.profile(id);Vector3f position=surface.add(0,profile.roadOffset(),0);
        if(!arena.bounds().contains(surface)||!world.freePose(id,position,rotation))return false;
        // Keep the small planted bomb readable instead of staging it underneath a pickup model.
        for(var pickup:arena.pickups())if(pickup.position().vector().subtract(surface).setY(0).length()<5)return false;
        for(var hazard:arena.hazards())if(surface.x>hazard.minX()-4&&surface.x<hazard.maxX()+4&&surface.z>hazard.minZ()-4&&surface.z<hazard.maxZ()+4)return false;
        for(int wheel=0;wheel<4;wheel++) {
            Vector3f connection=profile.wheelConnection(wheel);connection.y=0;
            Vector3f point=surface.add(rotation.mult(connection));var support=world.support(point.add(0,.5f,0),1);
            if(support==null||Math.abs(support.point().y-surface.y)>.08f||support.normal().y<.98f)return false;
        }
        return true;
    }
    public void accept(List<GameEvent> batch) {
        if(stage==2&&sparkStart!=null)sparkMotion=Math.max(sparkMotion,world.position(actors[2]).subtract(sparkStart).setY(0).length());
        if(session.outcome!=MatchSession.Outcome.NONE)fail("Match ended during staged evidence: "+session.outcome);
        for(var event:batch) {
            boolean relevant=false;
            if(event.type()==GameEvent.Type.SPECIAL_STARTED&&Arrays.stream(actors).anyMatch(id->id==event.sourceId())) {
                started.add(event.kind());relevant=true;
            } else if(event.type()==GameEvent.Type.GRAB_STARTED&&event.sourceId()==actors[1]&&event.subjectId()==targetId) {
                if(world.grabIntact(actors[1],targetId))nativeGrabs++;else fail("Capture event had no live native constraint");
                relevant=true;
            } else if(event.type()==GameEvent.Type.DAMAGE&&event.subjectId()==targetId) {
                if(event.kind().equals("pulse")&&event.sourceId()==actors[0]) {pulseDamage+=event.value();relevant=true;}
                if(event.kind().equals("grinder")&&event.sourceId()==actors[1]) {grinderDamage+=event.value();relevant=true;}
                if((event.kind().equals("machine-gun")||event.kind().equals("power"))&&event.sourceId()==actors[1]) {truckWeaponDamage+=event.value();relevant=true;}
                if(event.kind().equals("special-bomb")&&event.sourceId()==actors[2]) {bombDamage+=event.value();relevant=true;}
            } else if(event.type()==GameEvent.Type.EXPLOSION&&event.kind().equals("special-bomb"))relevant=true;
            if(relevant)timeline.add(Map.of("seconds",seconds(),"type",event.type().name(),"kind",event.kind(),"sourceId",event.sourceId(),
                    "subjectId",event.subjectId(),"amount",event.value(),"eventId",event.eventId()));
        }
    }
    private void fail(String reason){if(!failures.contains(reason))failures.add(reason);}
    public String label() {
        String profile=stage<0?"ТРИ МАШИНЫ":VehicleDefinition.forId(session.vehicle(actors[stage]).profileId).displayName();
        return profile+" / постановочные позиции и ресурсы / настоящие способности, физика и попадания";
    }
    public String frame(Camera camera) {
        if(stage<0||!positioned)return null;
        int actor=actors[stage];Vector3f actorPosition=world.position(actor),target=actorPosition.add(0,.65f,0);
        if(stage!=2)target.interpolateLocal(world.position(targetId).add(0,.5f,0),.35f);
        else if(sparkStart!=null)target.interpolateLocal(sparkStart.add(0,.4f,0),.4f);
        Vector3f side=new Vector3f(direction.z,0,-direction.x);
        camera.setFrustumPerspective(45,camera.getWidth()/(float)camera.getHeight(),.1f,450);
        camera.setLocation(target.add(side.mult(stage==2?11:9)).addLocal(direction.mult(9)).addLocal(0,5,0));
        camera.lookAt(target,Vector3f.UNIT_Y);
        String profile=session.vehicle(actor).profileId;
        float within=seconds()-stage*8;
        if(within>=.4f&&captures.add("vehicle-"+profile+"-exterior"))return "vehicle-"+profile+"-exterior";
        var state=session.vehicle(actor);
        if((state.specialPhase==VehicleState.SpecialPhase.PULSE_WINDUP||state.specialPhase==VehicleState.SpecialPhase.GRINDER_WINDUP)
                &&captures.add("vehicle-"+profile+"-charge"))return "vehicle-"+profile+"-charge";
        if(stage==0&&pulseDamage>0&&captures.add("vehicle-rivet-pulse-hit"))return "vehicle-rivet-pulse-hit";
        if(stage==1&&nativeGrabs>0&&captures.add("vehicle-grinder-capture"))return "vehicle-grinder-capture";
        if(stage==1&&truckWeaponDamage>0&&captures.add("vehicle-grinder-weapon-hit"))return "vehicle-grinder-weapon-hit";
        if(stage==2&&state.dashing()&&sparkMotion>=1.5f&&captures.add("vehicle-spark-dash"))return "vehicle-spark-dash";
        if(stage==2&&bombDamage>0&&captures.add("vehicle-spark-bomb-hit"))return "vehicle-spark-bomb-hit";
        return null;
    }
    public Map<String,Object> evidence() {
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("arenaId",arena.id());evidence.put("seconds",seconds());
        evidence.put("specialProfiles",List.copyOf(started));evidence.put("nativeGrabs",nativeGrabs);evidence.put("pulseDamage",pulseDamage);
        evidence.put("grinderDamage",grinderDamage);evidence.put("truckWeaponDamage",truckWeaponDamage);evidence.put("bombDamage",bombDamage);
        evidence.put("sparkMotionMetres",sparkMotion);evidence.put("captures",List.copyOf(captures));evidence.put("events",List.copyOf(timeline));
        evidence.put("staging",List.copyOf(staging));evidence.put("failures",List.copyOf(failures));
        evidence.put("method","Positions and full resources are staged on existing verified roads. Attacks, movement, capture constraints and damage originate only from normal runtime ticks. This is not balance or owner feel approval.");
        return Collections.unmodifiableMap(evidence);
    }
    public boolean complete(){return seconds()>=SECONDS;}
    public boolean demonstrated(){return complete()&&failures.isEmpty()&&started.containsAll(Set.of("rivet","grinder","spark"))
            &&nativeGrabs>0&&pulseDamage>0&&grinderDamage>0&&truckWeaponDamage>0&&bombDamage>0&&sparkMotion>=3;}
}
