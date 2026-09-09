package game.wreckriff.ui;

import game.wreckriff.combat.WeaponType;
import game.wreckriff.presentation.PickupStyle;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchSession;
import java.util.*;

/** Bounded, simulation-clock receipts. Restoring a snapshot cannot fabricate a collection event. */
public final class PickupFeedback {
    private record Receipt(PickupStyle style,float amount,long until) {}
    private final LinkedHashMap<String,Receipt> receipts=new LinkedHashMap<>();
    private final EnumMap<WeaponType,Long> highlights=new EnumMap<>(WeaponType.class);
    private final LinkedHashSet<Long> seen=new LinkedHashSet<>();
    private final UUID sessionId;
    private final int playerId;
    public PickupFeedback(UUID sessionId,int playerId){this.sessionId=sessionId;this.playerId=playerId;}
    public void accept(List<GameEvent> events,long tick) {
        expire(tick);
        for(var event:events) {
            if(event.type()!=GameEvent.Type.PICKUP||event.subjectId()!=playerId||event.value()<=0
                    ||!sessionId.equals(event.sessionId())||!seen.add(event.eventId()))continue;
            if(seen.size()>256)seen.remove(seen.iterator().next());
            var style=PickupStyle.ofKind(event.kind());
            var previous=receipts.remove(event.kind());
            receipts.put(event.kind(),new Receipt(style,event.value()+(previous==null?0:previous.amount),tick+144));
            while(receipts.size()>3)receipts.remove(receipts.keySet().iterator().next());
            if(style.weapon()!=null)highlights.put(style.weapon(),tick+48);
        }
    }
    public String text(long tick) {
        expire(tick);
        return String.join("  |  ",receipts.values().stream().map(r->r.style.receipt(r.amount)).toList());
    }
    public Set<WeaponType> highlighted(long tick) {
        expire(tick);return Set.copyOf(highlights.keySet());
    }
    private void expire(long tick) {
        receipts.values().removeIf(r->tick>=r.until);
        highlights.values().removeIf(until->tick>=until);
    }
}
