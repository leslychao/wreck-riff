package game.wreckriff.diagnostics;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.system.JmeSystem;
import game.wreckriff.Main;
import game.wreckriff.app.GameApplication;
import game.wreckriff.app.ScreenFlow;
import game.wreckriff.audio.AudioDirector;
import game.wreckriff.audio.DesktopAudioSystem;
import game.wreckriff.config.BuildInfo;
import game.wreckriff.config.NativeSetup;
import game.wreckriff.config.SettingsStore;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.ui.MenuView;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Normal application, normal UI commands, and post-shutdown proof of the pause-menu exit. */
public final class NativePauseExitReview {
    private static final int RUN_TIMEOUT_SECONDS=170,STOP_GRACE_SECONDS=8;
    private NativePauseExitReview() {}

    public static void main(String[] args) throws Exception {
        if(args.length!=2||!args[1].matches("(?i)[0-9a-f]{64}"))
            throw new IllegalArgumentException("Usage: fresh-output-directory expected-image-source-sha256");
        Path output=Path.of(args[0]).toAbsolutePath().normalize(),profile=output.resolve("profile");
        if(Files.exists(profile)||Files.exists(output.resolve("review.json")))
            throw new IllegalArgumentException("Use a fresh output directory; existing profiles and evidence are preserved");
        Files.createDirectories(output);
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("schemaVersion",1);evidence.put("startedAtUtc",Instant.now().toString());
        evidence.put("expectedSourceSha256",args[1]);evidence.put("ownerAcceptance","NOT_GRANTED");
        evidence.put("purpose","Normal-window pause, cancel, and actual application exit; no artistic acceptance");
        GameApplication app=null;Driver driver=null;Throwable failure=null;
        long started=System.nanoTime();
        try {
            var build=BuildInfo.current();evidence.put("sourceSha256",build.sourceSha256());
            require(args[1].equalsIgnoreCase(build.sourceSha256()),"Running image differs from expected source hash");
            JmeSystem.setSystemDelegate(new DesktopAudioSystem());
            var store=new SettingsStore(profile);NativeSetup.prepare(profile);
            var user=store.settings();user.width=1280;user.height=720;user.fullscreen=false;
            user.samples=4;user.vsync=false;user.uiScale=1;store.saveSettings();
            var options=Main.Options.parse(new String[0]);
            require(!options.dev()&&!options.automated()&&!options.noAudio(),"Ordinary launch options required");
            app=new GameApplication(options,store);
            var settings=new AppSettings(true);settings.setTitle("Wreck Riff / Native pause exit review");
            settings.setResolution(1280,720);settings.setFullscreen(false);settings.setResizable(false);
            settings.setVSync(false);settings.setFrameRate(60);settings.setSamples(4);settings.setGammaCorrection(true);
            app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);
            driver=new Driver(app);Driver attached=driver;GameApplication running=app;
            app.start(JmeContext.Type.Display);
            app.enqueue(()->{running.getStateManager().attach(attached);return null;});
            while(secondsSince(started)<RUN_TIMEOUT_SECONDS) {
                if(driver.failure.get()!=null)throw new IllegalStateException("Native UI scenario failed",driver.failure.get());
                if(driver.initialized&&!app.getContext().isCreated())break;
                Thread.sleep(100);
            }
            require(driver.phase==Phase.EXIT_REQUESTED,"Scenario did not reach the real exit button: "+driver.phase);
            require(!app.getContext().isCreated(),"Application context did not close before the timeout");
            require(!app.getContext().isRenderable(),"Closed application still exposes a renderable context");
            evidence.putAll(driver.snapshot());
            Path launchPath=profile.resolve("launch-summary.json");
            require(Files.isRegularFile(launchPath),"Normal launch-summary.json was not produced");
            JsonObject launch=JsonParser.parseString(Files.readString(launchPath)).getAsJsonObject();
            require("normal".equals(launch.get("mode").getAsString())&&!launch.get("dev").getAsBoolean(),"Run used diagnostic launch mode");
            require("CLOSED".equals(launch.get("status").getAsString()),"Normal launch did not finish CLOSED");
            require(launch.get("shutdownComplete").getAsBoolean(),"Normal shutdown did not finish");
            require(launch.get("progressFlushed").getAsBoolean(),"Progress was not flushed by normal shutdown");
            require(launch.getAsJsonArray("errors").isEmpty(),"Normal launch recorded errors: "+launch.get("errors"));
            require(launch.get("sessionStartCount").getAsInt()==1,"Cancel or confirmation restarted the match");
            require(launch.get("windowVisible").getAsBoolean()&&launch.get("audioEnabled").getAsBoolean(),"Real visible window with audio required");
            require(launch.get("renderedFrames").getAsLong()>0,"The real renderer produced no frames");
            require(args[1].equalsIgnoreCase(launch.get("sourceSha256").getAsString()),"Launch evidence has another source identity");
            List<String> captures=verifyCaptures(profile.resolve("captures"),driver.capturePrefixes);
            evidence.put("captures",captures);evidence.put("launchSummary","profile/launch-summary.json");
            evidence.put("shutdownComplete",true);evidence.put("progressFlushed",true);evidence.put("status","PASS");
        } catch(Throwable problem) {
            failure=problem;evidence.put("status","FAIL");evidence.put("failure",problem.toString());problem.printStackTrace();
            if(app!=null) {
                app.stop();long stopping=System.nanoTime();
                while(app.getContext()!=null&&app.getContext().isCreated()&&secondsSince(stopping)<STOP_GRACE_SECONDS)Thread.sleep(100);
            }
            if(driver!=null)evidence.putAll(driver.snapshot());
        }
        boolean contextAlive=app!=null&&app.getContext()!=null&&app.getContext().isCreated();
        evidence.put("contextAliveAfterShutdown",contextAlive);evidence.put("closedAtUtc",Instant.now().toString());
        evidence.put("elapsedSeconds",secondsSince(started));evidence.put("timeoutSeconds",RUN_TIMEOUT_SECONDS+STOP_GRACE_SECONDS);
        Files.writeString(output.resolve("review.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence)+"\n",StandardCharsets.UTF_8);
        System.out.println("NATIVE_PAUSE_EXIT "+evidence.get("status")+": "+output.resolve("review.json"));
        // This standalone verifier terminates only after checking the application's own shutdown.
        // On a timeout it cannot turn a surviving native context into a passing result.
        System.exit(failure==null&&!contextAlive?0:2);
    }

    private static List<String> verifyCaptures(Path directory,List<String> prefixes) throws Exception {
        List<Path> files;
        try(var entries=Files.list(directory)){files=entries.filter(path->path.getFileName().toString().endsWith(".png")).toList();}
        var captures=new ArrayList<String>();
        for(String prefix:prefixes) {
            Path capture=files.stream().filter(path->path.getFileName().toString().startsWith(prefix)).findFirst()
                    .orElseThrow(()->new IllegalStateException("Missing rendered capture: "+prefix));
            var image=javax.imageio.ImageIO.read(capture.toFile());
            require(image!=null&&image.getWidth()==1280&&image.getHeight()==720,"Capture is not a real 1280x720 frame: "+capture);
            captures.add("profile/captures/"+capture.getFileName());
        }
        require(captures.size()==3,"Pause, confirmation, and cancelled-pause captures required");return List.copyOf(captures);
    }
    private static double secondsSince(long start){return (System.nanoTime()-start)/1_000_000_000.0;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private enum Phase { MENU, VEHICLES, COMBAT, PAUSED, CONFIRMATION, CANCELLED, SECOND_CONFIRMATION, EXIT_REQUESTED }

    /** Reflection is confined to this diagnostic; production navigation and state remain the owners. */
    private static final class Driver extends BaseAppState {
        private final GameApplication app;
        private final AtomicReference<Throwable> failure=new AtomicReference<>();
        private final Map<String,Object> evidence=new LinkedHashMap<>();
        private final List<String> capturePrefixes=new ArrayList<>();
        private final List<Map<String,Object>> actions=new ArrayList<>();
        private volatile boolean initialized;
        private volatile Phase phase=Phase.MENU;
        private long phaseSince,pausedTick;
        private MatchSession pausedSession;
        private Object pausedWorld;
        private float pausedMusic,maxMusicDrift;
        private int pausedFrames;
        private boolean captured;
        Driver(GameApplication app){this.app=app;}
        @Override protected void initialize(Application application) {
            try {
                require(app.getContext().getType()==JmeContext.Type.Display&&app.getAudioRenderer()!=null,"Real window and real audio renderer required");
                long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                require(window!=0&&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0,"Window is not visible");
                require(app.getCamera().getWidth()==1280&&app.getCamera().getHeight()==720,"Client surface must be 1280x720");
                synchronized(evidence) {
                    evidence.put("context",app.getContext().getType().name());evidence.put("audioEnabled",true);
                    evidence.put("resolution",List.of(1280,720));evidence.put("windowVisible",true);
                    evidence.put("renderer",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
                }
                phaseSince=System.nanoTime();initialized=true;
            } catch(Throwable problem){fail(problem);}
        }
        @Override public void update(float dt) {
            if(failure.get()!=null||phase==Phase.EXIT_REQUESTED)return;
            try {
                ScreenFlow flow=field("flow",ScreenFlow.class);
                require(flow.screen()!=ScreenFlow.Screen.ERROR,"Game entered its error screen");
                if(pausedSession!=null)verifyPaused();
                double held=secondsSince(phaseSince);
                switch(phase) {
                    case MENU -> {
                        require(flow.screen()==ScreenFlow.Screen.MENU,"Expected initial main menu");
                        if(held>.7){activate("new");advance(Phase.VEHICLES);}
                    }
                    case VEHICLES -> {
                        require(flow.screen()==ScreenFlow.Screen.VEHICLES,"New campaign did not open vehicle selection");
                        if(held>.7){activate("vehicle:choose");advance(Phase.COMBAT);}
                    }
                    case COMBAT -> {
                        if(flow.screen()!=ScreenFlow.Screen.RUNNING)return;
                        MatchSession session=field("session",MatchSession.class);
                        if(session.phase!=MatchSession.Phase.ARENA_COMBAT||session.activeTicks<30)return;
                        require(session.mode==MatchSession.Mode.CAMPAIGN,"New campaign did not launch campaign gameplay");
                        dispatch("pause");require(flow.screen()==ScreenFlow.Screen.PAUSED,"Pause did not open");
                        pausedSession=session;pausedWorld=field("world",Object.class);pausedTick=session.tick;
                        pausedMusic=field("audio",AudioDirector.class).musicPlaybackSeconds();
                        require(pausedWorld!=null&&pausedTick>=360&&pausedMusic>.05f,"Real countdown, world and playing music required");
                        synchronized(evidence) {
                            evidence.put("sessionId",session.sessionId.toString());evidence.put("arena",session.arenaId);
                            evidence.put("mode",session.mode.name());evidence.put("worldIdentity",System.identityHashCode(pausedWorld));
                            evidence.put("pausedTick",pausedTick);evidence.put("pausedMusicSeconds",pausedMusic);
                        }
                        advance(Phase.PAUSED);
                    }
                    case PAUSED -> {
                        require(flow.screen()==ScreenFlow.Screen.PAUSED,"Expected pause before exit");
                        if(!captured&&held>.25)capture("pause-exit-paused");
                        if(held>.8){activate("quit");advance(Phase.CONFIRMATION);}
                    }
                    case CONFIRMATION -> {
                        require(flow.screen()==ScreenFlow.Screen.CONFIRM,"Exit did not request confirmation");
                        require(field("confirmationTitle",String.class).contains("последней контрольной точки"),"Campaign exit omitted checkpoint notice");
                        require("cancel".equals(field("menu",MenuView.class).selectedId()),"Confirmation must initially select cancellation");
                        if(!captured&&held>.25)capture("pause-exit-confirmation");
                        if(held>.8){activate("cancel");advance(Phase.CANCELLED);}
                    }
                    case CANCELLED -> {
                        require(flow.screen()==ScreenFlow.Screen.PAUSED,"Cancellation did not return to the existing pause");
                        if(!captured&&held>.25)capture("pause-exit-cancelled");
                        if(held>1) {
                            synchronized(evidence){evidence.put("cancelPreservedSession",true);evidence.put("cancelPreservedWorld",true);}
                            activate("quit");advance(Phase.SECOND_CONFIRMATION);
                        }
                    }
                    case SECOND_CONFIRMATION -> {
                        require(flow.screen()==ScreenFlow.Screen.CONFIRM,"Second exit did not ask for confirmation");
                        if(held>.5) {
                            verifyPaused();
                            synchronized(evidence) {
                                evidence.put("pausedFramesChecked",pausedFrames);evidence.put("maximumPausedMusicDriftSeconds",maxMusicDrift);
                                evidence.put("tickAfterCancel",pausedSession.tick);evidence.put("exitViaPauseConfirmation",true);
                            }
                            activate("accept");advance(Phase.EXIT_REQUESTED);
                        }
                    }
                    default -> {}
                }
            } catch(Throwable problem){fail(problem);}
        }
        private void verifyPaused() {
            require(field("session",MatchSession.class)==pausedSession,"Pause/confirmation replaced the session");
            require(field("world",Object.class)==pausedWorld,"Pause/confirmation replaced the native world");
            require(pausedSession.tick==pausedTick,"Simulation advanced during pause/confirmation/cancel");
            float music=field("audio",AudioDirector.class).musicPlaybackSeconds();
            require(Float.isFinite(music),"Music playback position is not finite");
            maxMusicDrift=Math.max(maxMusicDrift,Math.abs(music-pausedMusic));
            require(maxMusicDrift<=.03f,"Music advanced or restarted during pause: "+maxMusicDrift);pausedFrames++;
        }
        private void activate(String id) {
            require(field("menu",MenuView.class).focus(id),"UI action unavailable: "+id);dispatch("activate");
            synchronized(evidence){actions.add(Map.of("control",id,"screenAfter",field("flow",ScreenFlow.class).screen().name()));}
        }
        private void dispatch(String action){invoke("dispatchUiAction",action);}
        private void capture(String label) {
            String prefix=(String)invoke("requestCapture",label);require(prefix!=null,"Screenshot subsystem unavailable");
            capturePrefixes.add(prefix);captured=true;
        }
        private void advance(Phase next){phase=next;phaseSince=System.nanoTime();captured=false;}
        private void fail(Throwable problem){failure.compareAndSet(null,problem);app.stop();}
        private Map<String,Object> snapshot() {
            synchronized(evidence) {
                var result=new LinkedHashMap<>(evidence);result.put("phase",phase.name());result.put("actions",List.copyOf(actions));return result;
            }
        }
        private <T> T field(String name,Class<T> type) {
            try {var field=GameApplication.class.getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(app));}
            catch(ReflectiveOperationException problem){throw new IllegalStateException("Diagnostic field unavailable: "+name,problem);}
        }
        private Object invoke(String name,String argument) {
            try {var method=GameApplication.class.getDeclaredMethod(name,String.class);method.setAccessible(true);return method.invoke(app,argument);}
            catch(InvocationTargetException problem){throw new IllegalStateException("UI command failed: "+name,problem.getCause());}
            catch(ReflectiveOperationException problem){throw new IllegalStateException("Diagnostic command unavailable: "+name,problem);}
        }
        @Override protected void cleanup(Application application) {}
        @Override protected void onEnable() {}
        @Override protected void onDisable() {}
    }
}
