package game.wreckriff.simulation;

import com.google.gson.GsonBuilder;
import com.jme3.math.*;
import game.wreckriff.ai.AiRules;
import game.wreckriff.arena.*;
import game.wreckriff.config.VehicleProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual first-ammo collection times from each authored spawn, with the shared native driver. */
class NativeFirstSupplyTravelTest {
    private static final Set<ArenaDefinition.PickupType> OFFENSIVE=Set.of(ArenaDefinition.PickupType.HOMING_AMMO,
            ArenaDefinition.PickupType.POWER_AMMO,ArenaDefinition.PickupType.CANNON_AMMO);
    private record Candidate(ArenaDefinition.Pickup pickup,float metres) {}

    @Test void everySpawnAndChassisCanActuallyCollectTwoSeparateLocalOffensivePickupsInTheRequestedTime() throws Exception {
        var results=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();int largeStarts=0;
        for(String arenaId:List.of("dead-air-yard","construction_17","neon_zero","euphoria_park")) {
            var arena=NativeCampaignBotNavigationTest.REGISTRY.definition(arenaId);
            boolean classic=arena.bosses().isEmpty();if(!classic)largeStarts+=arena.spawns().size();
            for(var spawn:arena.spawns())for(int participant=1;participant<=3;participant++) {
                String profileId=switch(participant){case 1->"rivet";case 2->"grinder";default->"spark";};
                var profile=VehicleProfile.player(profileId,NativeCampaignBotNavigationTest.RULES);
                var candidates=candidates(arena,spawn,profile);
                if(candidates.size()<2){failures.add(arenaId+" spawn="+spawn.id()+" "+profileId+": fewer than two traversable offensive locations");continue;}
                assertNotEquals(candidates.get(0).pickup().id(),candidates.get(1).pickup().id());
                var measured=new ArrayList<Map<String,Object>>();
                for(int choice=0;choice<2;choice++) {
                    measured.add(measure(arena,spawn,participant,candidates.get(choice),choice));
                }
                measured.sort(Comparator.comparingDouble(row->Boolean.TRUE.equals(row.get("collected"))?((Number)row.get("seconds")).doubleValue():Double.POSITIVE_INFINITY));
                for(int arrivalRank=0;arrivalRank<measured.size();arrivalRank++) {
                    var result=measured.get(arrivalRank);double seconds=((Number)result.get("seconds")).doubleValue();
                    double minimum=arrivalRank==0?(classic?1:3):0,maximum=arrivalRank==0?(classic?4:8):12;
                    result.put("arrivalRank",arrivalRank+1);result.put("targetSeconds",List.of(minimum,maximum));
                    result.put("withinRequestedTime",Boolean.TRUE.equals(result.get("collected"))&&seconds>=minimum&&seconds<=maximum);results.add(result);
                    if(!Boolean.TRUE.equals(result.get("withinRequestedTime")))failures.add(arenaId+" spawn="+spawn.id()+" "+profileId
                            +" arrival="+(arrivalRank+1)+" candidate="+result.get("choice")+": "+result.get("seconds")+"s, "+result.getOrDefault("failure","outside requested window"));
                }
            }
        }
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);
        report.put("method","Native Minie PhysicsWorld, ordinary BotController and VehicleController at 120 Hz. Every authored start and yaw, at rest after suspension settling, is measured independently for all three chassis. Two distinct Homing/Power/Cannon locations are ranked by traversable ground route, including endpoint connectors. Each run exposes just that real authored pickup to isolate its availability; positions, grant amounts, collection rules, vehicle tuning, hazards and geometry remain unchanged. Full HP and zero ammo match a fresh start. Arrival requires the actual PICKUP event and an increased ammo slot, never proximity alone. Setup settling is excluded; protection and collection delays during the run are included. No teleport or recovery is accepted.");
        report.put("limitation","The isolated drives establish physical travel and collection time for each alternative. Simultaneous contention, independent road branches and player driving quality require separate gameplay review.");
        report.put("largeMapStarts",largeStarts);report.put("runs",results);
        report.put("timeRanking","The first accessible pickup is the faster of the two actually measured collections, including authored spawn yaw and turn time. The other distinct candidate is the second alternative; route distance is only used to select candidates, never to assign first-arrival timing.");
        Path reportPath=Path.of("build","reports","first-supply-travel.json");Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n",StandardCharsets.UTF_8);
        assertEquals(33,largeStarts,"Every player and initial-rival spawn on all three large maps must be measured");
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }

    private static List<Candidate> candidates(ArenaDefinition arena,ArenaDefinition.Spawn spawn,VehicleProfile profile) {
        var graph=new NavGraph(arena);var ai=AiRules.load();var start=spawn.position().vector();int origin=graph.nearest(start);
        var mobility=new NavGraph.Mobility(profile.width(),profile.roadOffset()+profile.fullBounds().maxY(),ai.cruiseSpeed(),false,false,Set.of());
        var goals=arena.pickups().stream().filter(p->OFFENSIVE.contains(p.type())).toList();
        var nodes=new HashMap<String,Integer>();for(var pickup:goals)nodes.put(pickup.id(),graph.nearest(pickup.position().vector()));
        var routes=graph.routes(origin,new HashSet<>(nodes.values()),mobility,Set.of(),0,0);var candidates=new ArrayList<Candidate>();
        for(var pickup:goals) {
            int node=nodes.get(pickup.id());var route=routes.get(node);if(route==null||!route.found())continue;
            var point=pickup.position().vector();if(Math.abs(graph.position(node).y-point.y)>2.2f)continue;
            candidates.add(new Candidate(pickup,graph.pathLength(route.nodes())+start.distance(graph.position(origin))+point.distance(graph.position(node))));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::metres).thenComparing(c->c.pickup().id()));
        return candidates.stream().limit(2).toList();
    }

    private static Map<String,Object> measure(ArenaDefinition arena,ArenaDefinition.Spawn spawn,int participant,Candidate candidate,int choice) {
        var result=new LinkedHashMap<String,Object>();result.put("arenaId",arena.id());result.put("layoutRevision",arena.layoutRevision());
        result.put("spawn",spawn.id());result.put("choice",choice+1);result.put("pickupId",candidate.pickup().id());
        result.put("pickupType",candidate.pickup().type().name());result.put("routeMetres",round(candidate.metres()));
        var forward=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y).mult(Vector3f.UNIT_Z);
        try(var rig=new NativeCampaignBotNavigationTest.Rig(arena.withPickups(List.of(candidate.pickup())),participant,false,spawn.position().vector(),forward)) {
            var state=rig.session.vehicle(rig.id);state.hp=state.maximumHp;
            assertTrue(rig.session.vehicles.stream().allMatch(v->v.weapons().stream().allMatch(w->w.ammo==0)),"The first frame must be genuinely empty");
            result.put("chassis",state.profileId);result.put("settledStart",point(rig.world.position(rig.id)));
            int ticks=0;boolean collected=false;String failure="no physical collection within 12 seconds";
            var trace=new ArrayList<Map<String,Object>>();
            for(;ticks<12*MatchSession.TICKS_PER_SECOND&&!collected;ticks++) {
                try{rig.tick();}catch(AssertionError error){failure=error.getMessage();break;}
                collected=rig.events.stream().anyMatch(e->e.type()==GameEvent.Type.PICKUP&&e.subjectId()==rig.id)
                        &&state.weapons().stream().anyMatch(w->w.ammo>0);
                if(ticks%120==0)trace.add(Map.of("seconds",round((ticks+1)/120.0),"position",point(rig.world.position(rig.id)),
                        "speed",round(rig.world.velocity(rig.id).length()),"state",rig.bots.state(rig.id).name(),"command",rig.command.toString(),"route",rig.bots.route(rig.id)));
                if(rig.world.teleportGeneration(rig.id)!=0){collected=false;failure="teleport generation changed";break;}
            }
            double seconds=ticks/120.0;
            result.put("collected",collected);result.put("seconds",round(seconds));
            result.put("finalPosition",point(rig.world.position(rig.id)));result.put("teleportGeneration",rig.world.teleportGeneration(rig.id));
            result.put("traceEverySecond",trace);if(!collected)result.put("failure",failure);
            System.out.println("FIRST_SUPPLY "+arena.id()+" spawn="+spawn.id()+" "+state.profileId+" choice="+(choice+1)+" "+round(seconds)+"s collected="+collected);
        }
        return result;
    }
    private static ArenaDefinition.Vec3 point(Vector3f point){return new ArenaDefinition.Vec3(point.x,point.y,point.z);}
    private static double round(double value){return Math.round(value*1000)/1000.0;}
}
