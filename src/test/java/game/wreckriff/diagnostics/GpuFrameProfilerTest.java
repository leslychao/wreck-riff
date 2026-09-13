package game.wreckriff.diagnostics;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuFrameProfilerTest {
    @Test void lateGpuReadbackKeepsOriginEligibilityAndReportsMissingResults() {
        var query=new Queries();var profiler=new GpuFrameProfiler(query);
        profiler.begin(false);profiler.end(); // loading
        profiler.begin(true);profiler.end(); // measured combat
        query.ready.add(10);profiler.begin(true);profiler.end(); // collect loading in combat
        assertEquals(0L,((Map<?,?>)profiler.measuredCombatSnapshot().get("frames")).get("frames"));
        query.ready.add(11);profiler.begin(false);profiler.end(); // collect combat in Results
        var active=profiler.measuredCombatSnapshot();assertEquals(1L,((Map<?,?>)active.get("frames")).get("frames"));
        assertEquals(1L,active.get("pendingSamples"));assertEquals(2L,active.get("submittedSamples"));
        assertEquals(2L,((Map<?,?>)profiler.snapshot().get("frames")).get("frames"));profiler.close();
        assertEquals(0L,profiler.measuredCombatSnapshot().get("pendingSamples"));assertEquals(1L,profiler.measuredCombatSnapshot().get("discardedSamples"));
    }
    @Test void saturatedMeasuredQueryPoolReportsEveryUnmeasuredEligibleFrame() {
        var query=new Queries();var profiler=new GpuFrameProfiler(query);
        for(int frame=0;frame<6;frame++){profiler.begin(true);profiler.end();}
        var active=profiler.measuredCombatSnapshot();assertEquals(4L,active.get("submittedSamples"));assertEquals(4L,active.get("pendingSamples"));assertEquals(2L,active.get("skippedSamples"));
        profiler.close();assertEquals(4L,profiler.measuredCombatSnapshot().get("discardedSamples"));
    }
    private static final class Queries implements GpuFrameProfiler.Queries {
        Set<Integer> ready=new HashSet<>(),deleted=new HashSet<>();int reads,starts,stops;
        public int[] allocate(int count){assertEquals(4,count);return new int[]{10,11,12,13};}
        public boolean available(int id){return ready.contains(id);}
        public long nanos(int id){assertTrue(ready.remove(id),"Never request an unavailable result");reads++;return 5_000_000;}
        public void begin(int id){starts++;}
        public void end(){stops++;}
        public void delete(int id){assertTrue(deleted.add(id));}
    }
    @Test void slowGpuFillsBoundedPoolThenSkipsWithoutReadingOrWaiting() {
        var query=new Queries();var profiler=new GpuFrameProfiler(query);
        for(int i=0;i<8;i++){profiler.begin(false);profiler.end();}
        assertEquals(4,query.starts);assertEquals(4,query.stops);assertEquals(0,query.reads);
        assertEquals(4L,profiler.snapshot().get("skippedSamples"));
        query.ready.add(11);profiler.begin(false);profiler.end();
        assertEquals(1,query.reads);assertEquals(5,query.starts);
        assertEquals(5.0,((Map<?,?>)profiler.snapshot().get("frames")).get("maxFrameMs"));
    }
    @Test void shutdownEndsAnActiveQueryAndDeletesEachHandleExactlyOnce() {
        var query=new Queries();var profiler=new GpuFrameProfiler(query);profiler.begin(false);profiler.close();profiler.close();
        profiler.begin(false);profiler.end();assertEquals(1,query.starts);assertEquals(1,query.stops);assertEquals(4,query.deleted.size());
        assertEquals(0,query.reads);
    }
}
