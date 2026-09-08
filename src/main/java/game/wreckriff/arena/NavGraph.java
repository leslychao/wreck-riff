package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import java.util.*;

/** Small authored 3D graph. A* state includes the incoming edge for turn cost. */
public final class NavGraph {
    public record Link(int to, float width) {}
    private record Step(int previous, int current) {}
    private record QueueEntry(Step step, float priority) {}
    private final Map<Integer,ArenaDefinition.NavNode> nodes=new LinkedHashMap<>();
    private final Map<Integer,List<Link>> links=new HashMap<>();
    private final ArenaDefinition.Hazard hazard;

    public NavGraph(ArenaDefinition definition) {
        hazard=definition.hazard();
        for (var node:definition.nodes()) { nodes.put(node.id(),node); links.put(node.id(),new ArrayList<>()); }
        for (var edge:definition.edges()) {
            links.get(edge.from()).add(new Link(edge.to(),edge.width()));
            links.get(edge.to()).add(new Link(edge.from(),edge.width()));
        }
        if (reachable(nodes.keySet().iterator().next()).size()!=nodes.size())
            throw new IllegalArgumentException("Navigation graph is disconnected");
    }
    public Collection<ArenaDefinition.NavNode> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public Vector3f position(int id) {
        var node=nodes.get(id);
        if (node==null) throw new IllegalArgumentException("Unknown nav node " + id);
        return node.position().vector();
    }
    public List<Link> links(int id) { return List.copyOf(links.get(id)); }
    public int nearest(Vector3f point) {
        return nodes.values().stream().min(Comparator
                .comparingDouble((ArenaDefinition.NavNode n)->surfaceDistance(point,n.position().vector()))
                .thenComparingInt(ArenaDefinition.NavNode::id)).orElseThrow().id();
    }
    private static float surfaceDistance(Vector3f a, Vector3f b) {
        // A node immediately above/below is not a shortcut between floors.
        float dy=Math.abs(a.y-b.y);
        return a.distanceSquared(b)+(dy>2.2f ? 2500+dy*dy*100 : 0);
    }
    public List<Integer> path(int start,int goal,boolean hazardActive,float turnPenalty,float hazardPenalty) {
        if (!nodes.containsKey(start) || !nodes.containsKey(goal)) throw new IllegalArgumentException("Unknown path endpoint");
        Step first=new Step(-1,start);
        Map<Step,Float> cost=new HashMap<>();
        Map<Step,Step> previous=new HashMap<>();
        PriorityQueue<QueueEntry> open=new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::priority)
                .thenComparingInt(e->e.step.current).thenComparingInt(e->e.step.previous));
        cost.put(first,0f); open.add(new QueueEntry(first,position(start).distance(position(goal))));
        while (!open.isEmpty()) {
            Step current=open.remove().step;
            if (current.current==goal) {
                LinkedList<Integer> result=new LinkedList<>();
                for (Step item=current;item!=null;item=previous.get(item)) result.addFirst(item.current);
                return List.copyOf(result);
            }
            for (Link link:links.get(current.current)) {
                if (link.to==current.previous) continue;
                Vector3f a=position(current.current), b=position(link.to);
                float stepCost=a.distance(b);
                if (current.previous>=0) {
                    Vector3f incoming=a.subtract(position(current.previous)).normalizeLocal();
                    Vector3f outgoing=b.subtract(a).normalizeLocal();
                    stepCost+=turnPenalty*(1-Math.clamp(incoming.dot(outgoing),-1,1));
                }
                if (hazardActive && crossesHazard(a,b)) stepCost+=hazardPenalty;
                Step next=new Step(current.current,link.to);
                float proposed=cost.get(current)+stepCost;
                if (proposed<cost.getOrDefault(next,Float.POSITIVE_INFINITY)) {
                    cost.put(next,proposed); previous.put(next,current);
                    open.add(new QueueEntry(next,proposed+b.distance(position(goal))));
                }
            }
        }
        return List.of();
    }
    public float pathLength(List<Integer> path) {
        float total=0;
        for (int i=1;i<path.size();i++) total+=position(path.get(i-1)).distance(position(path.get(i)));
        return total;
    }
    public boolean crossesHazard(Vector3f a,Vector3f b) {
        if (Math.min(a.y,b.y)>hazard.maxY()) return false;
        float dx=b.x-a.x,dz=b.z-a.z;
        float lo=0,hi=1;
        float[] p={-dx,dx,-dz,dz};
        float[] q={a.x-hazard.minX(),hazard.maxX()-a.x,a.z-hazard.minZ(),hazard.maxZ()-a.z};
        for (int i=0;i<4;i++) {
            if (Math.abs(p[i])<1e-6f) { if (q[i]<0) return false; }
            else if (p[i]<0) lo=Math.max(lo,q[i]/p[i]);
            else hi=Math.min(hi,q[i]/p[i]);
        }
        return lo<=hi;
    }
    public Set<Integer> reachable(int from) {
        Set<Integer> visited=new HashSet<>(); ArrayDeque<Integer> todo=new ArrayDeque<>(); todo.add(from);
        while (!todo.isEmpty()) {
            int node=todo.removeFirst();
            if (visited.add(node)) for (Link link:links.get(node)) todo.addLast(link.to);
        }
        return visited;
    }
}
