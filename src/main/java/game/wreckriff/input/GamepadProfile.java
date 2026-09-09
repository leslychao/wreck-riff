package game.wreckriff.input;

import game.wreckriff.config.Configs;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.util.*;

/** GLFW's standard Xbox-compatible logical mapping; per-device verification remains explicit. */
public record GamepadProfile(int schemaVersion,int steerAxis,int throttleAxis,int brakeAxis,
        float triggerMinimum,float triggerMaximum,int handbrake,int turbo,int machineGun,int rocket,
        int shield,int previousWeapon,int nextWeapon,int rearView,int recover,int pause,int up,int down,int back,
        int freeze,int activate,int special) {
    public GamepadProfile {
        if(schemaVersion!=4 || !Float.isFinite(triggerMinimum) || !Float.isFinite(triggerMaximum)
                || triggerMinimum>=triggerMaximum) throw new IllegalArgumentException("Invalid gamepad profile");
        for(int axis:new int[]{steerAxis,throttleAxis,brakeAxis}) if(axis<0||axis>5) throw new IllegalArgumentException("Invalid gamepad axis");
        for(int button:new int[]{handbrake,turbo,machineGun,rocket,previousWeapon,nextWeapon,rearView,recover,pause,up,down,back,activate}) if(button<0||button>14) throw new IllegalArgumentException("Invalid gamepad button");
        if(shield < -1 || shield>14 || freeze < -1 || freeze>14 || special < -1 || special>14) throw new IllegalArgumentException("Invalid ability button");
        Set<Integer> occupied=new HashSet<>();
        for(int button:new int[]{handbrake,turbo,machineGun,rocket,previousWeapon,nextWeapon,rearView,recover,pause}) occupied.add(button);
        if(shield>=0&&!occupied.add(shield)) shield=-1;
        if(freeze>=0&&!occupied.add(freeze)) freeze=-1;
        if(special>=0&&!occupied.add(special)) special=-1;
    }
    public static GamepadProfile load(Path directory) {
        GamepadProfile bundled=Configs.load("gamepad",GamepadProfile.class);
        Path file=directory.resolve("gamepad.json");
        if(!Files.exists(file)) {
            try { Files.writeString(file,Configs.gson().toJson(bundled),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); }
            catch(IOException|SecurityException ignored) { System.err.println("Gamepad profile uses bundled defaults; user directory unavailable."); }
            return bundled;
        }
        try {
            JsonObject tree;
            try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { tree=JsonParser.parseReader(reader).getAsJsonObject(); }
            int version=tree.has("schemaVersion")?tree.get("schemaVersion").getAsInt():-1;
            boolean migrate=version>=1&&version<=3;
            if(version==1||version==2) {
                // Validate the original shape before removing fields; migration must not hide typos.
                Set<String> originalFields=new HashSet<>(List.of("schemaVersion","steerAxis","throttleAxis","brakeAxis",
                        "triggerMinimum","triggerMaximum","handbrake","turbo","machineGun","rocket","pulse",
                        "previousWeapon","nextWeapon","rearView","recover","pause","up","down","back"));
                if(version==2) originalFields.add("abilityModifier");
                if(!tree.keySet().equals(originalFields)) throw new IllegalArgumentException("Invalid legacy gamepad fields");
                var formerSpecial=tree.remove("pulse");
                tree.remove("abilityModifier");
                tree.addProperty("schemaVersion",3);
                tree.add("shield",formerSpecial);
                tree.add("activate",formerSpecial.deepCopy());
                tree.add("freeze",tree.get("up").deepCopy());
            }
            if(migrate) {
                Set<String> previousFields=new HashSet<>();
                for(var component:GamepadProfile.class.getRecordComponents())if(!component.getName().equals("special"))previousFields.add(component.getName());
                if(!tree.keySet().equals(previousFields))throw new IllegalArgumentException("Invalid version-three gamepad fields");
                tree.addProperty("schemaVersion",4);
                tree.addProperty("special",13);
            }
            Configs.validate(tree,GamepadProfile.class,"gamepad");
            GamepadProfile loaded=Configs.gson().fromJson(tree,GamepadProfile.class);
            if(migrate) {
                try {
                    Path backup=file.resolveSibling("gamepad.json.v"+version+".bak"),temporary=file.resolveSibling("gamepad.json.tmp");
                    if(!Files.exists(backup)) Files.copy(file,backup);
                    Files.writeString(temporary,Configs.gson().toJson(loaded),StandardCharsets.UTF_8);
                    try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                    catch(AtomicMoveNotSupportedException e) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
                } catch(IOException|SecurityException e) { System.err.println("Gamepad migration stays in memory; original profile preserved."); }
            }
            return loaded;
        } catch(IOException|RuntimeException e) { System.err.println("Invalid gamepad.json; preserved original, using bundled defaults: "+e.getMessage()); return bundled; }
    }
    public static GamepadProfile bundled() { return Configs.load("gamepad",GamepadProfile.class); }
    public String warning() {
        List<String> unbound=new ArrayList<>();
        if(shield<0) unbound.add("Shield");if(freeze<0) unbound.add("Freeze");if(special<0)unbound.add("Special");
        return unbound.isEmpty()?"":"Unbound gamepad actions: "+String.join(", ",unbound)+". Set a free button in gamepad.json.";
    }
    public float trigger(float value) {
        return (float)Math.clamp(((double)value-triggerMinimum)/((double)triggerMaximum-triggerMinimum),0d,1d);
    }
}
