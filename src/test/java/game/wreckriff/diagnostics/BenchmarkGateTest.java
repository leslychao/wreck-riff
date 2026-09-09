package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkGateTest {
    private static final double FRAME=1.0/64;
    private FrameMetrics measured(int count) {var metrics=new FrameMetrics();for(int i=0;i<count;i++)metrics.add(FRAME);return metrics;}
    private BenchmarkGate.Environment standard(long memory) {return new BenchmarkGate.Environment(1920,1080,4,false,true,true,false,memory);}
    @Test void requiresSixHundredMeasuredSecondsAfterThirtyActiveWarmupSeconds() {
        var metrics=measured(600*64-1);var environment=standard(BenchmarkGate.MAXIMUM_PROCESS_MEMORY_BYTES);
        assertFalse(BenchmarkGate.evaluate(30,metrics,0,environment).releaseEligible());
        metrics.add(FRAME);assertEquals(600,metrics.seconds());
        assertTrue(BenchmarkGate.evaluate(30,metrics,0,environment).passed());
        assertFalse(BenchmarkGate.evaluate(30-FRAME,metrics,0,environment).releaseEligible());
        assertFalse(BenchmarkGate.evaluate(30,measured(60*64),0,environment).releaseEligible());
    }
    @Test void processMemoryAndDroppedSimulationTimeAreRequiredNotHeapSurrogates() {
        var metrics=measured(600*64);
        assertTrue(BenchmarkGate.evaluate(30,metrics,0,standard(BenchmarkGate.MAXIMUM_PROCESS_MEMORY_BYTES)).passed());
        assertFalse(BenchmarkGate.evaluate(30,metrics,0,standard(BenchmarkGate.MAXIMUM_PROCESS_MEMORY_BYTES+1)).passed());
        assertFalse(BenchmarkGate.evaluate(30,metrics,0,standard(0)).passed());
        assertFalse(BenchmarkGate.evaluate(30,metrics,.000001,standard(1)).passed());
        assertFalse(BenchmarkGate.evaluate(30,metrics,Double.NaN,standard(1)).passed());
    }
    @Test void actualGraphicsAudioAndDisabledProfilingAreMandatory() {
        var metrics=measured(600*64);
        var invalid=List.of(new BenchmarkGate.Environment(1280,720,4,false,true,true,false,1),
                new BenchmarkGate.Environment(1920,1080,8,false,true,true,false,1),
                new BenchmarkGate.Environment(1920,1080,4,true,true,true,false,1),
                new BenchmarkGate.Environment(1920,1080,4,false,false,true,false,1),
                new BenchmarkGate.Environment(1920,1080,4,false,true,false,false,1),
                new BenchmarkGate.Environment(1920,1080,4,false,true,true,true,1));
        for(var environment:invalid)assertFalse(BenchmarkGate.evaluate(30,metrics,0,environment).releaseEligible(),environment.toString());
    }
    @Test void aSingleSevereCombatFrameFailsEvenWhenPercentilesRemainFast() {
        var metrics=measured(600*64);metrics.add(.100001);
        assertFalse(BenchmarkGate.evaluate(30,metrics,0,standard(1)).passed());
    }
}
