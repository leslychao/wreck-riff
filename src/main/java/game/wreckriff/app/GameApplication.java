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
import game.wreckriff.diagnostics.DiagnosticEvidence;
import game.wreckriff.diagnostics.DiagnosticCompletion;
import game.wreckriff.diagnostics.CombatShowcase;
import game.wreckriff.input.*;
import game.wreckriff.presentation.*;
import game.wreckriff.simulation.*;
import game.wreckriff.ui.GameUi;
import game.wreckriff.ui.HudView;
import game.wreckriff.ui.MatchHudPresenter;
import game.wreckriff.ui.ProgressNotice;
import game.wreckriff.ui.MenuView;
import game.wreckriff.ui.UiFocusModel;
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
    private ChaseCamera chase;
    private DiagnosticCameraTour visualTour;
    private CombatShowcase showcase;
    private VideoRecorderAppState videoRecorder;
    private AudioCapture audioCapture;
    private BitmapText showcaseText;
    private final List<String> diagnosticCaptures=new ArrayList<>();
    private SessionReport report;
    private ScreenshotAppState screenshots;
    private FileHandler fileLog;
    private Handler warningLog;
    private BitmapText noticeText;
    private HudView hud;
    private final MatchHudPresenter hudPresenter=new MatchHudPresenter();
    private double nextHudDiagnostics;
    private boolean hudUsesGamepad;
    private final ProgressNotice progressNotice=new ProgressNotice();
    private String progressStatus="",displayedFooter="";
    private boolean debug,navDebug,showHelp,smokeStarted;
    private Node navNode;
    private float noticeTime;
    private double elapsed,videoDeadline;
    private SettingsStore.Settings previousVideo;
    private String message="",error="",rebinding;
    private int controlsPage;
    private Runnable confirmation;
    private String confirmationTitle;
    private boolean rearView;
    private int viewWidth,viewHeight;
    private Screen renderedScreen;
    private final java.util.concurrent.CountDownLatch terminated=new java.util.concurrent.CountDownLatch(1);
    private final DiagnosticCompletion diagnosticCompletion=new DiagnosticCompletion();
    private DiagnosticEvidence diagnostic;
    private double screenSince,diagnosticPausedAt,undrawableSeconds;
    private long loadingStarted;
    private int diagnosticRetries,diagnosticResults,baselineBodies,baselineListeners;
    private long pauseTick;
    private float pauseMusicSeconds;
    private boolean pauseChecked,diagnosticEnding,initialized;

    public GameApplication(Main.Options options,SettingsStore store) {
        this.options=options;this.store=store;
        Path progressDirectory=options.dev()&&!options.automated()
                ?store.directory().resolve("diagnostics").resolve("interactive-progress"):store.directory();
        progress=new ProgressStore(progressDirectory,checkpoint->MatchCheckpoint.validateReferences(arenaRegistry,checkpoint));
    }
    @Override public void simpleInitApp() {
        if(options.automated()) {
            long window=org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            if(window==0) throw new IllegalStateException("Benchmark requires a real GLFW window");
            // Automated fullscreen measurement must remain drawable when the operator uses another app.
            // Explicit minimize still pauses/fails the run; restore is performed only once at startup.
            org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(window,org.lwjgl.glfw.GLFW.GLFW_AUTO_ICONIFY,org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwRestoreWindow(window);
        }
        flyCam.setEnabled(false); setDisplayFps(false); setDisplayStatView(false);
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        viewPort.setBackgroundColor(new ColorRGBA(0.033f,0.045f,0.065f,1));
        SceneLighting.install(assetManager,rootNode,viewPort);
        rootNode.attachChild(menuNode); rootNode.attachChild(matchNode);
        try {
            matchRules=MatchRules.load(); vehicleRules=VehicleRules.load(); loop=new SimulationLoop(matchRules);
            initializeLogging();
            if(options.automated()) {
                diagnostic=new DiagnosticEvidence(options.benchmarkSeconds()>0,options.showcase()?CombatShowcase.SECONDS:options.benchmarkSeconds()>0?options.benchmarkSeconds():options.smokeSeconds());
                if(options.showcase())diagnostic.put("mode","showcase");
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
            ui=new GameUi(assetManager,guiNode); ui.resize(cam.getWidth(),cam.getHeight());
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
        Node hero=VehicleVisual.create(assetManager,0); hero.setLocalTranslation(2,1,0); hero.setLocalScale(1.35f); menuNode.attachChild(hero);
        Geometry floor=new Geometry("show-floor",SurfaceMesh.box(30,0.1f,30,4));
        floor.setMaterial(new SurfaceMaterials(assetManager).material("concrete"));
        floor.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive); menuNode.attachChild(floor);
    }
    private void continueCampaign() {
        var campaign=progress.snapshot().campaign();
        if(campaign.currentArenaId()==null)return;
        startMatch(campaign.currentArenaId(),ProgressStore.Mode.CAMPAIGN,campaign.checkpoint());
    }
    private void newCampaign() {
        progress.beginNewCampaign();continueCampaign();
    }
    private void startArena(String id,boolean bossDuel) {
        startMatch(id,id.equals(ProgressStore.LEGACY_ARENA)?ProgressStore.Mode.LEGACY:
                bossDuel?ProgressStore.Mode.BOSS_DUEL:ProgressStore.Mode.ARENA,null);
    }
    private void startMatch() {
        startMatch(requestedArena,requestedMode,retryCheckpoint);
    }
    private void startMatch(String arenaId,ProgressStore.Mode mode,ProgressStore.Checkpoint checkpoint) {
        if(flow.screen()==Screen.LOADING)return;
        ArenaDefinition selected=arenaRegistry.definition(arenaId);
        if(checkpoint!=null)MatchCheckpoint.validateReferences(arenaRegistry,checkpoint);
        requestedArena=arenaId;requestedMode=mode;retryCheckpoint=checkpoint;
        loadingStarted=System.nanoTime();
        long seed=checkpoint!=null?checkpoint.seed():options.fixedSeed()?options.seed():System.nanoTime();
        boolean bossCheckpoint=checkpoint!=null&&checkpoint.stage()==ProgressStore.CheckpointStage.BOSS;
        List<ArenaDefinition.Spawn> spawns=new ArrayList<>(selected.spawns());
        Collections.shuffle(spawns,new Random(seed));
        List<MatchLoading.Stage> stages=new ArrayList<>();
        stages.add(new MatchLoading.Stage("Подготовка арены",()->{
            cleanupMatch();arena=selected;
            attempt=progress.beginAttempt(arenaId,mode,bossCheckpoint);
            session=new MatchSession(seed,arena,MatchSession.Mode.valueOf(mode.name()),Configs.load("combat",CombatRules.class),
                    attempt.id(),bossCheckpoint,checkpoint==null?0:checkpoint.liveryId());
            report=new SessionReport();world=new PhysicsWorld(vehicleRules);world.configureArena(arena);
        }));
        stages.add(new MatchLoading.Stage("Окружение",()->{
            content=new ArenaFactory(assetManager).build(arena);matchNode.attachChild(content.visual());
            if(options.automated()&&!options.showcase())visualTour=new DiagnosticCameraTour(arena);
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
                var profile=VehicleProfile.rivet(vehicleRules);
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
            drivers.putAll(runtime.drivers());arenaSystems=runtime.arenaSystems();bots=runtime.bots();combat=runtime.combat();
            if(checkpoint!=null)runtime.restoreCheckpoint(checkpoint);
            ArenaPresentation.attach(assetManager,content.visual(),session,arena,arenaSystems);
            combatVisuals=new CombatVisuals(assetManager,matchNode,world);createNavigationLines();
        }));
        loading=new MatchLoading(stages,()->world.step(),()->{
            // Restoring resource timers after suspension warmup cannot spend a saved cooldown.
            if(checkpoint!=null)MatchCheckpoint.restorePlayer(session.vehicle(0),checkpoint.player());
            drivers.values().forEach(driver->driver.recordSafePose(session.tick));
            if(mode==ProgressStore.Mode.CAMPAIGN&&checkpoint==null) {
                retryCheckpoint=runtime.checkpoint(ProgressStore.CheckpointStage.ARENA);
                progress.saveCheckpoint(attempt,retryCheckpoint);
            }
            loop=new SimulationLoop(matchRules);chase.reset();
            audio.startMatch(arena.metadata().music(),arena.metadata().bossMusic());
            audio.bossMusic(session.phase==MatchSession.Phase.BOSS_ENTRY);
            input.clear();flow.running();finishLoading();
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
    private void finishLoading() {
        report.loadSeconds((System.nanoTime()-loadingStarted)/1_000_000_000.0);
        if(diagnostic!=null) {
            int bodies=world.bodyCount(),listeners=world.space().countCollisionListeners();
            if(baselineBodies==0) {baselineBodies=bodies;baselineListeners=listeners;}
            if(bodies!=baselineBodies||listeners!=baselineListeners||!combat.projectiles().isEmpty()||audio.voiceCount()>audio.musicSourceCount())
                throw new IllegalStateException("Retry resource baseline changed");
            diagnostic.cycle(diagnosticRetries,bodies,listeners,combat.projectiles().size(),audio.voiceCount());
            if(session.tick!=0 || session.vehicles.stream().anyMatch(v->world.wheelContacts(v.id)!=4||v.hp!=v.maximumHp))
                throw new IllegalStateException("Loading did not prepare grounded, undamaged participants at tick zero");
            if(!combat.mines().isEmpty()||!combat.fireZones().isEmpty()||combat.reservedFireZones()!=0
                    ||!combat.ballisticWarnings().isEmpty()||combat.reservedBallisticCharges()!=0||runtime.hasWrecks())
                throw new IllegalStateException("Transient weapons survive match preparation");
            diagnostic.put("initialSimulationTick",session.tick);
            diagnostic.put("initialHp",session.vehicles.stream().map(v->v.hp).toList());
            diagnostic.put("startWithoutCountdown",true);
            diagnostic.put("latestLoadSeconds",(System.nanoTime()-loadingStarted)/1_000_000_000.0);
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

    }
    @Override public void simpleUpdate(float dt) {
        elapsed+=dt;
        if(ui==null || input==null) return;
        try {
            input.pollGamepad();
            if(settingsDirty&&elapsed>=settingsSaveAt){store.saveSettings();settingsDirty=false;}
            pollProgressStatus();
            boolean drawable=cam.getWidth()>0&&cam.getHeight()>0;
            if(drawable&&(cam.getWidth()!=viewWidth || cam.getHeight()!=viewHeight)) {
                viewWidth=cam.getWidth();viewHeight=cam.getHeight();ui.usePixelCoordinates();resizeHud();
                if(menu!=null)menu.resize(viewWidth,viewHeight,store.settings().uiScale);
            }
            if(!drawable) {undrawableSeconds+=dt;pause("Window minimized. Resume when ready.");}
            if(flow.screen()!=Screen.RUNNING)menu.hover(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);
            if(previousVideo!=null && elapsed>=videoDeadline) restoreVideo();
            var volume=store.settings(); audio.setVolumes(volume.master,volume.music,volume.sfx);
            if(options.automated() && !smokeStarted && elapsed>0.5) { smokeStarted=true; startMatch(); }
            if(flow.screen()==Screen.LOADING&&loading!=null)loading.advance();
            if(flow.screen()==Screen.RUNNING) {
                loop.advance(dt,true,this::fixedTick); report.frame(dt);
                if(diagnostic!=null&&options.benchmarkSeconds()>0&&elapsed>=30&&drawable)diagnostic.frames.add(dt);
            } else if(flow.screen()==Screen.RESULTS&&runtime!=null&&runtime.hasWrecks()) {
                loop.advance(dt,true,runtime::tickPhysicsTail);
            } else loop.resetAccumulator();
            if(world!=null&&drawable&&flow.screen()!=Screen.LOADING) renderMatch(Math.min(dt,0.1f));
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
            updateEnemyHealthBars(drawable);
            if(options.automated()) advanceDiagnostic(drawable);
        } catch(Exception e) { fail(e); }
    }
    private void fixedTick() {
        if(session.outcome!=MatchSession.Outcome.NONE || flow.screen()!=Screen.RUNNING) return;
        VehicleCommand player=input.consume(); rearView=player.rearView();
        int recoveries=session.vehicle(0).recoveries;
        List<GameEvent> events=showcase==null?runtime.tick(player,options.aiPlayer()||options.automated()):runtime.tick(showcase.commands(),false);
        if(showcase!=null)showcase.accept(events);
        for(var state:session.vehicles)if(world.containsVehicle(state.id))attachVehicleModel(state);
        drivers.putAll(runtime.drivers());
        if(session.checkpointRequested) {
            session.checkpointRequested=false;
            retryCheckpoint=runtime.checkpoint(ProgressStore.CheckpointStage.BOSS);
            if(session.mode==MatchSession.Mode.CAMPAIGN)progress.saveCheckpoint(attempt,retryCheckpoint);
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
        if(session.outcome!=MatchSession.Outcome.NONE) {
            diagnosticResults++;
            var playerState=session.vehicle(0);
            progress.record(new ProgressStore.Result(attempt,ProgressStore.Outcome.valueOf(session.outcome.name()),
                    playerState.damageDealt,playerState.eliminations,session.activeTicks,
                    session.bossParticipantId>=0&&!session.vehicle(session.bossParticipantId).alive()));
            writeReport(); audio.stopMatch();
            audio.accept(events);
            flow.results();
        } else audio.accept(events);
    }
    private void renderMatch(float dt) {
        boolean advancing=flow.screen()==Screen.RUNNING;
        boolean results=flow.screen()==Screen.RESULTS;
        float alpha=advancing||results&&runtime.hasWrecks()?loop.alpha():1;
        for(var state:session.vehicles) {
            Node model=vehicleModels.get(state.id);
            VehicleVisual.updateDamage(model,showcase==null?state.hp/state.maximumHp:showcase.displayHpFraction(state));
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
        if(advancing) {
            combatVisuals.update(combat.projectiles(),combat.mines(),combat.fireZones(),combat.ballisticWarnings(),session,dt);
            var hazard=arenaSystems.hazardPhase();
            Vector3f hazardPosition=arena.hazards().stream().filter(h->arenaSystems.hazardPhase(h.id())!=ArenaSystems.HazardPhase.OFF)
                    .map(h->h.center().vector()).findFirst().orElseGet(()->world.position(0));
            audio.hazard(hazard==ArenaSystems.HazardPhase.WARNING,hazard==ArenaSystems.HazardPhase.ACTIVE,hazardPosition);
            audio.update(session,world,dt);
        } else if(results) {
            combatVisuals.update(List.of(),List.of(),List.of(),List.of(),null,dt);
            audio.updateTail(dt);
        }
        for(var pickup:arena.pickups()) {
            Spatial visual=content.visual().getChild("pickup-"+pickup.id());
            if(visual!=null) visual.setCullHint(arenaSystems.active(pickup.id())?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        }
        chase.update(world,0,alpha,dt,rearView,drivers.get(0).turboActive(),store.settings().shake);
        updateHud();
    }
    /** Runs after camera overrides, including rear view and diagnostic shots. */
    private void updateEnemyHealthBars(boolean drawable) {
        boolean visible=drawable&&world!=null&&session!=null&&flow.screen()==Screen.RUNNING;
        enemyHealthBars.setVisible(visible);
        if(!visible)return;
        enemyHealthBars.resize(cam.getWidth(),cam.getHeight(),1);
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
        renderedScreen=flow.screen();ui.clear();ui.usePixelCoordinates();showcaseText=null;
        if(hud!=null)hud.setVisible(false);
        menu.resize(cam.getWidth(),cam.getHeight(),store.settings().uiScale);
        switch(flow.screen()) {
            case MENU -> drawMainMenu();
            case MAPS -> drawMaps();
            case STATISTICS -> drawStatistics();
            case LOADING -> drawLoading();
            case RUNNING -> createHud();
            case PAUSED -> menu.show("pause","ПАУЗА",arena==null?"":arena.metadata().title(),List.of(),List.of(
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
        var campaign=progress.snapshot().campaign();var rows=new ArrayList<MenuView.Row>();
        if(campaign.checkpoint()!=null)rows.add(new MenuView.Action("continue","ПРОДОЛЖИТЬ",arenaRegistry.definition(campaign.currentArenaId()).metadata().title(),true,this::continueCampaign));
        rows.add(new MenuView.Action("new","НОВАЯ КАМПАНИЯ",()->{
            if(campaign.checkpoint()!=null||!campaign.completedArenaIds().isEmpty())confirm("Начать кампанию заново? Открытые карты и статистика сохранятся.",this::newCampaign);
            else newCampaign();}));
        rows.add(new MenuView.Action("maps","ОТДЕЛЬНЫЙ БОЙ",flow::maps));
        rows.add(new MenuView.Action("stats","СТАТИСТИКА",flow::statistics));
        rows.add(new MenuView.Action("settings","НАСТРОЙКИ",()->flow.open(Screen.SETTINGS)));
        rows.add(new MenuView.Action("credits","АВТОРЫ И ЛИЦЕНЗИИ",()->flow.open(Screen.CREDITS)));
        menu.show("main","WRECK RIFF","Тяжёлый металл. Последняя машина.",List.of(),rows,List.of(new MenuView.Action("quit","ВЫХОД",this::stop)),campaign.checkpoint()!=null?"continue":"new");
    }
    private void drawMaps() {
        var campaign=progress.snapshot().campaign();boolean duels=campaign.completedArenaIds().size()==ProgressStore.CAMPAIGN_ARENAS.size();
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
        var snapshot=progress.snapshot();var stats=snapshot.stats();var rows=new ArrayList<MenuView.Row>();
        rows.add(new MenuView.Metrics("outcomes",List.of(new MenuView.Metric("Боёв",Long.toString(stats.completedMatches())),new MenuView.Metric("Побед",Long.toString(stats.wins())),new MenuView.Metric("Поражений",Long.toString(stats.losses())))));
        rows.add(new MenuView.Metrics("combat",List.of(new MenuView.Metric("Урон",String.format(Locale.ROOT,"%.0f",stats.totalDamage())),new MenuView.Metric("Уничтожено",Long.toString(stats.totalEliminations())),new MenuView.Metric("Ничьих",Long.toString(stats.draws())))));
        for(var entry:arenaRegistry.entries()) {
            var record=snapshot.records().get(entry.id());String name=arenaRegistry.definition(entry.id()).metadata().title();
            rows.add(new MenuView.Text("record:"+entry.id(),name+"\nКарта: "+formatTicks(record==null?null:record.bestFullMapTicks())+"    Босс: "+formatTicks(record==null?null:record.bestBossDuelTicks())));
        }
        menu.show("statistics","СТАТИСТИКА","Пройдено арен: "+snapshot.campaign().completedArenaIds().size()+" / 5",List.of(),rows,List.of(new MenuView.Action("back","НАЗАД",flow::menu)),"back");
    }
    private void drawResults() {
        var campaign=progress.snapshot().campaign();boolean victory=session.outcome==MatchSession.Outcome.VICTORY;
        boolean completed= victory&&session.mode==MatchSession.Mode.CAMPAIGN&&campaign.currentArenaId()==null;
        String title=completed?"КАМПАНИЯ ПРОЙДЕНА":victory?"ПОБЕДА":session.outcome==MatchSession.Outcome.DRAW?"НИЧЬЯ":"ПОРАЖЕНИЕ";
        var player=session.vehicle(0);var rows=new ArrayList<MenuView.Row>();
        rows.add(new MenuView.Metrics("result",List.of(new MenuView.Metric("Время",formatTicks(session.activeTicks)),new MenuView.Metric("Урон",String.format(Locale.ROOT,"%.0f",player.damageDealt)),new MenuView.Metric("Уничтожено",Integer.toString(player.eliminations)))));
        if(victory&&session.bossParticipantId>=0)rows.add(new MenuView.Text("boss-result","Побеждён: "+session.vehicle(session.bossParticipantId).name));
        var actions=new ArrayList<MenuView.Action>();
        if(victory&&session.mode==MatchSession.Mode.CAMPAIGN&&campaign.currentArenaId()!=null) {
            rows.add(new MenuView.Text("unlocked","Следующая арена: "+arenaRegistry.definition(campaign.currentArenaId()).metadata().title()));
            actions.add(new MenuView.Action("next","ДАЛЬШЕ",this::continueCampaign));
        } else if(!completed)actions.add(new MenuView.Action("retry",retryCheckpoint!=null&&retryCheckpoint.stage()==ProgressStore.CheckpointStage.BOSS?"ПОВТОР БОССА":"ПОВТОР КАРТЫ",this::startMatch));
        if(completed)rows.add(new MenuView.Text("duels","Все дуэли с боссами доступны в отдельном бою."));
        actions.add(new MenuView.Action("menu","ГЛАВНОЕ МЕНЮ",this::returnToMenu));
        menu.show("results:"+session.sessionId,title,arena.metadata().title(),List.of(),rows,actions,actions.getFirst().id());
    }
    private void drawLoading() {
        String title=arenaRegistry.definition(requestedArena).metadata().title();
        float fraction=loading==null?0:loading.fraction();String stage=loading==null?"Подготовка":loading.title();
        menu.show("loading","ЗАГРУЗКА",title,List.of(),List.of(new MenuView.Text("loading-stage",stage),
                new MenuView.Metrics("loading-progress",List.of(new MenuView.Metric("Готово",Math.round(fraction*100)+"%")))),List.of(),null);
    }
    private void drawCredits() {
        menu.show("credits","АВТОРЫ И ЛИЦЕНЗИИ","Wreck Riff",List.of(),List.of(
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
        if(showcase!=null)showcaseText=ui.text("",90,1020,28,GameUi.PAPER);
        if(hud==null)hud=new HudView(assetManager,guiNode);
        resizeHud();hud.setVisible(true);nextHudDiagnostics=0;
        hudUsesGamepad=input.usingGamepad();
        hud.setHelp(showHelp?hudHelp():List.of());updateHud();
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
                new HudView.HelpItem(input.displayBinding("Rear view"),"Rear view"),
                new HudView.HelpItem(input.displayBinding("Recover"),"Hold to recover"),
                new HudView.HelpItem(input.displayBinding("Pause"),"Pause"));
    }
    private void updateHud() {
        if(hud==null||session==null||flow.screen()!=Screen.RUNNING)return;
        var player=session.vehicle(0);
        if(hudUsesGamepad!=input.usingGamepad()) {hudUsesGamepad=input.usingGamepad();if(showHelp)hud.setHelp(hudHelp());}
        int lock=combat.lockTarget(0);
        hud.update(hudPresenter.snapshot(session,world,new MatchHudPresenter.Targeting(lock,combat.napalmAssistTarget(0),combat.ballisticTarget(0)),
                input.displayBinding("Selected weapon"),noticeTime>0?message:progressStatus,elapsed));
        if((debug||navDebug)&&elapsed>=nextHudDiagnostics) {
            StringBuilder diagnostics=new StringBuilder(String.format(Locale.ROOT,"%.0f FPS | tick %d | %.1f m/s | wheels %d | %s\nHP %.1f energy %.1f | target %d | projectiles %d | voices %d\ndropped %.4fs | bodies %d\n%s",1/Math.max(0.0001f,timer.getTimePerFrame()),session.tick,world.velocity(0).length(),world.wheelContacts(0),drivers.get(0).reversing()?"REVERSE":"FORWARD",player.hp,player.turbo,lock,combat.projectiles().size(),audio.voiceCount(),loop.droppedSimulationTime(),world.bodyCount(),input.diagnostics()));
            if(navDebug)for(var state:session.vehicles)if(!state.player&&state.alive())diagnostics.append("\n").append(state.name).append(" ").append(bots.state(state.id)).append(" -> ").append(bots.targetId(state.id));
            hud.setDiagnostics(diagnostics.toString());nextHudDiagnostics=elapsed+.25;
        } else if(!debug&&!navDebug)hud.setDiagnostics("");
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
            for(String action:List.of("Throttle","Brake / reverse","Steer","Handbrake","Turbo","Machine gun","Selected weapon","Previous weapon","Next weapon","Freeze","Shield","Rear view","Recover","Pause"))
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
            case "Previous weapon"->"Предыдущее оружие";case "Next weapon"->"Следующее оружие";case "Freeze"->"Заморозка";case "Shield"->"Щит";
            case "Rear view"->"Вид назад";case "Recover"->"Восстановление";case "Pause"->"Пауза";
            default->action.startsWith("Select ")?"Выбрать "+action.substring(7):action;
        };
    }
    private void nextResolution() {
        List<int[]> sizes=new ArrayList<>();
        var modes=org.lwjgl.glfw.GLFW.glfwGetVideoModes(org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor());
        if(modes!=null) for(int i=0;i<modes.limit();i++) {
            var mode=modes.get(i); int width=mode.width(),height=mode.height();
            if(width>=1280 && height>=720 && sizes.stream().noneMatch(a->a[0]==width&&a[1]==height)) sizes.add(new int[]{width,height});
        }
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
        var s=store.settings(); settings.setResolution(s.width,s.height); settings.setFullscreen(s.fullscreen); settings.setVSync(s.vsync); settings.setSamples(s.samples); restart();
    }
    private void restoreVideo() {
        if(previousVideo==null) return;
        var old=previousVideo;previousVideo=null;store.replaceSettings(old);applyVideo();redraw();notice("Previous video mode restored.");
    }
    private void key(int code) {
        if(options.automated()) return;
        if(rebinding!=null) {
            if(code==KeyInput.KEY_ESCAPE) {rebinding=null;redraw();return;}
            String action=rebinding;
            boolean conflict=store.settings().keys.entrySet().stream().anyMatch(e->!e.getKey().equals(action)&&e.getValue()==code);
            if(conflict) { notice("That key is already assigned. Choose another key."); return; }
            store.settings().keys.put(action,code);store.saveSettings();rebinding=null;redraw();return;
        }
        if(code==KeyInput.KEY_ESCAPE) uiAction("back");
        else if(code==KeyInput.KEY_UP) uiAction("up"); else if(code==KeyInput.KEY_DOWN) uiAction("down");
        else if(code==KeyInput.KEY_RETURN || code==KeyInput.KEY_NUMPADENTER) { if(flow.screen()!=Screen.RUNNING) uiAction("activate"); }
        else if(code==KeyInput.KEY_F1) {showHelp=!showHelp;if(flow.screen()==Screen.RUNNING)redraw();}
        else if(code==KeyInput.KEY_F3) {debug=!debug;}
        else if(code==KeyInput.KEY_F4) {navDebug=!navDebug;if(navNode!=null)navNode.setCullHint(navDebug?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}
        else if(code==KeyInput.KEY_F12) capture();
    }
    private void uiAction(String action) {
        if(options.automated()) return;
        boolean driving=flow.screen()==Screen.RUNNING;
        if(action.equals("back") || action.equals("pause")) {
            if(driving) pause(""); else if(flow.screen()==Screen.PAUSED) flow.resume();
            else if(flow.screen()==Screen.SETTINGS||flow.screen()==Screen.CONTROLS||flow.screen()==Screen.CREDITS||flow.screen()==Screen.CONFIRM) {rebinding=null;if(previousVideo!=null)restoreVideo();flow.back();}
            return;
        }
        if(driving) return;
        switch(action) {
            case "up","left" -> {ui.move(-1);audio.ui(false);}
            case "down","right" -> {ui.move(1);audio.ui(false);}
            case "activate" -> {audio.ui(true);ui.activate();}
            case "click" -> {audio.ui(true);ui.click(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);}
            default -> {}
        }
    }
    private void pause(String reason) { if(input==null)return;message=reason;input.clear();flow.pause(); }
    @Override public void loseFocus() { super.loseFocus(); enqueue(()->{pause("Focus lost. Resume when ready.");return null;}); }
    private void confirm(String title,Runnable action) { confirmationTitle=title;confirmation=action;flow.open(Screen.CONFIRM); }
    private void returnToMenu() { cleanupMatch();flow.menu(); }
    private void notice(String text) {message=text;noticeTime=5;if(noticeText!=null)noticeText.setText(text);}
    private void pollProgressStatus() {
        var status=progressNotice.update(progress.warning(),progress.savePending(),progress.writable(),elapsed);
        progressStatus=status.indicator();
        if(!status.detailToLog().isEmpty())Logger.getLogger(getClass().getName()).warning(status.detailToLog());
        if(!status.announcement().isEmpty())notice(status.announcement());
    }
    private void capture() {
        capture("manual");
    }
    private void capture(String label) {
        if(screenshots!=null) {
            String prefix="WreckRiff-0.4.0-"+label+"-"+System.currentTimeMillis()+"-";
            screenshots.setFileName(prefix);screenshots.takeScreenshot();
            if(diagnostic!=null) {diagnosticCaptures.add(prefix);diagnostic.put("capturePrefixes",List.copyOf(diagnosticCaptures));}
        }
    }
    private void writeReport() {
        if(!options.dev()||session==null||world==null||report==null)return;
        try {report.write(store.directory().resolve("session-summary.json"),session,loop,org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),cam.getWidth(),cam.getHeight(),store.settings().vsync,audioRenderer!=null,audio.voiceCount(),world.bodyCount());
            if(options.automated()&&session.outcome!=MatchSession.Outcome.NONE)Files.copy(store.directory().resolve("session-summary.json"),store.directory().resolve("match-"+diagnosticResults+".json"),StandardCopyOption.REPLACE_EXISTING);}
        catch(IOException e) {Logger.getLogger(getClass().getName()).warning("Cannot write report: "+e.getMessage());}
    }
    private void cleanupMatch() {
        if(hud!=null){hud.close();hud=null;}
        if(enemyHealthBars!=null){enemyHealthBars.clear();enemyHealthBars.setVisible(false);}
        enemyHealthMarkers.clear();
        if(!finishAudioCapture())diagnosticCompletion.shutdownFailed();
        if(audio!=null)audio.stopMatch(); if(combatVisuals!=null){combatVisuals.close();combatVisuals=null;}
        if(runtime!=null){runtime.close();runtime=null;world=null;combat=null;}
        else if(world!=null){world.close();world=null;}
        matchNode.detachAllChildren();vehicleModels.clear();wheels.clear();drivers.clear();
        session=null;arenaSystems=null;bots=null;content=null;navNode=null;visualTour=null;showcase=null;showcaseText=null;
    }
    private void fail(Exception exception) {
        Logger.getLogger(getClass().getName()).log(Level.SEVERE,"Game failure",exception);
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
        try {
            if(!finishAudioCapture())diagnosticCompletion.shutdownFailed();
            writeReport();cleanupMatch();if(audio!=null)audio.close();if(input!=null)input.close();
            if(enemyHealthBars!=null)enemyHealthBars.close();
            super.destroy();
            diagnosticCompletion.shutdownCompleted();
        } catch(RuntimeException | Error failure) {
            diagnosticCompletion.shutdownFailed();
            if(diagnostic!=null)diagnostic.error("Shutdown failed: "+failure);
            throw failure;
        } finally {
            progress.close();
            if(progress.savePending()) {
                Logger.getLogger(getClass().getName()).warning(progress.warning());
                if(diagnostic!=null) {diagnostic.error("Progress did not flush during shutdown: "+progress.warning());diagnosticCompletion.shutdownFailed();}
            }
            if(fileLog!=null){Logger.getLogger("").removeHandler(fileLog);fileLog.close();}
            if(warningLog!=null)Logger.getLogger("").removeHandler(warningLog);
            if(options.automated()) {
                writeDiagnostic(diagnosticCompletion.passed()?"PASS":"FAIL");
                System.out.println(diagnosticCompletion.passed()?"GRAPHICS_DIAGNOSTIC_PASS":"GRAPHICS_DIAGNOSTIC_FAIL");
            }
            terminated.countDown();
        }
    }
    @Override public void handleError(String message,Throwable failure) {
        if(options.automated()) {
            Logger.getLogger(getClass().getName()).log(Level.SEVERE,message,failure);
            if(diagnostic!=null)diagnostic.error(message+": "+failure);
            finishDiagnostic(false);
        } else super.handleError(message,failure);
    }
    public boolean awaitDiagnostic() throws InterruptedException {
        int timeout=options.showcase()?240:options.benchmarkSeconds()>0?options.benchmarkSeconds()+90:options.smokeSeconds()+60;
        if(!terminated.await(timeout,java.util.concurrent.TimeUnit.SECONDS)) {System.err.println("Diagnostic shutdown timed out");diagnosticCompletion.shutdownFailed();stop();return false;}
        return diagnosticCompletion.passed();
    }
    private void advanceDiagnostic(boolean drawable) {
        if(diagnosticEnding)return;
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
        if(benchmark&&elapsed>=30+options.benchmarkSeconds()) {
            diagnostic.put("warmupSeconds",30);diagnostic.put("renderTargetMet",diagnostic.frames.withinTarget());
            finishDiagnostic(diagnosticResults>0&&diagnostic.frames.withinTarget()&&undrawableSeconds==0);return;
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
            if(flow.screen()==Screen.RESULTS) {writeReport();diagnosticRetries++;startMatch();}
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
            diagnostic.put("completedMatches",diagnosticResults);diagnostic.put("restarts",diagnosticRetries);
            diagnostic.put("elapsedSeconds",elapsed);diagnostic.put("undrawableSeconds",undrawableSeconds);
            try {diagnostic.write(store.directory(),status);}
            catch(IOException e) {diagnosticCompletion.shutdownFailed();System.err.println("Cannot write diagnostic evidence: "+e.getMessage());}
        }
    }
    private static String percent(float value) {return Math.round(value*100)+"%";}
    private static float nextLevel(float value) {return value>=0.99f?0:Math.min(1,value+0.1f);}
    private static String wrap(String input,int width) {StringBuilder out=new StringBuilder();int column=0;for(String word:input.split(" ")){if(column+word.length()>width){out.append('\n');column=0;}out.append(word).append(' ');column+=word.length()+1;}return out.toString();}
}
