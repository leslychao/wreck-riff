package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameMetricsTest {
    @Test void keepsEarlyStallsEvenAfterMoreThanTheFormerRingCapacity() {
        var metrics=new FrameMetrics();metrics.add(.2);
        for(int i=0;i<200000;i++)metrics.add(.01);
        assertEquals(200001L,metrics.snapshot().get("frames"));
        assertEquals(200.0,metrics.snapshot().get("maxFrameMs"));
        assertEquals(1L,metrics.snapshot().get("framesOver100ms"));
        assertFalse(metrics.withinTarget());
    }
    @Test void acceptsTargetDistributionAndRejectsInvalidInput() {
        var metrics=new FrameMetrics();for(int i=0;i<1000;i++)metrics.add(.012);
        assertTrue(metrics.withinTarget());assertEquals(12.0,metrics.snapshot().get("p95FrameMs"));
        assertThrows(IllegalArgumentException.class,()->metrics.add(Double.NaN));
    }
    @Test void percentileLimitsHaveInclusiveBoundariesAndRejectActualOverruns() {
        var accepted=new FrameMetrics();for(int i=0;i<980;i++)accepted.add(.0167);for(int i=0;i<20;i++)accepted.add(.025);
        assertTrue(accepted.withinTarget());assertEquals(16.7,accepted.percentileMilliseconds(.95));assertEquals(25,accepted.percentileMilliseconds(.99));
        var slow95=new FrameMetrics();for(int i=0;i<1000;i++)slow95.add(.01671);assertFalse(slow95.withinTarget());
        var slow99=new FrameMetrics();for(int i=0;i<980;i++)slow99.add(.01);for(int i=0;i<20;i++)slow99.add(.02501);assertFalse(slow99.withinTarget());
    }
}
