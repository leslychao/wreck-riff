package game.wreckriff.diagnostics;

import game.wreckriff.config.Configs;
import game.wreckriff.simulation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded frame samples and reproducible context, with load time kept separate. */
public final class SessionReport {
    private final double[] frameTimes=new double[120000];
    private int count;
    private long totalFrames;
    public void frame(float seconds) { frameTimes[count++%frameTimes.length]=seconds*1000; totalFrames++; }
    public void write(Path output,MatchSession session,SimulationLoop loop,String gpu,int width,int height,boolean vsync,boolean audio,int voices,int bodies) throws IOException {
        Map<String,Object> data=new LinkedHashMap<>();
        Properties build=new Properties();
        try(InputStream input=SessionReport.class.getResourceAsStream("/build-info.properties")) { if(input!=null) build.load(input); }
        data.put("version",build.getProperty("version","unknown")); data.put("commit",build.getProperty("commit","unknown")); data.put("seed",session.seed);
        data.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        data.put("jdk",System.getProperty("java.runtime.version")); data.put("gpu",gpu);
        data.put("width",width); data.put("height",height); data.put("vsync",vsync); data.put("audioEnabled",audio);
        data.put("ticks",session.tick); data.put("seconds",session.seconds()); data.put("outcome",session.outcome);
        data.put("droppedSimulationTime",loop.droppedSimulationTime()); data.put("activeBodies",bodies); data.put("voices",voices);
        data.put("participants",session.vehicles); data.put("frames",totalFrames);
        double[] sorted=Arrays.copyOf(frameTimes,Math.min(count,frameTimes.length)); Arrays.sort(sorted);
        if(sorted.length>0) { data.put("medianFrameMs",percentile(sorted,0.5)); data.put("p95FrameMs",percentile(sorted,0.95)); data.put("p99FrameMs",percentile(sorted,0.99)); data.put("maxFrameMs",sorted[sorted.length-1]); }
        Runtime runtime=Runtime.getRuntime(); data.put("javaHeapUsedBytes",runtime.totalMemory()-runtime.freeMemory());
        Map<String,String> hashes=new LinkedHashMap<>();
        for(String name:List.of("vehicle","camera","match","combat","arena","ai","audio")) {
            try(InputStream input=SessionReport.class.getResourceAsStream("/config/"+name+".json")) {
                if(input!=null) hashes.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
        data.put("configHashes",hashes);
        Files.createDirectories(output.getParent()); Files.writeString(output,Configs.gson().toJson(data),StandardCharsets.UTF_8);
    }
    private static double percentile(double[] sorted,double fraction) { return sorted[Math.min(sorted.length-1,(int)Math.ceil(sorted.length*fraction)-1)]; }
}
