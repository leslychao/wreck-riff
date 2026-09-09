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
}
