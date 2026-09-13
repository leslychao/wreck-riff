package game.wreckriff.app;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.app.state.VideoRecorderAppState;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.Main;
import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.audio.AudioDirector;
import game.wreckriff.audio.AudioCapture;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.diagnostics.SessionReport;
import game.wreckriff.diagnostics.LaunchReport;
import game.wreckriff.diagnostics.DiagnosticEvidence;
import game.wreckriff.diagnostics.DiagnosticCompletion;
import game.wreckriff.diagnostics.CombatShowcase;
import game.wreckriff.diagnostics.ArtShowcase;
import game.wreckriff.diagnostics.VehicleShowcase;
import game.wreckriff.diagnostics.FrameSample;
import game.wreckriff.diagnostics.StageProfiler;
import game.wreckriff.diagnostics.SoakSchedule;
import game.wreckriff.diagnostics.ResourceRetention;
import game.wreckriff.diagnostics.UiReview;
import game.wreckriff.diagnostics.UiReviewFixtures;
import game.wreckriff.diagnostics.SoakProfile;
import game.wreckriff.input.*;
import game.wreckriff.presentation.*;
import game.wreckriff.simulation.*;
import game.wreckriff.ui.GameUi;
import game.wreckriff.ui.HudView;
import game.wreckriff.ui.MatchHudPresenter;
import game.wreckriff.ui.ProgressNotice;
import game.wreckriff.ui.MenuView;
import game.wreckriff.ui.UiFocusModel;
import game.wreckriff.ui.PickupFeedback;
import game.wreckriff.ui.EncounterSubtitles;
import game.wreckriff.ui.EnemyHealthBars;
import game.wreckriff.ui.EnemyHealthBarProjection;
import game.wreckriff.vehicle.VehicleController;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import static game.wreckriff.app.ScreenFlow.Screen;

/** Composition root. Mutable game state is advanced only in fixedTick(). */
public final class GameApplication extends SimpleApplication {
    private final Main.Options options;
    private final SettingsStore store;
    private final ArenaRegistry arenaRegistry=ArenaRegistry.load();
    private final ProgressStore progress;
    private ProgressStore.Attempt attempt;
    private MatchLoading loading;
    private String requestedArena=ProgressStore.LEGACY_ARENA;
    private ProgressStore.Mode requestedMode=ProgressStore.Mode.LEGACY;
    private ProgressStore.Checkpoint retryCheckpoint;
    private final ScreenFlow flow=new ScreenFlow();
    private final Node matchNode=new Node("match"),menuNode=new Node("menu-scene");
    private final Map<Integer,Node> vehicleModels=new HashMap<>();
    private final Map<Integer,Spatial[]> wheels=new HashMap<>();
    private final Map<Integer,VehicleController> drivers=new LinkedHashMap<>();
    private InputSystem input;
    private GameUi ui;
    private MenuView menu;
    private int settingsTab;
    private boolean controlsGamepad,settingsDirty;
    private double settingsSaveAt;
    private EnemyHealthBars enemyHealthBars;
    private final List<EnemyHealthBars.Marker> enemyHealthMarkers=new ArrayList<>();
    private AudioDirector audio;
    private PhysicsWorld world;
    private MatchSession session;
    private MatchRuntime runtime;
    private MatchRules matchRules;
    private VehicleRules vehicleRules;
    private SimulationLoop loop;
    private ArenaDefinition arena;
    private ArenaContent content;
    private ArenaSystems arenaSystems;
    private BotController bots;
    private CombatSystem combat;
    private CombatVisuals combatVisuals;
    private SpecialPresentation specialPresentation;
    private BossActionPresentation bossActionPresentation;
    private PickupPresentation pickupPresentation;
    private SceneLighting.Handle sceneLighting;
    private ChaseCamera chase;
    private DiagnosticCameraTour visualTour;
    private CombatShowcase showcase;
    private ArtShowcase artShowcase;
    private VehicleShowcase vehicleShowcase;
    private boolean vehicleSelectionCaptured;
    private VideoRecorderAppState videoRecorder;
    private AudioCapture audioCapture;
    private BitmapText showcaseText;
    private final List<String> diagnosticCaptures=new ArrayList<>();
    private SessionReport report;
    private ScreenshotAppState screenshots;
    private FileHandler fileLog;
    private Handler warningLog;
    private HudView hud;
    private PickupFeedback pickupFeedback;
    private EncounterSubtitles encounterSubtitles;
    private final MatchHudPresenter hudPresenter=new MatchHudPresenter();
    private double nextHudDiagnostics;
    private boolean hudUsesGamepad;
    private final ProgressNotice progressNotice=new ProgressNotice();
    private String progressStatus="";
    private int loadingUiPercent=-1;
    private String loadingUiStage="";
    private boolean debug,navDebug,showHelp,smokeStarted;
    private Node navNode;
    private float noticeTime;
    private double elapsed,videoDeadline;
    private SettingsStore.Settings previousVideo;
    private String message="",error="",rebinding;
    private String requestedProfileId="rivet";
    private Runnable vehicleSelectionAction;
    private Runnable confirmation;
    private String confirmationTitle;
    private boolean rearView;
    private int viewWidth,viewHeight;
    private final java.util.concurrent.CountDownLatch terminated=new java.util.concurrent.CountDownLatch(1);
    private final DiagnosticCompletion diagnosticCompletion=new DiagnosticCompletion();
    private DiagnosticEvidence diagnostic;
    private UiReview uiReview;
    private UiReview.Host uiReviewHost;
    private ProgressStore.Snapshot uiReviewProgress;
    private MatchSession uiReviewResult;
    private boolean uiReviewHud,uiReviewFrameRendered;
    private boolean loadingFramePrepared;
    private final LaunchReport launchReport;
    private boolean normalFramePending,normalFrameDrawable;
    private StageProfiler stageProfiler;
    private FrameSample pendingFrame;
    private SessionReport pendingFrameReport;
    private long renderedFrame;
    private long previousFrameNanos;
    private double screenSince,diagnosticPausedAt,undrawableSeconds;
    private long loadingStarted;
    private int diagnosticRetries,diagnosticResults;
    private final ResourceRetention resourceRetention=new ResourceRetention();
    private SoakSchedule soak;
    private SoakSchedule.Selection soakSelection,soakNext;
    private ResourceRetention.Key loadedResourceKey,unloadedResourceKey;
    private int unloadedBodies,unloadedListeners,unloadedTickListeners;
    private double soakLoadedAt,soakUnloadReadyAt;
    private boolean soakFinishing;
    private long benchmarkBlock;
    private long pauseTick;
    private float pauseMusicSeconds;
    private boolean pauseChecked,diagnosticEnding,initialized;

