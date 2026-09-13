package game.wreckriff.diagnostics;

import com.jme3.profile.AppStep;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StageProfilerTest {
    @Test void visualUpdateBoundSumsEachFramesDisjointIntervalsBeforeTakingPercentiles() {
        var clock=new AtomicLong();var profiler=new StageProfiler(clock::get);
        for(int frame=0;frame<101;frame++) {
            long start=frame*20_000_000L;clock.set(start);profiler.appStep(AppStep.BeginFrame);profiler.appStep(AppStep.StateManagerUpdate);
            profiler.record(StageProfiler.Stage.PRESENTATION,frame%2==0?900_000:100_000);
            profiler.record(StageProfiler.Stage.CONTACT_PRESENTATION,frame%2==0?100_000:900_000);
            clock.set(start+1_000_000);profiler.appStep(AppStep.SpatialUpdate);
            clock.set(start+(frame==100?10_000_000:1_200_000));profiler.appStep(AppStep.StateManagerRender);
            profiler.measuredCombatFrame(frame,frame<100,true);
            clock.set(start+12_000_000);profiler.appStep(AppStep.RenderFrame);
            clock.set(start+19_000_000);profiler.appStep(AppStep.EndFrame);
        }
        var active=(Map<?,?>)profiler.snapshot().get("measuredCombat");
        var bound=(Map<?,?>)active.get("visualUpdateCpuUpperBound");assertNotNull(bound);
        assertEquals(100L,bound.get("frames"));assertEquals(1.2,bound.get("p95FrameMs"));assertEquals(1.2,bound.get("maxFrameMs"));
        var stages=(Map<?,?>)active.get("cpuStages");var engine=(Map<?,?>)active.get("jmeAppSteps");
        double incorrectlyAddedPercentiles=(Double)((Map<?,?>)stages.get("PRESENTATION")).get("p95FrameMs")
                +(Double)((Map<?,?>)stages.get("CONTACT_PRESENTATION")).get("p95FrameMs")
                +(Double)((Map<?,?>)engine.get("SpatialUpdate")).get("p95FrameMs");
        assertEquals(2.0,incorrectlyAddedPercentiles,.000001);assertNotEquals(incorrectlyAddedPercentiles,(Double)bound.get("p95FrameMs"));
        assertTrue((Double)((Map<?,?>)engine.get("RenderFrame")).get("p95FrameMs")>6,
                "Draw submission interval is observed separately and cannot inflate the update bound");
    }
    @Test void measuredCombatAggregatesCpuPerRenderFrameAndExcludesWarmupPauseLoadingAndResults() {
        var profiler=new StageProfiler();
        for(int frame=0;frame<6;frame++) {
            profiler.appStep(AppStep.BeginFrame);
            profiler.record(StageProfiler.Stage.PRESENTATION,frame==2?500_000:9_000_000);
            profiler.record(StageProfiler.Stage.PRESENTATION,frame==2?250_000:9_000_000);
            profiler.measuredCombatFrame(frame,frame==2||frame==3,true);
            profiler.appStep(AppStep.EndFrame);
        }
        var active=(Map<?,?>)profiler.snapshot().get("measuredCombat");
        assertEquals(2L,active.get("frames"));
        var stages=(Map<?,?>)active.get("cpuStages");
        var presentation=(Map<?,?>)stages.get("PRESENTATION");
        assertEquals(2L,presentation.get("frames"));assertEquals(18.0,presentation.get("maxFrameMs"));
        assertEquals(.01875,(Double)presentation.get("sampleSeconds"),.0000001);
        assertEquals(12L,((Map<?,?>)((Map<?,?>)profiler.snapshot().get("cpuStages")).get("PRESENTATION")).get("frames"));
        assertEquals(2L,((Map<?,?>)stages.get("BULLET")).get("frames"));
        assertEquals(0.0,((Map<?,?>)stages.get("BULLET")).get("maxFrameMs"));
        var smoke=new StageProfiler();smoke.measuredCombatFrame(0,false,false);
        assertEquals("NOT_APPLICABLE",((Map<?,?>)smoke.snapshot().get("measuredCombat")).get("status"));
    }
    @Test void hiddenStatsOverlayCannotDisableDiagnosticCollection() {
        var profiler=new StageProfiler();var statistics=new com.jme3.renderer.Statistics();
        profiler.attachRendererStatistics(statistics);
        statistics.setEnabled(false); // StatsAppState initialises with the overlay hidden.
        profiler.appStep(AppStep.RenderFrame);assertTrue(statistics.isEnabled());
        statistics.onMeshDrawn(new com.jme3.scene.shape.Box(1,1,1),0);
        profiler.renderStatistics(statistics);
        var snapshot=profiler.snapshot();var counts=(Map<?,?>)snapshot.get("maximumRendererStatistics");
        assertEquals(12,counts.get("Triangles"));assertEquals(24,counts.get("Vertices"));
        assertEquals("VALID",((Map<?,?>)snapshot.get("rendererStatisticsCollection")).get("status"));
        statistics.clearFrame();profiler.renderStatistics(statistics);
        assertEquals(12,((Map<?,?>)profiler.snapshot().get("maximumRendererStatistics")).get("Triangles"));
    }
    @Test void unobservedGeometryIsReportedAsMissingEvidenceInsteadOfZeroCost() {
        var profiler=new StageProfiler();profiler.renderStatistics(new com.jme3.renderer.Statistics());
        var counts=(Map<?,?>)profiler.snapshot().get("rendererStatisticsCollection");
        assertEquals("NO_RENDERED_GEOMETRY",counts.get("status"));assertEquals(1L,counts.get("disabledFrames"));
    }
    @Test void attributesElapsedCpuToTheStageThatJustFinishedAndExcludesBetweenFrameIdle() {
        var clock=new AtomicLong();var profiler=new StageProfiler(clock::get);
        profiler.appStep(AppStep.BeginFrame);clock.set(2_000_000);profiler.appStep(AppStep.ProcessInput);
        clock.set(5_000_000);profiler.appStep(AppStep.EndFrame);clock.set(100_000_000);profiler.appStep(AppStep.BeginFrame);
        profiler.record(StageProfiler.Stage.BULLET,4_000_000);
        var snapshot=profiler.snapshot();var engine=(Map<?,?>)snapshot.get("jmeAppSteps");
        assertEquals(2.0,((Map<?,?>)engine.get("BeginFrame")).get("maxFrameMs"));
        assertEquals(3.0,((Map<?,?>)engine.get("ProcessInput")).get("maxFrameMs"));assertFalse(engine.containsKey("EndFrame"));
        var stages=(Map<?,?>)snapshot.get("cpuStages");assertEquals(4.0,((Map<?,?>)stages.get("BULLET")).get("maxFrameMs"));
        assertThrows(IllegalArgumentException.class,()->profiler.record(StageProfiler.Stage.AI,-1));
    }
}
