package game.wreckriff.ai;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.NavGraph;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Short native balance probes: all pairs and mirrors plus three-car fights; no claimed win-rate balance. */
class NativeRosterBalanceMatrixTest {
    static Stream<Arguments> scenarios() {
        var rows=new ArrayList<Arguments>();var ids=List.of("rivet","grinder","spark");
        for(String layout:List.of("open","narrow","pickup")) {
            for(int first=0;first<ids.size();first++)for(int second=first;second<ids.size();second++)
                rows.add(Arguments.of(List.of(ids.get(first),ids.get(second)),layout));
            rows.add(Arguments.of(ids,layout));
        }
        return rows.stream();
    }

    @ParameterizedTest @MethodSource("scenarios")
    void everyPairAndFreeForAllUsesNativeContactsAndSharedControlLimits(List<String> profiles,String layout) {
        var rules=VehicleRules.load();var arena=arena(layout);
        var session=MatchSession.balanced(73,profiles,Configs.load("combat",CombatRules.class));
        try(var world=new PhysicsWorld(rules)) {
            world.configureArena(arena);
            for(var box:arena.boxes())world.addStatic(box.id(),new BoxCollisionShape(box.size().vector().mult(.5f)),box.center().vector(),new Quaternion());
            for(var state:session.vehicles) {
                var profile=VehicleDefinition.forId(state.profileId).profile(rules);
                var point=new Vector3f(0,profile.roadOffset()+.3f,state.id==0?-9:state.id==1?9:29);
                world.addVehicle(state.id,point,new Quaternion().fromAngleAxis(state.id==0?0:(float)Math.PI,Vector3f.UNIT_Y),profile);
                assertEquals(VehicleDefinition.forId(state.profileId).maximumHp(),state.maximumHp);
            }
            for(int tick=0;tick<360;tick++)world.step();
            try(var runtime=new MatchRuntime(session,world,arena,new NavGraph(arena),rules)) {
                int shots=0,activations=0,captures=0,pickups=0,maxControl=0;
                float damage=0;var controlRun=new int[profiles.size()];
                var grindDamage=new HashMap<Integer,Float>();
                for(int tick=0;tick<12*120&&session.outcome==MatchSession.Outcome.NONE;tick++) {
                    var events=runtime.tick(runtime.bots().commands(world),false);
                    for(var event:events) {
                        if(event.type()==GameEvent.Type.SHOT)shots++;
                        if(event.type()==GameEvent.Type.SPECIAL_STARTED)activations++;
                        if(event.type()==GameEvent.Type.GRAB_STARTED)captures++;
                        if(event.type()==GameEvent.Type.PICKUP)pickups++;
                        if(event.type()==GameEvent.Type.DAMAGE) {
                            damage+=event.value();
                            if(event.kind().equals("grinder"))grindDamage.merge(event.sourceId(),event.value(),Float::sum);
                        }
                    }
                    assertTrue(world.grabCount()<=1,"Two or three participants cannot form stacked grabs");
                    for(var state:session.vehicles) {
                        assertTrue(Float.isFinite(state.hp)&&state.hp>=0&&state.hp<=state.maximumHp);
                        assertFalse(state.frozenTicks>0&&state.grabbedBy>=0,"Freeze and hold never overlap");
                        controlRun[state.id]=state.controlled()?controlRun[state.id]+1:0;
                        maxControl=Math.max(maxControl,controlRun[state.id]);
                        assertTrue(controlRun[state.id]<=241,"Immunity must separate control episodes");
                        if(world.containsVehicle(state.id))assertTrue(Vector3f.isValidVector(world.position(state.id))&&Vector3f.isValidVector(world.velocity(state.id)));
                    }
                }
                System.out.printf(Locale.ROOT,"ROSTER_PROBE profiles=%s layout=%s seconds=%.2f shots=%d specials=%d captures=%d damage=%.2f maxControlSeconds=%.3f pickups=%d hp=%s%n",
                        profiles,layout,session.seconds(),shots,activations,captures,damage,maxControl/120f,pickups,session.vehicles.stream().map(v->v.hp).toList());
                assertTrue(shots>0&&damage>0,"A balance probe must include actual attacks and hits");
                // Twelve-second probes are shorter than Grinder's 20s cooldown, so each source has at most one activation.
                for(float dealt:grindDamage.values())assertTrue(dealt<=120.02f,"Contact damage cap includes every victim of one activation");
            }
        }
    }

    private static ArenaDefinition arena(String layout) {
        var base=ArenaDefinition.load();var boxes=new ArrayList<ArenaDefinition.BoxPart>();
        boxes.add(new ArenaDefinition.BoxPart("floor",new ArenaDefinition.Vec3(0,-.5f,0),new ArenaDefinition.Vec3(160,1,200),"concrete",true));
        if(layout.equals("narrow"))for(int sign:new int[]{-1,1})boxes.add(new ArenaDefinition.BoxPart("wall-"+sign,
                new ArenaDefinition.Vec3(sign*4,2,0),new ArenaDefinition.Vec3(1,4,180),"concrete",true));
        var nodes=new ArrayList<ArenaDefinition.NavNode>();var edges=new ArrayList<ArenaDefinition.NavEdge>();
        for(int id=0;id<9;id++) {
            nodes.add(new ArenaDefinition.NavNode(id,new ArenaDefinition.Vec3(0,0,-80+id*20),"road-ground"));
            if(id>0)edges.add(new ArenaDefinition.NavEdge("road-"+id,id-1,id,20,layout.equals("narrow")?7:24,ArenaDefinition.Transition.ROAD,"",true));
        }
        var pickups=layout.equals("pickup")?List.of(
                new ArenaDefinition.Pickup("central-repair",ArenaDefinition.PickupType.REPAIR,new ArenaDefinition.Vec3(0,0,0),600),
                new ArenaDefinition.Pickup("central-power",ArenaDefinition.PickupType.POWER_AMMO,new ArenaDefinition.Vec3(0,0,15),600)):List.<ArenaDefinition.Pickup>of();
        return new ArenaDefinition(base.schemaVersion(),"roster-"+layout,base.metadata(),new ArenaDefinition.Bounds(-80,80,-100,100,-8),
                boxes,List.of(),base.spawns(),pickups,List.of(),nodes,edges,List.of(new ArenaDefinition.Surface("road-ground","floor",0,1)),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
}
