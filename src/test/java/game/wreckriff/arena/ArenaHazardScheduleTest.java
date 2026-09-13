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
        int warning=registry.definition(match.arenaId).hazards().getFirst().warningTicks();
        steps(schedule,match,1680);assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state("crane-1").phase);
        assertEquals(warning,schedule.state("crane-1").remaining);
        match.phase=MatchSession.Phase.BOSS_ENTRY;steps(schedule,match,360);assertEquals(warning,schedule.state("crane-1").remaining);
        match.phase=MatchSession.Phase.BOSS_COMBAT;steps(schedule,match,warning-1);assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state("crane-1").phase);
        steps(schedule,match,1);assertEquals(ProgressStore.HazardPhase.ACTIVE,schedule.state("crane-1").phase);
    }
    @Test void bossModesKeepOneDamageHazardAndStoppingCancelsTheQueuedProtocolBeforeImpact() {
        var match=session("construction_17");var schedule=new ArenaHazardSchedule(match,registry.definition(match.arenaId));
        steps(schedule,match,1680);assertEquals(1,schedule.pendingDamage());
        assertFalse(schedule.request("crane-2",ArenaSystems.BossAction.HEAVY_STRIKE));
        match.phase=MatchSession.Phase.BOSS_COMBAT;match.bossMode=3;
        assertTrue(schedule.request("crane-2",ArenaSystems.BossAction.HEAVY_STRIKE),"One future intention waits for the active hazard");
        assertEquals(1,schedule.pendingDamage());
        assertFalse(schedule.request("crane-3",ArenaSystems.BossAction.HEAVY_STRIKE),"Only one queued intention is retained");
        schedule.cancelAll();assertEquals(0,schedule.pendingDamage());
        steps(schedule,match,1679);assertEquals(0,schedule.pendingDamage());
        assertTrue(schedule.request("crane-3",ArenaSystems.BossAction.HEAVY_STRIKE),"Requests during cooldown wait; they do not start a warning");
        assertEquals(0,schedule.pendingDamage());
        steps(schedule,match,1);assertEquals(1,schedule.pendingDamage());
        assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state("crane-3").phase);
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
    @Test void snapshotRestoresExactRemainingWarningAndDoesNotStartDamageEarly() {
        var match=session("construction_17");var arena=registry.definition(match.arenaId);var schedule=new ArenaHazardSchedule(match,arena);
        steps(schedule,match,1680+120);
        var saved=new ProgressStore.ArenaState(java.util.Map.of(),java.util.Map.of(),schedule.snapshot(),schedule.cooldown(),schedule.cursor());
        var restored=new ArenaHazardSchedule(match,arena);restored.restore(saved);assertEquals(schedule.snapshot(),restored.snapshot());
        steps(restored,match,Math.toIntExact(saved.hazards().get("crane-1").remainingTicks())-1);
        assertEquals(ProgressStore.HazardPhase.WARNING,restored.state("crane-1").phase);
        assertEquals(1,restored.state("crane-1").remaining);assertTrue(restored.drainActions().isEmpty());
        steps(restored,match,1);assertEquals(ProgressStore.HazardPhase.ACTIVE,restored.state("crane-1").phase);
        assertEquals(arena.hazards().getFirst().activeTicks(),restored.state("crane-1").remaining);
    }
}
