package game.wreckriff.config;

import com.google.gson.*;
import com.jme3.input.KeyInput;
import game.wreckriff.simulation.MatchSession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class SettingsStore {
    public static final class Settings {
        public int schemaVersion=1, width=1280,height=720;
        public boolean fullscreen=false,vsync=true;
        public float master=0.8f,music=0.8f,sfx=0.9f,shake=0.6f,sensitivity=1,deadZone=0.15f;
        public Map<String,Integer> keys=defaultKeys();
        public Settings copy() { return Configs.gson().fromJson(Configs.gson().toJson(this),Settings.class); }
    }
    public static final class Stats {
        public int schemaVersion=1;
        public long completedMatches,wins,losses,draws,totalEliminations;
        public double totalDamage;
    }
    private final Path directory;
    private boolean settingsWritable=true,statsWritable=true;
    private final Set<UUID> recorded=new HashSet<>();
    private String warning="";
    private Settings settings;
    private Stats stats;
    public SettingsStore() { this(defaultDirectory()); }
    public SettingsStore(Path directory) {
        this.directory=directory;
        try { Files.createDirectories(directory); }
        catch(IOException | SecurityException e) { warn("Data directory unavailable; settings stay in memory."); settingsWritable=false; statsWritable=false; }
        settings=load("settings.json",Settings.class,new Settings());
        stats=load("stats.json",Stats.class,new Stats());
        normalize(settings);
    }
    public static Path defaultDirectory() {
        String local=System.getenv("LOCALAPPDATA");
        return local!=null&&!local.isBlank()?Path.of(local,"WreckRiff"):Path.of(System.getProperty("user.home"),".wreckriff");
    }
    public Path directory() { return directory; }
    public Settings settings() { return settings; }
    public Stats stats() { return stats; }
    public String warning() { return warning; }
    private void warn(String text) { warning=text; System.err.println(text); }
    private <T> T load(String name,Class<T> type,T fallback) {
        Path path=directory.resolve(name);
        if (!Files.exists(path)) return fallback;
        try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            JsonObject tree=JsonParser.parseReader(reader).getAsJsonObject();
            if (!tree.has("schemaVersion") || tree.get("schemaVersion").getAsInt()!=1) {
                if (name.equals("settings.json")) settingsWritable=false; else statsWritable=false;
                warn("Unsupported "+name+" version; original file preserved."); return fallback;
            }
            T loaded=Configs.gson().fromJson(tree,type);
            if(loaded instanceof Settings user) normalize(user);
            if(loaded instanceof Stats statistics) {
                if(!Double.isFinite(statistics.totalDamage) || statistics.totalDamage<0 || statistics.completedMatches<0
                        || statistics.wins<0 || statistics.losses<0 || statistics.draws<0 || statistics.totalEliminations<0
                        || statistics.wins+statistics.losses+statistics.draws!=statistics.completedMatches) {
                    throw new IllegalArgumentException("Invalid local statistics");
                }
            }
            return loaded;
        } catch(IOException | RuntimeException e) {
            try { Files.move(path,path.resolveSibling(name+".broken-"+Instant.now().toEpochMilli())); }
            catch(IOException renameError) {
                if (name.equals("settings.json")) settingsWritable=false; else statsWritable=false;
            }
            warn("Damaged "+name+" preserved; defaults restored."); return fallback;
        }
    }
    public void saveSettings() { normalize(settings); if(settingsWritable) write("settings.json",settings); }
    public void replaceSettings(Settings replacement) { settings=replacement; normalize(settings); saveSettings(); }
    public void useInMemory(Settings replacement) { settings=replacement; normalize(settings); }
    public void resetDefaults() { replaceSettings(new Settings()); }
    public void record(MatchSession session) {
        if(session.outcome==MatchSession.Outcome.NONE || !recorded.add(session.sessionId)) return;
        stats.completedMatches++;
        switch(session.outcome) { case VICTORY->stats.wins++; case DEFEAT->stats.losses++; case DRAW->stats.draws++; default->{} }
        stats.totalDamage+=session.vehicle(0).damageDealt; stats.totalEliminations+=session.vehicle(0).eliminations;
        if(statsWritable) write("stats.json",stats);
    }
    private void write(String name,Object value) {
        Path path=directory.resolve(name),temporary=directory.resolve(name+".tmp"),backup=directory.resolve(name+".bak");
        try {
            byte[] bytes=Configs.gson().toJson(value).getBytes(StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var data=java.nio.ByteBuffer.wrap(bytes); while(data.hasRemaining()) channel.write(data); channel.force(true);
            }
            try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) {
                if(Files.exists(path)) Files.copy(path,backup,StandardCopyOption.REPLACE_EXISTING);
                Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(IOException | SecurityException e) { warn("Could not save "+name+"; this session continues in memory."); }
    }
    private static void normalize(Settings value) {
        if(value.width<640 || value.width>7680 || value.height<480 || value.height>4320) { value.width=1280; value.height=720; value.fullscreen=false; }
        value.master=unit(value.master,0.8f); value.music=unit(value.music,0.8f); value.sfx=unit(value.sfx,0.9f); value.shake=unit(value.shake,0.6f);
        value.deadZone=Math.clamp(Float.isFinite(value.deadZone)?value.deadZone:0.15f,0,0.45f);
        value.sensitivity=Math.clamp(Float.isFinite(value.sensitivity)?value.sensitivity:1,0.25f,2);
        if(value.keys==null) value.keys=defaultKeys();
        for(var entry:defaultKeys().entrySet()) value.keys.putIfAbsent(entry.getKey(),entry.getValue());
        value.keys.entrySet().removeIf(e->!defaultKeys().containsKey(e.getKey()) || e.getValue()==null || e.getValue()<1 || e.getValue()>255 || e.getValue()==KeyInput.KEY_ESCAPE);
        for(var entry:defaultKeys().entrySet()) value.keys.putIfAbsent(entry.getKey(),entry.getValue());
        if(new HashSet<>(value.keys.values()).size()!=value.keys.size()) throw new IllegalArgumentException("Conflicting keyboard bindings");
    }
    private static float unit(float n,float fallback) { return Math.clamp(Float.isFinite(n)?n:fallback,0,1); }
    public static LinkedHashMap<String,Integer> defaultKeys() {
        LinkedHashMap<String,Integer> keys=new LinkedHashMap<>();
        keys.put("Throttle",KeyInput.KEY_W); keys.put("Brake / reverse",KeyInput.KEY_S);
        keys.put("Steer left",KeyInput.KEY_A); keys.put("Steer right",KeyInput.KEY_D);
        keys.put("Handbrake",KeyInput.KEY_SPACE); keys.put("Turbo",KeyInput.KEY_LSHIFT);
        keys.put("Previous weapon",KeyInput.KEY_Q); keys.put("Next weapon",KeyInput.KEY_E);
        keys.put("Feedback Pulse",KeyInput.KEY_F); keys.put("Rear view",KeyInput.KEY_V); keys.put("Recover",KeyInput.KEY_R);
        return keys;
    }
}
