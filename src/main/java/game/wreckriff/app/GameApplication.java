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
    private final ScreenFlow flow=new ScreenFlow();
    private final Node matchNode=new Node("match"),menuNode=new Node("menu-scene");
    private final Map<Integer,Node> vehicleModels=new HashMap<>();
    private final Map<Integer,Spatial[]> wheels=new HashMap<>();
    private final Map<Integer,VehicleController> drivers=new LinkedHashMap<>();
    private InputSystem input;
    private GameUi ui;
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
    private BitmapText hpText,weaponText,timerText,debugText,noticeText,abilitiesText,statusText;
    private Geometry hpBar,turboBar;
    private record WeaponHud(BitmapText title,BitmapText ammo,Geometry plate) {}
    private final Map<WeaponType,WeaponHud> weaponHud=new EnumMap<>(WeaponType.class);
    private final Map<Integer,BitmapText> radarMarkers=new HashMap<>();
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

    public GameApplication(Main.Options options,SettingsStore store) { this.options=options; this.store=store; }
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
    private void startMatch() {
        if(flow.screen()==Screen.LOADING) return;
        loadingStarted=System.nanoTime();
        flow.loading();
        enqueue(()-> {
            try {
                cleanupMatch();
                long seed=options.fixedSeed()?options.seed():System.nanoTime();
                session=new MatchSession(seed,matchRules.durationSeconds()); report=new SessionReport();
                world=new PhysicsWorld(vehicleRules);
                arena=ArenaDefinition.load(); content=new ArenaFactory(assetManager).build(arena);
                if(options.automated()&&!options.showcase()) visualTour=new DiagnosticCameraTour(arena);
                matchNode.attachChild(content.visual());
                for(var body:content.bodies()) world.addStatic(body.shape(),body.position(),body.rotation());
                List<ArenaDefinition.Spawn> spawns=new ArrayList<>(content.spawns()); Collections.shuffle(spawns,new Random(seed));
                for(var state:session.vehicles) {
                    var spawn=spawns.get(state.id);
                    world.addVehicle(state.id,spawn.position().vector().add(0,0.85f,0),new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));
                    Node model=VehicleVisual.create(assetManager,state.id); model.setName("vehicle-"+state.id); vehicleModels.put(state.id,model); matchNode.attachChild(model);
                    Spatial[] wheelNodes=new Spatial[4];
                    for(int i=0;i<4;i++) { wheelNodes[i]=model.getChild("wheel-"+i); if(wheelNodes[i]!=null) { wheelNodes[i].removeFromParent(); matchNode.attachChild(wheelNodes[i]); } }
                    wheels.put(state.id,wheelNodes);
                }
                runtime=new MatchRuntime(session,world,arena,content.graph(),vehicleRules);
                drivers.putAll(runtime.drivers()); arenaSystems=runtime.arenaSystems();bots=runtime.bots();combat=runtime.combat();
                ArenaPresentation.attach(assetManager,content.visual(),session,arena,arenaSystems);
                combatVisuals=new CombatVisuals(assetManager,matchNode,world);
                createNavigationLines();
                // Loading prepares native suspension without consuming a single gameplay tick.
                for(int step=0;step<360;step++) world.step();
                loop=new SimulationLoop(matchRules); chase.reset();
                audio.startMatch(); input.clear(); flow.running();
                report.loadSeconds((System.nanoTime()-loadingStarted)/1_000_000_000.0);
                if(diagnostic!=null) {
                    int bodies=world.bodyCount(),listeners=world.space().countCollisionListeners();
                    if(baselineBodies==0) {baselineBodies=bodies;baselineListeners=listeners;}
                    if(bodies!=baselineBodies||listeners!=baselineListeners||!combat.projectiles().isEmpty()||audio.voiceCount()>(audioRenderer==null?0:1))
                        throw new IllegalStateException("Retry resource baseline changed");
                    diagnostic.cycle(diagnosticRetries,bodies,listeners,combat.projectiles().size(),audio.voiceCount());
                    if(session.tick!=0 || session.vehicles.stream().anyMatch(v->world.wheelContacts(v.id)!=4||v.hp!=v.maximumHp))
                        throw new IllegalStateException("Loading did not prepare grounded, undamaged participants at tick zero");
                    if(!combat.mines().isEmpty()||!combat.fireZones().isEmpty()||combat.reservedFireZones()!=0)
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
            } catch(Exception e) { fail(e); }
            return null;
        });
    }
    @Override public void simpleUpdate(float dt) {
        elapsed+=dt;
        if(ui==null || input==null) return;
        try {
            input.pollGamepad();
            boolean drawable=cam.getWidth()>0&&cam.getHeight()>0;
            if(drawable&&(cam.getWidth()!=viewWidth || cam.getHeight()!=viewHeight)) { viewWidth=cam.getWidth(); viewHeight=cam.getHeight(); ui.resize(viewWidth,viewHeight); }
            if(!drawable) {undrawableSeconds+=dt;pause("Window minimized. Resume when ready.");}
            ui.hover(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);
            if(previousVideo!=null && elapsed>=videoDeadline) restoreVideo();
            var volume=store.settings(); audio.setVolumes(volume.master,volume.music,volume.sfx);
            if(options.automated() && !smokeStarted && elapsed>0.5) { smokeStarted=true; startMatch(); }
            if(flow.screen()==Screen.RUNNING) {
                loop.advance(dt,true,this::fixedTick); report.frame(dt);
                if(diagnostic!=null&&options.benchmarkSeconds()>0&&elapsed>=30&&drawable)diagnostic.frames.add(dt);
            } else if(flow.screen()==Screen.RESULTS&&runtime!=null&&runtime.hasWrecks()) {
                loop.advance(dt,true,runtime::tickPhysicsTail);
            } else loop.resetAccumulator();
            if(world!=null&&drawable) renderMatch(Math.min(dt,0.1f));
            else {
                float rotation=(float)Math.sin(elapsed*0.16)*0.15f;
                menuNode.setLocalRotation(new Quaternion().fromAngleAxis(rotation,Vector3f.UNIT_Y));
                cam.setLocation(new Vector3f(-6,4.5f,10)); cam.lookAt(new Vector3f(0.5f,0.7f,0),Vector3f.UNIT_Y);
            }
            if(noticeTime>0) { noticeTime-=dt; if(noticeTime<=0&&noticeText!=null) noticeText.setText(""); }
            if(visualTour!=null&&world!=null&&drawable&&elapsed<30) {
                String shot=visualTour.apply(cam,world,elapsed);
                if(shot!=null) capture(shot);
            }
            if(showcase!=null&&world!=null&&drawable) {
                String shot=showcase.frame(cam);
                if(showcaseText!=null)showcaseText.setText(showcase.label());
                if(shot!=null)capture(shot);
            }
            if(options.automated()) advanceDiagnostic(drawable);
        } catch(Exception e) { fail(e); }
    }
    private void fixedTick() {
        if(session.outcome!=MatchSession.Outcome.NONE || flow.screen()!=Screen.RUNNING) return;
        VehicleCommand player=input.consume(); rearView=player.rearView();
        int recoveries=session.vehicle(0).recoveries;
        List<GameEvent> events=showcase==null?runtime.tick(player,options.aiPlayer()||options.automated()):runtime.tick(showcase.commands(),false);
        if(showcase!=null)showcase.accept(events);
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
            store.record(session); writeReport(); audio.stopMatch();
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
            audio.hazard(hazard==ArenaSystems.HazardPhase.WARNING,hazard==ArenaSystems.HazardPhase.ACTIVE,
                    new Vector3f((arena.hazard().minX()+arena.hazard().maxX())/2,0.2f,(arena.hazard().minZ()+arena.hazard().maxZ())/2));
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
        if(flow.screen()==Screen.RUNNING) message="";
        if(diagnostic!=null)diagnostic.transition(flow.screen().name(),elapsed);
        input.clear(); input.setGameplay(flow.screen()==Screen.RUNNING);
        if(world!=null && flow.screen()!=Screen.RUNNING && flow.screen()!=Screen.RESULTS) audio.pause();
        if(world!=null && flow.screen()==Screen.RUNNING) audio.resume();
        menuNode.setCullHint(world==null?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        loop.resetAccumulator(); redraw();
    }
    private void redraw() {
        int selection=renderedScreen==flow.screen()?ui.selection():0;
        renderedScreen=flow.screen();
        ui.clear(); hpText=weaponText=timerText=debugText=abilitiesText=statusText=showcaseText=null; radarMarkers.clear();weaponHud.clear();
        switch(flow.screen()) {
            case MENU -> {
                ui.title("WRECK RIFF","DEAD AIR. LOUD ENGINES. LAST CAR STANDING.");
                ui.text("A radio yard demolition show",145,783,25,GameUi.PAPER);
                ui.button("PLAY",140,640,640,this::startMatch);
                ui.button("SETTINGS",140,562,640,()->flow.open(Screen.SETTINGS));
                ui.button("CONTROLS",140,484,640,()->flow.open(Screen.CONTROLS));
                ui.button("CREDITS & LICENSES",140,406,640,()->flow.open(Screen.CREDITS));
                ui.button("QUIT",140,328,640,this::stop);
                ui.text("ONE ARENA / FIVE MACHINES / NO SECOND CHANCES",144,245,18,GameUi.ACCENT);
                ui.text("0.4.0  |  Local single-player  |  "+store.stats().completedMatches+" completed matches",144,196,18,GameUi.PAPER);
            }
            case LOADING -> { ui.title("TUNING IN","Loading Dead Air Yard..."); }
            case RUNNING -> createHud();
            case PAUSED -> {
                ui.title("PAUSED","The broadcast is on hold.");
                ui.button("RESUME",140,710,640,flow::resume);
                ui.button("SETTINGS",140,632,640,()->flow.open(Screen.SETTINGS));
                ui.button("CONTROLS",140,554,640,()->flow.open(Screen.CONTROLS));
                ui.button("RESTART",140,476,640,()->confirm("Restart this unfinished match?",this::startMatch));
                ui.button("MAIN MENU",140,398,640,()->confirm("Leave this unfinished match?",this::returnToMenu));
                ui.text(message,145,305,20,GameUi.ACCENT);
            }
            case RESULTS -> {
                ui.title(session.outcome.name(),session.outcomeReason);
                ui.text(String.format(Locale.ROOT,"TIME  %02d:%02d\nDAMAGE DEALT  %.0f\nELIMINATIONS  %d",(int)session.seconds()/60,(int)session.seconds()%60,session.vehicle(0).damageDealt,session.vehicle(0).eliminations),145,760,30,GameUi.PAPER);
                ui.button("RETRY",140,480,640,this::startMatch); ui.button("MAIN MENU",140,402,640,this::returnToMenu);
            }
            case SETTINGS -> drawSettings();
            case CONTROLS -> drawControls();
            case CREDITS -> {
                ui.title("WRECK RIFF","Original single-player vehicle combat MVP");
                ui.text("RIVET / DEAD AIR YARD\nOriginal vehicle and arena.\n\nMETALMANIA — Kevin MacLeod (incompetech.com)\nCreative Commons: By Attribution 4.0\nAudio converted and edited for game playback.\n\nCombat SFX: Free Firearm Sound Library / CC0\n25 bang/firework SFX — rubberduck / CC0\nTextures: Poly Haven / CC0\nFont: Roboto Condensed / SIL OFL 1.1\n\nJava 21 / jMonkeyEngine / Minie / LWJGL / Gson\nFull sources and notices ship in the licenses directory.",145,790,23,GameUi.PAPER);
                ui.button("BACK",140,260,640,flow::back);
            }
            case CONFIRM -> {
                ui.title("CONFIRM",confirmationTitle);
                ui.button("CONFIRM",140,630,640,()->{ Runnable action=confirmation; confirmation=null; action.run(); });
                ui.button("CANCEL",140,552,640,flow::back);
            }
            case ERROR -> {
                ui.title("SIGNAL LOST","The match could not continue.");
                ui.text(wrap(error,62),145,770,22,GameUi.PAPER);
                ui.button("MAIN MENU",140,320,640,this::returnToMenu); ui.button("QUIT",140,242,640,this::stop);
            }
            default -> {}
        }
        noticeText=ui.text(message,105,53,18,GameUi.ACCENT);
        if(options.dev()) ui.text("DEV  |  SEED "+(session!=null?session.seed:options.seed())+(options.configDir()!=null?"  |  CONFIG OVERRIDE":""),1120,48,17,GameUi.ACCENT);
        ui.select(selection);
    }
    private void createHud() {
        if(showcase!=null)showcaseText=ui.text("",90,1020,28,GameUi.PAPER);
        ui.rect("hp-panel",45,40,470,180,GameUi.INK,0);
        hpText=ui.text("",70,193,33,GameUi.PAPER);
        ui.rect("hp-track",70,121,420,17,new ColorRGBA(0.2f,0.18f,0.16f,0.9f),1);
        hpBar=ui.rect("hp",70,121,420,17,GameUi.ACCENT,2);
        ui.text("ARMOR",70,112,17,GameUi.PAPER);
        ui.rect("turbo-track",70,67,420,9,new ColorRGBA(.13f,.19f,.2f,1),1);
        turboBar=ui.rect("turbo",70,67,420,9,ColorRGBA.Cyan,2);
        ui.text("TURBO",70,62,16,GameUi.PAPER);
        statusText=ui.text("",70,265,26,GameUi.ACCENT);
        ui.rect("abilities-panel",535,40,520,180,GameUi.INK,0);
        abilitiesText=ui.text("",557,192,25,GameUi.PAPER);
        ui.rect("weapon-panel",1075,40,800,180,GameUi.INK,0);
        int slot=0;
        for(WeaponType type:WeaponType.values()) {
            float x=1094+(slot%3)*258,y=134-(slot/3)*80;slot++;
            Geometry plate=ui.rect("weapon-"+type,x,y,244,72,new ColorRGBA(.12f,.14f,.16f,1),1);
            BitmapText title=ui.text(type.name(),x+12,y+59,21,GameUi.PAPER);
            BitmapText ammo=ui.text("",x+12,y+31,24,GameUi.PAPER);
            weaponHud.put(type,new WeaponHud(title,ammo,plate));
        }
        ui.rect("weapon-status",1075,225,800,38,GameUi.INK,0);
        weaponText=ui.text("",1096,252,21,GameUi.PAPER);
        timerText=ui.text("",785,1030,29,GameUi.PAPER);
        ui.text("+",949,575,32,GameUi.ACCENT);
        ui.rect("radar",1630,805,240,240,GameUi.INK,0);
        ui.rect("radar-center",1748,923,5,5,GameUi.PAPER,2);
        for(int id=1;id<5;id++) radarMarkers.put(id,ui.text("",0,0,18,GameUi.ACCENT));
        debugText=ui.text("",58,1015,18,GameUi.PAPER);
        if(showHelp) ui.text(bindingName("Throttle")+" / "+bindingName("Brake / reverse")+" drive / brake / reverse    "+bindingName("Steer left")+" / "+bindingName("Steer right")+" steer\n"+
                bindingName("Handbrake")+" handbrake    "+bindingName("Turbo")+" turbo    LMB machine gun    RMB selected weapon\n"+
                bindingName("Freeze")+" Freeze    "+bindingName("Shield")+" Shield    "+bindingName("Rear view")+" rear view\n"+
                "Hold "+bindingName("Recover")+" recover    ESC pause    F3 diagnostics    F12 screenshot",510,370,23,GameUi.PAPER);
    }
    private void updateHud() {
        if(hpText==null || session==null) return;
        var player=session.vehicle(0);
        hpText.setText(String.format(Locale.ROOT,"RIVET   %.0f / %.0f",Math.max(0,player.hp),player.maximumHp));
        hpBar.setLocalScale(Math.clamp(player.hp/player.maximumHp,0,1),1,1); turboBar.setLocalScale(player.turbo/100,1,1);
        int lock=combat.lockTarget(0);
        for(var entry:weaponHud.entrySet()) {
            var slot=player.weapon(entry.getKey()); var card=entry.getValue(); boolean selected=player.selectedWeapon==entry.getKey();
            card.title().setColor(selected?GameUi.ACCENT:GameUi.PAPER);
            card.title().setText(entry.getKey().name()+" ["+bindingName(InputSystem.weaponBinding(entry.getKey()))+"]");
            card.plate().getMaterial().setColor("Color",selected?new ColorRGBA(.27f,.17f,.075f,.97f):new ColorRGBA(.12f,.14f,.16f,1));
            card.ammo().setText(slot.ammo+" / "+slot.maximumAmmo+(slot.cooldownTicks>0?"  "+cooldown(slot.cooldownTicks):""));
        }
        int assisted=combat.napalmAssistTarget(0);
        String targeting=player.selectedWeapon==WeaponType.NAPALM
                ?(assisted>=0?"NAPALM ASSIST: "+session.vehicle(assisted).name:"NAPALM: FREE ARC")
                :lock>=0?"LOCK: "+session.vehicle(lock).name:bindingName("Previous weapon")+" / "+bindingName("Next weapon")+": CYCLE";
        weaponText.setText("LMB: MACHINE GUN    |    "+targeting);
        abilitiesText.setText("FREEZE ["+bindingName("Freeze")+"]  "+cooldown(player.abilityCooldown(AbilityId.FREEZE))+
                "\nSHIELD ["+bindingName("Shield")+"]  "+cooldown(player.abilityCooldown(AbilityId.SHIELD)));
        statusText.setText(player.shieldTicks>0?"SHIELD  "+cooldown(player.shieldTicks):
                player.frozenTicks>0?"FROZEN  "+cooldown(player.frozenTicks)+"  /  SHIELD TO BREAK FREE":player.controlImmunityTicks>0?"CONTROL IMMUNITY  "+cooldown(player.controlImmunityTicks):"");
        int remaining=Math.max(0,matchRules.durationSeconds()-(int)session.seconds());
        timerText.setText(String.format(Locale.ROOT,"%02d:%02d   /   %d RIVALS",remaining/60,remaining%60,session.vehicles.stream().filter(v->v.id!=0&&v.alive()).count()));
        if(debug||navDebug) {
            StringBuilder diagnostics=new StringBuilder(String.format(Locale.ROOT,"%.0f FPS | tick %d | %.1f m/s | wheels %d | %s\nHP %.1f energy %.1f | target %d | projectiles %d | voices %d\ndropped %.4fs | bodies %d\n%s",1/Math.max(0.0001f,timer.getTimePerFrame()),session.tick,world.velocity(0).length(),world.wheelContacts(0),drivers.get(0).reversing()?"REVERSE":"FORWARD",player.hp,player.turbo,lock,combat.projectiles().size(),audio.voiceCount(),loop.droppedSimulationTime(),world.bodyCount(),input.diagnostics()));
            if(navDebug) for(int id=1;id<5;id++) diagnostics.append("\n").append(session.vehicle(id).name).append(" ").append(bots.state(id)).append(" -> ").append(bots.targetId(id));
            debugText.setText(diagnostics.toString());
        } else debugText.setText("");
        Quaternion inverse=world.rotation(0).inverse();
        for(var entry:radarMarkers.entrySet()) {
            int id=entry.getKey(); BitmapText marker=entry.getValue();
            Vector3f relative=inverse.mult(world.position(id).subtract(world.position(0)));
            boolean visible=session.vehicle(id).alive() && relative.length()<=matchRules.radarRange();
            marker.setText(visible?(relative.y>2?"^":relative.y< -2?"v":"+")+id:"");
            marker.setLocalTranslation(1746-relative.x*1.65f,934+relative.z*1.65f,5);
        }
    }
    private static String cooldown(int ticks) { return ticks<=0?"READY":String.format(Locale.ROOT,"%.1fs",ticks/120f); }
    private String bindingName(String action) {
        Integer key=store.settings().keys.get(action);
        return KeyLabels.name(key);
    }
    private void drawSettings() {
        var s=store.settings(); ui.title("SETTINGS","Select a row to change it. ESC returns.");
        if(previousVideo!=null) {
            ui.text("Confirm this video mode within 10 seconds.",145,750,26,GameUi.ACCENT);
            ui.button("KEEP VIDEO MODE",140,640,710,()->{ previousVideo=null;store.saveSettings();redraw(); });
            ui.button("REVERT VIDEO MODE",140,562,710,this::restoreVideo);
            return;
        }
        float y=760;
        ui.button("DISPLAY  "+(s.fullscreen?"FULLSCREEN":"WINDOWED"),140,y,710,()->videoChange(()->s.fullscreen=!s.fullscreen)); y-=65;
        ui.button("RESOLUTION  "+s.width+" x "+s.height,140,y,710,()->videoChange(this::nextResolution)); y-=65;
        ui.button("VSYNC  "+(s.vsync?"ON":"OFF"),140,y,710,()->videoChange(()->s.vsync=!s.vsync)); y-=65;
        ui.button("ANTI-ALIASING  "+(s.samples==0?"OFF":"MSAA "+s.samples+"x"),140,y,710,()->videoChange(()->s.samples=s.samples==0?2:s.samples==2?4:s.samples==4?8:0)); y-=65;
        ui.button("MASTER  "+percent(s.master),140,y,710,()->{s.master=nextLevel(s.master);store.saveSettings();redraw();}); y-=65;
        ui.button("MUSIC  "+percent(s.music),140,y,710,()->{s.music=nextLevel(s.music);store.saveSettings();redraw();}); y-=65;
        ui.button("SFX  "+percent(s.sfx),140,y,710,()->{s.sfx=nextLevel(s.sfx);store.saveSettings();redraw();}); y-=65;
        ui.button("CAMERA SHAKE  "+percent(s.shake),140,y,710,()->{s.shake=nextLevel(s.shake);store.saveSettings();redraw();}); y-=65;
        ui.button("STEERING SENSITIVITY  "+String.format(Locale.ROOT,"%.2f",s.sensitivity),140,y,710,()->{s.sensitivity=s.sensitivity>=2?0.5f:s.sensitivity+0.25f;store.saveSettings();redraw();}); y-=65;
        ui.button("STICK DEAD ZONE  "+percent(s.deadZone),140,y,710,()->{s.deadZone=s.deadZone>=0.3f?0.05f:s.deadZone+0.05f;store.saveSettings();redraw();}); y-=65;
        ui.button("RESET DEFAULTS",140,y,340,()->videoChange(()->store.useInMemory(new SettingsStore.Settings()))); ui.button("BACK",500,y,350,flow::back);
    }
    private void drawControls() {
        ui.title("CONTROLS",rebinding==null?"Choose an action to rebind. ESC always remains available.":"Press a key for "+rebinding+". ESC cancels.");
        var keys=new ArrayList<>(store.settings().keys.entrySet());
        int start=controlsPage*8; float y=750;
        for(int i=start;i<Math.min(keys.size(),start+8);i++) {
            var binding=keys.get(i); String name=bindingName(binding.getKey());
            ui.button(binding.getKey()+"   ["+name+"]",140,y,710,()->{ rebinding=binding.getKey();redraw(); }); y-=65;
        }
        ui.button("NEXT PAGE",140,155,340,()->{ controlsPage=(controlsPage+1)%Math.max(1,(keys.size()+7)/8);redraw();ui.select(0); });
        ui.button("BACK",500,155,350,()->{rebinding=null;flow.back();});
        ui.text("MOUSE\nLMB  Machine gun    RMB  Selected weapon\n\nKEYBOARD ABILITIES\n"+
                bindingName("Freeze")+"  Freeze    "+bindingName("Shield")+"  Shield\nPress once to activate; no modifier needed.\n\n"+
                "DEFAULT GAMEPAD\nRT / LT  Throttle / brake / reverse\nLeft stick  Steer\nX  Handbrake    B  Turbo\nLB  Machine gun    RB  Selected weapon\nD-pad left/right  Weapon    A  Shield\nD-pad up  Freeze\nY  Rear view    View  Recovery    Menu  Pause\n\n"+
                input.diagnostics()+"\n"+wrap(store.bindingWarning(),57),1010,930,24,GameUi.PAPER);
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
            super.destroy();
            diagnosticCompletion.shutdownCompleted();
        } catch(RuntimeException | Error failure) {
            diagnosticCompletion.shutdownFailed();
            if(diagnostic!=null)diagnostic.error("Shutdown failed: "+failure);
            throw failure;
        } finally {
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
