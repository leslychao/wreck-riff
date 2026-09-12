package game.wreckriff.diagnostics;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuFrameProfilerTest {
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
        for(int i=0;i<8;i++){profiler.begin();profiler.end();}
        assertEquals(4,query.starts);assertEquals(4,query.stops);assertEquals(0,query.reads);
        assertEquals(4L,profiler.snapshot().get("skippedSamples"));
        query.ready.add(11);profiler.begin();profiler.end();
        assertEquals(1,query.reads);assertEquals(5,query.starts);
        assertEquals(5.0,((Map<?,?>)profiler.snapshot().get("frames")).get("maxFrameMs"));
    }
    @Test void shutdownEndsAnActiveQueryAndDeletesEachHandleExactlyOnce() {
        var query=new Queries();var profiler=new GpuFrameProfiler(query);profiler.begin();profiler.close();profiler.close();
        profiler.begin();profiler.end();assertEquals(1,query.starts);assertEquals(1,query.stops);assertEquals(4,query.deleted.size());
        assertEquals(0,query.reads);
    }
}
