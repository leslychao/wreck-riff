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

/** Measured isolated AI drives, not a claim about first contact with moving combatants. */
class NativeCampaignTravelAuditTest {
    private static final Path REPORT=Path.of("build","reports","campaign-travel-audit.json");
    private static final int LIMIT_TICKS=90*MatchSession.TICKS_PER_SECOND;
    private record Scenario(String kind,String targetId,Vector3f start,Vector3f goal,Vector3f forward,float routeMeters) {}

    @Test void measureInitialEnemyRoutesAndOnePrimaryNeighbourLinkForAllThreeChassis() throws Exception {
        List<Map<String,Object>> results=new ArrayList<>();List<String> failures=new ArrayList<>();
        for(String arenaId:NativeCampaignBotNavigationTest.REGISTRY.campaignIds()) {
            var arena=NativeCampaignBotNavigationTest.REGISTRY.definition(arenaId);
            for(int participant=1;participant<=3;participant++) {
                String profileId=switch(participant){case 1->"rivet";case 2->"grinder";default->"spark";};
                var profile=VehicleProfile.player(profileId,NativeCampaignBotNavigationTest.RULES);
                for(var scenario:scenarios(arena,profile)) {
                    var result=measure(arena,participant,scenario);results.add(result);
                    if(!Boolean.TRUE.equals(result.get("arrived")))failures.add(arenaId+" / "+profileId+" / "+scenario.kind+": "+result.get("failure"));
                    else if(!Boolean.TRUE.equals(result.get("withinRequestedTravelWindow")))
                        failures.add(arenaId+" / "+profileId+" / "+scenario.kind+": "+result.get("seconds")+"s outside "+result.get("targetSeconds"));
                }
            }
        }
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);
        report.put("method","Real Minie PhysicsWorld, production BotController and VehicleController at 120 Hz. One live ordinary chassis seeks a single repair socket. No moving enemies, damage exchange or player input; these are isolated AI travel measurements, not first combat contact. The initial 3-second suspension settle is excluded. Arrival requires actual position within 8 m, correct road height and four supported wheels. Recovery/teleport is a failure, never arrival.");
        report.put("initialConditions","Each initial-enemy run starts at authored spawn 0 with its authored yaw, at rest. The destination is the nearest other authored initial spawn by traversable ground route for that chassis. Each neighbouring-district run starts at the district centre's nearest real road node, facing the first route segment. Existing hazards, ramps, launch owners, AI limits and vehicle tuning remain active; other pickups are replaced only in this fixture to make the driving destination unambiguous.");
        report.put("timeLimitSeconds",90);report.put("runs",results);
        Files.createDirectories(REPORT.getParent());Files.writeString(REPORT,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n",StandardCharsets.UTF_8);
        assertEquals(18,results.size());assertTrue(failures.isEmpty(),String.join("\n",failures));
    }

