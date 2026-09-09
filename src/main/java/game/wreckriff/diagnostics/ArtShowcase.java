package game.wreckriff.diagnostics;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.*;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Forty-second real-window art evidence. Only scenario preparation is staged; pickup events are never fabricated. */
public final class ArtShowcase {
    public static final int SECONDS=40;
    private static final int INTRO_SECONDS=8,SLOT_SECONDS=3;
    private static final List<ArenaDefinition.PickupType> TYPES=List.of(
            ArenaDefinition.PickupType.HOMING_AMMO,ArenaDefinition.PickupType.POWER_AMMO,
            ArenaDefinition.PickupType.MINE_AMMO,ArenaDefinition.PickupType.NAPALM_AMMO,
            ArenaDefinition.PickupType.BALLISTIC_AMMO,ArenaDefinition.PickupType.CANNON_AMMO,
            ArenaDefinition.PickupType.REPAIR,ArenaDefinition.PickupType.TURBO_CELL);
    private final MatchSession session;
    private final PhysicsWorld world;
    private final MatchRuntime runtime;
    private final ArenaDefinition arena;
    private final List<ArenaDefinition.Pickup> pickups;
    private final Set<String> captures=new LinkedHashSet<>(),eventIds=new HashSet<>();
    private final EnumSet<ArenaDefinition.PickupType> collected=EnumSet.noneOf(ArenaDefinition.PickupType.class);
    private final List<Map<String,Object>> timeline=new ArrayList<>(),staging=new ArrayList<>();
    private final List<String> failures=new ArrayList<>();
    private final long startTick;
    private int activeSlot=-1;
    private Vector3f approachDirection=Vector3f.UNIT_Z.clone();
    private boolean launchPrepared,launched,landed;
    private double launchSeconds=-1,landingSeconds=-1;
    private WeaponType selectedBefore;

