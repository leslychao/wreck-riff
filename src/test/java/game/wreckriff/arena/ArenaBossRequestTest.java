package game.wreckriff.arena;

import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.simulation.MatchSession;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaBossRequestTest {
    private final ArenaDefinition arena=ArenaRegistry.load().definition("construction_17");
    private final MatchSession match=new MatchSession(42,arena,MatchSession.Mode.BOSS_DUEL,Configs.load("combat",CombatRules.class));
    private final ArenaHazardSchedule schedule=new ArenaHazardSchedule(match,arena);
    private void advance(int count) {for(int i=0;i<count;i++){schedule.advance(id->true);match.tick++;}}
    @Test void bossIntentionWinsTheNextAvailableCycleInsteadOfLosingToAutomaticSelection() {
        match.phase=MatchSession.Phase.BOSS_COMBAT;
        assertTrue(schedule.request("crane-3",ArenaSystems.BossAction.PROTOCOL));
        assertTrue(schedule.request("crane-3",ArenaSystems.BossAction.PROTOCOL),"Duplicate request does not replace or multiply the intention");
        assertFalse(schedule.request("crane-2",ArenaSystems.BossAction.PROTOCOL),"At most one future intention is retained");
        advance(1679);assertEquals(0,schedule.pendingDamage());
        advance(1);assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state("crane-3").phase);
        assertEquals(ProgressStore.HazardPhase.READY,schedule.state("crane-1").phase);
        assertEquals(240,schedule.state("crane-3").remaining);
        advance(240+360);assertEquals(List.of(ArenaSystems.BossAction.PROTOCOL),schedule.drainActions());
        assertTrue(schedule.drainActions().isEmpty());
    }
    @Test void shieldCancellationAlsoRemovesTheUnannouncedBossIntention() {
        match.phase=MatchSession.Phase.BOSS_COMBAT;
        assertTrue(schedule.request("crane-3",ArenaSystems.BossAction.PROTOCOL));
        schedule.cancelPreparedAndActive(1440);advance(1680);
        assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state("crane-1").phase,"The cancelled boss choice must not execute later");
        assertEquals(ProgressStore.HazardPhase.READY,schedule.state("crane-3").phase);
        assertTrue(schedule.drainActions().isEmpty());
    }
    @Test void invalidSavedBudgetDoesNotPartiallyMutateTheLiveSchedule() {
        match.phase=MatchSession.Phase.BOSS_COMBAT;var before=schedule.snapshot();
        var invalid=new LinkedHashMap<>(before);
        invalid.put("crane-1",new ProgressStore.HazardState(ProgressStore.HazardPhase.ACTIVE,10,0,1));
        invalid.put("crane-2",new ProgressStore.HazardState(ProgressStore.HazardPhase.WARNING,15,0,1));
        assertThrows(IllegalArgumentException.class,()->schedule.restore(new ProgressStore.ArenaState(Map.of(),Map.of(),invalid,0,42)));
        assertEquals(before,schedule.snapshot());assertEquals(1680,schedule.cooldown());
    }
}
