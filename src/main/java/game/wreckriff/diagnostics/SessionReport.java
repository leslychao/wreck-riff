package game.wreckriff.diagnostics;

import game.wreckriff.config.Configs;
import game.wreckriff.simulation.*;
import game.wreckriff.ai.BotController;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded frame samples and reproducible context, with load time kept separate. */
public final class SessionReport {
    private final FrameMetrics frames=new FrameMetrics();
    private final PhaseMetrics phaseMetrics=new PhaseMetrics();
    private final List<String> warnings=Collections.synchronizedList(new ArrayList<>());
    private long firstSight=-1,firstShot=-1,firstDamage=-1;
    private List<BotController.Metrics> aiMetrics=List.of();
    private double loadingSeconds;
    public void frame(FrameSample sample) {phaseMetrics.add(sample);if(sample.drawable()&&sample.phase().combat())frames.add(sample.seconds());}
    public void loadSeconds(double seconds) { loadingSeconds=seconds; }
    public void warning(String warning) { if(warnings.size()<100)warnings.add(warning); }
    public void tick(MatchSession session,List<GameEvent> events,BotController bots) {
        if(firstSight<0&&!bots.observation(0).visible().isEmpty())firstSight=Math.max(0,session.tick-1);
        for(var event:events) {
            if(firstShot<0&&event.type()==GameEvent.Type.SHOT)firstShot=Math.max(0,session.tick-1);
            if(firstDamage<0&&event.type()==GameEvent.Type.DAMAGE&&event.sourceId()>=0&&event.sourceId()!=event.subjectId())firstDamage=Math.max(0,session.tick-1);
        }
        aiMetrics=session.vehicles.stream().filter(v->!v.player).map(v->bots.metrics(v.id)).toList();
    }
    public void write(Path output,MatchSession session,SimulationLoop loop,String gpu,int width,int height,boolean vsync,boolean audio,int voices,int bodies) throws IOException {
        Map<String,Object> data=new LinkedHashMap<>();
        Properties build=new Properties();
        try(InputStream input=SessionReport.class.getResourceAsStream("/build-info.properties")) { if(input!=null) build.load(input); }
        data.put("version",build.getProperty("version","unknown")); data.put("commit",build.getProperty("commit","unknown")); data.put("seed",session.seed);
        data.put("sourceSha256",build.getProperty("sourceSha256","unknown"));
        data.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        data.put("jdk",System.getProperty("java.runtime.version")); data.put("gpu",gpu);
        data.put("width",width); data.put("height",height); data.put("vsync",vsync); data.put("audioEnabled",audio);
        data.put("ticks",session.tick); data.put("seconds",session.seconds()); data.put("outcome",session.outcome);
        data.put("loadingSeconds",loadingSeconds);data.put("firstPlayerSightTick",firstSight);
        data.put("firstShotTick",firstShot);data.put("firstExternalDamageTick",firstDamage);
        data.put("aiMetrics",aiMetrics);data.put("warnings",List.copyOf(warnings));
        data.put("droppedSimulationTime",loop.droppedSimulationTime()); data.put("activeBodies",bodies); data.put("voices",voices);
        data.put("participants",session.vehicles); data.putAll(frames.snapshot());
        data.put("phaseMetrics",phaseMetrics.snapshot());
        Runtime runtime=Runtime.getRuntime(); data.put("javaHeapUsedBytes",runtime.totalMemory()-runtime.freeMemory());
        Map<String,String> hashes=new LinkedHashMap<>();
        for(String name:List.of("vehicle","camera","match","combat","arena","ai","audio")) {
            try(Reader input=Configs.open(name)) {
                StringWriter json=new StringWriter();input.transferTo(json);
                hashes.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.toString().getBytes(StandardCharsets.UTF_8))));
            } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
        data.put("configHashes",hashes);
        Files.createDirectories(output.getParent()); Files.writeString(output,Configs.gson().toJson(data),StandardCharsets.UTF_8);
    }
}
