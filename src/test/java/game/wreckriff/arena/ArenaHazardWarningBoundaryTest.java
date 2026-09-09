package game.wreckriff.arena;

import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.config.ProgressStore;
import game.wreckriff.simulation.MatchSession;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ArenaHazardWarningBoundaryTest {
    @Test void bossRequestBeforeScheduleAdvanceGetsTheSameFullWarningAsAutomaticRequests() {
        var arena=ArenaRegistry.load().definition("construction_17");
        var match=new MatchSession(42,arena,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
        match.phase=MatchSession.Phase.BOSS_COMBAT;
        var schedule=new ArenaHazardSchedule(match,arena);
        schedule.restore(new ProgressStore.ArenaState(Map.of(),Map.of(),schedule.snapshot(),0,42));
        var hazard=arena.hazards().getFirst();
        assertTrue(schedule.request(hazard.id(),ArenaSystems.BossAction.HEAVY_STRIKE));
        // MatchRuntime accepts boss commands before ArenaSystems.beforePhysics in this same tick.
        schedule.advance(id->true);
        assertEquals(hazard.warningTicks(),schedule.state(hazard.id()).remaining);
        for(int tick=1;tick<hazard.warningTicks();tick++) {
            match.tick++;schedule.advance(id->true);
            assertEquals(ProgressStore.HazardPhase.WARNING,schedule.state(hazard.id()).phase);
        }
        match.tick++;schedule.advance(id->true);
        assertEquals(ProgressStore.HazardPhase.ACTIVE,schedule.state(hazard.id()).phase);
    }
}
