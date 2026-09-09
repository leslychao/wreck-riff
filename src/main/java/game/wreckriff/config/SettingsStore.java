package game.wreckriff.config;

import com.google.gson.*;
import com.jme3.input.KeyInput;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class SettingsStore {
    public static final class Settings {
        public int schemaVersion=4, width=1280,height=720, samples=4;
        public String selectedVehicleId="rivet";
        public boolean fullscreen=false,vsync=true,subtitles=true,glow=true;
        public float master=0.8f,music=0.8f,sfx=0.9f,shake=0.6f,sensitivity=1,deadZone=0.15f,uiScale=1,flashes=.6f;
        public Map<String,Integer> keys=defaultKeys();
        public Settings copy() { return Configs.gson().fromJson(Configs.gson().toJson(this),Settings.class); }
    }
    private final Path directory;
    private boolean settingsWritable=true;
    private final boolean firstRun;
    private int migratedFromVersion;
    private String warning="";
    private Settings settings;
    public SettingsStore() { this(defaultDirectory()); }
    public SettingsStore(Path directory) {
        this.directory=directory;
        firstRun=!Files.exists(directory.resolve("settings.json"));
        try { Files.createDirectories(directory); }
        catch(IOException | SecurityException e) { warn("Data directory unavailable; settings stay in memory."); settingsWritable=false; }
        settings=loadSettings();
        normalize(settings);
        if(migratedFromVersion>0 && settingsWritable) {
            try {
                Path original=directory.resolve("settings.json"), backup=directory.resolve("settings.json.v"+migratedFromVersion+".bak");
                if(!Files.exists(backup)) Files.copy(original,backup);
                writeSettings();
            } catch(IOException|SecurityException e) { warn("Settings migration stays in memory; original file preserved."); }
        }
    }
    public static Path defaultDirectory() {
        String local=System.getenv("LOCALAPPDATA");
        return local!=null&&!local.isBlank()?Path.of(local,"WreckRiff"):Path.of(System.getProperty("user.home"),".wreckriff");
    }
    public Path directory() { return directory; }
    public boolean firstRun() { return firstRun; }
    public Settings settings() { return settings; }
    public String warning() { return warning; }
    public String bindingWarning() {
        List<String> unbound=settings.keys.entrySet().stream().filter(e->e.getValue()==0).map(Map.Entry::getKey).toList();
        return unbound.isEmpty()?"":"Unbound actions: "+String.join(", ",unbound)+". Set a free key in Controls.";
    }
    private void warn(String text) { warning=text; System.err.println(text); }
    private Settings loadSettings() {
        String name="settings.json";Settings fallback=new Settings();
        Path path=directory.resolve(name);
        if (!Files.exists(path)) return fallback;
        try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            JsonObject tree=JsonParser.parseReader(reader).getAsJsonObject();
            int version=tree.has("schemaVersion")?tree.get("schemaVersion").getAsInt():-1;
            boolean supported=version>=1&&version<=4;
            if (!supported) {
                settingsWritable=false;
                warn("Unsupported "+name+" version; original file preserved."); return fallback;
            }
            if(version<4) {
                tree.addProperty("schemaVersion",4);
                if(version<3&&tree.has("keys")&&tree.get("keys").isJsonObject()) {
                    JsonObject keys=tree.getAsJsonObject("keys");
                    // v1 did not define zero as an unbound action; it was an invalid key.
                    if(version==1) keys.entrySet().removeIf(e->e.getValue().isJsonPrimitive()
                            && e.getValue().getAsJsonPrimitive().isNumber()&&e.getValue().getAsInt()==0);
                    JsonElement formerSpecial=keys.remove("Feedback Pulse");
                    keys.remove("Ability modifier");
                    if(formerSpecial!=null) keys.add("Shield",formerSpecial);
                }
                migratedFromVersion=version;
            }
            Settings loaded=Configs.gson().fromJson(tree,Settings.class);normalize(loaded);
            return loaded;
        } catch(IOException | RuntimeException e) {
            try { Files.move(path,path.resolveSibling(name+".broken-"+Instant.now().toEpochMilli())); }
            catch(IOException renameError) {
                settingsWritable=false;
            }
            warn("Damaged "+name+" preserved; defaults restored."); return fallback;
        }
    }
    public void saveSettings() { normalize(settings); if(settingsWritable) writeSettings(); }
    public void replaceSettings(Settings replacement) { settings=replacement; normalize(settings); saveSettings(); }
    public void useInMemory(Settings replacement) { settings=replacement; normalize(settings); }
    public void resetDefaults() { replaceSettings(new Settings()); }
    private void writeSettings() {
        String name="settings.json";
        Path path=directory.resolve(name),temporary=directory.resolve(name+".tmp"),backup=directory.resolve(name+".bak");
        try {
            byte[] bytes=Configs.gson().toJson(settings).getBytes(StandardCharsets.UTF_8);
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
        value.schemaVersion=4;
        try {value.selectedVehicleId=VehicleDefinition.forId(value.selectedVehicleId).id();}
        catch(IllegalArgumentException|NullPointerException e) {value.selectedVehicleId="rivet";}
        if(value.width<640 || value.width>7680 || value.height<480 || value.height>4320) { value.width=1280; value.height=720; value.fullscreen=false; }
        if(value.samples!=0&&value.samples!=2&&value.samples!=4&&value.samples!=8) value.samples=4;
        value.master=unit(value.master,0.8f); value.music=unit(value.music,0.8f); value.sfx=unit(value.sfx,0.9f); value.shake=unit(value.shake,0.6f);
        value.deadZone=Math.clamp(Float.isFinite(value.deadZone)?value.deadZone:0.15f,0,0.45f);
        value.sensitivity=Math.clamp(Float.isFinite(value.sensitivity)?value.sensitivity:1,0.25f,2);
        value.uiScale=Math.clamp(Float.isFinite(value.uiScale)?value.uiScale:1,.8f,1.5f);value.flashes=unit(value.flashes,.6f);
        Map<String,Integer> defaults=defaultKeys();
        Map<String,Integer> supplied=value.keys==null?Map.of():value.keys;
        LinkedHashMap<String,Integer> normalized=new LinkedHashMap<>();
        Set<Integer> used=new HashSet<>();
        // Explicit, valid user choices take precedence over every newly inserted default.
        for(var entry:supplied.entrySet()) {
            Integer key=entry.getValue();
            if(!defaults.containsKey(entry.getKey())||key==null||key<0||key>255||key==KeyInput.KEY_ESCAPE) continue;
            normalized.put(entry.getKey(),key==0||used.add(key)?key:0);
        }
        for(var entry:defaults.entrySet()) if(!normalized.containsKey(entry.getKey())) {
            int key=entry.getValue(); normalized.put(entry.getKey(),used.add(key)?key:0);
        }
        value.keys=new LinkedHashMap<>();
        for(String action:defaults.keySet()) value.keys.put(action,normalized.get(action));
    }
    private static float unit(float n,float fallback) { return Math.clamp(Float.isFinite(n)?n:fallback,0,1); }
    public static LinkedHashMap<String,Integer> defaultKeys() {
        LinkedHashMap<String,Integer> keys=new LinkedHashMap<>();
        keys.put("Throttle",KeyInput.KEY_W); keys.put("Brake / reverse",KeyInput.KEY_S);
        keys.put("Steer left",KeyInput.KEY_A); keys.put("Steer right",KeyInput.KEY_D);
        keys.put("Handbrake",KeyInput.KEY_SPACE); keys.put("Turbo",KeyInput.KEY_LSHIFT);
        keys.put("Previous weapon",KeyInput.KEY_Q); keys.put("Next weapon",KeyInput.KEY_E);
        keys.put("Shield",KeyInput.KEY_F); keys.put("Special",KeyInput.KEY_C); keys.put("Freeze",KeyInput.KEY_Z);
        keys.put("Select Homing",KeyInput.KEY_1); keys.put("Select Power",KeyInput.KEY_2);
        keys.put("Select Mine",KeyInput.KEY_3); keys.put("Select Napalm",KeyInput.KEY_4);
        keys.put("Select Ballistic",KeyInput.KEY_5); keys.put("Select Cannon",KeyInput.KEY_6);
        keys.put("Rear view",KeyInput.KEY_V); keys.put("Recover",KeyInput.KEY_R);
        return keys;
    }
}
