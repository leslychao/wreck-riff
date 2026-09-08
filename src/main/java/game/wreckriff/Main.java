package game.wreckriff;

import com.jme3.system.AppSettings;
import game.wreckriff.app.GameApplication;
import game.wreckriff.config.*;
import java.nio.file.Path;

public final class Main {
    public record Options(boolean dev,long seed,boolean fixedSeed,boolean noAudio,boolean aiPlayer,int smokeSeconds,Path configDir) {
        public static Options parse(String[] args) {
            boolean dev=false,fixed=false,mute=false,ai=false; long seed=System.nanoTime(); int smoke=0; Path config=null;
            for(String arg:args) {
                if(arg.equals("--dev")) dev=true;
                else if(arg.startsWith("--seed=")) { seed=Long.parseLong(arg.substring(7)); fixed=true; }
                else if(arg.equals("--no-audio")) mute=true;
                else if(arg.equals("--ai-player")) ai=true;
                else if(arg.startsWith("--smoke-seconds=")) { smoke=Integer.parseInt(arg.substring(16)); if(smoke<1||smoke>3600) throw new IllegalArgumentException("Smoke duration must be 1..3600 seconds"); }
                else if(arg.startsWith("--config-dir=")) config=Path.of(arg.substring(13)).toAbsolutePath();
                else throw new IllegalArgumentException("Unknown option "+arg);
            }
            if(!dev && (fixed||mute||ai||smoke>0||config!=null)) throw new IllegalArgumentException("Diagnostic options require --dev");
            return new Options(dev,seed,fixed,mute,ai,smoke,config);
        }
    }
    public static void main(String[] args) {
        if(args.length==1&&args[0].equals("--help")) { System.out.println("Wreck Riff: --dev [--seed=<long>] [--no-audio] [--ai-player] [--smoke-seconds=1..3600] [--config-dir=<path>]"); return; }
        try {
            Options options=Options.parse(args);
            if(options.configDir()!=null) Configs.setDevOverride(options.configDir());
            SettingsStore store=new SettingsStore();
            NativeSetup.prepare(store.directory());
            var user=store.settings();
            AppSettings settings=new AppSettings(true);
            settings.setTitle("Wreck Riff | MVP 0.1.0"); settings.setResolution(user.width,user.height);
            settings.setFullscreen(user.fullscreen); settings.setVSync(user.vsync); settings.setResizable(true);
            settings.setGammaCorrection(true); settings.setSamples(0); settings.setFrameRate(0);
            if(options.noAudio()) settings.setAudioRenderer(null);
            GameApplication application=new GameApplication(options,store);
            application.setShowSettings(false); application.setSettings(settings); application.setPauseOnLostFocus(false);
            application.start();
        } catch(Exception e) { System.err.println("Wreck Riff could not start: "+e.getMessage()); e.printStackTrace(); System.exit(1); }
    }
}
