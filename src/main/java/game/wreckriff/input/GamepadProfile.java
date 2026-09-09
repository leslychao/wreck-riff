package game.wreckriff.input;

import game.wreckriff.config.Configs;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.util.Set;

/** GLFW's standard Xbox-compatible logical mapping; per-device verification remains explicit. */
public record GamepadProfile(int schemaVersion,int steerAxis,int throttleAxis,int brakeAxis,
        float triggerMinimum,float triggerMaximum,int handbrake,int turbo,int machineGun,int rocket,
        int pulse,int previousWeapon,int nextWeapon,int rearView,int recover,int pause,int up,int down,int back,
        int abilityModifier) {
    public GamepadProfile {
        if(schemaVersion!=2 || !Float.isFinite(triggerMinimum) || !Float.isFinite(triggerMaximum)
                || triggerMinimum>=triggerMaximum) throw new IllegalArgumentException("Invalid gamepad profile");
        for(int axis:new int[]{steerAxis,throttleAxis,brakeAxis}) if(axis<0||axis>5) throw new IllegalArgumentException("Invalid gamepad axis");
        for(int button:new int[]{handbrake,turbo,machineGun,rocket,pulse,previousWeapon,nextWeapon,rearView,recover,pause,up,down,back}) if(button<0||button>14) throw new IllegalArgumentException("Invalid gamepad button");
        if(abilityModifier < -1 || abilityModifier>14) throw new IllegalArgumentException("Invalid ability modifier");
        for(int button:new int[]{handbrake,turbo,machineGun,rocket,pulse,previousWeapon,nextWeapon,rearView,recover,pause,up,down,back})
            if(button==abilityModifier) abilityModifier=-1;
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
            boolean migrate=tree.has("schemaVersion") && tree.get("schemaVersion").getAsInt()==1;
            if(migrate) {
                tree.addProperty("schemaVersion",2);
                if(tree.has("abilityModifier")) throw new IllegalArgumentException("Unexpected v1 abilityModifier");
                tree.addProperty("abilityModifier",10);
            }
            Configs.validate(tree,GamepadProfile.class,"gamepad");
            GamepadProfile loaded=Configs.gson().fromJson(tree,GamepadProfile.class);
            if(migrate) {
                try {
                    Path backup=file.resolveSibling("gamepad.json.v1.bak"),temporary=file.resolveSibling("gamepad.json.tmp");
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
    public String warning() { return abilityModifier<0?"Ability modifier unbound: choose a free button in gamepad.json.":""; }
    public float trigger(float value) {
        return (float)Math.clamp(((double)value-triggerMinimum)/((double)triggerMaximum-triggerMinimum),0d,1d);
    }
}
