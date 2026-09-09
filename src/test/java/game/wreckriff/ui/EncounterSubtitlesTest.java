package game.wreckriff.ui;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchSession;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EncounterSubtitlesTest {
    private final ArenaRegistry registry=ArenaRegistry.load();
    private MatchSession session() {
        return new MatchSession(42,registry.definition("neon_zero"),MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
    }
    private EncounterSubtitles presenter(MatchSession match) {
        return new EncounterSubtitles(registry.definition(match.arenaId),match.sessionId);
    }
    private GameEvent protocol(MatchSession match,long id) {
        return new GameEvent(GameEvent.Type.ARENA_HAZARD_WARNING,id,-1,match.bossParticipantId,Vector3f.ZERO,"city-electric",1.5f)
                .forObject("city-electric").inSession(match.sessionId);
    }
    @Test void introductionAndEntryUseAuthoredRussianTextAndPauseDoesNotConsumeTheLine() {
        var match=session();var presenter=presenter(match);var arena=registry.definition(match.arenaId);
        presenter.accept(match,List.of());
        assertTrue(presenter.text(0).contains(arena.metadata().title()));
        assertTrue(presenter.text(0).contains(arena.metadata().introduction()));
        for(int frame=0;frame<1000;frame++)assertFalse(presenter.text(0).isEmpty());
        match.tick=120;match.phase=MatchSession.Phase.BOSS_ENTRY;presenter.accept(match,List.of());
        String entry=arena.bosses().getFirst().quotes().getFirst();
        assertTrue(presenter.text(120).contains(entry));
        assertFalse(presenter.text(719).isEmpty());assertEquals("",presenter.text(720));
    }
    @Test void protocolWaitsForEntryAndDuplicateEventsCannotExtendItsLifetime() {
        var match=session();var presenter=presenter(match);var boss=registry.definition(match.arenaId).bosses().getFirst();
        match.phase=MatchSession.Phase.BOSS_ENTRY;match.bossParticipantId=7;presenter.accept(match,List.of());
        match.tick=360;match.phase=MatchSession.Phase.BOSS_COMBAT;
        var event=protocol(match,12);presenter.accept(match,List.of(event));
        assertTrue(presenter.text(360).contains(boss.quotes().getFirst()));
        assertTrue(presenter.text(600).contains(boss.quotes().get(1)));
        match.tick=700;presenter.accept(match,List.of(event));
        assertEquals("",presenter.text(1200));
    }
    @Test void bossModeChangeUsesAvailableQuoteAndOrdinaryWarningCannotSpeakForBoss() {
        var match=session();var presenter=presenter(match);var boss=registry.definition(match.arenaId).bosses().getFirst();
        match.phase=MatchSession.Phase.ARENA_COMBAT;presenter.accept(match,List.of());
        var ordinary=protocol(match,14);presenter.accept(match,List.of(ordinary));assertEquals("",presenter.text(0));
        match.phase=MatchSession.Phase.BOSS_COMBAT;match.bossParticipantId=7;presenter.accept(match,List.of());
        match.tick=700;match.bossMode=2;presenter.accept(match,List.of());
        assertTrue(presenter.text(700).contains(boss.quotes().get(Math.min(2,boss.quotes().size()-1))));
        assertEquals(2,match.bossMode);assertEquals(700,match.tick);
    }
    @Test void retryRejectsOldAttemptEventsAndOutcomeClearsQueuedSpeech() {
        var old=session();old.phase=MatchSession.Phase.BOSS_COMBAT;old.bossParticipantId=7;
        var retry=session();retry.phase=MatchSession.Phase.ARENA_COMBAT;retry.bossParticipantId=7;
        var presenter=presenter(retry);presenter.accept(retry,List.of(protocol(old,42)));assertEquals("",presenter.text(0));
        presenter.accept(old,List.of(protocol(old,43)));assertEquals("",presenter.text(0));
        retry.phase=MatchSession.Phase.BOSS_COMBAT;
        presenter.accept(retry,List.of(protocol(retry,44)));assertFalse(presenter.text(0).isEmpty());
        retry.outcome=MatchSession.Outcome.DEFEAT;retry.phase=MatchSession.Phase.RESULT;presenter.accept(retry,List.of());
        assertEquals("",presenter.text(0));assertEquals("",presenter.text(10000));
    }
}
