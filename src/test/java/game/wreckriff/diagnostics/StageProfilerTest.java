package game.wreckriff.diagnostics;

import com.jme3.profile.AppStep;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StageProfilerTest {
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
