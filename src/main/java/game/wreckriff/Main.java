package game.wreckriff;

import com.jme3.system.AppSettings;
import game.wreckriff.app.GameApplication;
import game.wreckriff.config.*;
import java.nio.file.Path;

public final class Main {
    public record Options(boolean dev,long seed,boolean fixedSeed,boolean noAudio,boolean aiPlayer,int smokeSeconds,Path configDir,int benchmarkSeconds,boolean showcase,
                          String arenaId,boolean artShowcase,int resolutionHeight,boolean noGlow,boolean profile,boolean vehicleShowcase) {
        public boolean automated() { return smokeSeconds>0 || benchmarkSeconds>0 || showcase || artShowcase || vehicleShowcase; }
        public static Options parse(String[] args) {
            boolean dev=false,fixed=false,mute=false,ai=false,showcase=false,art=false,noGlow=false,arenaSpecified=false,profile=false,vehicles=false;
            long seed=System.nanoTime();int smoke=0,benchmark=0,resolution=0;Path config=null;String arena="dead-air-yard";
            for(String arg:args) {
                if(arg.equals("--dev")) dev=true;
                else if(arg.startsWith("--seed=")) { seed=Long.parseLong(arg.substring(7)); fixed=true; }
                else if(arg.equals("--no-audio")) mute=true;
                else if(arg.equals("--ai-player")) ai=true;
                else if(arg.equals("--showcase")) showcase=true;
                else if(arg.equals("--art-showcase")) art=true;
                else if(arg.equals("--vehicle-showcase"))vehicles=true;
                else if(arg.equals("--no-glow")) noGlow=true;
                else if(arg.equals("--profile"))profile=true;
                else if(arg.startsWith("--arena=")) {
                    arena=arg.substring(8);arenaSpecified=true;
                    if(!arena.matches("[a-z][a-z0-9_-]*"))throw new IllegalArgumentException("Arena must be a stable arena ID");
                }
                else if(arg.startsWith("--resolution="))resolution=switch(arg.substring(13)) {
                    case "720p" -> 720;case "1080p" -> 1080;
                    default -> throw new IllegalArgumentException("Diagnostic resolution must be 720p or 1080p");
                };
                else if(arg.startsWith("--smoke-seconds=")) { smoke=Integer.parseInt(arg.substring(16)); if(smoke<1||smoke>3600) throw new IllegalArgumentException("Smoke duration must be 1..3600 seconds"); }
                else if(arg.startsWith("--config-dir=")) config=Path.of(arg.substring(13)).toAbsolutePath();
                else if(arg.startsWith("--benchmark-seconds=")) { benchmark=Integer.parseInt(arg.substring(20)); if(benchmark<1||benchmark>3600) throw new IllegalArgumentException("Benchmark duration must be 1..3600 seconds after 30s warmup"); }
                else throw new IllegalArgumentException("Unknown option "+arg);
            }
            if(!dev && (fixed||mute||ai||smoke>0||benchmark>0||showcase||art||vehicles||arenaSpecified||resolution>0||noGlow||profile||config!=null)) throw new IllegalArgumentException("Diagnostic options require --dev");
            if((smoke>0?1:0)+(benchmark>0?1:0)+(showcase?1:0)+(art?1:0)+(vehicles?1:0)>1) throw new IllegalArgumentException("Choose one diagnostic mode");
            if(showcase&&!arena.equals("dead-air-yard"))throw new IllegalArgumentException("Combat showcase uses dead-air-yard; use --art-showcase for other arenas");
            if(vehicles&&!arena.equals("dead-air-yard"))throw new IllegalArgumentException("Vehicle showcase uses dead-air-yard");
            return new Options(dev,seed,fixed,mute,ai,smoke,config,benchmark,showcase,arena,art,resolution,noGlow,profile,vehicles);
        }
    }
    public static void main(String[] args) {
        if(args.length==1&&args[0].equals("--help")) { System.out.println("Wreck Riff: --dev [--seed=<long>] [--no-audio] [--ai-player] [--arena=<stable-id>] [--resolution=720p|1080p] [--no-glow] [--profile] [--smoke-seconds=1..3600 | --benchmark-seconds=1..3600 | --showcase | --art-showcase | --vehicle-showcase] [--config-dir=<path>]"); return; }
        try {
            Options options=Options.parse(args);
            if(options.configDir()!=null) Configs.setDevOverride(options.configDir());
            SettingsStore store=new SettingsStore();
            if(options.automated()) store=new SettingsStore(store.directory().resolve("diagnostics").resolve("run-"+java.util.UUID.randomUUID()));
            NativeSetup.prepare(store.directory());
            var user=store.settings();
            if(store.firstRun() && !options.automated()) InitialVideo.apply(user);
            // Fullscreen prevents the desktop work area/title bar from silently
            // shrinking the requested 1080p client surface on a 1080p monitor.
            if(options.benchmarkSeconds()>0) { user.width=1920;user.height=1080;user.samples=4;user.vsync=false;user.fullscreen=true; }
            if(options.showcase()) { user.width=1600;user.height=900;user.vsync=false;user.fullscreen=false; }
            if(options.artShowcase()||options.vehicleShowcase()) {user.width=1280;user.height=720;user.vsync=false;user.fullscreen=false;}
            if(options.resolutionHeight()>0) {
                user.height=options.resolutionHeight();user.width=user.height*16/9;
                user.fullscreen=user.height==1080;user.vsync=false;
            }
            if(options.noGlow())user.glow=false;
            AppSettings settings=new AppSettings(true);
            settings.setTitle("Wreck Riff | 0.4.0"); settings.setResolution(user.width,user.height);
            settings.setFullscreen(user.fullscreen); settings.setVSync(user.vsync); settings.setResizable(true);
            settings.setGammaCorrection(true); settings.setSamples(user.samples); settings.setFrameRate(0);
            if(options.noAudio()) settings.setAudioRenderer(null);
            GameApplication application=new GameApplication(options,store);
            application.setShowSettings(false); application.setSettings(settings); application.setPauseOnLostFocus(false);
            application.start();
            if(options.automated() && !application.awaitDiagnostic()) System.exit(1);
        } catch(Exception e) { System.err.println("Wreck Riff could not start: "+e.getMessage()); System.err.println("Use --help for available launch options."); e.printStackTrace(); System.exit(1); }
    }
}