    public GameApplication(Main.Options options,SettingsStore store) {
        this.options=options;this.store=store;
        requestedArena=options.arenaId();
        requestedMode=requestedArena.equals(ProgressStore.LEGACY_ARENA)?ProgressStore.Mode.LEGACY:ProgressStore.Mode.ARENA;
        if(options.soakSeconds()>0) {
            soak=new SoakSchedule(arenaRegistry.entries().stream().map(ArenaRegistry.Entry::id).toList(),requestedArena);
            soakSelection=soak.first();requestedMode=soakSelection.mode();requestedProfileId=soakSelection.profileId();
        }
        Path progressDirectory=options.dev()&&!options.automated()
                ?store.directory().resolve("diagnostics").resolve("interactive-progress"):store.directory();
        progress=new ProgressStore(progressDirectory,checkpoint->MatchCheckpoint.validateReferences(arenaRegistry,checkpoint));
        launchReport=options.dev()?null:new LaunchReport(store.directory(),progress.snapshot().campaign().checkpoint()!=null);
    }
    @Override public void simpleInitApp() {
        flyCam.setEnabled(false); setDisplayFps(false); setDisplayStatView(false);
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        viewPort.setBackgroundColor(new ColorRGBA(0.033f,0.045f,0.065f,1));
        sceneLighting=SceneLighting.install(assetManager,rootNode,viewPort);
        sceneLighting.setSamples(store.settings().samples);
        rootNode.attachChild(menuNode); rootNode.attachChild(matchNode);
        try {
            matchRules=MatchRules.load(); vehicleRules=VehicleRules.load(); loop=new SimulationLoop(matchRules);
            initializeLogging();
            if(options.profile()) {
                stageProfiler=new StageProfiler();stageProfiler.startRecording(store.directory());
                stageProfiler.attachGpu(renderer);
                setAppProfiler(stageProfiler);renderer.getStatistics().setEnabled(true);
            }
            if(options.automated()) {
                diagnostic=new DiagnosticEvidence(options.benchmarkSeconds()>0,options.vehicleShowcase()?VehicleShowcase.SECONDS:options.artShowcase()?ArtShowcase.SECONDS:options.showcase()?CombatShowcase.SECONDS:options.benchmarkSeconds()>0?options.benchmarkSeconds():options.soakSeconds()>0?options.soakSeconds():options.smokeSeconds());
                renderer.getStatistics().setEnabled(true);
                if(soak!=null)diagnostic.configureSoak(soak,resourceRetention);
                if(options.showcase())diagnostic.put("mode","showcase");
                if(options.artShowcase())diagnostic.put("mode","art-showcase");
                if(options.vehicleShowcase())diagnostic.put("mode","vehicle-showcase");
                if(options.uiReview())diagnostic.put("mode","ui-review");
                diagnostic.put("arenaId",requestedArena);diagnostic.put("glow",store.settings().glow);
                diagnostic.put("detailedProfiling",options.profile());
                diagnostic.put("gpu",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
                diagnostic.put("graphicsVersionDriver",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
                diagnostic.put("width",cam.getWidth());diagnostic.put("height",cam.getHeight());
                diagnostic.put("vsync",store.settings().vsync);diagnostic.put("audioEnabled",audioRenderer!=null);
                long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                diagnostic.put("windowVisible",org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0);
                diagnostic.put("autoIconify",org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_AUTO_ICONIFY)!=0);
                diagnostic.put("msaaSamples",org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_SAMPLES));
                diagnostic.write(store.directory(),"RUNNING");
            }
            ui=new GameUi(assetManager,guiNode);
            menu=new MenuView(ui);
            enemyHealthBars=new EnemyHealthBars(assetManager,guiNode);
            enemyHealthBars.setVisible(false);
            input=new InputSystem(inputManager,store::settings,GamepadProfile.load(store.directory())); input.onKey(this::key); input.onUi(this::uiAction);
            input.onDisconnect(()->pause("Controller disconnected. Reconnect or use the keyboard."));
            audio=new AudioDirector(assetManager,audioRenderer,listener,rootNode);
            chase=new ChaseCamera(cam,CameraRules.load());
            createMenuScene();
            Path captures=store.directory().resolve("captures");
            try { Files.createDirectories(captures); screenshots=new ScreenshotAppState(captures.toAbsolutePath()+java.io.File.separator); stateManager.attach(screenshots); }
            catch(IOException e) { message="Screenshots unavailable: "+e.getMessage(); }
            flow.onChanged(this::screenChanged); flow.menu();
            initialized=true;
            if(options.uiReview()) {
                uiReview=new UiReview(store.directory(),store.settings().width,store.settings().height,store.settings().uiScale);
                uiReviewHost=createUiReviewHost();
                diagnostic.put("uiReviewManifest","ui-review-manifest.json");
                diagnostic.put("progressFixture","Render-only snapshots, isolated settings, no combat results recorded");
            }
            if(launchReport!=null) {
                long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                launchReport.started(cam.getWidth(),cam.getHeight(),window!=0&&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0,audioRenderer!=null);
            }
            if(!store.warning().isEmpty()) notice(store.warning());
            if(!progress.warning().isEmpty()) notice(progress.warning());
            if(!store.bindingWarning().isEmpty()) notice(store.bindingWarning());
            if(audioRenderer==null) notice("Audio unavailable / disabled. Music acceptance remains pending.");
        } catch(Exception e) { fail(e); }
    }
    private void initializeLogging() {
        try {
            Path logs=store.directory().resolve("logs"); Files.createDirectories(logs);
            fileLog=new FileHandler(logs.resolve("wreck-riff-%g.log").toString(),matchRules.logFileBytes(),matchRules.maxLogFiles(),true);
            fileLog.setFormatter(new SimpleFormatter()); Logger.getLogger("").addHandler(fileLog);
            warningLog=new Handler() {
                @Override public void publish(LogRecord entry) { if(report!=null&&entry.getLevel().intValue()>=Level.WARNING.intValue())report.warning(entry.getMessage()); }
                @Override public void flush() {}
                @Override public void close() {}
            };
            Logger.getLogger("").addHandler(warningLog);
        } catch(IOException e) { Logger.getLogger(getClass().getName()).warning("File logging unavailable: "+e.getMessage()); }
    }
    private void createMenuScene() {
        menuNode.detachAllChildren();
        Node hero=VehicleVisual.create(assetManager,VehicleDefinition.forId(store.settings().selectedVehicleId).profile(vehicleRules),0); hero.setLocalTranslation(2,1,0); hero.setLocalScale(1.35f); menuNode.attachChild(hero);
        Geometry floor=new Geometry("show-floor",SurfaceMesh.box(30,0.1f,30,4));
        floor.setMaterial(new SurfaceMaterials(assetManager).material("concrete"));
        floor.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive); menuNode.attachChild(floor);
    }
    private void continueCampaign() {
        var campaign=progress.snapshot().campaign();
        if(campaign.currentArenaId()==null)return;
        String profile=session!=null&&session.mode==MatchSession.Mode.CAMPAIGN?session.vehicle(0).profileId:store.settings().selectedVehicleId;
        startMatch(campaign.currentArenaId(),ProgressStore.Mode.CAMPAIGN,campaign.checkpoint(),profile);
    }
    private void newCampaign() {
        progress.beginNewCampaign();startMatch(progress.snapshot().campaign().currentArenaId(),ProgressStore.Mode.CAMPAIGN,null,store.settings().selectedVehicleId);
    }
    private void startArena(String id,boolean bossDuel) {
        chooseVehicle(()->startMatch(id,id.equals(ProgressStore.LEGACY_ARENA)?ProgressStore.Mode.LEGACY:
                bossDuel?ProgressStore.Mode.BOSS_DUEL:ProgressStore.Mode.ARENA,null,store.settings().selectedVehicleId));
    }
    private void startMatch() {
        startMatch(requestedArena,requestedMode,retryCheckpoint,requestedProfileId);
    }
    private void startMatch(String arenaId,ProgressStore.Mode mode,ProgressStore.Checkpoint checkpoint,String profileId) {
        if(flow.screen()==Screen.LOADING)return;
        ArenaDefinition selected=arenaRegistry.definition(arenaId);
        if(checkpoint!=null)MatchCheckpoint.validateReferences(arenaRegistry,checkpoint);
        requestedArena=arenaId;requestedMode=mode;retryCheckpoint=checkpoint;
        requestedProfileId=checkpoint==null?profileId:checkpoint.profileId();
        loadingStarted=System.nanoTime();
        long seed=checkpoint!=null?checkpoint.seed():options.fixedSeed()?options.seed():System.nanoTime();
        boolean bossCheckpoint=checkpoint!=null&&checkpoint.stage()==ProgressStore.CheckpointStage.BOSS;
        List<ArenaDefinition.Spawn> spawns=new ArrayList<>(selected.spawns());
        Collections.shuffle(spawns,new Random(seed));
        var arenaFactory=new ArenaFactory(assetManager);
        ArenaArt.Scene arenaArt;
        List<SurfaceMaterials.TextureUse> textures;
        try {
            arenaArt=ArenaArt.load(selected);
            textures=arenaFactory.textureRequirements(selected,arenaArt);
        } catch(RuntimeException exception) {fail(exception);return;}
        // Strong references keep jME's cloned textures alive only until scene materials own their images.
        var decodedTextures=new ArrayList<com.jme3.texture.Texture>();
        List<MatchLoading.Stage> stages=new ArrayList<>();
        stages.add(new MatchLoading.Stage("Подготовка арены",()->{
            cleanupMatch(false);arena=selected;
            attempt=options.automated()&&soak==null?new ProgressStore.Attempt(1,UUID.randomUUID(),arenaId,mode,bossCheckpoint):
                    progress.beginAttempt(arenaId,mode,bossCheckpoint);
            session=new MatchSession(seed,arena,MatchSession.Mode.valueOf(mode.name()),Configs.load("combat",CombatRules.class),
                    attempt.id(),bossCheckpoint,checkpoint==null?0:checkpoint.liveryId(),requestedProfileId);
            report=new SessionReport();world=new PhysicsWorld(vehicleRules);world.configureArena(arena);
        }));
        for(int i=0;i<textures.size();i++) {
            var texture=textures.get(i);
            stages.add(new MatchLoading.Stage("Текстуры: "+(i+1)+" / "+textures.size(),()->decodedTextures.add(texture.load(assetManager))));
        }
        stages.add(new MatchLoading.Stage("Окружение",()->{
            try {
                content=arenaFactory.build(arena,arenaArt);matchNode.attachChild(content.visual());
                if(options.cameraTour())visualTour=new DiagnosticCameraTour(arena);
            } finally {decodedTextures.clear();}
        }));
        stages.add(new MatchLoading.Stage("Дорожные опоры",()->{
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            if(checkpoint!=null)ArenaSystems.restoreGeometry(checkpoint.arena(),world,content.graph(),arena);
        }));
        int count=mode==ProgressStore.Mode.BOSS_DUEL||bossCheckpoint?1:selected.metadata().normalEnemies()+1;
        for(int id=0;id<count;id++) {
            final int participant=id;
            stages.add(new MatchLoading.Stage("Машины: "+(id+1)+" / "+count,()->{
                var state=session.vehicle(participant);var spawn=spawns.get(participant);
                var profile=VehicleDefinition.forId(state.profileId).profile(vehicleRules);
                Vector3f position=spawn.position().vector().add(0,profile.roadOffset(),0);
                Quaternion rotation=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
                if(participant==0&&checkpoint!=null) {
                    var pose=checkpoint.safePose();position.set((float)pose.x(),(float)pose.y(),(float)pose.z());
                    rotation.fromAngleAxis((float)pose.yaw(),Vector3f.UNIT_Y);
                    if(!world.freeSpawnPose(profile,position,rotation))throw new IllegalArgumentException("Контрольная точка перекрыта");
                }
                world.addVehicle(participant,position,rotation,profile);attachVehicleModel(state);
            }));
        }
        stages.add(new MatchLoading.Stage("Системы боя",()->{
            runtime=new MatchRuntime(session,world,arena,content.graph(),vehicleRules);
            runtime.profiler(stageProfiler);
            drivers.putAll(runtime.drivers());arenaSystems=runtime.arenaSystems();bots=runtime.bots();combat=runtime.combat();
            if(checkpoint!=null)runtime.restoreCheckpoint(checkpoint);
            ArenaPresentation.attach(assetManager,content.visual(),session,arena,arenaSystems);
            combatVisuals=new CombatVisuals(assetManager,matchNode,world);createNavigationLines();
            specialPresentation=new SpecialPresentation(assetManager,matchNode,world);
            specialPresentation.bindModels(vehicleModels);
            UUID presentationSessionId=session.sessionId;
            bossActionPresentation=new BossActionPresentation(session,bots,world,signal->
                    audio.bossTelegraph(presentationSessionId,signal.subjectId(),signal.beganTick(),signal.position(),signal.active()));
            pickupPresentation=new PickupPresentation(assetManager,content.visual(),session,arena,arenaSystems,combatVisuals::pickupBurst);
            pickupFeedback=new PickupFeedback(session.sessionId,0);
            encounterSubtitles=new EncounterSubtitles(arena,session.sessionId);
            sceneLighting.apply(arena.metadata().theme(),store.settings().glow);
        }));
        loading=new MatchLoading(stages,()->world.step(),()->{
            // Restoring resource timers after suspension warmup cannot spend a saved cooldown.
            if(checkpoint!=null)MatchCheckpoint.restorePlayer(session.vehicle(0),checkpoint.player());
            drivers.values().forEach(driver->driver.recordSafePose(session.tick));
            if(mode==ProgressStore.Mode.CAMPAIGN&&checkpoint==null) {
                retryCheckpoint=runtime.checkpoint(ProgressStore.CheckpointStage.ARENA);
                if(!options.automated()||soak!=null)progress.saveCheckpoint(attempt,retryCheckpoint);
            }
            chase.reset();menuNode.setCullHint(Spatial.CullHint.Always);createHud();
        },()->{
            loop=new SimulationLoop(matchRules);
            audio.startMatch(session.sessionId,arena.metadata().music(),arena.metadata().bossMusic());
            audio.bossMusic(session.phase==MatchSession.Phase.BOSS_ENTRY);
            input.clear();flow.running();finishLoading(checkpoint!=null);
        });
        flow.loading();
    }
    private void attachVehicleModel(VehicleState state) {
        if(vehicleModels.containsKey(state.id))return;
        Node model=VehicleVisual.create(assetManager,world.profile(state.id),state.liveryId);
        model.setName("vehicle-"+state.id);vehicleModels.put(state.id,model);matchNode.attachChild(model);
        Spatial[] wheelNodes=new Spatial[4];
        for(int i=0;i<4;i++) {
            wheelNodes[i]=model.getChild("wheel-"+i);
            if(wheelNodes[i]!=null) {wheelNodes[i].removeFromParent();matchNode.attachChild(wheelNodes[i]);}
        }
        wheels.put(state.id,wheelNodes);
    }
    private void finishLoading(boolean resumedCheckpoint) {
        report.loadSeconds((System.nanoTime()-loadingStarted)/1_000_000_000.0);
        if(launchReport!=null)launchReport.sessionStarted(session.arenaId,session.mode.name(),session.vehicle(0).profileId,resumedCheckpoint);
        if(diagnostic!=null) {
            int bodies=world.bodyCount(),listeners=world.space().countCollisionListeners();
            loadedResourceKey=resourceKey();
            resourceRetention.add(resourceSample("LOAD",loadedResourceKey,bodies,listeners,world.space().countTickListeners(),false),false);
            if(resourceRetention.failed()||!combat.projectiles().isEmpty()||audio.voiceCount()>audio.musicSourceCount())
                throw new IllegalStateException("Retry resource baseline changed");
            diagnostic.cycle(diagnosticRetries,bodies,listeners,combat.projectiles().size(),audio.voiceCount());
            if(session.tick!=0 || session.vehicles.stream().anyMatch(v->world.wheelContacts(v.id)!=4||
                    v.hp!=(v.id==0&&retryCheckpoint!=null?retryCheckpoint.player().hp():v.maximumHp)))
                throw new IllegalStateException("Loading did not prepare grounded participants with their initial or saved HP at tick zero");
            if(!combat.mines().isEmpty()||!combat.fireZones().isEmpty()||combat.reservedFireZones()!=0
                    ||!combat.ballisticWarnings().isEmpty()||combat.reservedBallisticCharges()!=0||runtime.hasWrecks())
                throw new IllegalStateException("Transient weapons survive match preparation");
            diagnostic.put("initialSimulationTick",session.tick);
            diagnostic.put("initialHp",session.vehicles.stream().map(v->v.hp).toList());
            diagnostic.put("startWithoutCountdown",true);
            diagnostic.put("latestLoadSeconds",(System.nanoTime()-loadingStarted)/1_000_000_000.0);
            if(soak!=null) {
                soak.loaded(soakSelection,session.sessionId,session.arenaId,ProgressStore.Mode.valueOf(session.mode.name()),session.vehicle(0).profileId);
                diagnosticRetries=soak.retries();soakLoadedAt=elapsed;
            }
        }
        if(options.showcase()) {
            showcase=new CombatShowcase(session,world,combat);
            MatchSession recordedSession=session;
            audioCapture=new AudioCapture(store.directory(),recordedSession::seconds,CombatShowcase.SECONDS);
            audio.setCapture(audioCapture);
            videoRecorder=new VideoRecorderAppState(store.directory().resolve("showcase.avi").toFile(),.85f,30);
            stateManager.attach(videoRecorder);
            showcaseText=ui.text("",90,1020,28,GameUi.PAPER);
        }
        if(options.artShowcase()) {
            artShowcase=new ArtShowcase(session,world,runtime,arena);
            audioCapture=new AudioCapture(store.directory(),artShowcase::seconds,ArtShowcase.SECONDS);
            audio.setCapture(audioCapture);
            videoRecorder=new VideoRecorderAppState(store.directory().resolve("art-showcase.avi").toFile(),.85f,30);
            stateManager.attach(videoRecorder);
            showcaseText=ui.text("",24,cam.getHeight()-52,16,GameUi.PAPER);
        }
        if(options.vehicleShowcase()) {
            vehicleShowcase=new VehicleShowcase(session,world,runtime,arena);
            audioCapture=new AudioCapture(store.directory(),vehicleShowcase::seconds,VehicleShowcase.SECONDS);
            audio.setCapture(audioCapture);
            videoRecorder=new VideoRecorderAppState(store.directory().resolve("vehicle-showcase.avi").toFile(),.85f,30);
            stateManager.attach(videoRecorder);
            showcaseText=ui.text("",24,cam.getHeight()-52,16,GameUi.PAPER);
        }

    }
    @Override public void simpleUpdate(float dt) {
        elapsed+=dt;
        if(ui==null || input==null) return;
        loadingFramePrepared=false;
        try {
            observePreviousFrame();
            FrameSample.Phase startingPhase=framePhase();
            SessionReport startingReport=report;
            SimulationLoop startingLoop=loop;
            double startingDropped=loop.droppedSimulationTime();
            input.pollGamepad();
            if(settingsDirty&&elapsed>=settingsSaveAt){store.saveSettings();settingsDirty=false;}
            pollProgressStatus();
            boolean drawable=cam.getWidth()>0&&cam.getHeight()>0;
            boolean diagnosticDrawable=drawable;
            if(diagnostic!=null) {
                long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                boolean visible=drawable&&window!=0&&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0
                        &&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_ICONIFIED)==0;
                diagnostic.observeWindow(cam.getWidth(),cam.getHeight(),visible);
                diagnosticDrawable=visible;
            }
            if(drawable&&(cam.getWidth()!=viewWidth || cam.getHeight()!=viewHeight)) {
                viewWidth=cam.getWidth();viewHeight=cam.getHeight();ui.usePixelCoordinates();resizeHud();
                if(menu!=null&&flow.screen()!=Screen.RUNNING)menu.resize(viewWidth,viewHeight,store.settings().uiScale);
            }
            if(!diagnosticDrawable)undrawableSeconds+=dt;
            if(!drawable)pause("Window minimized. Resume when ready.");
            if(flow.screen()!=Screen.RUNNING&&!options.uiReview())menu.hover(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);
            if(previousVideo!=null && elapsed>=videoDeadline) restoreVideo();
            var volume=store.settings(); audio.setVolumes(volume.master,volume.music,volume.sfx);
            sceneLighting.apply(world==null?ArenaDefinition.Theme.INDUSTRIAL_YARD:arena.metadata().theme(),volume.glow);
            sceneLighting.setSamples(volume.samples);
            if(options.automated() && !options.uiReview() && !smokeStarted && elapsed>0.5) {
                smokeStarted=true;
                if(options.vehicleShowcase())chooseVehicle(this::startMatch);else startMatch();
            }
            if(options.vehicleShowcase()&&flow.screen()==Screen.VEHICLES&&elapsed>1.5) {
                if(!vehicleSelectionCaptured){capture("vehicle-selection");vehicleSelectionCaptured=true;}
                if(elapsed>2.2)startMatch();
            }
            if(flow.screen()==Screen.LOADING&&loading!=null)loading.advance();
            if(flow.screen()==Screen.RUNNING&&!options.uiReview()) {
                long simulationStarted=profileStamp();
                advanceRunningFrame(loop,startingLoop,dt,this::fixedTick);
                profileStage(StageProfiler.Stage.SIMULATION,simulationStarted);
            } else if(flow.screen()==Screen.RESULTS&&runtime!=null&&runtime.hasWrecks()) {
                loop.advance(dt,true,runtime::tickPhysicsTail);
            } else loop.resetAccumulator();
            if(world!=null&&drawable&&(flow.screen()!=Screen.LOADING||preparingLoadingFrame())) {
                loadingFramePrepared=preparingLoadingFrame();
                renderMatch(loadingFramePrepared?0:Math.min(dt,0.1f));
            }
            else {
                float rotation=(float)Math.sin(elapsed*0.16)*0.15f;
                menuNode.setLocalRotation(new Quaternion().fromAngleAxis(rotation,Vector3f.UNIT_Y));
                cam.setLocation(new Vector3f(-6,4.5f,10)); cam.lookAt(new Vector3f(0.5f,0.7f,0),Vector3f.UNIT_Y);
            }
            if(noticeTime>0)noticeTime-=dt;
            if(flow.screen()!=Screen.RUNNING)menu.setStatus(noticeTime>0?message:progressStatus);
            if(flow.screen()==Screen.LOADING)drawLoading();
            if(visualTour!=null&&world!=null&&drawable&&elapsed<30) {
                String shot=visualTour.apply(cam,world,elapsed);
                if(shot!=null) capture(shot);
            }
            if(showcase!=null&&world!=null&&drawable) {
                String shot=showcase.frame(cam);
                if(showcaseText!=null)showcaseText.setText(showcase.label());
                if(shot!=null)capture(shot);
            }
            if(artShowcase!=null&&world!=null&&drawable) {
                String shot=artShowcase.frame(cam);
                if(showcaseText!=null)showcaseText.setText(artShowcase.label());
                if(shot!=null)capture(shot);
            }
            if(vehicleShowcase!=null&&world!=null&&drawable) {
                String shot=vehicleShowcase.frame(cam);
                if(showcaseText!=null)showcaseText.setText(vehicleShowcase.label());
                if(shot!=null)capture(shot);
            }
            updateEnemyHealthBars(drawable);
            if(options.dev()) {
                FrameSample.Phase phase=startingPhase==framePhase()&&startingReport==report?startingPhase:FrameSample.Phase.TRANSITION;
                pendingFrame=new FrameSample(renderedFrame++,session==null?0:session.tick,session==null?requestedArena:session.arenaId,phase,0,
                        session==null?0:session.vehicles.size(),world==null?0:world.bodyCount(),combat==null?0:combat.projectiles().size(),
                        audio.voiceCount(),combatVisuals==null?0:combatVisuals.effectCount(),launchingVehicles(),diagnosticDrawable,
                        startingLoop==loop?Math.max(0,loop.droppedSimulationTime()-startingDropped):loop.droppedSimulationTime());
                pendingFrameReport=report;
            }
            if(options.automated()) advanceDiagnostic(diagnosticDrawable);
        } catch(Exception e) { fail(e); }
    }
    private void observePreviousFrame() {
        if(uiReview!=null&&uiReviewFrameRendered){uiReview.drawnFrame();uiReviewFrameRendered=false;}
        long now=System.nanoTime(),previous=previousFrameNanos;previousFrameNanos=now;
        if(launchReport!=null&&normalFramePending&&previous!=0) {
            launchReport.frame(normalFrameDrawable,(now-previous)/1_000_000_000.0);normalFramePending=false;
        }
        if(pendingFrame==null||previous==0)return;
        double seconds=(now-previous)/1_000_000_000.0;
        var old=pendingFrame;
        var sample=new FrameSample(old.frame(),old.tick(),old.arenaId(),old.phase(),seconds,old.participants(),old.bodies(),
                old.projectiles(),old.sfxVoices(),old.effects(),old.launchingVehicles(),old.drawable(),old.droppedSimulationSeconds());
        if(diagnostic!=null)diagnostic.frame(sample);
        if(pendingFrameReport!=null)pendingFrameReport.frame(sample);
        pendingFrame=null;pendingFrameReport=null;
    }
    private long profileStamp() {return stageProfiler==null?0:System.nanoTime();}
    private void profileStage(StageProfiler.Stage stage,long started) {if(stageProfiler!=null)stageProfiler.record(stage,System.nanoTime()-started);}
    /** Incoming frame time belongs to the match that existed at the start of this update. */
    static int advanceRunningFrame(SimulationLoop current,SimulationLoop atFrameStart,double seconds,Runnable tick) {
        return current.advance(seconds,current==atFrameStart,tick);
    }
    private boolean preparingLoadingFrame() {return flow.screen()==Screen.LOADING&&loading!=null&&loading.awaitingFrame();}
    @Override public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if(uiReview!=null)uiReviewFrameRendered=true;
        if(loadingFramePrepared&&preparingLoadingFrame()) {
            // SimpleApplication calls this after RenderManager.render, so this acknowledges a real scene render.
            long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            boolean drawable=context.isRenderable()&&cam.getWidth()>0&&cam.getHeight()>0&&window!=0
                    &&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0
                    &&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_ICONIFIED)==0;
            loading.frameRendered(drawable);loadingFramePrepared=false;
        }
        if(stageProfiler!=null)stageProfiler.renderStatistics(renderer.getStatistics());
        if(launchReport!=null) {
            long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            normalFrameDrawable=window!=0&&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0
                    &&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_ICONIFIED)==0;
            normalFramePending=true;
        }
    }
    private int launchingVehicles() {
        if(session==null||world==null)return 0;int count=0;
        for(var state:session.vehicles)if(state.alive()&&world.roadContext(state.id).motion()==RoadContext.Motion.LAUNCH)count++;
        return count;
    }
    private FrameSample.Phase framePhase() {
        return switch(flow.screen()) {
            case LOADING->FrameSample.Phase.LOADING;case PAUSED->FrameSample.Phase.PAUSED;
            case RESULTS->FrameSample.Phase.RESULTS;case ERROR->FrameSample.Phase.ERROR;
            case RUNNING->session==null?FrameSample.Phase.TRANSITION:switch(session.phase) {
                case INTRO->FrameSample.Phase.INTRO;case ARENA_COMBAT->FrameSample.Phase.ARENA_COMBAT;
                case BOSS_ENTRY->FrameSample.Phase.BOSS_ENTRY;case BOSS_COMBAT->FrameSample.Phase.BOSS_COMBAT;
                case RESULT->FrameSample.Phase.RESULTS;case ERROR->FrameSample.Phase.ERROR;
            };
            default->FrameSample.Phase.MENU;
        };
    }
    private void fixedTick() {
        if(session.outcome!=MatchSession.Outcome.NONE || flow.screen()!=Screen.RUNNING) return;
        VehicleCommand player=input.consume(); rearView=player.rearView();
        int recoveries=session.vehicle(0).recoveries;
        List<GameEvent> events=vehicleShowcase!=null?runtime.tick(vehicleShowcase.commands(),false):artShowcase!=null?runtime.tick(artShowcase.commands(),false):
                showcase==null?runtime.tick(player,options.aiPlayer()||options.automated()):runtime.tick(showcase.commands(),false);
        if(showcase!=null)showcase.accept(events);
        if(artShowcase!=null)artShowcase.accept(events);
        if(vehicleShowcase!=null)vehicleShowcase.accept(events);
        for(var state:session.vehicles)if(world.containsVehicle(state.id))attachVehicleModel(state);
        drivers.putAll(runtime.drivers());
        if(session.checkpointRequested) {
            session.checkpointRequested=false;
            retryCheckpoint=runtime.checkpoint(ProgressStore.CheckpointStage.BOSS);
            if(session.mode==MatchSession.Mode.CAMPAIGN&&(!options.automated()||soak!=null))progress.saveCheckpoint(attempt,retryCheckpoint);
            notice("Аварийный ремонт: "+Math.round(session.vehicle(0).hp)+" HP");
        }
        audio.bossMusic(session.phase==MatchSession.Phase.BOSS_ENTRY||session.phase==MatchSession.Phase.BOSS_COMBAT);
        if(session.phase==MatchSession.Phase.ERROR) {
            error=session.outcomeReason;audio.pause();flow.error();return;
        }
        report.tick(session,events,bots);
        if(session.vehicle(0).recoveries>recoveries) chase.reset();
        for(GameEvent event:events) {
            if(event.type()==GameEvent.Type.DAMAGE && event.subjectId()==0) chase.impact(Math.min(0.7f,event.value()/60));
            if(event.type()==GameEvent.Type.RAM&&(event.subjectId()==0||event.sourceId()==0))
                chase.impact(Math.min(.7f,event.value()/40));
        }
        combatVisuals.accept(events);
        specialPresentation.accept(events);
        pickupPresentation.accept(events);
        pickupFeedback.accept(events,session.tick);
        if(encounterSubtitles!=null)encounterSubtitles.accept(session,events);
        if(session.outcome!=MatchSession.Outcome.NONE) {
            diagnosticResults++;
            var playerState=session.vehicle(0);
            if(!options.automated()||soak!=null)progress.record(new ProgressStore.Result(attempt,ProgressStore.Outcome.valueOf(session.outcome.name()),
                    playerState.damageDealt,playerState.eliminations,session.activeTicks,
                    session.bossParticipantId>=0&&!session.vehicle(session.bossParticipantId).alive()));
            writeReport(); audio.stopMatch();
            audio.accept(events);
            flow.results();
        } else audio.accept(events);
    }
    private void renderMatch(float dt) {
        long presentationStarted=profileStamp();
        boolean advancing=flow.screen()==Screen.RUNNING;
        boolean results=flow.screen()==Screen.RESULTS;
        float alpha=advancing||results&&runtime.hasWrecks()?loop.alpha():1;
        for(var state:session.vehicles) {
            Node model=vehicleModels.get(state.id);
            VehicleVisual.updateDamage(model,showcase==null?state.hp/state.maximumHp:showcase.displayHpFraction(state));
            if(state.id==session.bossParticipantId)VehicleVisual.updateBossPhase(model,session.bossMode-1,state.alive()&&arenaSystems.bossVulnerable());
            VehicleVisual.updateEffects(model,!results&&state.alive()&&state.frozenTicks>0,!results&&state.alive()&&state.shieldTicks>0);
            if(world.containsVehicle(state.id)) {
                var pose=world.interpolatedPose(state.id,alpha); model.setLocalTranslation(pose.position()); model.setLocalRotation(pose.rotation());
                for(int i=0;i<4;i++) if(wheels.get(state.id)[i]!=null) {
                    var wheel=world.interpolatedWheel(state.id,i,alpha); wheels.get(state.id)[i].setLocalTranslation(wheel.position()); wheels.get(state.id)[i].setLocalRotation(wheel.rotation());
                }
            } else if(!state.alive()) {
                model.removeFromParent();
                for(Spatial wheel:wheels.get(state.id))if(wheel!=null)wheel.removeFromParent();
            }
        }
        bossActionPresentation.setGlow(store.settings().glow);
        bossActionPresentation.update(vehicleModels);
        if(advancing) {
            combatVisuals.setFlashIntensity(store.settings().flashes);
            combatVisuals.update(combat.projectiles(),combat.mines(),combat.fireZones(),combat.ballisticWarnings(),session,dt);
            long audioStarted=profileStamp();
            if(session.mode==MatchSession.Mode.LEGACY) {
                var hazard=arenaSystems.hazardPhase();
                Vector3f hazardPosition=arena.hazards().stream().filter(h->arenaSystems.hazardPhase(h.id())!=ArenaSystems.HazardPhase.OFF)
                        .map(h->h.center().vector()).findFirst().orElseGet(()->world.position(0));
                audio.hazard(hazard==ArenaSystems.HazardPhase.WARNING,hazard==ArenaSystems.HazardPhase.ACTIVE,hazardPosition);
            }
            audio.update(session,world,dt);
            profileStage(StageProfiler.Stage.AUDIO,audioStarted);
        } else if(results) {
            combatVisuals.update(List.of(),List.of(),List.of(),List.of(),null,dt);
            audio.updateTail(dt);
        }
        specialPresentation.setFlashIntensity(store.settings().flashes);
        specialPresentation.update(session,combat.specialBombs(),alpha);
        pickupPresentation.setGlow(store.settings().glow);
        if(advancing||preparingLoadingFrame())pickupPresentation.update(alpha);
        chase.update(world,0,alpha,dt,rearView,drivers.get(0).turboActive(),store.settings().shake);
        updateHud();
        profileStage(StageProfiler.Stage.PRESENTATION,presentationStarted);
    }
    /** Runs after camera overrides, including rear view and diagnostic shots. */
    private void updateEnemyHealthBars(boolean drawable) {
        boolean visible=drawable&&world!=null&&session!=null&&(flow.screen()==Screen.RUNNING||preparingLoadingFrame());
        enemyHealthBars.setVisible(visible);
        if(!visible)return;
        enemyHealthBars.resize(cam.getWidth(),cam.getHeight(),store.settings().uiScale);
        enemyHealthMarkers.clear();
        for(var state:session.vehicles) {
            if(state.player||!state.alive()||!world.containsVehicle(state.id))continue;
            Node model=vehicleModels.get(state.id);
            if(model==null||model.getParent()==null)continue;
            var pose=new PhysicsWorld.Pose(model.getLocalTranslation(),model.getLocalRotation());
            var marker=EnemyHealthBarProjection.project(cam,world,state.id,pose,state.hp,state.maximumHp,world.profile(state.id));
            if(marker!=null)enemyHealthMarkers.add(marker);
        }
        enemyHealthBars.update(enemyHealthMarkers);
    }
    private void createNavigationLines() {
        navNode=new Node("navigation-debug"); matchNode.attachChild(navNode);
        Material material=new Material(assetManager,"Common/MatDefs/Misc/Unshaded.j3md"); material.setColor("Color",ColorRGBA.Cyan);
        Map<Integer,Vector3f> nodes=new HashMap<>(); arena.nodes().forEach(n->nodes.put(n.id(),n.position().vector().add(0,0.2f,0)));
        for(var edge:arena.edges()) {
            Geometry line=new Geometry("nav-"+edge.from()+"-"+edge.to(),new com.jme3.scene.shape.Line(nodes.get(edge.from()),nodes.get(edge.to())));
            line.setMaterial(material); navNode.attachChild(line);
        }
        navNode.setCullHint(navDebug?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
    }
    private void screenChanged() {
        screenSince=elapsed;
        if(enemyHealthBars!=null)enemyHealthBars.setVisible(flow.screen()==Screen.RUNNING);
        if(flow.screen()==Screen.RUNNING) message="";
        if(diagnostic!=null)diagnostic.transition(flow.screen().name(),elapsed);
        input.clear(); input.setGameplay(flow.screen()==Screen.RUNNING);
        if(world!=null && flow.screen()!=Screen.RUNNING && flow.screen()!=Screen.RESULTS) audio.pause();
        if(world!=null && flow.screen()==Screen.RUNNING) audio.resume();
        menuNode.setCullHint(world==null?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        loop.resetAccumulator(); redraw();
    }
    private void redraw() {
        ui.clear();ui.usePixelCoordinates();showcaseText=null;
        if(hud!=null)hud.setVisible(false);
        if(flow.screen()!=Screen.RUNNING)menu.resize(cam.getWidth(),cam.getHeight(),store.settings().uiScale);
        switch(flow.screen()) {
            case MENU -> drawMainMenu();
            case MAPS -> drawMaps();
            case VEHICLES -> drawVehicles();
            case STATISTICS -> drawStatistics();
            case LOADING -> {loadingUiPercent=-1;drawLoading();}
            case RUNNING -> createHud();
            case PAUSED -> menu.show("pause","ПАУЗА",!flow.pauseReason().isBlank()?flow.pauseReason():arena==null?"":arena.metadata().title(),List.of(),List.of(
                    new MenuView.Action("resume","ПРОДОЛЖИТЬ",flow::resume),
                    new MenuView.Action("settings","НАСТРОЙКИ",()->flow.open(Screen.SETTINGS)),
                    new MenuView.Action("controls","УПРАВЛЕНИЕ",()->flow.open(Screen.CONTROLS)),
                    new MenuView.Action("retry",retryCheckpoint!=null&&retryCheckpoint.stage()==ProgressStore.CheckpointStage.BOSS?"ПОВТОРИТЬ БОССА":"ПОВТОРИТЬ КАРТУ",
                            ()->confirm("Текущая попытка завершится. Начать снова с контрольной точки?",this::startMatch)),
                    new MenuView.Action("leave","ГЛАВНОЕ МЕНЮ",()->confirm(session.mode==MatchSession.Mode.CAMPAIGN?
                            "Прогресс сохранён у последней контрольной точки. Покинуть бой?":"Текущая попытка завершится. Покинуть бой?",this::returnToMenu))),
                    List.of(new MenuView.Action("resume-footer","НАЗАД В БОЙ",flow::resume)),"resume");
            case RESULTS -> drawResults();
            case SETTINGS -> drawSettings();
            case CONTROLS -> drawControls();
            case CREDITS -> drawCredits();
            case CONFIRM -> menu.show("confirm:"+confirmationTitle,"ПОДТВЕРЖДЕНИЕ","",List.of(),List.of(new MenuView.Text("question",confirmationTitle)),
                    List.of(new MenuView.Action("cancel","ОТМЕНА",flow::back),new MenuView.Action("accept","ПОДТВЕРДИТЬ",()->{
                        Runnable action=confirmation;confirmation=null;flow.back();if(action!=null)action.run();})),"cancel");
            case ERROR -> menu.show("error","НЕ УДАЛОСЬ ПРОДОЛЖИТЬ","Последнее корректное сохранение сохранено.",List.of(),
                    List.of(new MenuView.Text("error-detail",error)),List.of(new MenuView.Action("menu","ГЛАВНОЕ МЕНЮ",this::returnToMenu),
                    new MenuView.Action("quit","ВЫХОД",this::stop)),"menu");
            default -> {}
        }
        if(flow.screen()!=Screen.RUNNING)menu.setStatus(noticeTime>0?message:progressStatus);
    }
    private void drawMainMenu() {
        var campaign=displayProgress().campaign();var rows=new ArrayList<MenuView.Row>();
        boolean canContinue=campaign.currentArenaId()!=null&&(campaign.checkpoint()!=null||!campaign.completedArenaIds().isEmpty());
        if(canContinue)rows.add(new MenuView.Action("continue","ПРОДОЛЖИТЬ",arenaRegistry.definition(campaign.currentArenaId()).metadata().title(),true,this::continueCampaign));
        rows.add(new MenuView.Action("new","НОВАЯ КАМПАНИЯ",()->{
            if(campaign.checkpoint()!=null||!campaign.completedArenaIds().isEmpty())confirm("Начать кампанию заново? Открытые карты и статистика сохранятся.",()->chooseVehicle(this::newCampaign));
            else chooseVehicle(this::newCampaign);}));
        rows.add(new MenuView.Action("maps","ОТДЕЛЬНЫЙ БОЙ",flow::maps));
        rows.add(new MenuView.Action("stats","СТАТИСТИКА",flow::statistics));
        rows.add(new MenuView.Action("settings","НАСТРОЙКИ",()->flow.open(Screen.SETTINGS)));
        rows.add(new MenuView.Action("credits","АВТОРЫ И ЛИЦЕНЗИИ",()->flow.open(Screen.CREDITS)));
        menu.show("main","WRECK RIFF","Тяжёлый металл. Последняя машина.",List.of(),rows,List.of(new MenuView.Action("quit","ВЫХОД",this::stop)),canContinue?"continue":"new");
    }
    private void drawMaps() {
        var campaign=displayProgress().campaign();boolean duels=campaign.completedArenaIds().size()==ProgressStore.CAMPAIGN_ARENAS.size();
        var rows=new ArrayList<MenuView.Row>();
        for(var entry:arenaRegistry.entries()) {
            var selected=arenaRegistry.definition(entry.id());boolean unlocked=!entry.campaign()||campaign.unlockedArenaIds().contains(entry.id());
            String detail=!unlocked?"Завершите предыдущую арену":campaign.completedArenaIds().contains(entry.id())?"Пройдена":entry.campaign()?"Открыта":"Классический бой / 6 минут";
            if(unlocked&&!selected.bosses().isEmpty())detail+=" / "+selected.bosses().getFirst().name();
            rows.add(new MenuView.ArenaCard("arena:"+entry.id(),selected,detail,unlocked,()->startArena(entry.id(),false),entry.campaign()&&duels?()->startArena(entry.id(),true):null));
        }
        menu.show("maps","ВЫБОР АРЕНЫ",duels?"Дуэли с боссами открыты":"Дуэли с боссами откроются после кампании",List.of(),rows,
                List.of(new MenuView.Action("back","НАЗАД",flow::menu)),null);
    }
    private void drawStatistics() {
        var snapshot=displayProgress();var stats=snapshot.stats();var rows=new ArrayList<MenuView.Row>();
        rows.add(new MenuView.Metrics("outcomes",List.of(new MenuView.Metric("Боёв",Long.toString(stats.completedMatches())),new MenuView.Metric("Побед",Long.toString(stats.wins())),new MenuView.Metric("Поражений",Long.toString(stats.losses())))));
        rows.add(new MenuView.Metrics("combat",List.of(new MenuView.Metric("Урон",String.format(Locale.ROOT,"%.0f",stats.totalDamage())),new MenuView.Metric("Уничтожено",Long.toString(stats.totalEliminations())),new MenuView.Metric("Ничьих",Long.toString(stats.draws())))));
        for(var entry:arenaRegistry.entries()) {
            var record=snapshot.records().get(entry.id());String name=arenaRegistry.definition(entry.id()).metadata().title();
            rows.add(new MenuView.Text("record:"+entry.id(),name+"\nКарта: "+formatTicks(record==null?null:record.bestFullMapTicks())+"    Босс: "+formatTicks(record==null?null:record.bestBossDuelTicks())));
        }
        menu.show("statistics","СТАТИСТИКА","Пройдено арен: "+snapshot.campaign().completedArenaIds().size()+" / 5",List.of(),rows,List.of(new MenuView.Action("back","НАЗАД",flow::menu)),"back");
    }
    private void drawResults() {
        MatchSession displayed=uiReviewResult==null?session:uiReviewResult;
        var campaign=displayProgress().campaign();boolean victory=displayed.outcome==MatchSession.Outcome.VICTORY;
        boolean completed= victory&&displayed.mode==MatchSession.Mode.CAMPAIGN&&campaign.currentArenaId()==null;
        String title=completed?"КАМПАНИЯ ПРОЙДЕНА":victory?"ПОБЕДА":displayed.outcome==MatchSession.Outcome.DRAW?"НИЧЬЯ":"ПОРАЖЕНИЕ";
        var player=displayed.vehicle(0);var rows=new ArrayList<MenuView.Row>();
        rows.add(new MenuView.Metrics("result",List.of(new MenuView.Metric("Время",formatTicks(displayed.activeTicks)),new MenuView.Metric("Урон",String.format(Locale.ROOT,"%.0f",player.damageDealt)),new MenuView.Metric("Уничтожено",Integer.toString(player.eliminations)))));
        if(victory&&displayed.bossParticipantId>=0)rows.add(new MenuView.Text("boss-result","Побеждён: "+displayed.vehicle(displayed.bossParticipantId).name));
        var actions=new ArrayList<MenuView.Action>();
        if(victory&&displayed.mode==MatchSession.Mode.CAMPAIGN&&campaign.currentArenaId()!=null) {
            rows.add(new MenuView.Text("unlocked","Следующая арена: "+arenaRegistry.definition(campaign.currentArenaId()).metadata().title()));
            actions.add(new MenuView.Action("next","ДАЛЬШЕ",this::continueCampaign));
        } else if(!completed)actions.add(new MenuView.Action("retry",retryCheckpoint!=null&&retryCheckpoint.stage()==ProgressStore.CheckpointStage.BOSS?"ПОВТОР БОССА":"ПОВТОР КАРТЫ",this::startMatch));
        if(completed)rows.add(new MenuView.Text("duels","Все дуэли с боссами доступны в отдельном бою."));
        actions.add(new MenuView.Action("menu","ГЛАВНОЕ МЕНЮ",this::returnToMenu));
        menu.show("results:"+displayed.sessionId,title,arena.metadata().title(),List.of(),rows,actions,actions.getFirst().id());
    }
    private ProgressStore.Snapshot displayProgress() {return uiReviewProgress==null?progress.snapshot():uiReviewProgress;}
    private void drawLoading() {
        String title=arenaRegistry.definition(requestedArena).metadata().title();
        float fraction=loading==null?0:loading.fraction();String stage=loading==null?"Подготовка":loading.title();
        int percentage=Math.round(fraction*100);
        if(menu.pageId().equals("loading")&&loadingUiPercent==percentage&&loadingUiStage.equals(stage))return;
        loadingUiPercent=percentage;loadingUiStage=stage;
        menu.show("loading","ЗАГРУЗКА",title,List.of(),List.of(new MenuView.Text("loading-stage",stage),
                new MenuView.Metrics("loading-progress",List.of(new MenuView.Metric("Готово",Math.round(fraction*100)+"%")))),List.of(),null);
    }
    private void drawCredits() {
        menu.show("credits","АВТОРЫ И ЛИЦЕНЗИИ","Wreck Riff "+BuildInfo.current().version(),List.of(),List.of(
                new MenuView.Text("original","Оригинальные машины, арены, иконки и процедурные ресурсы: Wreck Riff."),
                new MenuView.Text("music","METALMANIA — Kevin MacLeod (incompetech.com)\nCreative Commons: By Attribution 4.0. Запись обработана для игры."),
                new MenuView.Text("sfx","Combat SFX: Free Firearm Sound Library / CC0\n25 bang/firework SFX — rubberduck / CC0"),
                new MenuView.Text("art","Текстуры: Poly Haven / CC0\nШрифт: Roboto Condensed / SIL OFL 1.1"),
                new MenuView.Text("runtime","Java 21 / jMonkeyEngine / Minie / LWJGL / Gson"),
                new MenuView.Text("licenses","Полные лицензии и источники поставляются в папке licenses.")),List.of(new MenuView.Action("back","НАЗАД",flow::back)),"back");
    }
    private static String formatTicks(Long ticks) {
        if(ticks==null)return "—";long seconds=ticks/MatchSession.TICKS_PER_SECOND;
        return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
    private void createHud() {
        if(showcase!=null)showcaseText=ui.text("",18,cam.getHeight()-18,Math.max(14,22*store.settings().uiScale),GameUi.PAPER);
        if(hud==null)hud=new HudView(assetManager,guiNode);
        resizeHud();hud.setVisible(true);nextHudDiagnostics=0;
        hudUsesGamepad=input.usingGamepad();
        hud.setHelp(showHelp?hudHelp():List.of());updateHud();
    }
    private void chooseVehicle(Runnable action) {
        vehicleSelectionAction=action;createMenuScene();flow.open(Screen.VEHICLES);
    }
    private void drawVehicles() {
        VehicleDefinition selected=VehicleDefinition.forId(store.settings().selectedVehicleId);
        var rows=new ArrayList<MenuView.Row>();
        for(var definition:VehicleDefinition.values()) {
            var profile=definition.profile(vehicleRules);
            String detail=String.format(Locale.ROOT,"%.0f HP  /  %.0f м/с  /  %s",definition.maximumHp(),vehicleRules.maxSpeed()*profile.speedMultiplier(),definition.specialName());
            rows.add(new MenuView.VehicleCard("vehicle:"+definition.id(),definition.displayName(),detail,definition==selected,
                    ()->VehicleVisual.create(assetManager,profile,0),()->{
                store.settings().selectedVehicleId=definition.id();store.saveSettings();createMenuScene();redraw();
            }));
        }
        rows.add(new MenuView.Text("vehicle-description",selected.specialName()+": "+selected.description()));
        rows.add(new MenuView.Text("vehicle-control","Личный спешл: "+input.displayBinding("Special")+". Заморозка и щит доступны каждой машине."));
        menu.show("vehicles","ВЫБОР МАШИНЫ","Все три машины доступны сразу. Повтор сохраняет ваш выбор.",List.of(),rows,
                List.of(new MenuView.Action("back","НАЗАД",()->{vehicleSelectionAction=null;flow.back();}),
                        new MenuView.Action("start","В БОЙ",()->{Runnable action=vehicleSelectionAction;vehicleSelectionAction=null;flow.back();if(action!=null)action.run();})),"vehicle:"+selected.id());
    }
    private void resizeHud() {
        if(hud!=null&&cam.getWidth()>=320&&cam.getHeight()>=240)hud.resize(cam.getWidth(),cam.getHeight(),store.settings().uiScale);
    }
    private List<HudView.HelpItem> hudHelp() {
        return List.of(new HudView.HelpItem(input.displayBinding("Throttle")+" / "+input.displayBinding("Brake / reverse"),"Drive / brake / reverse"),
                new HudView.HelpItem(input.usingGamepad()?input.displayBinding("Steer"):input.displayBinding("Steer left")+" / "+input.displayBinding("Steer right"),"Steer"),
                new HudView.HelpItem(input.displayBinding("Handbrake")+" / "+input.displayBinding("Turbo"),"Handbrake / turbo"),
                new HudView.HelpItem(input.displayBinding("Machine gun")+" / "+input.displayBinding("Selected weapon"),"Machine gun / selected weapon"),
                new HudView.HelpItem(input.displayBinding("Previous weapon")+" / "+input.displayBinding("Next weapon"),"Cycle six weapons"),
                new HudView.HelpItem(input.displayBinding("Freeze")+" / "+input.displayBinding("Shield"),"Freeze / shield"),
                new HudView.HelpItem(input.displayBinding("Special"),VehicleDefinition.forId(session.vehicle(0).profileId).specialName()),
                new HudView.HelpItem(input.displayBinding("Rear view"),"Rear view"),
                new HudView.HelpItem(input.displayBinding("Recover"),"Hold to recover"),
                new HudView.HelpItem(input.displayBinding("Pause"),"Pause"));
    }
    private void updateHud() {
        if(hud==null||session==null||flow.screen()!=Screen.RUNNING&&!preparingLoadingFrame())return;
        long hudStarted=profileStamp();
        var player=session.vehicle(0);
        if(hudUsesGamepad!=input.usingGamepad()) {hudUsesGamepad=input.usingGamepad();if(showHelp)hud.setHelp(hudHelp());}
        int lock=combat.lockTarget(0);
        String receipt=pickupFeedback==null?"":pickupFeedback.text(session.tick);
        hud.setPickupReceipt(receipt);
        if(encounterSubtitles!=null)encounterSubtitles.accept(session,List.of());
        hud.setSubtitles(store.settings().subtitles&&encounterSubtitles!=null?encounterSubtitles.text(session.tick):"");
        hud.setPickupHighlights(pickupFeedback==null?Set.of():pickupFeedback.highlighted(session.tick));
        var definition=VehicleDefinition.forId(session.vehicle(0).profileId);
        hud.setSpecial(definition.id(),definition.specialName(),input.displayBinding("Special"));
        hud.update(hudPresenter.snapshot(session,world,new MatchHudPresenter.Targeting(lock,combat.napalmAssistTarget(0),combat.ballisticTarget(0)),
                input.displayBinding("Selected weapon"),noticeTime>0?message:progressStatus,elapsed));
        if((debug||navDebug)&&elapsed>=nextHudDiagnostics) {
            StringBuilder diagnostics=new StringBuilder(String.format(Locale.ROOT,"%.0f FPS | tick %d | %.1f m/s | wheels %d | %s\nHP %.1f energy %.1f | target %d | projectiles %d | voices %d\ndropped %.4fs | bodies %d\n%s",1/Math.max(0.0001f,timer.getTimePerFrame()),session.tick,world.velocity(0).length(),world.wheelContacts(0),drivers.get(0).reversing()?"REVERSE":"FORWARD",player.hp,player.turbo,lock,combat.projectiles().size(),audio.voiceCount(),loop.droppedSimulationTime(),world.bodyCount(),input.diagnostics()));
            if(navDebug)for(var state:session.vehicles)if(!state.player&&state.alive())diagnostics.append("\n").append(state.name).append(" ").append(bots.state(state.id)).append(" -> ").append(bots.targetId(state.id));
            hud.setDiagnostics(diagnostics.toString());nextHudDiagnostics=elapsed+.25;
        } else if(!debug&&!navDebug)hud.setDiagnostics("");
        profileStage(StageProfiler.Stage.HUD,hudStarted);
    }
    private String bindingName(String action) {
        Integer key=store.settings().keys.get(action);
        return KeyLabels.name(key);
    }
    private void drawSettings() {
        var s=store.settings();
        if(previousVideo!=null) {
            menu.show("video-confirm","ПРОВЕРКА ЭКРАНА","Через 10 секунд прежний режим вернётся автоматически.",List.of(),List.of(),
                    List.of(new MenuView.Action("revert","ВЕРНУТЬ ПРЕЖНИЙ",this::restoreVideo),new MenuView.Action("keep","ОСТАВИТЬ",()->{previousVideo=null;store.saveSettings();redraw();})),"revert");return;
        }
        String[] titles={"ВИДЕО","ЗВУК","ИНТЕРФЕЙС","ВВОД"};var tabs=new ArrayList<MenuView.Tab>();
        for(int i=0;i<titles.length;i++){int tab=i;tabs.add(new MenuView.Tab("settings-tab:"+i,titles[i],settingsTab==i,()->{settingsTab=tab;redraw();}));}
        var rows=new ArrayList<MenuView.Row>();
        switch(settingsTab) {
            case 0 -> {
                rows.add(new MenuView.Action("fullscreen","Режим: "+(s.fullscreen?"полный экран":"окно"),()->videoChange(()->s.fullscreen=!s.fullscreen)));
                rows.add(new MenuView.Action("resolution","Разрешение: "+s.width+" x "+s.height,()->videoChange(this::nextResolution)));
                rows.add(new MenuView.Action("vsync","VSync: "+(s.vsync?"вкл.":"выкл."),()->videoChange(()->s.vsync=!s.vsync)));
                rows.add(new MenuView.Action("samples","Сглаживание: "+(s.samples==0?"выкл.":"MSAA "+s.samples+"x"),()->videoChange(()->s.samples=s.samples==0?2:s.samples==2?4:s.samples==4?8:0)));
            }
            case 1 -> {
                rows.add(slider("master","Общая громкость",s.master,0,1,.05f,percent(s.master),value->s.master=(float)value));
                rows.add(slider("music","Музыка",s.music,0,1,.05f,percent(s.music),value->s.music=(float)value));
                rows.add(slider("sfx","Эффекты",s.sfx,0,1,.05f,percent(s.sfx),value->s.sfx=(float)value));
            }
            case 2 -> {
                rows.add(slider("ui-scale","Масштаб интерфейса",s.uiScale,.8f,1.5f,.1f,percent(s.uiScale),value->{s.uiScale=(float)value;resizeHud();}));
                rows.add(slider("flashes","Интенсивность вспышек",s.flashes,0,1,.1f,percent(s.flashes),value->s.flashes=(float)value));
                rows.add(new MenuView.Action("glow","Свечение: "+(s.glow?"вкл.":"выкл."),()->{s.glow=!s.glow;settingsChanged();}));
                rows.add(slider("shake","Тряска камеры",s.shake,0,1,.1f,percent(s.shake),value->s.shake=(float)value));
                rows.add(new MenuView.Action("subtitles","Субтитры: "+(s.subtitles?"вкл.":"выкл."),()->{s.subtitles=!s.subtitles;settingsChanged();}));
            }
            default -> {
                rows.add(slider("sensitivity","Чувствительность руля",s.sensitivity,.25f,2,.05f,String.format(Locale.ROOT,"%.2f",s.sensitivity),value->s.sensitivity=(float)value));
                rows.add(slider("dead-zone","Мёртвая зона стика",s.deadZone,0,.45f,.05f,percent(s.deadZone),value->s.deadZone=(float)value));
                rows.add(new MenuView.Action("bindings","НАЗНАЧЕНИЯ КНОПОК",()->flow.open(Screen.CONTROLS)));
            }
        }
        menu.show("settings:"+settingsTab,"НАСТРОЙКИ","Стрелки влево/вправо или мышь изменяют значение.",tabs,rows,List.of(
                new MenuView.Action("back","НАЗАД",()->{flushSettings();flow.back();}),
                new MenuView.Action("reset","СБРОСИТЬ",()->confirm("Вернуть настройки и назначения кнопок по умолчанию?",()->videoChange(()->store.useInMemory(new SettingsStore.Settings()))))),null);
    }
    private MenuView.Slider slider(String id,String label,float value,float minimum,float maximum,float step,String formatted,java.util.function.DoubleConsumer change) {
        return new MenuView.Slider(id,label,value,minimum,maximum,step,formatted,next->{if(Float.compare(value,(float)next)!=0){change.accept(next);settingsChanged();}});
    }
    private void settingsChanged() {settingsDirty=true;settingsSaveAt=elapsed+.3;redraw();}
    private void flushSettings() {if(settingsDirty){store.saveSettings();settingsDirty=false;}}
    private void drawControls() {
        var tabs=List.of(new MenuView.Tab("keyboard","КЛАВИАТУРА / МЫШЬ",!controlsGamepad,()->{controlsGamepad=false;redraw();}),
                new MenuView.Tab("gamepad","ГЕЙМПАД",controlsGamepad,()->{controlsGamepad=true;redraw();}));
        var rows=new ArrayList<MenuView.Row>();
        if(controlsGamepad) {
            rows.add(new MenuView.Text("pad-name",input.padName()));
            for(String action:List.of("Throttle","Brake / reverse","Steer","Handbrake","Turbo","Machine gun","Selected weapon","Previous weapon","Next weapon","Freeze","Shield","Special","Rear view","Recover","Pause"))
                rows.add(new MenuView.Text("pad:"+action,controlName(action)+"   "+input.displayBinding(action,true)));
        } else {
            rows.add(new MenuView.Text("mouse","Мышь: LMB — пулемёт, RMB — выбранное оружие."));
            for(var entry:store.settings().keys.entrySet()) {
                String action=entry.getKey();rows.add(new MenuView.Action("binding:"+action,controlName(action)+"   ["+bindingName(action)+"]",
                        entry.getValue()==0?"Не назначено: выберите свободную клавишу.":"",true,()->{rebinding=action;redraw();}));
            }
        }
        menu.show("controls:"+controlsGamepad,"УПРАВЛЕНИЕ",rebinding==null?"Выберите действие. ESC всегда возвращает назад.":"Нажмите клавишу: "+controlName(rebinding)+". ESC — отмена.",tabs,rows,
                List.of(new MenuView.Action("back","НАЗАД",()->{rebinding=null;flow.back();})),null);
    }
    private static String controlName(String action) {
        return switch(action) {
            case "Throttle"->"Газ";case "Brake / reverse"->"Тормоз / задний ход";case "Steer"->"Руль";case "Steer left"->"Руль влево";case "Steer right"->"Руль вправо";
            case "Handbrake"->"Ручник";case "Turbo"->"Турбо";case "Machine gun"->"Пулемёт";case "Selected weapon"->"Выстрел";
            case "Previous weapon"->"Предыдущее оружие";case "Next weapon"->"Следующее оружие";case "Freeze"->"Заморозка";case "Shield"->"Щит";case "Special"->"Личный спешл";
            case "Rear view"->"Вид назад";case "Recover"->"Восстановление";case "Pause"->"Пауза";
            default->action.startsWith("Select ")?"Выбрать "+action.substring(7):action;
        };
    }
    private void nextResolution() {
        List<int[]> sizes=new ArrayList<>();
        var modes=org.lwjgl.glfw.GLFW.glfwGetVideoModes(org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor());
        if(modes!=null) for(int i=0;i<modes.limit();i++) {
            var mode=modes.get(i); int width=mode.width(),height=mode.height();
            if(width>=640 && height>=480 && sizes.stream().noneMatch(a->a[0]==width&&a[1]==height)) sizes.add(new int[]{width,height});
        }
        if(!store.settings().fullscreen&&sizes.stream().noneMatch(a->a[0]==640&&a[1]==480))sizes.add(new int[]{640,480});
        if(sizes.isEmpty()) sizes.add(new int[]{1280,720});
        sizes.sort(Comparator.comparingInt(a->a[0]*a[1]));
        var s=store.settings(); int index=-1;
        for(int i=0;i<sizes.size();i++) if(sizes.get(i)[0]==s.width&&sizes.get(i)[1]==s.height) index=i;
        int[] size=sizes.get((index+1)%sizes.size()); s.width=size[0];s.height=size[1];
    }
    private void videoChange(Runnable change) {
        if(previousVideo==null) previousVideo=store.settings().copy();
        change.run(); videoDeadline=elapsed+10; applyVideo(); redraw();
    }
    private void applyVideo() {
        if(stageProfiler!=null)stageProfiler.detachGpuForVideoRestart();
        var s=store.settings(); settings.setResolution(s.width,s.height); settings.setFullscreen(s.fullscreen); settings.setVSync(s.vsync); settings.setSamples(s.samples); restart();
    }
    private void restoreVideo() {
        if(previousVideo==null) return;
        var old=previousVideo;previousVideo=null;store.replaceSettings(old);applyVideo();redraw();notice("Previous video mode restored.");
    }
    private void key(int code) {
        if(options.automated()) return;
        if(flow.screen()==Screen.RUNNING&&session!=null&&session.phase==MatchSession.Phase.INTRO&&(code==KeyInput.KEY_RETURN||code==KeyInput.KEY_SPACE)) {
            runtime.skipIntro();input.clear();return;
        }
        if(rebinding!=null) {
            if(code==KeyInput.KEY_ESCAPE) {rebinding=null;redraw();return;}
            String action=rebinding;
            boolean conflict=store.settings().keys.entrySet().stream().anyMatch(e->!e.getKey().equals(action)&&e.getValue()==code);
            if(conflict) { notice("That key is already assigned. Choose another key."); return; }
            store.settings().keys.put(action,code);store.saveSettings();rebinding=null;redraw();return;
        }
        if(code==KeyInput.KEY_ESCAPE) uiAction("back");
        else if(code==KeyInput.KEY_UP)uiAction("up");else if(code==KeyInput.KEY_DOWN)uiAction("down");
        else if(code==KeyInput.KEY_LEFT)uiAction("left");else if(code==KeyInput.KEY_RIGHT)uiAction("right");
        else if(code==KeyInput.KEY_TAB&&flow.screen()!=Screen.RUNNING)menu.cycle(input.shiftHeld());
        else if(code==KeyInput.KEY_RETURN || code==KeyInput.KEY_NUMPADENTER) { if(flow.screen()!=Screen.RUNNING) uiAction("activate"); }
        else if(input.gameplayOwnsKey(code)) return;
        else if(code==KeyInput.KEY_F1) {showHelp=!showHelp;if(flow.screen()==Screen.RUNNING)redraw();}
        else if(code==KeyInput.KEY_F3) {debug=!debug;}
        else if(code==KeyInput.KEY_F4) {navDebug=!navDebug;if(navNode!=null)navNode.setCullHint(navDebug?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}
        else if(code==KeyInput.KEY_F12) capture();
    }
    private void uiAction(String action) {
        if(options.automated()) return;
        dispatchUiAction(action);
    }
    /** Shared by real input and the explicit UI review driver; external automated input stays ignored. */
    private void dispatchUiAction(String action) {
        boolean driving=flow.screen()==Screen.RUNNING;
        if(driving&&action.equals("accept")&&session.phase==MatchSession.Phase.INTRO){runtime.skipIntro();input.clear();return;}
        if(action.equals("back") || action.equals("pause")) {
            if(driving) pause(""); else if(flow.screen()==Screen.PAUSED) flow.resume();
            else if(flow.screen()==Screen.SETTINGS||flow.screen()==Screen.CONTROLS||flow.screen()==Screen.CREDITS||flow.screen()==Screen.CONFIRM||flow.screen()==Screen.VEHICLES) {rebinding=null;vehicleSelectionAction=null;flushSettings();if(previousVideo!=null)restoreVideo();flow.back();}
            else if(flow.screen()==Screen.MAPS||flow.screen()==Screen.STATISTICS)flow.menu();
            return;
        }
        if(driving) return;
        switch(action) {
            case "up" -> {menu.move(UiFocusModel.Direction.UP);audio.ui(false);}
            case "down" -> {menu.move(UiFocusModel.Direction.DOWN);audio.ui(false);}
            case "left" -> {menu.move(UiFocusModel.Direction.LEFT);audio.ui(false);}
            case "right" -> {menu.move(UiFocusModel.Direction.RIGHT);audio.ui(false);}
            case "activate" -> {audio.ui(true);menu.activate();}
            case "click" -> {audio.ui(true);menu.click(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);}
            case "pointer-move" -> menu.drag(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);
            case "release" -> {menu.release();flushSettings();}
            case "scroll-up" -> menu.scroll(-64*store.settings().uiScale);
            case "scroll-down" -> menu.scroll(64*store.settings().uiScale);
            default -> {}
        }
    }
    private void pause(String reason) { if(input==null)return;input.clear();flow.pause(reason); }
    @Override public void loseFocus() { super.loseFocus(); enqueue(()->{pause("Focus lost. Resume when ready.");return null;}); }
    private void confirm(String title,Runnable action) { confirmationTitle=title;confirmation=action;flow.open(Screen.CONFIRM); }
    private void returnToMenu() { cleanupMatch();flow.menu(); }
    private void notice(String text) {message=text;noticeTime=5;if(menu!=null&&flow.screen()!=Screen.RUNNING)menu.setStatus(text);}
    private void pollProgressStatus() {
        var status=progressNotice.update(progress.warning(),progress.savePending(),progress.writable(),elapsed);
        progressStatus=status.indicator();
        if(!status.detailToLog().isEmpty())Logger.getLogger(getClass().getName()).warning(status.detailToLog());
        if(!status.announcement().isEmpty())notice(status.announcement());
        if(menu!=null&&flow.screen()!=Screen.RUNNING)menu.setStatus(noticeTime>0?message:progressStatus);
    }
    private void capture() {
        capture("manual");
    }
    private void capture(String label) {
        requestCapture(label);
    }
    private String requestCapture(String label) {
        if(screenshots!=null) {
            String prefix="WreckRiff-"+BuildInfo.current().version()+"-"+label+"-"+System.currentTimeMillis()+"-";
            screenshots.setFileName(prefix);screenshots.takeScreenshot();
            if(diagnostic!=null) {diagnosticCaptures.add(prefix);diagnostic.put("capturePrefixes",List.copyOf(diagnosticCaptures));}
            return prefix;
        }
        return null;
    }
    private void writeReport() {
        if(!options.dev()||session==null||world==null||report==null)return;
        try {report.write(store.directory().resolve("session-summary.json"),session,loop,org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),cam.getWidth(),cam.getHeight(),store.settings().vsync,audioRenderer!=null,audio.voiceCount(),world.bodyCount());
            if(options.automated()&&session.outcome!=MatchSession.Outcome.NONE)Files.copy(store.directory().resolve("session-summary.json"),store.directory().resolve("match-"+diagnosticResults+".json"),StandardCopyOption.REPLACE_EXISTING);}
        catch(IOException e) {Logger.getLogger(getClass().getName()).warning("Cannot write report: "+e.getMessage());}
    }
    private void cleanupMatch() {
        cleanupMatch(true);
    }
    private void cleanupMatch(boolean cancelLoading) {
        if(cancelLoading&&loading!=null){loading.close();loading=null;}
        PhysicsWorld unloading=world;
        if(soak!=null&&world!=null)unloadedResourceKey=loadedResourceKey==null?resourceKey():loadedResourceKey;
        if(specialPresentation!=null){specialPresentation.close();specialPresentation=null;}
        if(bossActionPresentation!=null){bossActionPresentation.close();bossActionPresentation=null;}
        if(pickupPresentation!=null){pickupPresentation.close();pickupPresentation=null;}
        pickupFeedback=null;
        encounterSubtitles=null;
        if(hud!=null){hud.close();hud=null;}
        if(enemyHealthBars!=null){enemyHealthBars.clear();enemyHealthBars.setVisible(false);}
        enemyHealthMarkers.clear();
        if(!finishAudioCapture())diagnosticCompletion.shutdownFailed();
        if(audio!=null)audio.stopMatch(); if(combatVisuals!=null){combatVisuals.close();combatVisuals=null;}
        if(runtime!=null){runtime.close();runtime=null;world=null;combat=null;}
        else if(world!=null){world.close();world=null;}
        if(soak!=null&&unloading!=null) {
            unloadedBodies=unloading.bodyCount();unloadedListeners=unloading.space().countCollisionListeners();
            unloadedTickListeners=unloading.space().countTickListeners();
        }
        matchNode.detachAllChildren();vehicleModels.clear();wheels.clear();drivers.clear();
        session=null;arenaSystems=null;bots=null;content=null;navNode=null;visualTour=null;showcase=null;artShowcase=null;vehicleShowcase=null;showcaseText=null;
        loadedResourceKey=null;
    }
    private void fail(Exception exception) {
        Logger.getLogger(getClass().getName()).log(Level.SEVERE,"Game failure",exception);
        if(launchReport!=null)launchReport.error(exception.getClass().getSimpleName()+": "+exception.getMessage());
        error=exception.getClass().getSimpleName()+": "+exception.getMessage();
        if(options.automated()) {
            if(diagnostic!=null)diagnostic.error(error);
            cleanupMatch();
            finishDiagnostic(false);return;
        }
        if(!initialized) {super.handleError("Wreck Riff could not initialize: "+error,exception);return;}
        cleanupMatch();
        if(ui!=null){if(input!=null)input.clear();if(audio!=null)audio.pause();flow.error();}
        else {System.err.println(error);stop();}
    }
    @Override public void destroy() {
        boolean shutdownComplete=false;
        try {
            if(stageProfiler!=null){stageProfiler.close();setAppProfiler(null);}
            flushSettings();
            if(!finishAudioCapture())diagnosticCompletion.shutdownFailed();
            writeReport();cleanupMatch();if(audio!=null)audio.close();if(input!=null)input.close();
            if(enemyHealthBars!=null)enemyHealthBars.close();
            super.destroy();
            shutdownComplete=true;
            diagnosticCompletion.shutdownCompleted();
        } catch(RuntimeException | Error failure) {
            diagnosticCompletion.shutdownFailed();
            if(launchReport!=null)launchReport.error("Shutdown failed: "+failure);
            if(diagnostic!=null)diagnostic.error("Shutdown failed: "+failure);
            throw failure;
        } finally {
            progress.close();
            if(progress.savePending()) {
                Logger.getLogger(getClass().getName()).warning(progress.warning());
                if(diagnostic!=null) {diagnostic.error("Progress did not flush during shutdown: "+progress.warning());diagnosticCompletion.shutdownFailed();}
            }
            if(launchReport!=null)try {
                launchReport.closed(initialized&&shutdownComplete,!progress.savePending());
            }catch(IOException failure){System.err.println("Cannot write normal launch evidence: "+failure.getMessage());}
            if(fileLog!=null){Logger.getLogger("").removeHandler(fileLog);fileLog.close();}
            if(warningLog!=null)Logger.getLogger("").removeHandler(warningLog);
            if(options.automated()) {
                String status=diagnostic==null?"FAIL":diagnostic.completionStatus(diagnosticCompletion.passed());
                writeDiagnostic(status);
                System.out.println("GRAPHICS_DIAGNOSTIC_"+status);
            }
            terminated.countDown();
        }
    }
    @Override public void handleError(String message,Throwable failure) {
        if(launchReport!=null)launchReport.error(message+": "+failure);
        if(options.automated()) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE,message,failure);
            if(diagnostic!=null)diagnostic.error(message+": "+failure);
            finishDiagnostic(false);
        } else super.handleError(message,failure);
    }
    @Override public void requestClose(boolean escape) {
        if(diagnostic!=null&&!diagnosticEnding) {
            diagnostic.error("Window close requested before diagnostic completion (escape="+escape+")");
            diagnostic.put("windowCloseRequestedAtSeconds",elapsed);
        }
        super.requestClose(escape);
    }
    public boolean awaitDiagnostic() throws InterruptedException {
        int timeout=options.uiReview()?UiReview.MAXIMUM_SECONDS+60:options.showcase()||options.artShowcase()||options.vehicleShowcase()?240:options.soakSeconds()>0?options.soakSeconds()+120:options.benchmarkSeconds()>0?30+options.benchmarkSeconds()+Math.max(120,options.benchmarkSeconds()/2):options.smokeSeconds()+60;
        if(!terminated.await(timeout,java.util.concurrent.TimeUnit.SECONDS)) {
            System.err.println("Diagnostic shutdown timed out");diagnosticCompletion.shutdownFailed();
            try {
                StringBuilder trace=new StringBuilder("Diagnostic timeout after "+timeout+" seconds\n");
                Thread.getAllStackTraces().entrySet().stream().sorted(Comparator.comparing(entry->entry.getKey().getName())).limit(128).forEach(entry->{
                    trace.append('\n').append(entry.getKey().getName()).append(" state=").append(entry.getKey().getState()).append('\n');
                    Arrays.stream(entry.getValue()).limit(64).forEach(frame->trace.append("  at ").append(frame).append('\n'));
                });
                Files.writeString(store.directory().resolve("timeout-threads.txt"),trace);
                if(diagnostic!=null){diagnostic.error("Diagnostic shutdown timed out");writeDiagnostic("FAIL");}
            } catch(IOException|RuntimeException failure){System.err.println("Cannot write timeout evidence: "+failure);}
            stop();return false;
        }
        return diagnosticCompletion.passed();
    }
    private void advanceDiagnostic(boolean drawable) {
        if(diagnosticEnding)return;
        if(uiReview!=null) {
            try {
                uiReview.advance(uiReviewHost,elapsed);
                if(uiReview.complete()) {
                    diagnostic.put("uiReviewCaptureStatus",uiReview.failed()?"FAIL":"CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING");
                    if(uiReview.failed())diagnostic.error("UI review failed; see ui-review-manifest.json");
                    finishDiagnostic(!uiReview.failed());
                }
            } catch(IOException failure){fail(failure);}
            return;
        }
        if(flow.screen()==Screen.ERROR) {diagnostic.error("Runtime entered error screen: "+error);finishDiagnostic(false);return;}
        if(soak!=null) {advanceSoak(drawable);return;}
        if(options.vehicleShowcase()) {
            if(flow.screen()==Screen.PAUSED&&drawable)flow.resume();
            if(vehicleShowcase!=null&&vehicleShowcase.complete()) {
                diagnostic.put("vehicleShowcase",vehicleShowcase.evidence());
                if(!vehicleShowcase.demonstrated())diagnostic.error("Vehicle showcase missed required real special events");
                finishDiagnostic(vehicleShowcase.demonstrated()&&vehicleSelectionCaptured&&audioRenderer!=null&&undrawableSeconds==0);
            }
            return;
        }
        if(options.artShowcase()) {
            if(flow.screen()==Screen.PAUSED&&drawable)flow.resume();
            if(artShowcase!=null&&artShowcase.complete()) {
                diagnostic.put("artShowcase",artShowcase.evidence());
                if(!artShowcase.demonstrated())diagnostic.error("Art showcase missed required real pickup events");
                finishDiagnostic(artShowcase.demonstrated()&&audioRenderer!=null&&undrawableSeconds==0);
            }
            return;
        }
        if(options.showcase()) {
            if(flow.screen()==Screen.PAUSED&&drawable)flow.resume();
            if(showcase!=null&&showcase.complete()) {
                diagnostic.put("showcase",showcase.evidence());
                if(!showcase.demonstrated())diagnostic.error("Showcase did not produce every required combat event");
                finishDiagnostic(showcase.demonstrated()&&audioRenderer!=null&&undrawableSeconds==0);
            }
            return;
        }
        boolean benchmark=options.benchmarkSeconds()>0;
        if(benchmark&&diagnostic.measuredActiveSeconds()>=options.benchmarkSeconds()) {
            // Completion is evidence, not release approval; the strict gate also requires external process memory.
            finishDiagnostic(undrawableSeconds==0);return;
        }
        if(!benchmark&&elapsed>=options.smokeSeconds()) {
            diagnostic.error("Time limit reached before complete match and 20 restarts");finishDiagnostic(false);return;
        }
        if(!drawable)return;
        if(flow.screen()==Screen.PAUSED) {
            if(diagnosticPausedAt>0&&elapsed-diagnosticPausedAt<0.25)return;
            if(diagnosticPausedAt>0) {
                if(session.tick!=pauseTick)throw new IllegalStateException("Simulation advanced while paused");
                float musicAdvance=audio.musicPlaybackSeconds()-pauseMusicSeconds;
                if(audioRenderer!=null&&Math.abs(musicAdvance)>.03f)throw new IllegalStateException("Music advanced while paused: "+musicAdvance);
                diagnostic.put("pausedMusicAdvanceSeconds",musicAdvance);
                diagnostic.put("pauseTickPreserved",true);diagnosticPausedAt=0;
            }
            flow.resume();
        }
        if(!benchmark&&!pauseChecked&&flow.screen()==Screen.RUNNING&&session.tick>=120) {
            pauseChecked=true;pauseTick=session.tick;diagnosticPausedAt=elapsed;pause("Diagnostic focus/pause check");
            pauseMusicSeconds=audio.musicPlaybackSeconds();return;
        }
        if(benchmark) {
            long block=(long)(diagnostic.measuredActiveSeconds()/120);
            if(flow.screen()!=Screen.LOADING&&(flow.screen()==Screen.RESULTS||block!=benchmarkBlock)) {
                benchmarkBlock=block;writeReport();diagnosticRetries++;
                var mode=requestedArena.equals(ProgressStore.LEGACY_ARENA)?ProgressStore.Mode.LEGACY:
                        block%2==0?ProgressStore.Mode.ARENA:ProgressStore.Mode.BOSS_DUEL;
                // Ordinary fresh arena/duel menu paths; no synthetic damage, spawning, or forced results.
                startMatch(requestedArena,mode,null,requestedProfileId);
            }
            return;
        }
        if(flow.screen()==Screen.RESULTS&&diagnosticResults>0&&diagnosticRetries==0) {diagnosticRetries++;startMatch();}
        else if(diagnosticRetries>0&&flow.screen()==Screen.RUNNING&&elapsed-screenSince>=1) {
            if(diagnosticRetries<20) {pause("");diagnosticRetries++;startMatch();}
            else {
                returnToMenu();
                if(world!=null||!drivers.isEmpty()||matchNode.getQuantity()!=0||audio.voiceCount()!=0)
                    throw new IllegalStateException("Resources survive return to menu");
                diagnostic.put("menuCleanupVerified",true);finishDiagnostic(pauseChecked&&diagnosticResults>0);
            }
        }
    }
    private ResourceRetention.Key resourceKey() {
        var topology=new TreeMap<String,String>();
        if(arenaSystems!=null) {
            var state=arenaSystems.snapshot();
            state.objects().forEach((id,object)->topology.put("object:"+id,object.destroyed()+"/"+object.open()));
            state.hazards().forEach((id,hazard)->topology.put("hazard:"+id,hazard.phase().name()));
        }
        return new ResourceRetention.Key(session.arenaId,session.mode.name(),session.phase.name(),session.vehicle(0).profileId,topology.toString());
    }
    private ResourceRetention.Sample resourceSample(String stage,ResourceRetention.Key key,int bodies,int listeners,int ticks,boolean collected) {
        var statistics=renderer.getStatistics();String[] labels=statistics.getLabels();int[] values=new int[labels.length];statistics.getData(values);
        int textures=-1;for(int i=0;i<labels.length;i++)if(labels[i].equals("Textures (M)"))textures=values[i];
        long directBytes=java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)
                .stream().mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        var writer=progress.diagnostics();
        return new ResourceRetention.Sample(stage,elapsed,System.currentTimeMillis(),key,bodies,listeners,ticks,
                combat==null?0:combat.projectiles().size(),audio==null?0:audio.voiceCount(),com.jme3.bullet.NativePhysicsObject.countTrackers(),
                directBytes,textures,writer.pendingSnapshotCount(),writer.workerScheduled(),writer.revision(),writer.persistedRevision(),collected);
    }
    private void advanceSoak(boolean drawable) {
        if(!drawable)return;
        if(soakUnloadReadyAt>0) {
            if(elapsed<soakUnloadReadyAt)return;
            if(world!=null||runtime!=null||!drivers.isEmpty()||matchNode.getQuantity()!=0)
                resourceRetention.error("Runtime or scene survived return to menu");
            if(unloadedResourceKey!=null)resourceRetention.add(resourceSample("UNLOAD",unloadedResourceKey,unloadedBodies,
                    unloadedListeners,unloadedTickListeners,true),soak.resourcesWarmed());
            soakUnloadReadyAt=0;unloadedResourceKey=null;
            if(resourceRetention.failed()) {
                diagnostic.error("Soak resource checks failed: "+resourceRetention.evidence().get("errors"));finishDiagnostic(false);return;
            }
            if(soakFinishing) {
                boolean full=options.soakSeconds()>=SoakSchedule.MINIMUM_SECONDS;
                boolean complete=!full||soak.coverageComplete()&&resourceRetention.passed();
                if(!complete)diagnostic.error("Soak ended before required arena/retry coverage or warmed resource comparisons");
                diagnostic.put("menuCleanupVerified",true);
                finishDiagnostic(complete&&audioRenderer!=null&&undrawableSeconds==0);return;
            }
            soakSelection=soakNext;soakNext=null;
            if(soakSelection.retry())startMatch();
            else startMatch(soakSelection.arenaId(),soakSelection.mode(),null,soakSelection.profileId());
            return;
        }
        if(flow.screen()==Screen.LOADING||session==null)return;
        if(flow.screen()==Screen.PAUSED)flow.resume();
        soakFinishing=soak.measuredSeconds()>=options.soakSeconds();
        if(!soakFinishing&&flow.screen()!=Screen.RESULTS&&elapsed-soakLoadedAt<SoakSchedule.MATCH_DWELL_SECONDS)return;
        if(!soakFinishing)soakNext=soak.next();
        writeReport();returnToMenu();
        // Retention diagnostics only, outside the measured performance benchmark and outside a combat tick.
        // Reporting this request preserves the distinction between collection pauses and retained-resource growth.
        System.gc();soakUnloadReadyAt=elapsed+SoakSchedule.UNLOAD_SETTLE_SECONDS;
    }
    private void finishDiagnostic(boolean success) {
        if(!finishAudioCapture())success=false;
        if(!diagnosticCompletion.requestStop(success))return;
        diagnosticEnding=true;
        if(diagnostic!=null)diagnostic.put("inputDevices",input==null?"initialization failed":input.diagnostics());
        writeDiagnostic(success?"CHECKS_PASSED_AWAITING_SHUTDOWN":"CHECKS_FAILED_AWAITING_SHUTDOWN");
        stop();
    }
    private boolean finishAudioCapture() {
        if(audioCapture==null)return true;
        AudioCapture capture=audioCapture;audioCapture=null;
        try {audio.setCapture(null);capture.close();return true;}
        catch(IOException|RuntimeException failure) {
            if(diagnostic!=null)diagnostic.error("Showcase audio capture failed: "+failure);
            Logger.getLogger(getClass().getName()).log(Level.WARNING,"Showcase audio capture failed",failure);return false;
        }
    }
    private void writeDiagnostic(String status) {
        if(diagnostic!=null) {
            if(stageProfiler!=null)diagnostic.put("profiling",stageProfiler.snapshot());
            diagnostic.put("completedMatches",diagnosticResults);diagnostic.put("restarts",diagnosticRetries);
            diagnostic.put("elapsedSeconds",elapsed);diagnostic.put("undrawableSeconds",undrawableSeconds);
            try {diagnostic.write(store.directory(),status);}
            catch(IOException e) {diagnosticCompletion.shutdownFailed();System.err.println("Cannot write diagnostic evidence: "+e.getMessage());}
        }
    }
    private static String percent(float value) {return Math.round(value*100)+"%";}

    private UiReview.Host createUiReviewHost() {
        return new UiReview.Host() {
            public void command(UiReview.Command command) {
                switch(command.kind()) {
                    case FIXTURE->applyUiReviewFixture(command.value());
                    case FOCUS->{if(!menu.focus(command.value()))throw new IllegalStateException("Missing focus target "+command.value()+" on "+menu.pageId());}
                    case ACTIVATE->{if(!menu.focus(command.value()))throw new IllegalStateException("Missing action "+command.value()+" on "+menu.pageId());dispatchUiAction("activate");}
                    case DISPATCH->dispatchUiAction(command.value());
                }
            }
            public UiReview.Observation observe() {
                long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                boolean visible=window!=0&&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_VISIBLE)!=0
                        &&org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_ICONIFIED)==0;
                return new UiReview.Observation(flow.screen().name(),uiReviewHud&&flow.screen()==Screen.RUNNING?"hud":menu.pageId(),
                        uiReviewHud&&flow.screen()==Screen.RUNNING?"":Objects.requireNonNullElse(menu.selectedId(),""),
                        uiReviewHud&&flow.screen()==Screen.RUNNING?0:menu.scrollOffset(),cam.getWidth(),cam.getHeight(),store.settings().uiScale,visible,
                        Map.of("vsync",store.settings().vsync,"fullscreen",store.settings().fullscreen,"videoConfirmationPending",previousVideo!=null,
                                "storedWins",progress.snapshot().stats().wins(),"storedMatches",progress.snapshot().stats().completedMatches(),
                                "settingsTab",settingsTab,"gamepadControlsTab",controlsGamepad));
            }
            public String capture(String caseId) {return requestCapture("ui-review-"+caseId);}
        };
    }
    private void applyUiReviewFixture(String fixture) {
        if(!options.uiReview())throw new IllegalStateException("UI fixtures require explicit --ui-review");
        uiReviewHud=false;uiReviewResult=null;noticeTime=0;message="";
        if(fixture.equals("fresh")||fixture.equals("unlocked")) {
            uiReviewProgress=fixture.equals("fresh")?UiReviewFixtures.fresh():SoakProfile.unlockedSnapshot();flow.menu();return;
        }
        if(fixture.equals("loading")){flow.loading();return;}
        if(fixture.equals("error")){error="Тестовый пример: не удалось прочитать контрольную точку. Повторите попытку или вернитесь в меню.";flow.error();return;}
        if(fixture.startsWith("result:")) {
            arena=arenaRegistry.definition(ProgressStore.LEGACY_ARENA);
            uiReviewResult=new MatchSession(42,arena,MatchSession.Mode.LEGACY,Configs.load("combat",CombatRules.class));
            uiReviewResult.outcome=MatchSession.Outcome.valueOf(fixture.substring(7));
            uiReviewResult.activeTicks=21720;uiReviewResult.vehicle(0).damageDealt=12345.6f;uiReviewResult.vehicle(0).eliminations=4;
            flow.results();return;
        }
        if(fixture.startsWith("hud:")) {
            String[] parts=fixture.split(":");var definition=VehicleDefinition.forId(parts[1]);int variant=Integer.parseInt(parts[2]);
            flow.running();uiReviewHud=true;
            hud.setSpecial(definition.id(),definition.specialName(),input.displayBinding("Special"));
            hud.setPickupReceipt(variant%2==0?"+4 ракеты · +2 мины · +25 HP":"");
            hud.setSubtitles(variant%2==0?"Последний перекрёсток принадлежит мне. Попробуй пройти через эту арену!":"");
            hud.setPickupHighlights(variant%2==0?Set.of(WeaponType.HOMING,WeaponType.MINE):Set.of());
            hud.update(UiReviewFixtures.hud(definition.id(),variant));return;
        }
        if(fixture.equals("pause")) {
            arena=arenaRegistry.definition(ProgressStore.CAMPAIGN_ARENAS.getFirst());
            flow.running();flow.pause("Геймпад отключён. Подключите устройство и продолжите бой.");return;
        }
        throw new IllegalArgumentException("Unknown UI render fixture "+fixture);
    }
}
