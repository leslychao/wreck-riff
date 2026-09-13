package game.wreckriff.diagnostics;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.combat.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.*;

/** Final showcase episode: staged starting poses, then only native driver and combat commands. */
final class FreezeBallisticShowcase {
    static final int START_TICK=40*MatchSession.TICKS_PER_SECOND;
    private static final Set<String> REQUIRED_CAPTURES=Set.of("combo-freeze-launch","combo-freeze-flight","combo-freeze-hit",
            "combo-ballistic-launch","combo-ballistic-warning","combo-ballistic-hit-1","combo-ballistic-hit-2",
            "combo-ballistic-hit-3","combo-ballistic-hit-4","combo-freeze-ended");
    private final MatchSession session;
    private final PhysicsWorld world;
    private final CombatSystem combat;
    private final Set<Long> damagingCharges=new HashSet<>();
    private final Set<String> captures=new HashSet<>();
    private final Set<String> emittedCaptures=new HashSet<>();
    private final Deque<String> pendingCaptures=new ArrayDeque<>();
    private final List<Map<String,Object>> timeline=new ArrayList<>();
    private long frozenAt=-1,ballisticAt=-1,endedAt=-1;
    private boolean allHitsWhileFrozen=true;

    FreezeBallisticShowcase(MatchSession session,PhysicsWorld world,CombatSystem combat) {
        this.session=session;this.world=world;this.combat=combat;
        world.teleport(0,new Vector3f(-42,.85f,-48),new Quaternion());
        world.teleport(2,new Vector3f(-42,.85f,-13),new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y));
        session.vehicle(2).hp=session.vehicle(2).maximumHp*.65f;
    }
    Map<Integer,VehicleCommand> commands() {
        var result=new HashMap<Integer,VehicleCommand>();
        if(session.tick>=START_TICK+60)result.put(2,new VehicleCommand(.3f,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE));
        if(session.tick==START_TICK+120)result.put(0,new VehicleCommand(0,0,0,false,false,false,false,WeaponType.BALLISTIC,0,false,false,AbilityId.FREEZE));
        if(frozenAt>=0&&session.tick==frozenAt+30) {
            ballisticAt=session.tick;
            result.put(0,new VehicleCommand(0,0,0,false,false,false,true,WeaponType.BALLISTIC,0,false,false,AbilityId.NONE));
        }
        return result;
    }
    void accept(List<GameEvent> batch) {
        for(var event:batch) {
            String capture=null;
            if(event.type()==GameEvent.Type.SHOT&&event.kind().equals("freeze")&&event.sourceId()==0)capture="combo-freeze-launch";
            if(event.type()==GameEvent.Type.FREEZE&&event.subjectId()==2&&event.sourceId()==0) {
                frozenAt=event.simulationTick();capture="combo-freeze-hit";
            }
            if(event.type()==GameEvent.Type.SHOT&&event.kind().equals("ballistic")&&event.sourceId()==0)capture="combo-ballistic-launch";
            if(event.type()==GameEvent.Type.DAMAGE&&event.kind().equals("ballistic")&&event.subjectId()==2&&event.sourceId()==0) {
                allHitsWhileFrozen&=session.vehicle(2).frozenTicks>0;
                if(damagingCharges.add(event.eventId()))capture="combo-ballistic-hit-"+damagingCharges.size();
            }
            if(event.type()==GameEvent.Type.CONTROL_ENDED&&event.kind().equals("freeze")&&event.subjectId()==2) {
                endedAt=event.simulationTick();capture="combo-freeze-ended";
            }
            if(capture!=null) {
                enqueue(capture);
                timeline.add(Map.of("tick",event.simulationTick(),"kind",event.kind(),"type",event.type().name(),
                        "eventId",event.eventId(),"frozenTicks",session.vehicle(2).frozenTicks,"value",event.value()));
            }
        }
    }
    private void enqueue(String capture) {if(captures.add(capture))pendingCaptures.addLast(capture);}
    String frame(Camera camera) {
        Vector3f target=world.position(2),offset=new Vector3f(6,3.3f,-7);
        var freeze=combat.projectiles().stream().filter(p->p.kind().equals("freeze")&&p.ownerId()==0).findFirst().orElse(null);
        if(freeze!=null) {
            target=freeze.position();offset=new Vector3f(4,2.5f,-5);
            if(freeze.remainingTicks()<=CombatRules.ticks(session.combatRules.control().freezeTtlSeconds())-8)enqueue("combo-freeze-flight");
        } else if(frozenAt<0) {target=world.position(0).interpolateLocal(target,.5f);offset=new Vector3f(20,11,-23);}
        else if(ballisticAt>=0&&damagingCharges.isEmpty()) {
            target=target.add(0,5,0);offset=new Vector3f(20,12,-24);
            if(!combat.ballisticWarnings().isEmpty())enqueue("combo-ballistic-warning");
        }
        camera.setFrustumPerspective(50,camera.getWidth()/(float)camera.getHeight(),.1f,500);
        camera.setLocation(target.add(offset));camera.lookAt(target,Vector3f.UNIT_Y);
        String capture=pendingCaptures.pollFirst();if(capture!=null)emittedCaptures.add(capture);
        return capture;
    }
    String label() {
        if(frozenAt<0)return "CRYO ROCKET / INSTANT HOMING ON A MOVING HULL";
        if(endedAt>=0)return "FREEZE ENDED / ICE BREAKUP AND NATIVE MOVEMENT RESUME";
        return "FREEZE -> BALLISTIC / FOUR SECONDS, FOUR NATIVE HITS ("+damagingCharges.size()+"/4)";
    }
    boolean demonstrated() {
        return frozenAt>=0&&ballisticAt-frozenAt==30&&endedAt-frozenAt==480&&damagingCharges.size()==4&&allHitsWhileFrozen
                &&emittedCaptures.containsAll(REQUIRED_CAPTURES);
    }
    Map<String,Object> evidence() {
        return Map.of("method","Staged 35m starting poses and 65% HP. Real lateral drive, Freeze impact, Ballistic 30 ticks later; no pose or timer writes during the combo.",
                "freezeHitTick",frozenAt,"ballisticLaunchTick",ballisticAt,"freezeEndTick",endedAt,
                "damagingCharges",damagingCharges.size(),"allHitsWhileFrozen",allHitsWhileFrozen,"controlEnded",endedAt>=0,
                "timeline",timeline,"captureNames",emittedCaptures);
    }
}
