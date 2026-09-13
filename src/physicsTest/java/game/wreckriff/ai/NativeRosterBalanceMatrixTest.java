package game.wreckriff.ai;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.NavGraph;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.SpecialRules;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.*;
import java.util.*;
import java.util.stream.Stream;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Repeatable native balance observations; a timeout is recorded, never counted as a completed fight. */
class NativeRosterBalanceMatrixTest {
    private static final List<Map<String,Object>> RESULTS=new ArrayList<>();
    private static final Path OUTPUT=Path.of("build","diagnostics","weapon-balance");
    @BeforeAll static void prepareOutput() throws IOException {RESULTS.clear();Files.createDirectories(OUTPUT);}
    @AfterAll static void writeOutput() throws IOException {
        RESULTS.sort(Comparator.comparing(row->row.get("scenario").toString()));
        Files.writeString(OUTPUT.resolve("matrix.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(RESULTS));
        StringBuilder csv=new StringBuilder("scenario,seed,profiles,layout,seconds,outcome,timeout,damage10,damage30,damage,shots,specials,captures,pickups,maxControlSeconds,controlGaps,minControlGapSeconds,ammoRemaining,hp\n");
        for(var row:RESULTS)csv.append(String.join(",",row.values().stream().map(value->"\""+value.toString().replace("\"","\"\"")+"\"").toList())).append('\n');
        Files.writeString(OUTPUT.resolve("matrix.csv"),csv);
    }
    static Stream<Arguments> scenarios() {
        var rows=new ArrayList<Arguments>();var ids=List.of("rivet","grinder","spark");
        for(long seed:List.of(42L,73L,101L))for(String layout:List.of("open","narrow","pickup")) {
            for(int first=0;first<ids.size();first++)for(int second=first;second<ids.size();second++)
                rows.add(Arguments.of(List.of(ids.get(first),ids.get(second)),layout,seed));
            rows.add(Arguments.of(ids,layout,seed));
        }
        return rows.stream();
    }

    @ParameterizedTest @MethodSource("scenarios")
    void everyPairAndFreeForAllUsesNativeContactsAndSharedControlLimits(List<String> profiles,String layout,long seed) {
        var rules=VehicleRules.load();var arena=arena(layout);
        var session=MatchSession.balanced(seed,profiles,Configs.load("combat",CombatRules.class));
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
                float damage=0,damage10=0,damage30=0;var controlRun=new int[profiles.size()];
                int freezeLimit=CombatRules.ticks(session.combatRules.control().freezeSeconds());
                int grabLimit=CombatRules.ticks(SpecialRules.GRINDER_CONTACT);
                int immunityTicks=CombatRules.ticks(session.combatRules.control().immunitySeconds());
                var controlEndedAt=new int[profiles.size()];Arrays.fill(controlEndedAt,-1);
                var freezeEpisode=new boolean[profiles.size()];
                int controlGaps=0,minimumControlGap=Integer.MAX_VALUE;
                var grindDamage=new HashMap<Integer,Float>();
                for(int tick=0;tick<180*120&&session.outcome==MatchSession.Outcome.NONE;tick++) {
                    var events=runtime.tick(runtime.bots().commands(world),false);
                    for(var event:events) {
                        if(event.type()==GameEvent.Type.SHOT)shots++;
                        if(event.type()==GameEvent.Type.SPECIAL_STARTED) {activations++;grindDamage.remove(event.sourceId());}
                        if(event.type()==GameEvent.Type.GRAB_STARTED)captures++;
                        if(event.type()==GameEvent.Type.PICKUP)pickups++;
                        if(event.type()==GameEvent.Type.DAMAGE) {
                            damage+=event.value();
                            if(tick<10*120)damage10+=event.value();
                            if(tick<30*120)damage30+=event.value();
                            if(event.kind().equals("grinder"))grindDamage.merge(event.sourceId(),event.value(),Float::sum);
                        }
                    }
                    assertTrue(world.grabCount()<=1,"Two or three participants cannot form stacked grabs");
                    for(var state:session.vehicles) {
                        assertTrue(Float.isFinite(state.hp)&&state.hp>=0&&state.hp<=state.maximumHp);
                        assertFalse(state.frozenTicks>0&&state.grabbedBy>=0,"Freeze and hold never overlap");
                        if(state.controlled()) {
                            if(controlRun[state.id]==0) {
                                if(controlEndedAt[state.id]>=0) {
                                    int gap=tick-controlEndedAt[state.id];
                                    assertTrue(gap>=immunityTicks,"Control episodes for vehicle "+state.id
                                            +" must be separated by at least "+immunityTicks+" ticks; actual gap="+gap);
                                    controlGaps++;minimumControlGap=Math.min(minimumControlGap,gap);
                                }
                                freezeEpisode[state.id]=state.frozenTicks>0;
                            } else assertEquals(freezeEpisode[state.id],state.frozenTicks>0,
                                    "Freeze and hold cannot replace each other without an immunity gap");
                            controlRun[state.id]++;
                            int limit=freezeEpisode[state.id]?freezeLimit:grabLimit;
                            assertTrue(controlRun[state.id]<=limit,"The "+(freezeEpisode[state.id]?"Freeze":"Grinder hold")
                                    +" episode exceeded its configured duration of "+limit+" ticks");
                        } else {
                            if(controlRun[state.id]>0)controlEndedAt[state.id]=tick;
                            controlRun[state.id]=0;
                        }
                        maxControl=Math.max(maxControl,controlRun[state.id]);
                        if(world.containsVehicle(state.id))assertTrue(Vector3f.isValidVector(world.position(state.id))&&Vector3f.isValidVector(world.velocity(state.id)));
                    }
                }
                var row=new LinkedHashMap<String,Object>();
                row.put("scenario",String.join("-",profiles)+"/"+layout+"/"+seed);row.put("seed",seed);row.put("profiles",profiles);row.put("layout",layout);
                row.put("seconds",session.seconds());row.put("outcome",session.outcome.name());row.put("timeout",session.outcome==MatchSession.Outcome.NONE);
                row.put("damage10",damage10);row.put("damage30",damage30);row.put("damage",damage);row.put("shots",shots);row.put("specials",activations);
                row.put("captures",captures);row.put("pickups",pickups);row.put("maxControlSeconds",maxControl/120f);
                row.put("controlGaps",controlGaps);row.put("minControlGapSeconds",controlGaps==0?"unobserved":minimumControlGap*MatchSession.DT);
                row.put("ammoRemaining",session.vehicles.stream().map(v->v.weapons().stream().map(w->w.ammo).toList()).toList());
                row.put("hp",session.vehicles.stream().map(v->v.hp).toList());RESULTS.add(row);
                System.out.printf(Locale.ROOT,"ROSTER_PROBE profiles=%s layout=%s seconds=%.2f shots=%d specials=%d captures=%d damage=%.2f maxControlSeconds=%.3f pickups=%d hp=%s%n",
                        profiles,layout,session.seconds(),shots,activations,captures,damage,maxControl/120f,pickups,session.vehicles.stream().map(v->v.hp).toList());
                assertTrue(shots>0&&damage>0,"A balance probe must include actual attacks and hits");
                // Reset on each activation; the cap applies to the full current hold, including every victim.
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
