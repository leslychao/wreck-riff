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
    public enum Stage {AI,BULLET,COMBAT,SIMULATION,HUD,PRESENTATION,AUDIO}
    private final EnumMap<Stage,FrameMetrics> stages=new EnumMap<>(Stage.class);
    private final EnumMap<AppStep,FrameMetrics> engine=new EnumMap<>(AppStep.class);
    private final LongSupplier clock;
    private AppStep previousStep;
    private long previousTime;
    private Recording recording;
    private Path recordingPath;
    private String[] renderLabels;
    private int[] renderValues,maximumRenderValues;
    private GpuFrameProfiler gpu;
    private String gpuUnavailable="No graphical context attached";
    public StageProfiler() {this(System::nanoTime);}
    StageProfiler(LongSupplier clock) {this.clock=clock;}
    public void attachGpu(com.jme3.renderer.Renderer renderer) {
        if(gpu!=null)throw new IllegalStateException("GPU profiler already attached");
        gpu=GpuFrameProfiler.create(renderer);gpuUnavailable="GL_ARB_timer_query / OpenGL 3.3 unavailable";
    }
    public void detachGpuForVideoRestart() {
        if(gpu!=null){gpu.close();gpu=null;}
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
    }
    @Override public void appStep(AppStep step) {
        if(gpu!=null) {if(step==AppStep.RenderFrame)gpu.begin();else if(step==AppStep.EndFrame)gpu.end();}
        long now=clock.getAsLong();
        if(previousStep!=null&&previousStep!=AppStep.EndFrame&&now>=previousTime)
            engine.computeIfAbsent(previousStep,ignored->new FrameMetrics()).add((now-previousTime)/1_000_000_000.0);
        previousStep=step;previousTime=now;
    }
    @Override public void appSubStep(String... steps) { }
    @Override public void vpStep(VpStep step,ViewPort viewport,RenderQueue.Bucket bucket) { }
    @Override public void spStep(SpStep step,String... information) { }
    public void renderStatistics(com.jme3.renderer.Statistics statistics) {
        if(renderLabels==null){renderLabels=statistics.getLabels();renderValues=new int[renderLabels.length];maximumRenderValues=new int[renderLabels.length];}
        statistics.getData(renderValues);for(int i=0;i<renderValues.length;i++)maximumRenderValues[i]=Math.max(maximumRenderValues[i],renderValues[i]);
    }
    public Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();var application=new LinkedHashMap<String,Object>();var jme=new LinkedHashMap<String,Object>();
        stages.forEach((stage,metrics)->application.put(stage.name(),metrics.snapshot()));
        engine.forEach((step,metrics)->jme.put(step.name(),metrics.snapshot()));
        result.put("cpuStages",application);result.put("jmeAppSteps",jme);
        result.put("measurement","CPU elapsed intervals; nested app stages are not additive and do not measure GPU execution");
        result.put("gpuTiming",gpu==null?Map.of("status","UNAVAILABLE","reason",gpuUnavailable):gpu.snapshot());
        result.put("jfrPath",recordingPath==null?"":recordingPath.toAbsolutePath().toString());result.put("jfrMaximumBytes",128L*1024*1024);
        var render=new LinkedHashMap<String,Integer>();if(renderLabels!=null)for(int i=0;i<renderLabels.length;i++)render.put(renderLabels[i],maximumRenderValues[i]);
        result.put("maximumRendererStatistics",render);
        return result;
    }
    @Override public void close() {
        if(gpu!=null)gpu.close();
        if(recording!=null){recording.stop();recording.close();recording=null;}
    }
}
