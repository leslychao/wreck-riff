package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import java.util.*;

/** Directed authored graph. One A* implementation serves ordinary roads and vertical transitions. */
public final class NavGraph {
    public record Link(String id,int to,float width,float clearance,ArenaDefinition.Transition type,String objectId) {}
    public record Traversal(int from,int to,String edgeId,ArenaDefinition.Transition type,String objectId) {}
    public record Mobility(float width,float height,float speed,boolean launch,boolean drop,Set<String> launchPadIds) {
        public Mobility {
            if(!Float.isFinite(width)||!Float.isFinite(height)||!Float.isFinite(speed)||width<=0||height<=0||speed<=0)
                throw new IllegalArgumentException("Invalid navigation mobility");
            launchPadIds=Set.copyOf(launchPadIds);
        }
        public static Mobility car() { return new Mobility(2.1f,1.25f,1,true,true,Set.of()); }
    }
    public record Route(List<Integer> nodes,List<Traversal> traversals,float cost,long revision) {
        public Route { nodes=List.copyOf(nodes);traversals=List.copyOf(traversals); }
        public boolean found() { return !nodes.isEmpty(); }
    }
    private record Step(int previous,int current) {}
    private record QueueEntry(Step step,float cost,float priority) {}
    private final Map<Integer,ArenaDefinition.NavNode> nodes=new LinkedHashMap<>();
    private final Map<Integer,List<Link>> links=new LinkedHashMap<>();
    private final Map<String,ArenaDefinition.LaunchPad> pads=new HashMap<>();
    private final List<ArenaDefinition.Hazard> hazards;
    private final Set<String> openObjects=new HashSet<>();
    private final Set<String> openableIds=new HashSet<>();
    private long revision;

