package game.wreckriff.diagnostics;

import java.util.ArrayList;
import java.util.List;

/** Release performance contract. Short runs remain useful evidence but can never pass this gate. */
public final class BenchmarkGate {
    public static final int WARMUP_ACTIVE_SECONDS=30,MINIMUM_MEASURED_ACTIVE_SECONDS=600;
    public static final long MAXIMUM_PROCESS_MEMORY_BYTES=1536L*1024*1024;
    private BenchmarkGate() {}
    public record Environment(int width,int height,int msaaSamples,boolean vsync,boolean audioEnabled,
                              boolean visibleWindow,boolean detailedProfiling,long peakWorkingSetBytes) {}
    public record Verdict(boolean releaseEligible,boolean passed,List<String> failures) {
        public Verdict {failures=List.copyOf(failures);}
    }
    public static Verdict evaluate(double warmedActiveSeconds,FrameMetrics measured,double droppedSeconds,Environment environment) {
        var failures=new ArrayList<String>();
        if(warmedActiveSeconds<WARMUP_ACTIVE_SECONDS)failures.add("Less than 30 actual active combat seconds warmed up");
        if(measured.seconds()<MINIMUM_MEASURED_ACTIVE_SECONDS)failures.add("Less than 600 measured active combat seconds");
        if(environment.width!=1920||environment.height!=1080)failures.add("Framebuffer must be 1920x1080");
        if(environment.msaaSamples!=4)failures.add("MSAA must be exactly 4 samples");
        if(environment.vsync)failures.add("VSync must be off");
        if(!environment.audioEnabled)failures.add("Audio device must be enabled");
        if(!environment.visibleWindow)failures.add("A real visible window is required");
        if(environment.detailedProfiling)failures.add("Detailed profiling must be off for the final benchmark");
        boolean eligible=failures.isEmpty();
        if(!measured.withinTarget())failures.add("Active frame thresholds exceeded or no samples (p95 16.7ms, p99 25ms, no frames over 100ms)");
        if(!Double.isFinite(droppedSeconds)||droppedSeconds>0)failures.add("Simulation time was dropped");
        if(environment.peakWorkingSetBytes<=0)failures.add("Process working-set memory evidence is unavailable");
        else if(environment.peakWorkingSetBytes>MAXIMUM_PROCESS_MEMORY_BYTES)failures.add("Process working set exceeds 1.5 GiB");
        return new Verdict(eligible,failures.isEmpty(),failures);
    }
}
