package game.wreckriff.diagnostics;

import game.wreckriff.config.Configs;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Evidence for real-window smoke/retry and benchmark runs, independent of game rules. */
public final class DiagnosticEvidence {
    private final FrameMetrics frames=new FrameMetrics();
    private final PhaseMetrics phaseMetrics=new PhaseMetrics();
    private final boolean benchmark;
    private SoakSchedule soak;
    private ResourceRetention resources;
    private double warmupActiveSeconds;
    private boolean invalidWindowObserved;
    private double measuredArenaSeconds,measuredBossSeconds;
    private int measuredMaximumEffects,measuredMaximumLaunching;
    private final Map<String,Object> data=new LinkedHashMap<>();
    private final List<Map<String,Object>> transitions=new ArrayList<>(),cycles=new ArrayList<>();
    private final List<String> errors=new ArrayList<>();
    public DiagnosticEvidence(boolean benchmark,int duration) {
        this.benchmark=benchmark;
        data.put("schemaVersion",2);data.put("mode",benchmark?"benchmark":"graphics-smoke");
        data.put("pid",ProcessHandle.current().pid());data.put("requestedSeconds",duration);
        data.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        data.put("cpu",Objects.toString(System.getenv("PROCESSOR_IDENTIFIER"),"unavailable"));
        data.put("logicalProcessors",Runtime.getRuntime().availableProcessors());
        data.put("jdk",System.getProperty("java.runtime.version"));
        data.put("javaHome",System.getProperty("java.home"));
        var build=game.wreckriff.config.BuildInfo.current();
        data.put("version",build.version());data.put("commit",build.commit());
        data.put("sourceSha256",build.sourceSha256());
        data.put("transitions",transitions);data.put("retryCycles",cycles);data.put("errors",errors);
        data.put("hardwareController","PENDING_MANUAL");data.put("feelApproval","PENDING_MANUAL");
    }
    public synchronized void put(String key,Object value) { data.put(key,value); }
    public void configureSoak(SoakSchedule schedule,ResourceRetention retention) {
        if(benchmark)throw new IllegalStateException("Benchmark and soak cannot share a run");
        soak=Objects.requireNonNull(schedule);resources=Objects.requireNonNull(retention);data.put("mode","soak");
        data.put("progressFixture","Fresh isolated profile with all arenas/duels unlocked; zero initial statistics. Only real subsequent results are recorded.");
    }
    public void observeWindow(int width,int height,boolean visible) {
        if(benchmark&&(width!=1920||height!=1080||!visible))invalidWindowObserved=true;
    }
    public synchronized void frame(FrameSample sample) {
        phaseMetrics.add(sample);
        if(soak!=null)soak.frame(sample);
        if(!benchmark||!sample.drawable()||!sample.phase().combat())return;
        // The boundary frame belongs wholly to warmup: no slow first frame can be partly discarded.
        if(warmupActiveSeconds<BenchmarkGate.WARMUP_ACTIVE_SECONDS)warmupActiveSeconds+=sample.seconds();
        else {
            frames.add(sample.seconds());
            if(sample.phase()==FrameSample.Phase.ARENA_COMBAT)measuredArenaSeconds+=sample.seconds();else measuredBossSeconds+=sample.seconds();
            measuredMaximumEffects=Math.max(measuredMaximumEffects,sample.effects());
            measuredMaximumLaunching=Math.max(measuredMaximumLaunching,sample.launchingVehicles());
        }
    }
    public double measuredActiveSeconds() {return frames.seconds();}
    public double warmedActiveSeconds() {return warmupActiveSeconds;}
    public boolean renderTargetMet() {return frames.withinTarget();}
    public synchronized Map<String,Object> snapshot(String status) {
        var result=new LinkedHashMap<String,Object>(data);result.put("status",status);
        result.put("phaseMetrics",phaseMetrics.snapshot());result.put("activeCombatFrames",frames.snapshot());
        if(soak!=null) {
            result.put("soakCoverage",soak.evidence());result.put("resourceSnapshots",resources.samples());result.put("resourceChecks",resources.evidence());
        }
        if(benchmark) {
            var environment=new BenchmarkGate.Environment(number("width").intValue(),number("height").intValue(),number("msaaSamples").intValue(),
                    Boolean.TRUE.equals(data.get("vsync")),Boolean.TRUE.equals(data.get("audioEnabled")),
                    Boolean.TRUE.equals(data.get("windowVisible"))&&!invalidWindowObserved&&number("undrawableSeconds").doubleValue()==0,
                    Boolean.TRUE.equals(data.get("detailedProfiling")),number("peakWorkingSetBytes").longValue());
            var verdict=BenchmarkGate.evaluate(warmupActiveSeconds,frames,phaseMetrics.droppedSimulationSeconds(),environment);
            result.put("warmupSeconds",BenchmarkGate.WARMUP_ACTIVE_SECONDS);result.put("warmupActiveSeconds",warmupActiveSeconds);
            result.put("measuredActiveSeconds",frames.seconds());result.put("releaseEligible",verdict.releaseEligible());
            result.put("renderTargetMet",frames.withinTarget());result.put("releaseGate",verdict);
            result.put("invalidBenchmarkWindowObserved",invalidWindowObserved);
            result.put("measuredCoverage",Map.of("arenaCombatSeconds",measuredArenaSeconds,"bossCombatSeconds",measuredBossSeconds,
                    "maximumEffects",measuredMaximumEffects,"maximumLaunchingVehicles",measuredMaximumLaunching));
            result.put("memoryEvidence","External Windows process working-set counter; heap is not an equivalent");
        } else result.put("releaseEligible",false);
        return result;
    }
    private Number number(String key) {return data.get(key) instanceof Number number?number:0;}
    public String completionStatus(boolean completed) {
        if(!completed||!errors.isEmpty())return "FAIL";
        if(soak!=null)return soak.coverageComplete()&&resources.passed()?"SOAK_MEASURED":"DIAGNOSTIC_COMPLETE";
        if(!benchmark)return "PASS";
        return measuredActiveSeconds()>=BenchmarkGate.MINIMUM_MEASURED_ACTIVE_SECONDS?"BENCHMARK_MEASURED":"DIAGNOSTIC_COMPLETE";
    }
    public synchronized void transition(String state,double at) { if(transitions.size()<1000)transitions.add(Map.of("screen",state,"elapsedSeconds",at)); }
    public synchronized void cycle(int cycle,int bodies,int listeners,int projectiles,int voices) {
        Map<String,Object> sample=new LinkedHashMap<>();
        sample.put("cycle",cycle);sample.put("bodies",bodies);sample.put("physicsListeners",listeners);
        sample.put("projectiles",projectiles);sample.put("voices",voices);
        sample.put("nativePhysicsTrackers",com.jme3.bullet.NativePhysicsObject.countTrackers());
        sample.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        sample.put("directBufferBytes",ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream().mapToLong(BufferPoolMXBean::getMemoryUsed).sum());
        if(cycles.size()<1000)cycles.add(sample);
    }
    public synchronized void error(String error) { if(errors.size()<100)errors.add(error); }
    public synchronized void write(Path directory,String status) throws IOException {
        Files.createDirectories(directory);
        Path output=directory.resolve("diagnostic-result.json");
        Files.writeString(output,Configs.gson().toJson(snapshot(status)),StandardCharsets.UTF_8);
        System.out.println("DIAGNOSTIC_REPORT: "+output.toAbsolutePath());
    }
}
