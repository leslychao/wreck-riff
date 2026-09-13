package game.wreckriff.diagnostics;

import com.jme3.profile.AppStep;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StageProfilerTest {
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