    private static List<Scenario> scenarios(ArenaDefinition arena,VehicleProfile profile) {
        var graph=new NavGraph(arena);var ai=AiRules.load();
        var mobility=new NavGraph.Mobility(profile.width(),profile.roadOffset()+profile.fullBounds().maxY(),ai.cruiseSpeed(),false,false,Set.of());
        var spawn=arena.spawns().getFirst();Vector3f start=spawn.position().vector();
        ArenaDefinition.Spawn nearest=null;float nearestLength=Float.POSITIVE_INFINITY;
        for(var enemy:arena.spawns().subList(1,arena.spawns().size())) {
            float length=distance(graph,start,enemy.position().vector(),mobility);
            if(length<nearestLength){nearest=enemy;nearestLength=length;}
        }
        assertNotNull(nearest);assertTrue(Float.isFinite(nearestLength));
        Vector3f forward=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y).mult(Vector3f.UNIT_Z);
        var first=new Scenario("initial-enemy-spawn","spawn-"+nearest.id(),start,nearest.position().vector(),forward,nearestLength);
        String[] pair=switch(arena.id()) {
            case "construction_17" -> new String[]{"plant","warehouses"};
            case "neon_zero" -> new String[]{"market","homes"};
            case "euphoria_park" -> new String[]{"rides","backstage"};
            default -> throw new AssertionError(arena.id());
        };
        Vector3f from=roadCentre(arena,graph,pair[0]),to=roadCentre(arena,graph,pair[1]);
        var route=graph.route(graph.nearest(from),graph.nearest(to),mobility,Set.of(),0,0);assertTrue(route.found());
        Vector3f direction=route.nodes().stream().map(graph::position).filter(p->p.distance(from)>2).findFirst().orElseThrow().subtract(from).setY(0).normalizeLocal();
        var second=new Scenario("neighbouring-districts",pair[0]+" -> "+pair[1],from,to,direction,distance(graph,from,to,mobility));
        return List.of(first,second);
    }

    private static Vector3f roadCentre(ArenaDefinition arena,NavGraph graph,String districtId) {
        var district=arena.districts().stream().filter(d->d.id().equals(districtId)).findFirst().orElseThrow();
        return graph.position(graph.nearest(district.center().vector()));
    }
    private static float distance(NavGraph graph,Vector3f start,Vector3f goal,NavGraph.Mobility mobility) {
        int from=graph.nearest(start),to=graph.nearest(goal);var route=graph.route(from,to,mobility,Set.of(),0,0);
        return route.found()?graph.pathLength(route.nodes())+start.distance(graph.position(from))+goal.distance(graph.position(to)):Float.POSITIVE_INFINITY;
    }

    private static Map<String,Object> measure(ArenaDefinition arena,int participant,Scenario scenario) {
        var goal=scenario.goal;var socket=new ArenaDefinition.Pickup("travel-audit-goal",ArenaDefinition.PickupType.REPAIR,
                new ArenaDefinition.Vec3(goal.x,goal.y,goal.z),3600);
        var result=new LinkedHashMap<String,Object>();result.put("arenaId",arena.id());result.put("layoutRevision",arena.layoutRevision());
        result.put("scenario",scenario.kind);result.put("target",scenario.targetId);result.put("start",point(scenario.start));result.put("goal",point(goal));
        result.put("shortestGroundRouteMeters",round(scenario.routeMeters));
        try(var rig=new NativeCampaignBotNavigationTest.Rig(arena.withPickups(List.of(socket)),participant,false,scenario.start,scenario.forward)) {
            result.put("chassis",rig.world.profile(rig.id).id());result.put("settledStart",point(rig.world.position(rig.id)));
            double distance=0;int ticks=0;boolean arrived=false;String failure="time limit exceeded";
            Vector3f previous=rig.world.position(rig.id).clone();List<Map<String,Object>> trace=new ArrayList<>();
            for(;ticks<LIMIT_TICKS&&!arrived;ticks++) {
                try {rig.tick();}catch(AssertionError error){failure=error.getMessage();break;}
                Vector3f position=rig.world.position(rig.id);distance+=previous.distance(position);previous.set(position);
                if(ticks%120==0)trace.add(Map.of("seconds",round((ticks+1)/120.0),"position",point(position),
                        "speedMetersPerSecond",round(rig.world.velocity(rig.id).length()),"state",rig.bots.metrics(rig.id).state().name(),
                        "navigation",rig.bots.navigation(rig.id).nodes(),"forward",point(rig.world.forward(rig.id)),
                        "command",rig.command.toString(),"transition",String.valueOf(rig.bots.navigation(rig.id).transition())));
                arrived=position.subtract(goal).setY(0).length()<8&&rig.world.supportedWheelContacts(rig.id)==4
                        &&Math.abs(position.y-rig.world.profile(rig.id).roadOffset()-goal.y)<.6f;
                if(rig.world.teleportGeneration(rig.id)!=0){arrived=false;failure="teleport generation changed";break;}
            }
            var metrics=rig.bots.metrics(rig.id);double seconds=ticks/120.0;
            result.put("arrived",arrived);result.put("seconds",round(seconds));result.put("travelledMeters",round(distance));
            result.put("meanMetersPerSecond",round(distance/Math.max(seconds,1.0/120)));result.put("finalPosition",point(rig.world.position(rig.id)));
            result.put("reverseAttempts",metrics.reverseAttempts());result.put("recoveries",metrics.recoveries());
            result.put("maximumUnplannedStationarySeconds",round(metrics.maximumUnplannedStationaryTicks()/120.0));
            result.put("teleportGeneration",rig.world.teleportGeneration(rig.id));result.put("traceEverySecond",trace);
            int minimum=scenario.kind.equals("initial-enemy-spawn")?15:10,maximum=scenario.kind.equals("initial-enemy-spawn")?30:25;
            result.put("targetSeconds",List.of(minimum,maximum));result.put("withinRequestedTravelWindow",arrived&&seconds>=minimum&&seconds<=maximum);
            if(!arrived)result.put("failure",failure);
            System.out.println("TRAVEL "+arena.id()+" "+result.get("chassis")+" "+scenario.kind+" "+round(seconds)+"s "+round(distance)+"m arrived="+arrived);
        }
        return result;
    }
    private static ArenaDefinition.Vec3 point(Vector3f point){return new ArenaDefinition.Vec3(point.x,point.y,point.z);}
    private static double round(double value){return Math.round(value*1000)/1000.0;}
}
