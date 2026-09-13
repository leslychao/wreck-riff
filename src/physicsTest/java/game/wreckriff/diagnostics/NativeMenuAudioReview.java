package game.wreckriff.diagnostics;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.VideoRecorderAppState;
import com.jme3.audio.*;
import com.jme3.bounding.BoundingBox;
import com.jme3.math.*;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.*;
import com.jme3.system.*;
import game.wreckriff.audio.*;
import game.wreckriff.config.*;
import game.wreckriff.presentation.*;
import game.wreckriff.ui.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.lwjgl.glfw.GLFW.*;

/** Bounded real-window inspection, intentionally separate from gameplay and performance acceptance. */
public final class NativeMenuAudioReview extends SimpleApplication {
    private record Options(Path output,String profile,boolean music,double seconds) {
        static Options parse(String[] args) {
            Path output=null;String profile=null;boolean music=false;Double seconds=null;
            for(String arg:args) {
                if(arg.startsWith("--output="))output=Path.of(arg.substring(9)).toAbsolutePath();
                else if(arg.startsWith("--profile="))profile=arg.substring(10);
                else if(arg.equals("--music"))music=true;
                else if(arg.startsWith("--seconds="))seconds=Double.valueOf(arg.substring(10));
                else throw new IllegalArgumentException("Unknown argument: "+arg);
            }
            if(output==null||music==(profile!=null))throw new IllegalArgumentException("Use --output=<new directory> and either --profile=rivet|grinder|spark or --music; optional --seconds=38/36");
            if(profile!=null)VehicleDefinition.forId(profile);
            double duration=seconds==null?(music?36:38):seconds;
            if(!Double.isFinite(duration)||duration<(music?34:37)||duration>40)throw new IllegalArgumentException("Duration must cover the full sequence and be <=40 seconds");
            return new Options(output,profile,music,duration);
        }
    }

    private static final UUID SESSION=new UUID(0,904);
    private static final int RECORDING_FPS=30;
    private final Options options;
    private final CountDownLatch ended=new CountDownLatch(1);
    private final Map<String,Object> evidence=new LinkedHashMap<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    private final Map<String,Node> cachedModels=new LinkedHashMap<>();
    private final long wallStarted=System.nanoTime();
    private GaragePresentation garage;
    private AudioDirector audio;
    private AudioCapture capture;
    private MenuView menu;
    private VehicleRules rules;
    private VehicleSelectionModel selection;
    private double seconds,rotation,lastYaw,pausedPosition;
    private int stage,frames,visibleFrames,maximumVoices,maximumMusic,baselineGeometry;
    private float minimumMargin=Float.POSITIVE_INFINITY;
    private boolean rotationStarted,finishRequested,destroyed,pauseVerified,uiDuringPause;
    private volatile String failure;

    private NativeMenuAudioReview(Options options){this.options=options;}

    public static void main(String[] args)throws Exception {
        Options options=Options.parse(args);
        if(Files.exists(options.output.resolve("review.json"))||Files.exists(options.output.resolve("review.avi")))
            throw new IllegalArgumentException("Use a fresh output directory; previous review evidence is preserved");
        Files.createDirectories(options.output);
        JmeSystem.setSystemDelegate(new DesktopAudioSystem());
        NativeSetup.prepare(options.output.resolve("native"));
        var app=new NativeMenuAudioReview(options);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff / "+(options.music?"Menu and music review":options.profile+" full turn"));
        settings.setResolution(1280,720);settings.setSamples(4);settings.setGammaCorrection(true);
        settings.setVSync(false);settings.setFrameRate(RECORDING_FPS);
        app.setSettings(settings);app.setShowSettings(false);app.start(JmeContext.Type.Display);
        if(!app.ended.await(240,TimeUnit.SECONDS)) {
            app.failure="Real-window recording exceeded the 240-second wall-time limit";app.stop();
            app.ended.await(10,TimeUnit.SECONDS);System.exit(2);
        }
        if(app.failure!=null)throw new IllegalStateException(app.failure);
    }

