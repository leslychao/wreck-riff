package game.wreckriff.presentation;

import game.wreckriff.simulation.GameEvent;
import java.util.*;

/** One match-local clock for visible contacts, damage history and contact sound. Never changes simulation. */
public final class ContactPresentationTimeline implements AutoCloseable {
    public static final int MAX_PENDING=1024;
    private static final int MAX_SEEN=8192,MAX_PARTICIPANTS=32;
    // At least one repair and one destruction per participant survive detail saturation.
    // Further distinct repairs may borrow free detail slots; health changes are never lossy-merged.
    private static final int CRITICAL_RESERVE=MAX_PARTICIPANTS*2,MAX_DETAILS=MAX_PENDING-CRITICAL_RESERVE;
    private record Key(GameEvent.Type type,long id,int subject,int source,long tick) {}
    private record Pending(GameEvent event,double due,long sequence) {}
    private static final Comparator<Pending> DETAIL_PRIORITY=Comparator.<Pending>comparingInt(p->p.event.subjectId()==0?0:1)
            .thenComparingDouble(Pending::due).thenComparingLong(Pending::sequence);
    private static final class Overflow {float loss;double due;}
    private final UUID sessionId;
    private final PriorityQueue<Pending> pending=new PriorityQueue<>(Comparator.comparingDouble(Pending::due)
            .thenComparingLong(Pending::sequence));
    private final LinkedHashSet<Key> seen=new LinkedHashSet<>();
    private final Map<Integer,Overflow> overflow=new HashMap<>();
    private long sequence,droppedDetails;
    private int detailCount;
    private double clock;
    private boolean closed;

    public ContactPresentationTimeline(UUID sessionId) {this.sessionId=Objects.requireNonNull(sessionId);}

    /** Queue in fixed-tick/ordinal order; only advanceTo delivers after the caller resolves render poses. */
    public void accept(List<GameEvent> events,double simulationSeconds) {
        if(closed)return;
        requireTime(simulationSeconds);
        for(var event:events) {
            if(!sessionId.equals(event.sessionId()))continue;
            var key=new Key(event.type(),event.eventId(),event.subjectId(),event.sourceId(),event.simulationTick());
            if(seen.contains(key))continue;
            double born=event.simulationTick()>=0?event.simulationTick()/120.0:simulationSeconds;
            if(event.type()==GameEvent.Type.REPAIRED)cancelDeformation(event.subjectId(),born);
            if(event.type()==GameEvent.Type.DESTROYED) {
                // Already-due contacts retain their place before death; only the future cosmetic tail expires.
                removePending(p->p.event.subjectId()==event.subjectId()&&p.due>born&&isContact(p.event));overflow.remove(event.subjectId());
            }
            double delay=contactDelay(event);
            Pending next=new Pending(event,born+delay,sequence++);
            boolean critical=isCritical(event),keep=true;
            if(pending.size()==MAX_PENDING||!critical&&detailCount>=MAX_DETAILS) {
                Pending least=pending.stream().filter(p->!isCritical(p.event)).max(DETAIL_PRIORITY).orElse(null);
                if(least==null) {
                    if(critical)throw new IllegalStateException("More than 1024 undelivered repair/destruction barriers; render drain contract violated");
                    keep=false;
                } else if(!critical&&DETAIL_PRIORITY.compare(next,least)>=0)keep=false;
                else {pending.remove(least);detailCount--;suppress(least);}
            }
            if(keep){pending.add(next);if(!critical)detailCount++;}else suppress(next);
            seen.add(key);if(seen.size()>MAX_SEEN)seen.remove(seen.iterator().next());
        }
    }

    public List<GameEvent> advanceTo(double simulationSeconds) {
        if(closed)return List.of();
        requireTime(simulationSeconds);
        clock=Math.max(clock,simulationSeconds);
        var delivered=new ArrayList<GameEvent>();
        while(!pending.isEmpty()&&pending.peek().due<=clock){var event=pending.remove().event;if(!isCritical(event))detailCount--;delivered.add(event);}
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

    private void cancelDeformation(int id,double barrierBorn) {
        // A partial repair must operate after earlier presented damage, including same-tick ordinals.
        removePending(p->p.event.subjectId()==id&&p.due>barrierBorn&&p.event.type()==GameEvent.Type.DAMAGE);
        overflow.remove(id);
    }
    private void removePending(java.util.function.Predicate<Pending> remove) {
        for(var iterator=pending.iterator();iterator.hasNext();) {
            var p=iterator.next();if(remove.test(p)){if(!isCritical(p.event))detailCount--;iterator.remove();}
        }
    }
    private static boolean isCritical(GameEvent event) {
        return event.type()==GameEvent.Type.REPAIRED||event.type()==GameEvent.Type.DESTROYED;
    }
    private static boolean isContact(GameEvent event) {
        return switch(event.type()){case DAMAGE,IMPACT,SHIELD_HIT,RAM->true;default->false;};
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
    @Override public void close(){pending.clear();seen.clear();overflow.clear();detailCount=0;closed=true;}
}
