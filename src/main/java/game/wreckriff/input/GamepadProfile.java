package game.wreckriff.input;

import game.wreckriff.config.Configs;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;

/** GLFW's standard Xbox-compatible logical mapping; per-device verification remains explicit. */
public record GamepadProfile(int schemaVersion,int steerAxis,int throttleAxis,int brakeAxis,
        float triggerMinimum,float triggerMaximum,int handbrake,int turbo,int machineGun,int rocket,
        int pulse,int previousWeapon,int nextWeapon,int rearView,int recover,int pause,int up,int down,int back) {
    public GamepadProfile {
        if(schemaVersion!=1 || triggerMinimum>=triggerMaximum) throw new IllegalArgumentException("Invalid gamepad profile");
        for(int axis:new int[]{steerAxis,throttleAxis,brakeAxis}) if(axis<0||axis>5) throw new IllegalArgumentException("Invalid gamepad axis");
        for(int button:new int[]{handbrake,turbo,machineGun,rocket,pulse,previousWeapon,nextWeapon,rearView,recover,pause,up,down,back}) if(button<0||button>14) throw new IllegalArgumentException("Invalid gamepad button");
    }
    public static GamepadProfile load(Path directory) {
        GamepadProfile bundled=Configs.load("gamepad",GamepadProfile.class);
        Path file=directory.resolve("gamepad.json");
        if(!Files.exists(file)) {
            try { Files.writeString(file,Configs.gson().toJson(bundled),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); }
            catch(IOException|SecurityException ignored) { System.err.println("Gamepad profile uses bundled defaults; user directory unavailable."); }
            return bundled;
        }
        try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            var tree=JsonParser.parseReader(reader); Configs.validate(tree,GamepadProfile.class,"gamepad");
            return Configs.gson().fromJson(tree,GamepadProfile.class);
        } catch(IOException|RuntimeException e) { System.err.println("Invalid gamepad.json; preserved original, using bundled defaults: "+e.getMessage()); return bundled; }
    }
    public static GamepadProfile bundled() { return Configs.load("gamepad",GamepadProfile.class); }
    public float trigger(float value) { return Math.clamp((value-triggerMinimum)/(triggerMaximum-triggerMinimum),0,1); }
}
