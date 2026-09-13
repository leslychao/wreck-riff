package game.wreckriff.diagnostics;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuPassProfilerTest {
    static final class Queries implements GpuPassProfiler.Queries {
        Set<Integer> ready=new HashSet<>(),deleted=new HashSet<>();int stamps,reads;
        public int[] allocate(int n){return java.util.stream.IntStream.range(1,n+1).toArray();}
        public void timestamp(int id){stamps++;}
        public boolean available(int id){return ready.contains(id);}
        public long nanos(int id){assertTrue(ready.remove(id));reads++;return id*1_000_000L;}
        public void delete(int id){assertTrue(deleted.add(id));}
    }
    @Test void timestampPairsSkipBusySlotsWithoutBlockingOrNestingElapsedQueries() {
        var query=new Queries();var profiler=new GpuPassProfiler(query);
        for(int i=0;i<20;i++){profiler.begin("combat-vfx-composite");profiler.end("combat-vfx-composite");}
        assertEquals(16,query.stamps);assertEquals(0,query.reads);
        assertEquals(12L,profiler.snapshot().get("skippedSamples"));
        query.ready.addAll(List.of(1,2));profiler.begin("combat-vfx-composite");profiler.end("combat-vfx-composite");
        assertEquals(2,query.reads);
        var passes=(Map<?,?>)profiler.snapshot().get("passes");
        assertEquals(1.0,((Map<?,?>)passes.get("combat-vfx-composite")).get("maxFrameMs"));
        profiler.close();profiler.close();assertEquals(16,query.deleted.size());
    }
}
