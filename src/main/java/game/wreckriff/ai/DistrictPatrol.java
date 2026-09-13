package game.wreckriff.ai;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import java.util.*;

/** Authored patrol stops, visited in local circuits before moving to a neighbouring district. */
public final class DistrictPatrol {
    public static final class Cursor {
        private String district;
        private int stop,visits;
        private final Map<String,Integer> visited=new HashMap<>();
        private final Map<Integer,Integer> nodeVisits=new HashMap<>();
    }
    private DistrictPatrol() {}
    public static int next(ArenaDefinition arena,NavGraph graph,Vector3f position,Cursor cursor,int participant) {
        if(arena.districts().isEmpty()) {
            int current=graph.nearest(position);var exits=graph.links(current);
            cursor.nodeVisits.merge(current,1,Integer::sum);
            // A single alternating counter can bounce between the same two junctions forever.
            // Local visit counts send the classic patrol through unexplored connected roads too.
            return exits.stream().min(Comparator.comparingInt((NavGraph.Link link)->cursor.nodeVisits.getOrDefault(link.to(),0))
                    .thenComparingInt(link->Math.floorMod(link.to()-participant,graph.nodes().size())))
                    .map(NavGraph.Link::to).orElse(current);
        }
        var districts=arena.districts();
        var district=districts.stream().filter(d->d.id().equals(cursor.district)).findFirst().orElse(null);
        if(district==null) {
            district=districts.stream().min(Comparator.comparingDouble(d->d.center().vector().distanceSquared(position))).orElseThrow();
            cursor.district=district.id();cursor.stop=Math.floorMod(participant,district.patrolNodeIds().size());
        } else if(cursor.visits>=Math.min(3,district.patrolNodeIds().size())) {
            cursor.visited.merge(district.id(),1,Integer::sum);
            String previous=district.id();
            district=districts.stream().filter(d->!d.id().equals(previous))
                    .min(Comparator.comparingDouble((ArenaDefinition.District d)->
                            d.center().vector().distance(position)+cursor.visited.getOrDefault(d.id(),0)*450f)
                            .thenComparing(ArenaDefinition.District::id)).orElse(district);
            cursor.district=district.id();cursor.visits=0;
            int nearest=0;float distance=Float.POSITIVE_INFINITY;
            for(int i=0;i<district.patrolNodeIds().size();i++) {
                float candidate=graph.position(district.patrolNodeIds().get(i)).distanceSquared(position);
                if(candidate<distance){distance=candidate;nearest=i;}
            }
            cursor.stop=nearest;
        }
        int goal=district.patrolNodeIds().get(Math.floorMod(cursor.stop++,district.patrolNodeIds().size()));
        cursor.visits++;return goal;
    }
}
