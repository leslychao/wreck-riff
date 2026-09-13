package game.wreckriff.ai;

import game.wreckriff.arena.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistrictPatrolTest {
    @Test void patrolEventuallyVisitsEveryDistrictThroughAvailableRoadsForEveryStartingParticipant() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var graph=new NavGraph(arena);
            for(int participant=0;participant<arena.spawns().size();participant++) {
                var cursor=new DistrictPatrol.Cursor();var position=arena.spawns().get(participant).position().vector();
                Set<String> seen=new HashSet<>();
                for(int stop=0;stop<60;stop++) {
                    int goal=DistrictPatrol.next(arena,graph,position,cursor,participant);
                    var route=graph.path(graph.nearest(position),goal,false,2,10);
                    assertFalse(route.isEmpty(),id+" unreachable patrol target "+goal);
                    arena.districts().stream().filter(d->d.patrolNodeIds().contains(goal)).forEach(d->seen.add(d.id()));
                    position=graph.position(goal);
                }
                assertEquals(arena.districts().size(),seen.size(),id+" participant "+participant+" starved a district");
            }
        }
    }
    @Test void classicPatrolMovesToAnAdjacentRoadNode() {
        var arena=ArenaDefinition.load();var graph=new NavGraph(arena);var cursor=new DistrictPatrol.Cursor();
        var position=arena.spawns().getFirst().position().vector();int start=graph.nearest(position);
        int next=DistrictPatrol.next(arena,graph,position,cursor,2);
        assertTrue(graph.links(start).stream().anyMatch(link->link.to()==next));
    }
    @Test void classicPatrolEscapesLocalPingPongAndVisitsEveryRoadFromEverySpawn() {
        var arena=ArenaDefinition.load();var graph=new NavGraph(arena);
        for(var spawn:arena.spawns()) {
            var cursor=new DistrictPatrol.Cursor();var position=spawn.position().vector();
            Set<Integer> seen=new HashSet<>();int limit=graph.nodes().size()*graph.nodes().size()*2;
            for(int step=0;step<limit&&seen.size()<graph.nodes().size();step++) {
                int current=graph.nearest(position);seen.add(current);
                int next=DistrictPatrol.next(arena,graph,position,cursor,spawn.id());
                assertTrue(graph.links(current).stream().anyMatch(link->link.to()==next),"Every patrol leg follows a real adjacent road");
                position=graph.position(next);
            }
            assertEquals(graph.nodes().size(),seen.size(),"Classic patrol must reach both road levels and all supply districts from spawn "+spawn.id());
        }
    }
}
