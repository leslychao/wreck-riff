package game.wreckriff.diagnostics;

import game.wreckriff.config.Configs;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Evidence for real-window smoke/retry and benchmark runs, independent of game rules. */
public final class DiagnosticEvidence {
    public final FrameMetrics frames=new FrameMetrics();
    private final Map<String,Object> data=new LinkedHashMap<>();
    private final List<Map<String,Object>> transitions=new ArrayList<>(),cycles=new ArrayList<>();
    private final List<String> errors=new ArrayList<>();
    public DiagnosticEvidence(boolean benchmark,int duration) {
        data.put("schemaVersion",1);data.put("mode",benchmark?"benchmark":"graphics-smoke");
        data.put("pid",ProcessHandle.current().pid());data.put("requestedSeconds",duration);
        data.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        data.put("cpu",Objects.toString(System.getenv("PROCESSOR_IDENTIFIER"),"unavailable"));
        data.put("logicalProcessors",Runtime.getRuntime().availableProcessors());
        data.put("jdk",System.getProperty("java.runtime.version"));
        data.put("javaHome",System.getProperty("java.home"));
        Properties build=new Properties();
        try(InputStream in=getClass().getResourceAsStream("/build-info.properties")){if(in!=null)build.load(in);}
        catch(IOException e){errors.add("Build info: "+e.getMessage());}
        data.put("version",build.getProperty("version","unknown"));data.put("commit",build.getProperty("commit","unknown"));
        data.put("sourceSha256",build.getProperty("sourceSha256","unknown"));
        data.put("transitions",transitions);data.put("retryCycles",cycles);data.put("errors",errors);
        data.put("hardwareController","PENDING_MANUAL");data.put("feelApproval","PENDING_MANUAL");
    }
    public synchronized void put(String key,Object value) { data.put(key,value); }
    public synchronized void transition(String state,double at) { if(transitions.size()<1000)transitions.add(Map.of("screen",state,"elapsedSeconds",at)); }
    public synchronized void cycle(int cycle,int bodies,int listeners,int projectiles,int voices) {
        Map<String,Object> sample=new LinkedHashMap<>();
        sample.put("cycle",cycle);sample.put("bodies",bodies);sample.put("physicsListeners",listeners);
        sample.put("projectiles",projectiles);sample.put("voices",voices);
        sample.put("nativePhysicsTrackers",com.jme3.bullet.NativePhysicsObject.countTrackers());
        sample.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        sample.put("directBufferBytes",ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream().mapToLong(BufferPoolMXBean::getMemoryUsed).sum());
        cycles.add(sample);
    }
    public synchronized void error(String error) { if(errors.size()<100)errors.add(error); }
    public synchronized void write(Path directory,String status) throws IOException {
        data.put("status",status);data.put("activeCombatFrames",frames.snapshot());
        Files.createDirectories(directory);
        Path output=directory.resolve("diagnostic-result.json");
        Files.writeString(output,Configs.gson().toJson(data),StandardCharsets.UTF_8);
        System.out.println("DIAGNOSTIC_REPORT: "+output.toAbsolutePath());
    }
}
