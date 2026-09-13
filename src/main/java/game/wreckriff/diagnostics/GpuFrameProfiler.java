package game.wreckriff.diagnostics;

import com.jme3.renderer.Renderer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;

/** Four asynchronous render queries; an occupied pool skips sampling instead of waiting for the GPU. */
final class GpuFrameProfiler implements AutoCloseable {
    interface Queries {
        int[] allocate(int count);
        boolean available(int id);
        long nanos(int id);
        void begin(int id);
        void end();
        void delete(int id);
    }
    private final Queries queries;
    private final int[] ids;
    private final boolean[] pending;
    private final boolean[] measured;
    private final FrameMetrics frames=new FrameMetrics();
    private final FrameMetrics combatFrames=new FrameMetrics();
    private int active=-1;
    private long skipped;
    private long submitted,combatSubmitted,combatSkipped,discarded,combatDiscarded;
    private boolean closed;
    GpuFrameProfiler(Queries queries) {this.queries=queries;ids=queries.allocate(4);pending=new boolean[ids.length];measured=new boolean[ids.length];}
    static GpuFrameProfiler create(Renderer renderer) {
        var capabilities=GL.getCapabilities();
        if(!capabilities.OpenGL33&&!capabilities.GL_ARB_timer_query)return null;
        return new GpuFrameProfiler(new Queries() {
            public int[] allocate(int count) {return renderer.generateProfilingTasks(count);}
            public boolean available(int id) {return renderer.isTaskResultAvailable(id);}
            public long nanos(int id) {return renderer.getProfilingTime(id);}
            public void begin(int id) {renderer.startProfiling(id);}
            public void end() {renderer.stopProfiling();}
            public void delete(int id) {GL15.glDeleteQueries(id);}
        });
    }
    void begin(boolean measuredCombat) {
        if(closed||active>=0)return;
        for(int i=0;i<ids.length;i++)if(pending[i]&&queries.available(ids[i])) {
            long nanos=queries.nanos(ids[i]);pending[i]=false;
            if(nanos>=0){frames.add(nanos/1_000_000_000.0);if(measured[i])combatFrames.add(nanos/1_000_000_000.0);}
        }
        for(int i=0;i<ids.length;i++)if(!pending[i]) {active=i;measured[i]=measuredCombat;submitted++;if(measuredCombat)combatSubmitted++;queries.begin(ids[i]);return;}
        skipped++;if(measuredCombat)combatSkipped++;
    }
    void end() {if(active>=0){queries.end();pending[active]=true;active=-1;}}
    Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();result.put("status","SUPPORTED");result.put("queryCapacity",ids.length);
        result.put("measurement","GPU elapsed time for jME RenderFrame through EndFrame; asynchronous available results only");
        result.put("scope","Whole diagnostic run, including loading, warmup, pause and Results");
        result.put("skippedSamples",skipped);result.put("submittedSamples",submitted);result.put("pendingSamples",pendingCount(false));result.put("discardedSamples",discarded);
        result.put("frames",frames.snapshot());return result;
    }
    Map<String,Object> measuredCombatSnapshot() {
        return Map.of("measurement","All jME GPU render passes from RenderFrame to EndFrame; origin-frame eligibility retained through asynchronous readback",
                "frames",combatFrames.snapshot(),"submittedSamples",combatSubmitted,"pendingSamples",pendingCount(true),"discardedSamples",combatDiscarded,"skippedSamples",combatSkipped);
    }
    private long pendingCount(boolean combatOnly){long count=0;for(int i=0;i<ids.length;i++)if(pending[i]&&(!combatOnly||measured[i]))count++;return count;}
    @Override public void close() {
        if(closed)return;end();discarded+=pendingCount(false);combatDiscarded+=pendingCount(true);java.util.Arrays.fill(pending,false);for(int id:ids)queries.delete(id);closed=true;
    }
}
