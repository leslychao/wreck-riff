package game.wreckriff.ui;

import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchSession;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only encounter speech, bounded to one visible line and one pending line per attempt. */
public final class EncounterSubtitles {
    private static final long DURATION=5L*MatchSession.TICKS_PER_SECOND;
    private record Line(String text,int priority) {}
    private final ArenaDefinition arena;
    private final UUID sessionId;
    private final LinkedHashSet<Long> seen=new LinkedHashSet<>();
    private MatchSession.Phase phase;
    private int bossMode=1;
    private Line current,pending;
    private long until;

    public EncounterSubtitles(ArenaDefinition arena,UUID sessionId) {
        this.arena=Objects.requireNonNull(arena);this.sessionId=Objects.requireNonNull(sessionId);
    }
    public void accept(MatchSession match,List<GameEvent> events) {
        if(!sessionId.equals(match.sessionId))return;
        if(match.outcome!=MatchSession.Outcome.NONE||match.phase==MatchSession.Phase.RESULT||match.phase==MatchSession.Phase.ERROR) {
            current=null;pending=null;phase=match.phase;return;
        }
        expire(match.tick);
        if(phase!=match.phase) {
            if(match.phase==MatchSession.Phase.INTRO)
                offer(new Line(arena.metadata().title()+" — "+arena.metadata().introduction(),0),match.tick);
            if(match.phase==MatchSession.Phase.BOSS_ENTRY
                    ||match.phase==MatchSession.Phase.BOSS_COMBAT&&phase!=MatchSession.Phase.BOSS_ENTRY)
                speak(0,3,match.tick);
            phase=match.phase;
        }
        if(match.phase==MatchSession.Phase.BOSS_COMBAT&&match.bossMode!=bossMode)speak(2,3,match.tick);
        bossMode=match.bossMode;
        for(var event:events) {
            if(!sessionId.equals(event.sessionId())||event.type()!=GameEvent.Type.ARENA_HAZARD_WARNING
                    ||match.phase!=MatchSession.Phase.BOSS_COMBAT||match.bossParticipantId<0
                    ||event.sourceId()!=match.bossParticipantId||!seen.add(event.eventId()))continue;
            if(seen.size()>256)seen.remove(seen.iterator().next());
            speak(1,2,match.tick);
        }
    }
    public String text(long tick) {
        expire(tick);return current==null?"":current.text();
    }
    private void speak(int quote,int priority,long tick) {
        if(arena.bosses().isEmpty())return;
        var boss=arena.bosses().getFirst();
        offer(new Line(boss.name()+": «"+boss.quotes().get(Math.min(quote,boss.quotes().size()-1))+"»",priority),tick);
    }
    private void offer(Line line,long tick) {
        if(current!=null&&current.text().equals(line.text())||pending!=null&&pending.text().equals(line.text()))return;
        if(current==null||line.priority()>current.priority()) {
            current=line;until=tick+DURATION;pending=null;
        } else if(pending==null||line.priority()>=pending.priority())pending=line;
    }
    private void expire(long tick) {
        if(current!=null&&tick>=until) {
            current=pending;pending=null;until=tick+DURATION;
        }
    }
}
