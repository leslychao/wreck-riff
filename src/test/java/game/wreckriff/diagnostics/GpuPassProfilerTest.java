package game.wreckriff.diagnostics;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuPassProfilerTest {
    @Test void latePassReadbackDoesNotReclassifyWarmupAsCombatOrCombatAsResults() {
        var query=new Queries();var profiler=new GpuPassProfiler(query);
        profiler.measuredCombatFrame(false);profiler.begin("combat-vfx-composite");profiler.end("combat-vfx-composite");
        profiler.measuredCombatFrame(true);profiler.begin("combat-vfx-composite");profiler.end("combat-vfx-composite");
        query.ready.addAll(List.of(1,2));profiler.begin("combat-vfx-composite");profiler.end("combat-vfx-composite");
        assertTrue(((Map<?,?>)profiler.measuredCombatSnapshot().get("passes")).isEmpty());
        query.ready.addAll(List.of(3,4));profiler.measuredCombatFrame(false);profiler.begin("combat-vfx-composite");profiler.end("combat-vfx-composite");
        var active=profiler.measuredCombatSnapshot();var pass=(Map<?,?>)((Map<?,?>)active.get("passes")).get("combat-vfx-composite");
        assertEquals(1L,pass.get("frames"));assertEquals(1L,active.get("pendingSamples"));assertEquals(2L,active.get("submittedSamples"));
        profiler.close();
        assertEquals(0L,profiler.measuredCombatSnapshot().get("pendingSamples"));assertEquals(1L,profiler.measuredCombatSnapshot().get("discardedSamples"));
    }
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
