package game.wreckriff.diagnostics;

import game.wreckriff.config.BuildInfo;
import game.wreckriff.config.Configs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded observations of the ordinary launch; no automatic gameplay or acceptance verdict. */
public final class LaunchReport {
    private final Path output;
    private final Map<String,Object> data=new LinkedHashMap<>();
    private final List<Map<String,Object>> sessions=new ArrayList<>();
    private final List<String> errors=new ArrayList<>();
    private long renderedFrames,sessionStarts;
    private double undrawableSeconds;

    public LaunchReport(Path directory,boolean checkpointPresent) {
        output=directory.resolve("launch-summary.json");
        var build=BuildInfo.current();
        data.put("schemaVersion",2);data.put("mode","normal");data.put("dev",false);
        data.put("launchId",UUID.randomUUID());data.put("pid",ProcessHandle.current().pid());
        data.put("version",build.version());data.put("sourceSha256",build.sourceSha256());
        data.put("javaHome",System.getProperty("java.home"));data.put("jdk",System.getProperty("java.runtime.version"));
        data.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        data.put("startedAtUtc",Instant.now().toString());data.put("initialCheckpointPresent",checkpointPresent);
        data.put("status","STARTED");data.put("shutdownComplete",false);data.put("progressFlushed",false);
    }
    public synchronized void started(int width,int height,boolean visible,boolean audio) throws IOException {
        data.put("width",width);data.put("height",height);data.put("windowVisible",visible);data.put("audioEnabled",audio);
        write();System.out.println("DIAGNOSTIC_REPORT: "+output.toAbsolutePath());
    }
    public synchronized void frame(boolean drawable,double seconds) {
        if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid frame duration");
        if(drawable)renderedFrames++;else undrawableSeconds+=seconds;
    }
    public synchronized void sessionStarted(String arenaId,String mode,String profileId,boolean resumedCheckpoint) {
        sessionStarts++;
        if(sessions.size()<64)sessions.add(Map.of("arenaId",arenaId,"mode",mode,"profileId",profileId,"resumedCheckpoint",resumedCheckpoint));
    }
    public synchronized void error(String error) {if(errors.size()<32)errors.add(Objects.requireNonNull(error));}
    public synchronized void closed(boolean shutdownComplete,boolean progressFlushed) throws IOException {
        data.put("closedAtUtc",Instant.now().toString());data.put("shutdownComplete",shutdownComplete);data.put("progressFlushed",progressFlushed);
        data.put("status",shutdownComplete&&progressFlushed&&errors.isEmpty()?"CLOSED":"FAILED");write();
    }
    private void write() throws IOException {
        data.put("renderedFrames",renderedFrames);data.put("undrawableSeconds",undrawableSeconds);
        data.put("sessionStarts",List.copyOf(sessions));data.put("sessionStartCount",sessionStarts);data.put("errors",List.copyOf(errors));
        Files.createDirectories(output.getParent());
        Path temporary=output.resolveSibling(output.getFileName()+".tmp");
        Files.writeString(temporary,Configs.gson().toJson(data),StandardCharsets.UTF_8);
        try {Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException exception){Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);}
    }
}
