package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticEvidenceTest {
    private FrameSample frame(FrameSample.Phase phase,double seconds) {return new FrameSample(7,120,"construction_17",phase,seconds,7,44,8,9,50,1,true,0);}
    @Test void menuLoadingIntroEntryAndResultsNeverAdvanceActiveWarmupOrMeasurements() {
        var evidence=new DiagnosticEvidence(true,600);
        for(var phase:FrameSample.Phase.values())if(!phase.combat())evidence.frame(frame(phase,100));
        assertEquals(0,evidence.warmedActiveSeconds());assertEquals(0,evidence.measuredActiveSeconds());
        evidence.frame(frame(FrameSample.Phase.ARENA_COMBAT,29));
        evidence.frame(frame(FrameSample.Phase.BOSS_ENTRY,5));
        evidence.frame(frame(FrameSample.Phase.BOSS_COMBAT,1.5));
        assertEquals(30.5,evidence.warmedActiveSeconds());assertEquals(0,evidence.measuredActiveSeconds());
        evidence.frame(frame(FrameSample.Phase.ARENA_COMBAT,.01));
        evidence.frame(frame(FrameSample.Phase.BOSS_COMBAT,.01));
        assertEquals(.02,evidence.measuredActiveSeconds());assertTrue(evidence.renderTargetMet());
        var phases=(Map<?,?>)((Map<?,?>)evidence.snapshot("RUNNING").get("phaseMetrics")).get("phases");
        assertTrue(phases.containsKey("LOADING"));assertTrue(phases.containsKey("TRANSITION"));
    }
    @Test void shortDiagnosticCanCompleteButCannotBecomeFinalPass() {
        var evidence=new DiagnosticEvidence(true,60);evidence.frame(frame(FrameSample.Phase.ARENA_COMBAT,30));
        for(int i=0;i<60*64;i++)evidence.frame(frame(FrameSample.Phase.ARENA_COMBAT,1.0/64));
        assertEquals(60,evidence.measuredActiveSeconds());assertEquals("DIAGNOSTIC_COMPLETE",evidence.completionStatus(true));
        assertEquals(false,evidence.snapshot("DIAGNOSTIC_COMPLETE").get("releaseEligible"));
        assertEquals("FAIL",evidence.completionStatus(false));
    }
    @Test void unavailableFramesDoNotCountAndWindowMismatchRemainsLatchedAfterRecovery() {
        var evidence=new DiagnosticEvidence(true,600);
        evidence.frame(new FrameSample(0,0,"construction_17",FrameSample.Phase.ARENA_COMBAT,600,1,1,0,0,0,0,false,0));
        assertEquals(0,evidence.warmedActiveSeconds());assertEquals(0,evidence.measuredActiveSeconds());
        evidence.observeWindow(1280,720,true);evidence.observeWindow(1920,1080,true);
        assertEquals(true,evidence.snapshot("RUNNING").get("invalidBenchmarkWindowObserved"));
    }
    @Test void phaseSpikesAreBoundedAndKeepTheWorstFrameWithCombatContext() {
        var metrics=new PhaseMetrics();
        for(int i=0;i<200;i++)metrics.add(new FrameSample(i,i*2,"neon_zero",FrameSample.Phase.BOSS_COMBAT,.034+i*.001,7,44,8,9,50,1,true,i==0?.01:0));
        var result=metrics.snapshot();var spikes=(List<?>)result.get("worstFrames");
        assertEquals(PhaseMetrics.MAXIMUM_SPIKES,spikes.size());
        var worst=(FrameSample)spikes.getFirst();assertEquals(199,worst.frame());assertEquals(398,worst.tick());
        assertEquals("neon_zero",worst.arenaId());assertEquals(9,worst.sfxVoices());assertEquals(.01,metrics.droppedSimulationSeconds());
        var phase=(Map<?,?>)((Map<?,?>)result.get("phases")).get("BOSS_COMBAT");
        assertEquals(200L,phase.get("framesOver33ms"));assertTrue((Long)phase.get("framesOver50ms")>0);assertTrue((Long)phase.get("framesOver100ms")>0);
    }
}