    public ArtShowcase(MatchSession session,PhysicsWorld world,MatchRuntime runtime,ArenaDefinition arena) {
        this.session=Objects.requireNonNull(session);this.world=Objects.requireNonNull(world);
        this.runtime=Objects.requireNonNull(runtime);this.arena=Objects.requireNonNull(arena);
        if(!session.arenaId.equals(arena.id()))throw new IllegalArgumentException("Art showcase arena mismatch");
        pickups=TYPES.stream().map(type->arena.pickups().stream().filter(p->p.type()==type).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Art showcase needs existing pickup "+type))).toList();
        runtime.skipIntro();startTick=session.tick;
        // Keep staged opponents alive and unable to consume the demonstration resources.
        for(var state:session.vehicles)state.protectionTicks=(state.player?INTRO_SECONDS:SECONDS+1)*MatchSession.TICKS_PER_SECOND;
    }
    public float seconds() {return(session.tick-startTick)/(float)MatchSession.TICKS_PER_SECOND;}
    public Map<Integer,VehicleCommand> commands() {
        float seconds=seconds();int slot=(int)((seconds-INTRO_SECONDS)/SLOT_SECONDS);
        if(seconds>=INTRO_SECONDS&&slot<pickups.size()) {
            if(activeSlot!=slot)stagePickup(slot);
            float within=seconds-INTRO_SECONDS-slot*SLOT_SECONDS;
            boolean got=collected.contains(pickups.get(slot).type());
            return Map.of(0,drive(within>=.65f&&!got?.65f:0,got?1:0));
        }
        if(seconds>=32&&!arena.launchPads().isEmpty()) {
            if(!launchPrepared)stageLaunch();
            return Map.of(0,drive(!launched&&failures.isEmpty()?.8f:0,landed?1:0));
        }
        return Map.of();
    }
    private static VehicleCommand drive(float throttle,float brake) {
        return new VehicleCommand(throttle,0,0,brake>0,false,false,false,null,0,false,false,game.wreckriff.combat.AbilityId.NONE);
    }
    private void stagePickup(int slot) {
        activeSlot=slot;var pickup=pickups.get(slot);var state=session.vehicle(0);
        state.protectionTicks=0;state.hp=state.maximumHp;state.turbo=100;
        for(var weapon:state.weapons())weapon.ammo=weapon.maximumAmmo;
        switch(pickup.type()) {
            case REPAIR -> state.hp=state.maximumHp*.65f;
            case TURBO_CELL -> state.turbo=50;
            case HOMING_AMMO -> state.weapon(WeaponType.HOMING).ammo=Math.max(0,state.weapon(WeaponType.HOMING).maximumAmmo-3);
            case POWER_AMMO -> state.weapon(WeaponType.POWER).ammo=Math.max(0,state.weapon(WeaponType.POWER).maximumAmmo-2);
            case MINE_AMMO -> state.weapon(WeaponType.MINE).ammo=Math.max(0,state.weapon(WeaponType.MINE).maximumAmmo-2);
            case NAPALM_AMMO -> state.weapon(WeaponType.NAPALM).ammo=Math.max(0,state.weapon(WeaponType.NAPALM).maximumAmmo-2);
            case BALLISTIC_AMMO -> state.weapon(WeaponType.BALLISTIC).ammo=Math.max(0,state.weapon(WeaponType.BALLISTIC).maximumAmmo-1);
            case CANNON_AMMO -> state.weapon(WeaponType.CANNON).ammo=Math.max(0,state.weapon(WeaponType.CANNON).maximumAmmo-1);
        }
        selectedBefore=state.selectedWeapon;
        if(!runtime.arenaSystems().active(pickup.id())) {
            failures.add("Pickup unavailable before staged drive: "+pickup.id());return;
        }
        Vector3f surface=pickup.position().vector();
        for(int heading=0;heading<8;heading++) {
            float angle=heading*FastMath.QUARTER_PI;
            Vector3f direction=new Vector3f(FastMath.sin(angle),0,FastMath.cos(angle));
            Quaternion rotation=new Quaternion().fromAngleAxis(angle,Vector3f.UNIT_Y);
            if(!supportedApproach(surface,direction,rotation,4.5f))continue;
            Vector3f position=surface.subtract(direction.mult(4.5f)).addLocal(0,world.profile(0).roadOffset(),0);
            world.teleport(0,position,rotation);approachDirection=direction;
            staging.add(Map.of("seconds",seconds(),"action","position-and-resource-preparation",
                    "pickupId",pickup.id(),"type",pickup.type().name(),"position",position.toString(),
                    "selectedWeapon",selectedBefore.name()));return;
        }
        failures.add("No supported clear approach to existing pickup: "+pickup.id());
    }
    private boolean supportedApproach(Vector3f target,Vector3f direction,Quaternion rotation,float distance) {
        var profile=world.profile(0);
        for(float offset=distance;offset>=0;offset-=.5f) {
            Vector3f surface=target.subtract(direction.mult(offset)),position=surface.add(0,profile.roadOffset(),0);
            if(!arena.bounds().contains(surface)||!world.freePose(0,position,rotation))return false;
            for(int wheel=0;wheel<4;wheel++) {
                Vector3f connection=profile.wheelConnection(wheel);connection.y=0;
                Vector3f point=surface.add(rotation.mult(connection));
                var support=world.support(point.add(0,.5f,0),1);
                if(support==null||Math.abs(support.point().y-target.y)>.15f||support.normal().y<.9f)return false;
            }
        }
        return true;
    }
    private void stageLaunch() {
        launchPrepared=true;
        var pad=arena.launchPads().getFirst();Vector3f direction=pad.direction();
        Quaternion rotation=new Quaternion().fromAngleAxis(FastMath.atan2(direction.x,direction.z),Vector3f.UNIT_Y);
        if(!supportedApproach(pad.source().vector(),direction,rotation,4)) {
            failures.add("No supported staged launch approach: "+pad.id());return;
        }
        Vector3f position=pad.source().vector().subtract(direction.mult(4)).addLocal(0,world.profile(0).roadOffset(),0);
        world.teleport(0,position,rotation);session.vehicle(0).protectionTicks=8*MatchSession.TICKS_PER_SECOND;
        staging.add(Map.of("seconds",seconds(),"action","launch-approach-positioning","launchId",pad.id(),"position",position.toString()));
    }
    public void accept(List<GameEvent> batch) {
        for(var event:batch) {
            if(event.subjectId()!=0)continue;
            if(event.type()==GameEvent.Type.PICKUP) {
                String key=event.sessionId()+":"+event.eventId();
                if(!eventIds.add(key)) {failures.add("Repeated pickup event delivered: "+key);continue;}
                var pickup=pickups.stream().filter(p->p.id().equals(event.objectId())).findFirst();
                if(pickup.isEmpty()) {failures.add("Unexpected pickup event: "+event.objectId());continue;}
                if(event.value()<=0)failures.add("Non-positive successful pickup: "+event.objectId());
                if(selectedBefore!=null&&session.vehicle(0).selectedWeapon!=selectedBefore)failures.add("Pickup changed selected weapon");
                collected.add(pickup.get().type());
                timeline.add(Map.of("seconds",seconds(),"type",event.type().name(),"kind",event.kind(),
                        "pickupId",event.objectId(),"amount",event.value(),"eventId",event.eventId(),
                        "grounded",world.grounded(0),"position",world.position(0).toString()));
            } else if(event.type()==GameEvent.Type.LAUNCHED) {
                launched=true;launchSeconds=seconds();timeline.add(Map.of("seconds",seconds(),"type","LAUNCHED","launchId",event.kind()));
            } else if(event.type()==GameEvent.Type.LANDED) {
                landed=true;landingSeconds=seconds();timeline.add(Map.of("seconds",seconds(),"type","LANDED","launchId",event.kind()));
            }
        }
    }
    public String label() {
        String prefix="ART SHOWCASE / staged positions and resources / real pickup events";
        if(seconds()<8)return prefix+" / "+arena.metadata().title()+" / scene overview";
        if(seconds()<32&&activeSlot>=0)return prefix+" / "+pickups.get(activeSlot).type().name();
        return prefix+(arena.launchPads().isEmpty()?" / Rivet exterior":" / native launch and landing");
    }
    public String frame(Camera camera) {
        float seconds=seconds();
        camera.setFrustumPerspective(52,camera.getWidth()/(float)camera.getHeight(),.1f,650);
        Vector3f target,offset;
        if(seconds<8) {
            var bounds=arena.bounds();float cx=(bounds.minX()+bounds.maxX())/2,cz=(bounds.minZ()+bounds.maxZ())/2;
            int view=(int)(seconds/2);
            var upper=arena.boxes().stream().filter(b->arena.surfaces().stream().anyMatch(s->s.level()==1&&s.geometryId().equals(b.id())))
                    .findFirst().orElse(arena.boxes().getFirst());
            target=switch(view) {
                case 0 -> new Vector3f(cx,2,cz);
                case 1 -> upper.center().vector().addLocal(0,1,0);
                case 2 -> arena.launchPads().isEmpty()?world.position(0):arena.launchPads().getFirst().source().vector();
                default -> new Vector3f(cx,18,bounds.maxZ()+14);
            };
            offset=switch(view) {case 0 -> new Vector3f(-65,47,-77);case 1 -> new Vector3f(34,19,-35);
                case 2 -> new Vector3f(14,9,-18);default -> new Vector3f(-42,7,-76);};
            camera.setLocation(target.add(offset));camera.lookAt(target,Vector3f.UNIT_Y);
            String name=List.of("art-overview","art-upper-route","art-launch-approach","art-landmark").get(view);
            if(seconds-view*2>=.9f&&captures.add(name))return name;
        } else if(seconds<32&&activeSlot>=0) {
            var pickup=pickups.get(activeSlot);target=pickup.position().vector().addLocal(0,.8f,0);
            Vector3f side=new Vector3f(approachDirection.z,0,-approachDirection.x);
            offset=approachDirection.mult(-7).addLocal(side.mult(5)).addLocal(0,3.7f,0);
            camera.setFrustumPerspective(43,camera.getWidth()/(float)camera.getHeight(),.1f,650);
            camera.setLocation(target.add(offset));camera.lookAt(target,Vector3f.UNIT_Y);
            float within=seconds-INTRO_SECONDS-activeSlot*SLOT_SECONDS;String suffix=pickup.type().name().toLowerCase(Locale.ROOT);
            if(within>=.35f&&captures.add("pickup-available-"+suffix))return "pickup-available-"+suffix;
            if(collected.contains(pickup.type())&&captures.add("pickup-collected-"+suffix))return "pickup-collected-"+suffix;
        } else {
            target=world.position(0);offset=new Vector3f(10,7,-13);
            if(!arena.launchPads().isEmpty()) {
                var pad=arena.launchPads().getFirst();Vector3f direction=pad.direction();
                offset=new Vector3f(direction.z*15,7,-direction.x*15).addLocal(direction.mult(-5));
            }
            camera.setLocation(target.add(offset));camera.lookAt(target,Vector3f.UNIT_Y);
            if(launched&&seconds>=launchSeconds+.7&&captures.add("art-native-launch"))return "art-native-launch";
            if(landed&&seconds>=landingSeconds+.1&&captures.add("art-native-landing"))return "art-native-landing";
            if(arena.launchPads().isEmpty()&&seconds>=36&&captures.add("art-rivet-exterior"))return "art-rivet-exterior";
        }
        return null;
    }
    public Map<String,Object> evidence() {
        return Map.of("arenaId",arena.id(),"seconds",seconds(),"collectedTypes",collected.stream().map(Enum::name).toList(),
                "captures",List.copyOf(captures),"events",List.copyOf(timeline),"staging",List.copyOf(staging),"failures",List.copyOf(failures),
                "nativeLaunch",launched,"nativeLanding",landed,
                "method","Positions and resource deficits are staged. All PICKUP events originate from the normal MatchRuntime/ArenaSystems/PhysicsWorld pipeline. No performance or owner feel approval is implied.");
    }
    public boolean complete() {return seconds()>=SECONDS;}
    public boolean demonstrated() {
        return complete()&&failures.isEmpty()&&collected.size()==TYPES.size()
                &&(arena.launchPads().isEmpty()||launched&&landed);
    }
}
