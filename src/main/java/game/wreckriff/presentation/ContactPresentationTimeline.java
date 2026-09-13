package game.wreckriff.presentation;

import game.wreckriff.simulation.GameEvent;
import java.util.*;

/** One match-local clock for visible contacts, damage history and contact sound. Never changes simulation. */
public final class ContactPresentationTimeline implements AutoCloseable {
    public static final int MAX_PENDING=1024;
    private static final int MAX_SEEN=8192,MAX_PARTICIPANTS=32;
    private record Key(GameEvent.Type type,long id,int subject,int source,long tick) {}
    private record Pending(GameEvent event,double due,long sequence) {}
    private static final class Overflow {float loss;double due;}
    private final UUID sessionId;
    private final PriorityQueue<Pending> pending=new PriorityQueue<>(Comparator.comparingDouble(Pending::due)
            .thenComparingLong(Pending::sequence));
    private final LinkedHashSet<Key> seen=new LinkedHashSet<>();
    private final Map<Integer,Overflow> overflow=new HashMap<>();
    private long sequence,droppedDetails;
    private double clock;
    private boolean closed;

    public ContactPresentationTimeline(UUID sessionId) {this.sessionId=Objects.requireNonNull(sessionId);}

    /** Input is in fixed-tick/ordinal order. Immediate events retain that order, including revision barriers. */
    public List<GameEvent> accept(List<GameEvent> events,double simulationSeconds) {
        if(closed)return List.of();
        requireTime(simulationSeconds);
        var delivered=new ArrayList<GameEvent>();
        // Do not advance time here: the caller presents deferred events once, with the same render pose.
        for(var event:events) {
            if(!sessionId.equals(event.sessionId()))continue;
            var key=new Key(event.type(),event.eventId(),event.subjectId(),event.sourceId(),event.simulationTick());
            if(!seen.add(key))continue;
            if(seen.size()>MAX_SEEN)seen.remove(seen.iterator().next());
            if(event.type()==GameEvent.Type.REPAIRED)cancelDeformation(event.subjectId());
            if(event.type()==GameEvent.Type.DESTROYED) {
                pending.removeIf(p->p.event.subjectId()==event.subjectId());overflow.remove(event.subjectId());
            }
            double delay=contactDelay(event);
            if(delay<=0) {delivered.add(event);continue;}
            Pending next=new Pending(event,simulationSeconds+delay,sequence++);
            if(pending.size()==MAX_PENDING) {
                // Keep nearby/player contacts. Suppressed detail retains its health debt until its due time.
                Pending least=pending.stream().max(Comparator.<Pending>comparingInt(p->p.event.subjectId()==0?0:1)
                        .thenComparingDouble(Pending::due)).orElseThrow();
                if(event.subjectId()!=0&&next.due>=least.due) {suppress(next);continue;}
                pending.remove(least);suppress(least);
            }
            pending.add(next);
        }
        return List.copyOf(delivered);
    }

    public List<GameEvent> advanceTo(double simulationSeconds) {
        if(closed)return List.of();
        requireTime(simulationSeconds);
        clock=Math.max(clock,simulationSeconds);
        var delivered=new ArrayList<GameEvent>();
        while(!pending.isEmpty()&&pending.peek().due<=clock)delivered.add(pending.remove().event);
        overflow.values().removeIf(debt->debt.due<=clock);
        return List.copyOf(delivered);
    }

    public float visibleHp(int vehicleId,float authoritativeHp,float maximumHp) {
        if(authoritativeHp<=0)return 0;
        float loss=0;
        for(var contact:pending)if(contact.event.subjectId()==vehicleId)loss+=deferredLoss(contact.event);
        var debt=overflow.get(vehicleId);if(debt!=null)loss+=debt.loss;
        return Math.min(maximumHp,authoritativeHp+loss);
    }

    private void cancelDeformation(int id) {
        pending.removeIf(p->p.event.subjectId()==id&&p.event.type()==GameEvent.Type.DAMAGE);
        overflow.remove(id);
    }
    private void suppress(Pending detail) {
        droppedDetails++;
        float loss=deferredLoss(detail.event);
        if(loss==0)return;
        int id=detail.event.subjectId();
        if(!overflow.containsKey(id)&&overflow.size()>=MAX_PARTICIPANTS)
            throw new IllegalStateException("Contact presentation exceeds the 32-participant match contract");
        var debt=overflow.computeIfAbsent(id,key->new Overflow());debt.loss+=loss;debt.due=Math.max(debt.due,detail.due);
    }
    private static float deferredLoss(GameEvent event) {
        if(event.type()!=GameEvent.Type.DAMAGE)return 0;
        return Math.max(0,event.healthChange()==null?event.value():event.healthChange().hpBefore()-event.healthChange().hpAfter());
    }
    private static double contactDelay(GameEvent event) {
        return "machine-gun".equals(event.kind())&&(event.type()==GameEvent.Type.IMPACT
                ||event.type()==GameEvent.Type.SHIELD_HIT||event.type()==GameEvent.Type.DAMAGE)
                ?event.origin().distance(event.position())/180.0:0;
    }
    private static void requireTime(double time) {
        if(!Double.isFinite(time)||time<0)throw new IllegalArgumentException("Invalid presentation clock");
    }
    public int pendingCount(){return pending.size();}
    public long suppressedDetails(){return droppedDetails;}
    @Override public void close(){pending.clear();seen.clear();overflow.clear();closed=true;}
}
