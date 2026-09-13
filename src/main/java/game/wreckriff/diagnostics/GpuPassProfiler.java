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
    private final Map<String,FrameMetrics> passes=new LinkedHashMap<>();
    private int active=-1;
    private long skipped;
    private boolean closed;
    GpuPassProfiler(Queries queries){this.queries=queries;ids=queries.allocate(16);}
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
            if(end>=start)passes.get(pending[i]).add((end-start)/1_000_000_000.0);
            pending[i]=null;
        }
        for(int i=0;i<pending.length;i++)if(pending[i]==null){active=i;pending[i]=pass;queries.timestamp(ids[i*2]);return;}
        skipped++;
    }
    @Override public void end(String pass) {
        if(active<0)return;
        if(!pending[active].equals(pass))throw new IllegalStateException("Unbalanced GPU pass probe");
        queries.timestamp(ids[active*2+1]);active=-1;
    }
    public Map<String,Object> snapshot() {
        var values=new LinkedHashMap<String,Object>();passes.forEach((key,value)->values.put(key,value.snapshot()));
        return Map.of("status","SUPPORTED","measurement","Asynchronous GL timestamp pairs; scene color resolve and VFX draw included",
                "queryPairs",8,"skippedSamples",skipped,"passes",values);
    }
    @Override public void close() {
        if(closed)return;
        if(active>=0)end(pending[active]);
        for(int id:ids)queries.delete(id);closed=true;
    }
}
