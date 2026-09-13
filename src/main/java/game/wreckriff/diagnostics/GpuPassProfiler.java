package game.wreckriff.diagnostics;

import game.wreckriff.presentation.CombatVfxFilter;
import java.util.*;
import org.lwjgl.opengl.*;

/** Bounded asynchronous timestamp pairs, safe inside the existing whole-frame elapsed query. */
public final class GpuPassProfiler implements CombatVfxFilter.Probe,AutoCloseable {
    interface Queries {
        int[] allocate(int count);void timestamp(int id);boolean available(int id);long nanos(int id);void delete(int id);
    }
    private final Queries queries;
    private final int[] ids;
    private final String[] pending=new String[8];
    private final boolean[] measured=new boolean[8];
    private final Map<String,FrameMetrics> passes=new LinkedHashMap<>();
    private final Map<String,FrameMetrics> combatPasses=new LinkedHashMap<>();
    private int active=-1;
    private long skipped;
    private long submitted,combatSubmitted,combatSkipped,discarded,combatDiscarded;
    private boolean measuredCombat;
    private boolean closed;
    GpuPassProfiler(Queries queries){this.queries=queries;ids=queries.allocate(16);}
    void measuredCombatFrame(boolean eligible){measuredCombat=eligible;}
    public static GpuPassProfiler create() {
        var caps=GL.getCapabilities();if(!caps.OpenGL33&&!caps.GL_ARB_timer_query)return null;
        return new GpuPassProfiler(new Queries() {
            public int[] allocate(int count){int[] ids=new int[count];GL15.glGenQueries(ids);return ids;}
            public void timestamp(int id){ARBTimerQuery.glQueryCounter(id,ARBTimerQuery.GL_TIMESTAMP);}
            public boolean available(int id){return GL15.glGetQueryObjecti(id,GL15.GL_QUERY_RESULT_AVAILABLE)!=0;}
            public long nanos(int id){return ARBTimerQuery.glGetQueryObjectui64(id,GL15.GL_QUERY_RESULT);}
            public void delete(int id){GL15.glDeleteQueries(id);}
        });
    }
    @Override public void begin(String pass) {
        if(closed)return;
        if(active>=0)throw new IllegalStateException("VFX pass probe is already active");
        if(!passes.containsKey(pass)&&passes.size()>=4)throw new IllegalArgumentException("At most four named GPU passes");
        passes.computeIfAbsent(pass,key->new FrameMetrics());
        for(int i=0;i<pending.length;i++)if(pending[i]!=null&&queries.available(ids[i*2+1])&&queries.available(ids[i*2])) {
            long start=queries.nanos(ids[i*2]),end=queries.nanos(ids[i*2+1]);
            if(end>=start){passes.get(pending[i]).add((end-start)/1_000_000_000.0);if(measured[i])combatPasses.computeIfAbsent(pending[i],key->new FrameMetrics()).add((end-start)/1_000_000_000.0);}
            pending[i]=null;
        }
        for(int i=0;i<pending.length;i++)if(pending[i]==null){active=i;pending[i]=pass;measured[i]=measuredCombat;submitted++;if(measuredCombat)combatSubmitted++;queries.timestamp(ids[i*2]);return;}
        skipped++;if(measuredCombat)combatSkipped++;
    }
    @Override public void end(String pass) {
        if(active<0)return;
        if(!pending[active].equals(pass))throw new IllegalStateException("Unbalanced GPU pass probe");
        queries.timestamp(ids[active*2+1]);active=-1;
    }
    public Map<String,Object> snapshot() {
        var values=new LinkedHashMap<String,Object>();passes.forEach((key,value)->values.put(key,value.snapshot()));
        return Map.of("status","SUPPORTED","measurement","Asynchronous GL timestamp pairs: color resolve, Translucent queue and fullscreen composite only; excludes opaque/Transparent debris and lighting",
                "scope","Whole diagnostic run, including loading, warmup, pause and Results","queryPairs",8,"skippedSamples",skipped,"passes",values,
                "submittedSamples",submitted,"pendingSamples",pendingCount(false),"discardedSamples",discarded);
    }
    Map<String,Object> measuredCombatSnapshot() {
        var values=new LinkedHashMap<String,Object>();combatPasses.forEach((key,value)->values.put(key,value.snapshot()));
        return Map.of("measurement","Origin-frame eligibility; color resolve + Translucent queue + fullscreen composite, not total VFX cost",
                "passes",values,"submittedSamples",combatSubmitted,"pendingSamples",pendingCount(true),"discardedSamples",combatDiscarded,"skippedSamples",combatSkipped);
    }
    private long pendingCount(boolean combatOnly){long count=0;for(int i=0;i<pending.length;i++)if(pending[i]!=null&&i!=active&&(!combatOnly||measured[i]))count++;return count;}
    @Override public void close() {
        if(closed)return;
        if(active>=0)end(pending[active]);
        discarded+=pendingCount(false);combatDiscarded+=pendingCount(true);Arrays.fill(pending,null);
        for(int id:ids)queries.delete(id);closed=true;
    }
}
