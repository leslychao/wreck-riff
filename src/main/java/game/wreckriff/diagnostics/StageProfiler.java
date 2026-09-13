package game.wreckriff.diagnostics;

import com.jme3.profile.AppProfiler;
import com.jme3.profile.AppStep;
import com.jme3.profile.SpStep;
import com.jme3.profile.VpStep;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Opt-in jME CPU intervals and bounded stock JDK Flight Recorder. Never enabled by final benchmark defaults. */
public final class StageProfiler implements AppProfiler,AutoCloseable {
    public enum Stage {AI,BULLET,COMBAT,SIMULATION,HUD,PRESENTATION,CONTACT_PRESENTATION,AUDIO}
    private final EnumMap<Stage,FrameMetrics> stages=new EnumMap<>(Stage.class);
    private final EnumMap<AppStep,FrameMetrics> engine=new EnumMap<>(AppStep.class);
    private final EnumMap<Stage,FrameMetrics> combatStages=new EnumMap<>(Stage.class);
    private final EnumMap<AppStep,FrameMetrics> combatEngine=new EnumMap<>(AppStep.class);
    private final FrameMetrics visualUpdateCpuUpperBound=new FrameMetrics();
    private final long[] frameStages=new long[Stage.values().length],frameEngine=new long[AppStep.values().length];
    private boolean benchmark,measuredCombat;
    private long frameId=-1,measuredFrames,firstMeasuredFrame=-1,lastMeasuredFrame=-1;
    private final LongSupplier clock;
    private AppStep previousStep;
    private long previousTime;
    private Recording recording;
    private Path recordingPath;
    private String[] renderLabels;
    private int[] renderValues,maximumRenderValues;
    private com.jme3.renderer.Statistics rendererStatistics;
    private long renderSamples,renderSamplesWithGeometry,disabledRenderSamples;
    private GpuFrameProfiler gpu;
    private GpuPassProfiler vfxGpu;
    private String gpuUnavailable="No graphical context attached";
    public StageProfiler() {this(System::nanoTime);}
    StageProfiler(LongSupplier clock) {this.clock=clock;}
    public void attachGpu(com.jme3.renderer.Renderer renderer) {
        if(gpu!=null)throw new IllegalStateException("GPU profiler already attached");
        attachRendererStatistics(renderer.getStatistics());
        gpu=GpuFrameProfiler.create(renderer);gpuUnavailable="GL_ARB_timer_query / OpenGL 3.3 unavailable";
        vfxGpu=GpuPassProfiler.create();
    }
    public game.wreckriff.presentation.CombatVfxFilter.Probe vfxProbe(){return vfxGpu;}
    void attachRendererStatistics(com.jme3.renderer.Statistics statistics) {
        rendererStatistics=statistics;statistics.setEnabled(true);
    }
    public void detachGpuForVideoRestart() {
        if(gpu!=null){gpu.close();gpu=null;}
        if(vfxGpu!=null){vfxGpu.close();vfxGpu=null;}
        gpuUnavailable="Video context restarted; start a new diagnostic run to capture GPU timings";
    }
    public void startRecording(Path directory) throws IOException,ParseException {
        if(recording!=null)throw new IllegalStateException("Recording already started");
        Files.createDirectories(directory);recordingPath=directory.resolve("profile.jfr");
        recording=new Recording(Configuration.getConfiguration("profile"));
        recording.setName("Wreck Riff diagnostic");recording.setToDisk(true);
        recording.setMaxSize(128L*1024*1024);recording.setMaxAge(Duration.ofMinutes(20));
        recording.setDestination(recordingPath);recording.setDumpOnExit(true);recording.start();
    }
    public void record(Stage stage,long nanos) {
        if(nanos<0)throw new IllegalArgumentException("Negative CPU duration");
        stages.computeIfAbsent(stage,ignored->new FrameMetrics()).add(nanos/1_000_000_000.0);
        frameStages[stage.ordinal()]+=nanos;
    }
    /** Called after final phase classification and before RenderFrame; CPU work earlier in this frame is retained. */
    public void measuredCombatFrame(long id,boolean eligible,boolean benchmark) {
        if(id<0||eligible&&!benchmark)throw new IllegalArgumentException("Invalid measured-combat frame classification");
        frameId=id;this.benchmark=benchmark;measuredCombat=eligible;if(vfxGpu!=null)vfxGpu.measuredCombatFrame(eligible);
    }
    @Override public void appStep(AppStep step) {
        if(step==AppStep.BeginFrame){java.util.Arrays.fill(frameStages,0);java.util.Arrays.fill(frameEngine,0);measuredCombat=false;frameId=-1;if(vfxGpu!=null)vfxGpu.measuredCombatFrame(false);}
        // StatsAppState initialises after simpleInitApp and disables counters when its overlay
        // is hidden. A diagnostic owns collection independently of that UI preference.
        if(step==AppStep.RenderFrame&&rendererStatistics!=null)rendererStatistics.setEnabled(true);
        if(gpu!=null) {if(step==AppStep.RenderFrame)gpu.begin(measuredCombat);else if(step==AppStep.EndFrame)gpu.end();}
        long now=clock.getAsLong();
        if(previousStep!=null&&previousStep!=AppStep.EndFrame&&now>=previousTime) {
            engine.computeIfAbsent(previousStep,ignored->new FrameMetrics()).add((now-previousTime)/1_000_000_000.0);
            frameEngine[previousStep.ordinal()]+=now-previousTime;
        }
        previousStep=step;previousTime=now;
        if(step==AppStep.EndFrame&&measuredCombat) {
            measuredFrames++;if(firstMeasuredFrame<0)firstMeasuredFrame=frameId;lastMeasuredFrame=frameId;
            for(Stage stage:Stage.values())combatStages.computeIfAbsent(stage,ignored->new FrameMetrics()).add(frameStages[stage.ordinal()]/1_000_000_000.0);
            for(AppStep engineStep:AppStep.values())if(engineStep!=AppStep.EndFrame)combatEngine.computeIfAbsent(engineStep,ignored->new FrameMetrics()).add(frameEngine[engineStep.ordinal()]/1_000_000_000.0);
            // jME 3.8.1 ends simpleUpdate before SpatialUpdate; StateManagerRender follows scene updates.
            // These intervals are disjoint. Aggregate actual frame costs, never independently computed percentiles.
            long visualUpdateNanos=frameStages[Stage.PRESENTATION.ordinal()]+frameStages[Stage.CONTACT_PRESENTATION.ordinal()]+frameEngine[AppStep.SpatialUpdate.ordinal()];
            visualUpdateCpuUpperBound.add(visualUpdateNanos/1_000_000_000.0);
            measuredCombat=false;
        }
    }
    @Override public void appSubStep(String... steps) { }
    @Override public void vpStep(VpStep step,ViewPort viewport,RenderQueue.Bucket bucket) { }
    @Override public void spStep(SpStep step,String... information) { }
    public void renderStatistics(com.jme3.renderer.Statistics statistics) {
        renderSamples++;
        if(!statistics.isEnabled()){disabledRenderSamples++;return;}
        if(renderLabels==null){renderLabels=statistics.getLabels();renderValues=new int[renderLabels.length];maximumRenderValues=new int[renderLabels.length];}
        statistics.getData(renderValues);for(int i=0;i<renderValues.length;i++)maximumRenderValues[i]=Math.max(maximumRenderValues[i],renderValues[i]);
        if(renderValues.length>1&&renderValues[0]>0&&renderValues[1]>0)renderSamplesWithGeometry++;
    }
    public Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();var application=new LinkedHashMap<String,Object>();var jme=new LinkedHashMap<String,Object>();
        stages.forEach((stage,metrics)->application.put(stage.name(),metrics.snapshot()));
        engine.forEach((step,metrics)->jme.put(step.name(),metrics.snapshot()));
        result.put("cpuStages",application);result.put("jmeAppSteps",jme);
        result.put("measurement","CPU elapsed intervals; nested app stages are not additive and do not measure GPU execution");
        result.put("scope","Whole diagnostic run; legacy CPU stage samples are per invocation, including loading, warmup, pause and Results");
        result.put("gpuTiming",gpu==null?Map.of("status","UNAVAILABLE","reason",gpuUnavailable):gpu.snapshot());
        result.put("vfxGpuTiming",vfxGpu==null?Map.of("status","UNAVAILABLE","reason",gpuUnavailable):vfxGpu.snapshot());
        result.put("jfrPath",recordingPath==null?"":recordingPath.toAbsolutePath().toString());result.put("jfrMaximumBytes",128L*1024*1024);
        var render=new LinkedHashMap<String,Integer>();if(renderLabels!=null)for(int i=0;i<renderLabels.length;i++)render.put(renderLabels[i],maximumRenderValues[i]);
        result.put("maximumRendererStatistics",render);
        result.put("rendererStatisticsCollection",Map.of("status",renderSamplesWithGeometry>0?"VALID":"NO_RENDERED_GEOMETRY",
                "sampledFrames",renderSamples,"framesWithGeometry",renderSamplesWithGeometry,"disabledFrames",disabledRenderSamples));
        var active=new LinkedHashMap<String,Object>();var activeStages=new LinkedHashMap<String,Object>();var activeEngine=new LinkedHashMap<String,Object>();
        combatStages.forEach((stage,metrics)->activeStages.put(stage.name(),metrics.snapshot()));combatEngine.forEach((step,metrics)->activeEngine.put(step.name(),metrics.snapshot()));
        active.put("status",!benchmark?"NOT_APPLICABLE":measuredFrames==0?"NO_ELIGIBLE_FRAMES":"MEASURED");
        active.put("scope","Benchmark only: drawable ARENA_COMBAT/BOSS_COMBAT frames after 30 complete active warmup seconds; same DiagnosticEvidence gate. Smoke and soak excluded.");
        active.put("measurement","CPU stages aggregate all calls in each eligible render frame, including zero-call frames; nested CPU stages are not additive. PRESENTATION covers renderMatch (including HUD/audio/camera); CONTACT_PRESENTATION covers fixed-tick contact refinement and VFX acceptance. Both exclude jME scene update and render submission, shown separately in jmeAppSteps.");
        active.put("frames",measuredFrames);active.put("firstFrame",firstMeasuredFrame);active.put("lastFrame",lastMeasuredFrame);active.put("cpuStages",activeStages);active.put("jmeAppSteps",activeEngine);
        var visualUpdate=visualUpdateCpuUpperBound.snapshot();
        visualUpdate.put("measurement","Per-render-frame sum of disjoint PRESENTATION + CONTACT_PRESENTATION + jME 3.8.1 SpatialUpdate intervals before histogram insertion. Conservative CPU visual-update bound including HUD/audio/camera and all root/gui logical/geometric scene updates. Excludes draw submission, which is reported separately in jmeAppSteps; this is not the CPU cost of new graphics alone.");
        active.put("visualUpdateCpuUpperBound",visualUpdate);
        active.put("gpuTiming",gpu==null?Map.of("status","UNAVAILABLE","reason",gpuUnavailable):gpu.measuredCombatSnapshot());
        active.put("vfxGpuTiming",vfxGpu==null?Map.of("status","UNAVAILABLE","reason",gpuUnavailable):vfxGpu.measuredCombatSnapshot());
        result.put("measuredCombat",active);
        return result;
    }
    @Override public void close() {
        if(gpu!=null)gpu.close();
        if(vfxGpu!=null)vfxGpu.close();
        if(recording!=null){recording.stop();recording.close();recording=null;}
    }
}