    @Override public void simpleInitApp() {
        if(context.getType()!=JmeContext.Type.Display||audioRenderer==null)throw new IllegalStateException("A real display window and audio device are required");
        // SimpleApplication samples tpf before initializing attached states. Install the same
        // timer now so the first recorded frame cannot consume the entire application startup.
        setTimer(new VideoRecorderAppState.IsoTimer(RECORDING_FPS));
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);inputManager.setCursorVisible(false);
        rules=VehicleRules.load();
        var lighting=SceneLighting.install(assetManager,rootNode,viewPort);lighting.setSamples(4);lighting.applyMenu(true);
        lighting.initialize(renderManager);
        garage=new GaragePresentation(assetManager,rules);rootNode.attachChild(garage.node());
        for(VehicleDefinition definition:VehicleDefinition.values()) {
            garage.show(definition.id());cachedModels.put(definition.id(),vehicle());
        }
        for(VehicleDefinition definition:VehicleDefinition.values()) {
            garage.show(definition.id());require(cachedModels.get(definition.id())==vehicle(),"Garage browsing rebuilt a cached chassis");
        }
        selection=new VehicleSelectionModel(options.profile==null?"rivet":options.profile);garage.show(selection.preview().id());
        menu=new MenuView(new GameUi(assetManager,guiNode));menu.resize(1280,720,1);
        audio=new AudioDirector(assetManager,audioRenderer,listener,rootNode);audio.setVolumes(.8f,.85f,.85f);
        menu.onFeedback(cue->{audio.ui(cue);event("ui-"+cue.name().toLowerCase(Locale.ROOT));if(audio.isPaused())uiDuringPause=true;});
        capture=new AudioCapture(options.output,()->seconds,options.seconds);audio.setCapture(capture);
        stateManager.attach(new VideoRecorderAppState(options.output.resolve("review.avi").toFile(),.82f,RECORDING_FPS));
        audio.startMenu();if(options.music)showMusic("ГЛАВНОЕ МЕНЮ");else showVehicle();
        baselineGeometry=geometryCount();
        evidence.put("startedUtc",Instant.now().toString());evidence.put("mode",options.music?"music":"profile");
        evidence.put("profile",options.profile);evidence.put("requestedSeconds",options.seconds);
        evidence.put("cachedProfiles",cachedModels.keySet());evidence.put("cacheIdentityStable",true);
        evidence.put("audioEnabled",true);evidence.put("context",context.getType().name());
        evidence.put("audioEvidence","audio.wav is a reconstruction of actual AudioDirector sources using local PCM and an unpaused presentation clock; it is not microphone, loopback, native OpenAL/HRTF or device-output capture");
        evidence.put("timing","AVI and reconstructed audio use a 30 fps presentation timeline; wall-time is reported separately; this is not a performance benchmark");
        evidence.put("recordingFps",RECORDING_FPS);evidence.put("expectedFrames",(int)Math.ceil(options.seconds*RECORDING_FPS));
        try(var input=assetManager.locateAsset(new com.jme3.asset.AssetKey<>("build-info.properties")).openStream()) {
            Properties properties=new Properties();properties.load(input);evidence.put("sourceSha256",properties.getProperty("sourceSha256"));
        } catch(Exception missing){throw new IllegalStateException("Cannot identify the current application build",missing);}
        event("menu-start");
    }

    @Override public void simpleUpdate(float tpf) {
        if(finishRequested||audio==null)return;
        require(Math.abs(tpf-1f/RECORDING_FPS)<.000001f,"Recorder must advance exactly one presentation frame, including startup");
        seconds=Math.min(options.seconds,(frames+1)/(double)RECORDING_FPS);
        if(options.music)musicSequence();
        else if(stage==0&&seconds>=1) {menu.cycle(false);stage++;}
        else if(stage==1&&seconds>=2) {menu.focus("vehicle:choose");menu.activate();stage++;}
        audio.updatePresentation(tpf);
        garage.update(tpf,cam,menu.sceneLeftOcclusion(),menu.sceneBottomOcclusion());
        listener.setLocation(cam.getLocation());listener.setRotation(cam.getRotation());
        maximumVoices=Math.max(maximumVoices,audio.voiceCount());maximumMusic=Math.max(maximumMusic,audio.musicSourceCount());
        require(audio.voiceCount()<=32&&audio.musicSourceCount()<=2,"Audio source budget exceeded");
        if(audio.isPaused())require(Math.abs(audio.musicPlaybackSeconds()-pausedPosition)<=.03,"Music advanced during pause");
    }

    private void musicSequence() {
        if(stage==0&&seconds>=8) {audio.prepareMatch(SESSION,"audio/music/construction_17-normal.wav","audio/music/construction_17-boss.wav");showMusic("ЗАГРУЗКА КАРТЫ");event("prepare-construction");stage++;}
        if(stage==1&&seconds>=9) {audio.startPreparedMatch();showMusic("СТРОЙПЛОЩАДКА / МУЗЫКА БОЯ");event("battle-start");stage++;}
        if(stage==2&&seconds>=16) {audio.bossMusic(true);showMusic("СТРОЙПЛОЩАДКА / БОСС");event("boss-start");stage++;}
        if(stage==3&&seconds>=22) {audio.pause();pausedPosition=audio.musicPlaybackSeconds();showMusic("ПАУЗА / ЗВУКИ ИНТЕРФЕЙСА");event("pause");stage++;}
        if(stage==4&&seconds>=23) {menu.move(UiFocusModel.Direction.DOWN);stage++;}
        if(stage==5&&seconds>=24) {menu.focus("inspect");menu.activate();stage++;}
        if(stage==6&&seconds>=25) {menu.focus("back");menu.activate();stage++;}
        if(stage==7&&seconds>=26) {pauseVerified=Math.abs(audio.musicPlaybackSeconds()-pausedPosition)<=.03;audio.resume();showMusic("СТРОЙПЛОЩАДКА / ПРОДОЛЖЕНИЕ");event("resume");stage++;}
        if(stage==8&&seconds>=30) {audio.stopMatch();audio.startMenu();showMusic("ВОЗВРАТ В ГЛАВНОЕ МЕНЮ");event("return-menu");stage++;}
    }

    private void showVehicle() {
        var definition=selection.preview();
        menu.showVehicleSelection(selection,rules.maxSpeed()*definition.profile(rules).speedMultiplier(),
                ()->browse(-1),()->browse(1),()->event("vehicle-confirm"),()->event("vehicle-back"));
    }
    private void browse(int direction){garage.show(selection.browse(direction).id());showVehicle();}
    private void showMusic(String title) {
        menu.show("main",title,"WRECK RIFF",List.of(),List.of(
                new MenuView.Action("inspect","ВЫБРАТЬ",()->event("menu-confirm")),
                new MenuView.Action("back","НАЗАД",UiCue.BACK,()->event("menu-back"))),List.of(),"inspect");
    }

    @Override public void simpleRender(RenderManager manager) {
        if(finishRequested||garage==null)return;
        frames++;long window=glfwGetCurrentContext();
        boolean visible=window!=0&&glfwGetWindowAttrib(window,GLFW_VISIBLE)!=0&&glfwGetWindowAttrib(window,GLFW_ICONIFIED)==0;
        require(visible,"The recording window became hidden or minimized");visibleFrames++;
        int[] width={0},height={0};glfwGetFramebufferSize(window,width,height);
        require(width[0]==1280&&height[0]==720,"Unexpected actual framebuffer dimensions");
        evidence.put("framebuffer",List.of(width[0],height[0]));
        if(!options.music) {
            Node model=vehicle();require(model==cachedModels.get(options.profile),"Selected vehicle identity changed");
            require(geometryCount()==baselineGeometry,"Garage geometry accumulated during animation");
            Vector3f forward=model.getLocalRotation().mult(Vector3f.UNIT_Z);double yaw=Math.atan2(forward.x,forward.z);
            if(rotationStarted){double change=yaw-lastYaw;while(change< -Math.PI)change+=Math.PI*2;while(change>Math.PI)change-=Math.PI*2;rotation+=change;}
            rotationStarted=true;lastYaw=yaw;
            assertBounds((BoundingBox)model.getWorldBound());
        }
        if(seconds>=options.seconds) {
            require(frames==(int)Math.ceil(options.seconds*RECORDING_FPS),"Recording skipped required presentation frames");
            if(options.music)require(stage==9&&pauseVerified&&uiDuringPause&&audio.gameplayVoiceCount()==0,"Music lifecycle or paused UI verification incomplete");
            else require(rotation>=Math.PI*2,"Vehicle did not complete its full 36-second rotation");
            finishRequested=true;stop();
        }
    }

    private void assertBounds(BoundingBox bounds) {
        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1}) {
            Vector3f point=cam.getScreenCoordinates(bounds.getCenter().add(x*bounds.getXExtent(),y*bounds.getYExtent(),z*bounds.getZExtent()));
            float margin=Math.min(Math.min(point.x-1280*menu.sceneLeftOcclusion(),1280-point.x),
                    Math.min(point.y-720*menu.sceneBottomOcclusion(),720-point.y));
            minimumMargin=Math.min(minimumMargin,margin);require(margin>=0&&point.z>=0&&point.z<=1,"Vehicle or attached equipment is clipped by the viewport/menu");
        }
    }
    private Node vehicle(){return (Node)garage.node().getChild("garage-vehicle");}
    private int geometryCount(){int[] count={0};garage.node().depthFirstTraversal(node->{if(node instanceof Geometry)count[0]++;});return count[0];}
    private void event(String name){if(events.size()>=64)throw new IllegalStateException("Review event budget exceeded");events.add(Map.of("seconds",seconds,"event",name));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}

    @Override public void destroy() {
        if(destroyed)return;destroyed=true;
        try {if(audio!=null)audio.setCapture(null);if(capture!=null)capture.close();if(audio!=null)audio.close();if(garage!=null)garage.close();}
        catch(Throwable error){failure="Review cleanup: "+error;}
        try {super.destroy();}catch(Throwable error){failure="Renderer/recorder cleanup: "+error;}
        try {
            if(!finishRequested&&failure==null)failure="Recording stopped before the required sequence completed";
            if(failure==null&&(Files.size(options.output.resolve("review.avi"))<1024||Files.size(options.output.resolve("audio.wav"))<1024))failure="Recorded media is missing or empty";
            evidence.put("status",failure==null?"PASS":"FAILED");evidence.put("failure",failure);
            evidence.put("presentationSeconds",seconds);evidence.put("wallSeconds",(System.nanoTime()-wallStarted)/1_000_000_000.0);
            evidence.put("renderedFrames",frames);evidence.put("visibleFrames",visibleFrames);evidence.put("windowVisible",frames>0&&visibleFrames==frames);
            evidence.put("maximumVoices",maximumVoices);evidence.put("maximumMusicSources",maximumMusic);evidence.put("rotationDegrees",Math.toDegrees(rotation));
            evidence.put("minimumVehicleMarginPixels",Float.isFinite(minimumMargin)?minimumMargin:null);evidence.put("pausePositionPreserved",pauseVerified);
            evidence.put("uiDuringPause",uiDuringPause);evidence.put("events",events);evidence.put("video","review.avi");evidence.put("audio","audio.wav");
            evidence.put("artisticStatus","NEEDS_CREATIVE_REVIEW");
            Files.writeString(options.output.resolve("review.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence)+"\n",StandardCharsets.UTF_8);
        }catch(Exception error){failure="Cannot write review evidence: "+error;System.err.println(failure);}
        finally {ended.countDown();}
    }
    @Override public void handleError(String message,Throwable error) {
        failure=message+": "+error;System.err.println(failure);error.printStackTrace();stop();
    }
}
