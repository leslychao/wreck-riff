package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real collision corridors and boss routes, independent of authored landmark coordinates. */
class CampaignNavClearanceTest {
    @Test void roadCentrelinesAndBossWidthClearAllStaticSolids() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var nodes=new HashMap<Integer,Vector3f>();arena.nodes().forEach(n->nodes.put(n.id(),n.position().vector()));
            boolean covered=false,open=false;
            for(var edge:arena.edges()) {
                if(edge.type()==ArenaDefinition.Transition.LAUNCH||edge.type()==ArenaDefinition.Transition.OPENABLE)continue;
                Vector3f a=nodes.get(edge.from()),b=nodes.get(edge.to());int count=(int)Math.ceil(a.distance(b)/2);
                covered|=edge.clearance()<40;open|=edge.clearance()>=40;
                assertTrue(edge.clearance()>=5,id+" "+edge.id());
                for(int i=0;i<=count;i++) {
                    Vector3f p=a.clone().interpolateLocal(b,i/(float)Math.max(1,count));
                    assertTrue(arena.surfaceAt(p,0,.06f).isPresent(),id+" unsupported "+edge.id()+" "+p);
                    for(var box:arena.boxes())if(box.collision()&&box.containsXZ(p.x,p.z,-2.3f)) {
                        float top=box.center().y()+box.size().y()/2,bottom=box.center().y()-box.size().y()/2;
                        if(top<=p.y+.65f)continue;
                        assertTrue(bottom-p.y>=5,id+" blocked "+edge.id()+" / "+box.id()+" at "+p);
                    }
                }
            }
            assertTrue(covered&&open,id+" requires both covered and open routes");
        }
    }
    @Test void bossesCanReachEveryDistrictAndRaisedRoadWithoutLaunches() {
        var registry=ArenaRegistry.load();var rules=VehicleRules.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var boss=arena.bosses().getFirst();var profile=VehicleProfile.boss(boss.profileId(),rules);var size=profile.fullBounds();var graph=new NavGraph(arena);
            int start=graph.nearest(boss.entrances().getFirst().position().vector());
            var mobility=new NavGraph.Mobility(size.maxX()-size.minX(),profile.roadOffset()+size.maxY(),14,false,true,Set.of());
            for(var district:arena.districts()) {
                int goal=district.patrolNodeIds().getFirst();assertTrue(graph.route(start,goal,mobility,Set.of(),2,10).found(),id+" "+district.id());
            }
            var raised=arena.nodes().stream().filter(n->n.position().y()>2).findFirst().orElseThrow();
            var route=graph.route(start,raised.id(),mobility,Set.of(),2,10);assertTrue(route.found(),id);
            assertTrue(route.traversals().stream().anyMatch(t->t.type()==ArenaDefinition.Transition.RAMP),id);
            assertTrue(route.traversals().stream().noneMatch(t->t.type()==ArenaDefinition.Transition.LAUNCH),id);
        }
    }
    @Test void temporarilyClosedAndDestructibleShortcutsNeverDisconnectTheMap() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var graph=new NavGraph(arena);
            for(var barrier:arena.barriers())graph.setOpen(barrier.id(),false);
            assertEquals(arena.nodes().size(),graph.reachable(arena.nodes().getFirst().id()).size(),id);
        }
    }
    @Test void districtCentresAndEachDriveThroughInteriorHaveTwoEdgeDisjointApproaches() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var graph=new NavGraph(arena);
            // Undirected pairs collapse duplicate authored links on the SAME physical corridor.
            Map<Integer,Set<Integer>> adjacency=new HashMap<>();for(var node:arena.nodes())adjacency.put(node.id(),new HashSet<>());
            Set<Long> roads=new HashSet<>();
            for(var edge:arena.edges())if(edge.bidirectional()&&(edge.type()==ArenaDefinition.Transition.ROAD||edge.type()==ArenaDefinition.Transition.RAMP)) {
                adjacency.get(edge.from()).add(edge.to());adjacency.get(edge.to()).add(edge.from());roads.add(pair(edge.from(),edge.to()));
            }
            List<Integer> districtCentres=arena.districts().stream().map(d->graph.nearest(d.center().vector())).toList();
            for(int i=0;i<districtCentres.size();i++)for(int j=i+1;j<districtCentres.size();j++)
                assertTwoRoutes(adjacency,roads,districtCentres.get(i),districtCentres.get(j),id+" districts "+i+" / "+j);
            List<String> halls=switch(id) {
                case "construction_17" -> List.of("unfinished-apartments","concrete-plant","warehouse");
                case "neon_zero" -> List.of("shopping-passage","technical-complex","parking-ground-floor");
                case "euphoria_park" -> List.of("circus","ride-pavilion","repair-depot");
                default -> throw new AssertionError(id);
            };
            for(String hall:halls) {
                var roof=arena.boxes().stream().filter(b->b.id().equals(hall+"-roof")).findFirst().orElseThrow();
                int inside=graph.nearest(new Vector3f(roof.center().x(),0,roof.center().z()));
                Vector3f point=graph.position(inside);assertTrue(roof.containsXZ(point.x,point.z,8),hall+" needs an actual interior route node");
                assertTrue(Math.abs(point.y)<.01f,hall+" must be reached inside, not via its roof");
                for(int target:districtCentres)if(target!=inside)assertTwoRoutes(adjacency,roads,inside,target,id+" "+hall);
            }
        }
    }
    private static void assertTwoRoutes(Map<Integer,Set<Integer>> graph,Set<Long> edges,int from,int to,String label) {
        assertTrue(reachableWithout(graph,from,to,-1),label+" disconnected");
        // Menger: if removing ANY single physical edge leaves the pair connected, two edge-disjoint paths exist.
        for(long blocked:edges)assertTrue(reachableWithout(graph,from,to,blocked),label+" has a single required corridor "+blocked);
    }
    private static boolean reachableWithout(Map<Integer,Set<Integer>> graph,int from,int to,long blocked) {
        Set<Integer> seen=new HashSet<>();ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(from);
        while(!queue.isEmpty()) {
            int current=queue.remove();if(current==to)return true;if(!seen.add(current))continue;
            for(int next:graph.get(current))if(pair(current,next)!=blocked&&!seen.contains(next))queue.add(next);
        }
        return false;
    }
    private static long pair(int a,int b) {return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
}
