package game.wreckriff.arena;

import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.simulation.MatchSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaHazardScheduleTest {
    private final ArenaRegistry registry=ArenaRegistry.load();
    private MatchSession session(String id) {
        var match=new MatchSession(42,registry.definition(id),MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
        match.phase=MatchSession.Phase.ARENA_COMBAT;return match;
    }
    private void steps(ArenaHazardSchedule schedule,MatchSession session,int count) {for(int i=0;i<count;i++){schedule.advance(id->true);session.tick++;}}
    @Test void warningHasFullDurationAndOnlyCombatTicksAdvanceTheSchedule() {
        var match=session("construction_17");var schedule=new ArenaHazardSchedule(match,registry.definition(match.arenaId));
        steps(schedule,match,1680);assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state("crane-1").phase);
        assertEquals(240,schedule.state("crane-1").remaining);
        match.phase=MatchSession.Phase.BOSS_ENTRY;steps(schedule,match,360);assertEquals(240,schedule.state("crane-1").remaining);
        match.phase=MatchSession.Phase.BOSS_COMBAT;steps(schedule,match,239);assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state("crane-1").phase);
        steps(schedule,match,1);assertEquals(ProgressStore.HazardPhase.ACTIVE,schedule.state("crane-1").phase);
    }
    @Test void directorThirdModeAllowsTwoDisjointSectorsAndShieldCancelsBothBeforeImpact() {
        var match=session("doomsday_arena");var schedule=new ArenaHazardSchedule(match,registry.definition(match.arenaId));
        steps(schedule,match,1920);assertEquals(1,schedule.pendingDamage());
        assertFalse(schedule.request("show-electric",ArenaSystems.BossAction.PROTOCOL));
        match.phase=MatchSession.Phase.BOSS_COMBAT;match.bossMode=3;
        assertTrue(schedule.request("show-electric",ArenaSystems.BossAction.PROTOCOL));assertEquals(2,schedule.pendingDamage());
        assertFalse(schedule.request("show-fire",ArenaSystems.BossAction.PROTOCOL));
        schedule.cancelPreparedAndActive(1440);assertEquals(0,schedule.pendingDamage());
        steps(schedule,match,1439);assertEquals(0,schedule.pendingDamage());
        assertFalse(schedule.request("show-fire",ArenaSystems.BossAction.PROTOCOL));
        steps(schedule,match,1);assertEquals(0,schedule.pendingDamage(),"Shield does not shorten the already longer ordinary event interval");
        steps(schedule,match,480);assertEquals(1,schedule.pendingDamage());
        assertTrue(schedule.drainActions().isEmpty(),"Cancelled protocols never open a boss panel");
    }
    @Test void onlyOneBarrierCanPrepareAndOccupiedVolumeCancelsItsRise() {
        var match=session("neon_zero");var schedule=new ArenaHazardSchedule(match,registry.definition(match.arenaId));
        steps(schedule,match,1680);
        assertTrue(schedule.request("city-barrier-west",ArenaSystems.BossAction.PROTOCOL));
        assertFalse(schedule.request("city-barrier-east",ArenaSystems.BossAction.PROTOCOL));
        for(int i=0;i<=180;i++){schedule.advance(id->false);match.tick++;}
        assertEquals(ProgressStore.HazardPhase.COOLDOWN,schedule.state("city-barrier-west").phase);
        assertTrue(schedule.drainActions().isEmpty());
    }
    @Test void snapshotRestoresExactTimersAndStatueWaitsTwoSecondsBeforeOpening() {
        var match=session("ash_necropolis");var arena=registry.definition(match.arenaId);var schedule=new ArenaHazardSchedule(match,arena);
        schedule.statue("short-cut");steps(schedule,match,120);
        var saved=new ProgressStore.ArenaState(java.util.Map.of(),java.util.Map.of(),schedule.snapshot(),schedule.cooldown(),schedule.cursor());
        var restored=new ArenaHazardSchedule(match,arena);restored.restore(saved);assertEquals(schedule.snapshot(),restored.snapshot());
        steps(restored,match,119);assertTrue(restored.drainCompleted().isEmpty());
        steps(restored,match,2);assertEquals(java.util.List.of("short-cut"),restored.drainCompleted());
        assertEquals(ProgressStore.HazardPhase.DISABLED,restored.state("short-cut").phase);
    }
}