    public NavGraph(ArenaDefinition definition) {
        hazards=definition.hazards();
        for(var node:definition.nodes()) { nodes.put(node.id(),node);links.put(node.id(),new ArrayList<>()); }
        for(var pad:definition.launchPads())pads.put(pad.id(),pad);
        for(var barrier:definition.barriers())openObjects.add(barrier.id());
        for(var edge:definition.edges()) {
            links.get(edge.from()).add(link(edge,edge.to()));
            if(edge.bidirectional())links.get(edge.to()).add(link(edge,edge.from()));
            if(edge.type()==ArenaDefinition.Transition.OPENABLE)openableIds.add(edge.objectId());
        }
        if(reachable(nodes.keySet().iterator().next()).size()!=nodes.size())
            throw new IllegalArgumentException("Navigation graph is disconnected with passages closed: "+definition.id());
    }
    private static Link link(ArenaDefinition.NavEdge edge,int to) {
        return new Link(edge.id(),to,edge.width(),edge.clearance(),edge.type(),edge.objectId());
    }
    public Collection<ArenaDefinition.NavNode> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public Vector3f position(int id) {
        var node=nodes.get(id);if(node==null)throw new IllegalArgumentException("Unknown nav node "+id);return node.position().vector();
    }
    public String surfaceId(int id) { return Objects.requireNonNull(nodes.get(id),"Unknown nav node").surfaceId(); }
    public List<Link> links(int id) {
        var result=links.get(id);if(result==null)throw new IllegalArgumentException("Unknown nav node "+id);return List.copyOf(result);
    }
    public long revision() { return revision; }
    public void setOpen(String objectId,boolean open) {
        if(!openableIds.contains(objectId))throw new IllegalArgumentException("Unknown navigation passage: "+objectId);
        if(open?openObjects.add(objectId):openObjects.remove(objectId))revision++;
    }
    public boolean isOpen(String objectId) { return openObjects.contains(objectId); }
    public int nearest(Vector3f point) { return nearest(point,null); }
    public int nearest(Vector3f point,String surfaceId) {
        return nodes.values().stream().filter(n->surfaceId==null||n.surfaceId().equals(surfaceId))
                .min(Comparator.comparingDouble((ArenaDefinition.NavNode n)->surfaceDistance(point,n.position().vector()))
                .thenComparingInt(ArenaDefinition.NavNode::id)).orElseThrow(()->new IllegalArgumentException("No navigation surface: "+surfaceId)).id();
    }
    private static float surfaceDistance(Vector3f a,Vector3f b) {
        float dy=Math.abs(a.y-b.y);
        return a.distanceSquared(b)+(dy>2.2f?2500+dy*dy*100:0);
    }
    public List<Integer> path(int start,int goal,boolean hazardActive,float turnPenalty,float hazardPenalty) {
        Set<String> active=hazardActive?hazards.stream().map(ArenaDefinition.Hazard::id).collect(java.util.stream.Collectors.toSet()):Set.of();
        return route(start,goal,Mobility.car(),active,turnPenalty,hazardPenalty).nodes();
    }
    public Route route(int start,int goal,Mobility mobility,Set<String> activeHazards,float turnPenalty,float hazardPenalty) {
        if(!nodes.containsKey(start)||!nodes.containsKey(goal))throw new IllegalArgumentException("Unknown route endpoint");
        Step first=new Step(-1,start);Map<Step,Float> costs=new HashMap<>();
        Map<Step,Step> previous=new HashMap<>();Map<Step,Link> arrival=new HashMap<>();
        // Account for the fastest possible air edge, so the time heuristic remains admissible.
        float secondsPerMeter=1/mobility.speed;
        if(mobility.launch)for(var pad:pads.values()) {
            float length=pad.source().vector().distance(pad.target().vector());
            secondsPerMeter=Math.min(secondsPerMeter,(pad.flightSeconds()+pad.compressionTicks()/120f)/length);
        }
        PriorityQueue<QueueEntry> queue=new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::priority)
                .thenComparingInt(e->e.step.current).thenComparingInt(e->e.step.previous));
        costs.put(first,0f);queue.add(new QueueEntry(first,0,position(start).distance(position(goal))*secondsPerMeter));
        while(!queue.isEmpty()) {
            var queued=queue.remove();Step current=queued.step;
            if(queued.cost>costs.get(current))continue;
            if(current.current==goal) {
                LinkedList<Integer> path=new LinkedList<>();LinkedList<Traversal> steps=new LinkedList<>();
                for(Step item=current;item!=null;item=previous.get(item)) {
                    path.addFirst(item.current);Link edge=arrival.get(item);
                    if(edge!=null)steps.addFirst(new Traversal(item.previous,item.current,edge.id,edge.type,edge.objectId));
                }
                return new Route(path,steps,costs.get(current),revision);
            }
            for(Link edge:links.get(current.current)) {
                if(edge.to==current.previous||!available(edge,mobility))continue;
                Vector3f a=position(current.current),b=position(edge.to);
                float cost=a.distance(b)/mobility.speed;
                if(edge.type==ArenaDefinition.Transition.LAUNCH) {
                    var pad=pads.get(edge.objectId);cost=pad.flightSeconds()+pad.compressionTicks()/120f;
                }
                if(current.previous>=0) {
                    Vector3f incoming=a.subtract(position(current.previous)).normalizeLocal(),outgoing=b.subtract(a).normalizeLocal();
                    cost+=turnPenalty*(1-Math.clamp(incoming.dot(outgoing),-1,1))/mobility.speed;
                }
                if(crossesHazard(a,b,activeHazards))cost+=hazardPenalty/mobility.speed;
                Step next=new Step(current.current,edge.to);float proposed=costs.get(current)+cost;
                if(proposed<costs.getOrDefault(next,Float.POSITIVE_INFINITY)) {
                    costs.put(next,proposed);previous.put(next,current);arrival.put(next,edge);
                    queue.add(new QueueEntry(next,proposed,proposed+b.distance(position(goal))*secondsPerMeter));
                }
            }
        }
        return new Route(List.of(),List.of(),Float.POSITIVE_INFINITY,revision);
    }
    private boolean available(Link edge,Mobility mobility) {
        if(edge.width<mobility.width||edge.clearance<mobility.height)return false;
        return switch(edge.type) {
            case OPENABLE -> openObjects.contains(edge.objectId);
            case LAUNCH -> mobility.launch&&(mobility.launchPadIds.isEmpty()||mobility.launchPadIds.contains(edge.objectId));
            case DROP -> mobility.drop;
            default -> true;
        };
    }
    public float pathLength(List<Integer> path) {
        float total=0;for(int i=1;i<path.size();i++)total+=position(path.get(i-1)).distance(position(path.get(i)));return total;
    }
    public boolean crossesHazard(Vector3f a,Vector3f b) {
        return hazards.stream().anyMatch(h->crosses(a,b,h));
    }
    public boolean crossesHazard(Vector3f a,Vector3f b,Set<String> activeIds) {
        return hazards.stream().anyMatch(h->activeIds.contains(h.id())&&crosses(a,b,h));
    }
    private static boolean crosses(Vector3f a,Vector3f b,ArenaDefinition.Hazard h) {
        float[] p={a.x,a.y,a.z},d={b.x-a.x,b.y-a.y,b.z-a.z};
        float[] min={h.minX(),h.minY(),h.minZ()},max={h.maxX(),h.maxY(),h.maxZ()};
        float lo=0,hi=1;
        for(int i=0;i<3;i++) {
            if(Math.abs(d[i])<1e-6f) { if(p[i]<min[i]||p[i]>max[i])return false; }
            else {
                float t1=(min[i]-p[i])/d[i],t2=(max[i]-p[i])/d[i];
                lo=Math.max(lo,Math.min(t1,t2));hi=Math.min(hi,Math.max(t1,t2));if(lo>hi)return false;
            }
        }
        return true;
    }
    public Set<Integer> reachable(int from) {
        if(!nodes.containsKey(from))throw new IllegalArgumentException("Unknown nav node "+from);
        Set<Integer> visited=new HashSet<>();ArrayDeque<Integer> todo=new ArrayDeque<>();todo.add(from);
        while(!todo.isEmpty()) {
            int node=todo.removeFirst();
            if(visited.add(node))for(Link edge:links.get(node))if(available(edge,Mobility.car()))todo.addLast(edge.to);
        }
        return Set.copyOf(visited);
    }
}
