package game.wreckriff.app;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.light.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import game.wreckriff.Main;
import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.audio.AudioDirector;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.diagnostics.SessionReport;
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
    private final Map<Integer,Float> wreckTimers=new HashMap<>();
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
    private SessionReport report;
    private ScreenshotAppState screenshots;
    private FileHandler fileLog;
    private BitmapText hpText,weaponText,timerText,debugText,noticeText,countdownText;
    private Geometry hpBar,turboBar,heatBar;
    private final Map<Integer,BitmapText> radarMarkers=new HashMap<>();
    private boolean debug,navDebug,showHelp,smokeStarted,smokeCapture;
    private Node navNode;
    private float countdown,noticeTime;
    private double elapsed,videoDeadline;
    private SettingsStore.Settings previousVideo;
    private String message="",error="",rebinding;
    private int controlsPage;
    private Runnable confirmation;
    private String confirmationTitle;
    private boolean rearView;
    private int viewWidth,viewHeight;
    private Screen renderedScreen;

    public GameApplication(Main.Options options,SettingsStore store) { this.options=options; this.store=store; }
    @Override public void simpleInitApp() {
        flyCam.setEnabled(false); setDisplayFps(false); setDisplayStatView(false);
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        viewPort.setBackgroundColor(new ColorRGBA(0.033f,0.045f,0.065f,1));
        AmbientLight ambient=new AmbientLight(); ambient.setColor(new ColorRGBA(0.55f,0.61f,0.68f,1)); rootNode.addLight(ambient);
        DirectionalLight sun=new DirectionalLight(); sun.setDirection(new Vector3f(-0.5f,-1,-0.25f).normalizeLocal());
        sun.setColor(new ColorRGBA(1.15f,1.04f,0.88f,1)); rootNode.addLight(sun);
        rootNode.attachChild(menuNode); rootNode.attachChild(matchNode);
        try {
            matchRules=MatchRules.load(); vehicleRules=VehicleRules.load(); loop=new SimulationLoop(matchRules);
            initializeLogging();
            ui=new GameUi(assetManager,guiNode); ui.resize(cam.getWidth(),cam.getHeight());
            input=new InputSystem(inputManager,store::settings); input.onKey(this::key); input.onUi(this::uiAction);
            input.onDisconnect(()->pause("Controller disconnected. Reconnect or use the keyboard."));
            audio=new AudioDirector(assetManager,audioRenderer,listener,rootNode);
            chase=new ChaseCamera(cam,CameraRules.load());
            createMenuScene();
            Path captures=store.directory().resolve("captures");
            try { Files.createDirectories(captures); screenshots=new ScreenshotAppState(captures.toAbsolutePath()+java.io.File.separator); stateManager.attach(screenshots); }
            catch(IOException e) { message="Screenshots unavailable: "+e.getMessage(); }
            flow.onChanged(this::screenChanged); flow.menu();
            if(!store.warning().isEmpty()) notice(store.warning());
            if(audioRenderer==null) notice("Audio unavailable / disabled. Music acceptance remains pending.");
        } catch(Exception e) { fail(e); }
    }
    private void initializeLogging() {
        try {
            Path logs=store.directory().resolve("logs"); Files.createDirectories(logs);
            fileLog=new FileHandler(logs.resolve("wreck-riff-%g.log").toString(),matchRules.logFileBytes(),matchRules.maxLogFiles(),true);
            fileLog.setFormatter(new SimpleFormatter()); Logger.getLogger("").addHandler(fileLog);
        } catch(IOException e) { Logger.getLogger(getClass().getName()).warning("File logging unavailable: "+e.getMessage()); }
    }
    private void createMenuScene() {
        menuNode.detachAllChildren();
        Node hero=VehicleVisual.create(assetManager,0); hero.setLocalTranslation(2,1,0); hero.setLocalScale(1.35f); menuNode.attachChild(hero);
        Geometry floor=new Geometry("show-floor",new Box(30,0.1f,30));
        Material material=new Material(assetManager,"Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors",true); material.setColor("Diffuse",new ColorRGBA(0.10f,0.12f,0.14f,1));
        material.setColor("Ambient",new ColorRGBA(0.10f,0.12f,0.14f,1)); floor.setMaterial(material); menuNode.attachChild(floor);
    }
    private void startMatch() {
        flow.loading();
        enqueue(()-> {
            try {
                cleanupMatch();
                long seed=options.fixedSeed()?options.seed():System.nanoTime();
                session=new MatchSession(seed,matchRules.durationSeconds()); report=new SessionReport();
                world=new PhysicsWorld(vehicleRules);
                arena=ArenaDefinition.load(); content=new ArenaFactory(assetManager).build(arena);
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
                combatVisuals=new CombatVisuals(assetManager,matchNode,world);
                createNavigationLines();
                countdown=matchRules.countdownSeconds(); loop.resetAccumulator(); chase.reset();
                audio.startMatch(); input.clear(); flow.countdown();
            } catch(Exception e) { fail(e); }
            return null;
        });
    }
    @Override public void simpleUpdate(float dt) {
        elapsed+=dt;
        if(ui==null || input==null) return;
        try {
            input.pollGamepad();
            if(cam.getWidth()!=viewWidth || cam.getHeight()!=viewHeight) { viewWidth=cam.getWidth(); viewHeight=cam.getHeight(); ui.resize(viewWidth,viewHeight); }
            ui.hover(inputManager.getCursorPosition().x,inputManager.getCursorPosition().y);
            if(previousVideo!=null && elapsed>=videoDeadline) restoreVideo();
            var volume=store.settings(); audio.setVolumes(volume.master,volume.music,volume.sfx);
            if(options.smokeSeconds()>0 && !smokeStarted && elapsed>0.5) { smokeStarted=true; startMatch(); }
            if(flow.screen()==Screen.COUNTDOWN && world!=null) {
                loop.advance(dt,true,()->world.step());
                countdown-=Math.min(dt,0.1f);
                if(countdownText!=null) countdownText.setText(countdown>0?Integer.toString((int)Math.ceil(countdown)):"GO");
                if(countdown<=0) { loop.resetAccumulator(); input.clear(); flow.running(); }
            } else if(flow.screen()==Screen.RUNNING) {
                loop.advance(dt,true,this::fixedTick); report.frame(dt);
            } else loop.resetAccumulator();
            if(world!=null) renderMatch(Math.min(dt,0.1f));
            else {
                float rotation=(float)Math.sin(elapsed*0.16)*0.15f;
                menuNode.setLocalRotation(new Quaternion().fromAngleAxis(rotation,Vector3f.UNIT_Y));
                cam.setLocation(new Vector3f(-6,4.5f,10)); cam.lookAt(new Vector3f(0.5f,0.7f,0),Vector3f.UNIT_Y);
            }
            if(noticeTime>0) { noticeTime-=dt; if(noticeTime<=0&&noticeText!=null) noticeText.setText(""); }
            if(options.smokeSeconds()>0 && elapsed>Math.min(8,options.smokeSeconds()-1) && !smokeCapture && world!=null) { capture(); smokeCapture=true; }
            if(options.smokeSeconds()>0 && elapsed>=options.smokeSeconds()) { writeReport(); stop(); }
        } catch(Exception e) { fail(e); }
    }
    private void fixedTick() {
        if(session.outcome!=MatchSession.Outcome.NONE || flow.screen()!=Screen.RUNNING) return;
        VehicleCommand player=input.consume(); rearView=player.rearView();
        int recoveries=session.vehicle(0).recoveries;
        List<GameEvent> events=runtime.tick(player,options.aiPlayer());
        if(session.vehicle(0).recoveries>recoveries) chase.reset();
        for(GameEvent event:events) {
            if(event.type()==GameEvent.Type.DESTROYED) {
                wreckTimers.put(event.subjectId(),3f);
                Spatial[] deadWheels=wheels.get(event.subjectId()); if(deadWheels!=null) for(Spatial wheel:deadWheels) if(wheel!=null) wheel.removeFromParent();
            }
            if(event.type()==GameEvent.Type.DAMAGE && event.subjectId()==0) chase.impact(Math.min(0.7f,event.value()/60));
        }
        combatVisuals.accept(events);
        if(session.outcome!=MatchSession.Outcome.NONE) {
            store.record(session); writeReport(); audio.stopMatch();
            audio.accept(events);
            flow.results();
        } else audio.accept(events);
    }
    private void renderMatch(float dt) {
        boolean advancing=flow.screen()==Screen.RUNNING||flow.screen()==Screen.COUNTDOWN;
        float alpha=advancing?loop.alpha():1;
        for(var state:session.vehicles) {
            Node model=vehicleModels.get(state.id);
            if(state.alive() && world.containsVehicle(state.id)) {
                var pose=world.interpolatedPose(state.id,alpha); model.setLocalTranslation(pose.position()); model.setLocalRotation(pose.rotation());
                for(int i=0;i<4;i++) if(wheels.get(state.id)[i]!=null) {
                    var wheel=world.interpolatedWheel(state.id,i,alpha); wheels.get(state.id)[i].setLocalTranslation(wheel.position()); wheels.get(state.id)[i].setLocalRotation(wheel.rotation());
                }
            } else if(advancing&&wreckTimers.containsKey(state.id)) {
                float remaining=wreckTimers.compute(state.id,(id,value)->value-dt);
                if(remaining<=0) { model.removeFromParent(); wreckTimers.remove(state.id); }
                else model.setLocalScale(Math.min(1,remaining));
            }
        }
        if(advancing) {
            combatVisuals.update(combat.projectiles(),session,dt);
            var hazard=arenaSystems.hazardPhase();
            audio.hazard(hazard==ArenaSystems.HazardPhase.WARNING,hazard==ArenaSystems.HazardPhase.ACTIVE,
                    new Vector3f((arena.hazard().minX()+arena.hazard().maxX())/2,0.2f,(arena.hazard().minZ()+arena.hazard().maxZ())/2));
            audio.update(session,world,dt);
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
        input.clear(); input.setGameplay(flow.screen()==Screen.RUNNING || flow.screen()==Screen.COUNTDOWN);
        if(world!=null && flow.screen()!=Screen.RUNNING && flow.screen()!=Screen.COUNTDOWN && flow.screen()!=Screen.RESULTS) audio.pause();
        if(world!=null && (flow.screen()==Screen.RUNNING||flow.screen()==Screen.COUNTDOWN)) audio.resume();
        menuNode.setCullHint(world==null?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        loop.resetAccumulator(); redraw();
    }
    private void redraw() {
        int selection=renderedScreen==flow.screen()?ui.selection():0;
        renderedScreen=flow.screen();
        ui.clear(); hpText=weaponText=timerText=debugText=countdownText=null; radarMarkers.clear();
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
                ui.text("MVP 0.1.0  |  Local single-player  |  "+store.stats().completedMatches+" completed matches",144,196,18,GameUi.PAPER);
            }
            case LOADING -> { ui.title("TUNING IN","Loading Dead Air Yard..."); }
            case RUNNING,COUNTDOWN -> createHud();
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
                ui.text("RIVET / DEAD AIR YARD / DEAD AIR CIRCUIT\nOriginal procedural geometry, music and sound.\n\nJava 21 / jMonkeyEngine / Minie / LWJGL / Gson\nThird-party notices ship in the licenses directory.\n\nNo assets, characters or recordings from other games.\n\nMusic and driving feel require owner acceptance.",145,770,24,GameUi.PAPER);
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
        ui.rect("hp-panel",50,35,485,175,GameUi.INK,0);
        hpText=ui.text("",76,185,25,GameUi.PAPER);
        ui.rect("hp-track",76,121,410,16,new ColorRGBA(0.2f,0.18f,0.16f,0.9f),1);
        hpBar=ui.rect("hp",76,121,410,16,GameUi.ACCENT,2);
        turboBar=ui.rect("turbo",76,93,410,10,ColorRGBA.Cyan,2);
        heatBar=ui.rect("heat",76,68,410,10,new ColorRGBA(1,0.18f,0.12f,1),2);
        ui.rect("weapon-panel",1360,35,510,175,GameUi.INK,0);
        weaponText=ui.text("",1385,185,24,GameUi.PAPER);
        timerText=ui.text("",785,1030,29,GameUi.PAPER);
        ui.text("+",949,575,32,GameUi.ACCENT);
        ui.rect("radar",1630,805,240,240,GameUi.INK,0);
        ui.rect("radar-center",1748,923,5,5,GameUi.PAPER,2);
        for(int id=1;id<5;id++) radarMarkers.put(id,ui.text("",0,0,18,GameUi.ACCENT));
        debugText=ui.text("",58,1015,18,GameUi.PAPER);
        countdownText=ui.text("",885,760,105,GameUi.ACCENT);
        if(showHelp) ui.text("W/S drive / brake / reverse    A/D steer    SPACE handbrake\nSHIFT turbo    LMB machine gun    RMB rocket    Q/E weapon\nF pulse    V rear view    Hold R recover    ESC pause\nF1 help    F3 diagnostics    F4 navigation    F12 screenshot",565,280,22,GameUi.PAPER);
    }
    private void updateHud() {
        if(hpText==null || session==null) return;
        var player=session.vehicle(0);
        hpText.setText(String.format(Locale.ROOT,"RIVET  /  HP %.0f     TURBO %.0f",Math.max(0,player.hp),player.turbo));
        hpBar.setLocalScale(Math.max(0,player.hp/200),1,1); turboBar.setLocalScale(player.turbo/100,1,1); heatBar.setLocalScale(player.heat/100,1,1);
        int lock=combat.lockTarget(0);
        weaponText.setText(String.format(Locale.ROOT,"%s  %d\nPULSE  %s\n%s",player.selectedWeapon==0?"HOMING":"POWER",player.selectedWeapon==0?player.homingAmmo:player.powerAmmo,
                player.pulseCooldown==0?"READY":String.format(Locale.ROOT,"%.1fs",player.pulseCooldown/120f),player.overheated?"MG OVERHEATED":lock>=0?"LOCK: "+session.vehicle(lock).name:"MG READY"));
        int remaining=Math.max(0,matchRules.durationSeconds()-(int)session.seconds());
        timerText.setText(String.format(Locale.ROOT,"%02d:%02d   /   %d RIVALS",remaining/60,remaining%60,session.vehicles.stream().filter(v->v.id!=0&&v.alive()).count()));
        if(debug||navDebug) {
            StringBuilder diagnostics=new StringBuilder(String.format(Locale.ROOT,"%.0f FPS | tick %d | %.1f m/s | wheels %d | %s\nHP %.1f heat %.1f energy %.1f | target %d | rockets %d | voices %d\ndropped %.4fs | bodies %d\n%s",1/Math.max(0.0001f,timer.getTimePerFrame()),session.tick,world.velocity(0).length(),world.wheelContacts(0),drivers.get(0).reversing()?"REVERSE":"FORWARD",player.hp,player.heat,player.turbo,lock,combat.projectiles().size(),audio.voiceCount(),loop.droppedSimulationTime(),world.bodyCount(),input.diagnostics()));
            if(navDebug) for(int id=1;id<5;id++) diagnostics.append("\n").append(session.vehicle(id).name).append(" ").append(bots.state(id)).append(" -> ").append(bots.targetId(id));
            debugText.setText(diagnostics.toString());
        } else debugText.setText("");
        Quaternion inverse=world.rotation(0).inverse();
        for(var entry:radarMarkers.entrySet()) {
            int id=entry.getKey(); BitmapText marker=entry.getValue();
            Vector3f relative=inverse.mult(world.position(id).subtract(world.position(0)));
            boolean visible=session.vehicle(id).alive() && relative.length()<=matchRules.radarRange();
            marker.setText(visible?(relative.y>2?"^":relative.y< -2?"v":"+")+id:"");
            marker.setLocalTranslation(1746+relative.x*1.65f,934+relative.z*1.65f,5);
        }
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
            var binding=keys.get(i); String name=inputManager.getKeyName(binding.getValue());
            ui.button(binding.getKey()+"   ["+name+"]",140,y,710,()->{ rebinding=binding.getKey();redraw(); }); y-=65;
        }
        ui.button("NEXT PAGE",140,155,340,()->{ controlsPage=(controlsPage+1)%2;redraw();ui.select(0); });
        ui.button("BACK",500,155,350,()->{rebinding=null;flow.back();});
        ui.text("MOUSE\nLMB  Machine gun\nRMB  Selected rocket\n\nXBOX-COMPATIBLE GAMEPAD\nRT / LT  Throttle / brake / reverse\nLeft stick  Steer\nX  Handbrake    B  Turbo\nLB  Machine gun    RB  Rocket\nD-pad  Weapon    A  Pulse\nY  Rear view    View  Recovery\nMenu  Pause\n\n"+input.diagnostics(),1010,930,24,GameUi.PAPER);
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
        var s=store.settings(); settings.setResolution(s.width,s.height); settings.setFullscreen(s.fullscreen); settings.setVSync(s.vsync); restart();
    }
    private void restoreVideo() {
        if(previousVideo==null) return;
        var old=previousVideo;previousVideo=null;store.replaceSettings(old);applyVideo();redraw();notice("Previous video mode restored.");
    }
    private void key(int code) {
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
        boolean driving=flow.screen()==Screen.RUNNING||flow.screen()==Screen.COUNTDOWN;
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
        if(screenshots!=null) {screenshots.setFileName("WreckRiff-0.1.0-"+System.currentTimeMillis()+"-");screenshots.takeScreenshot();}
    }
    private void writeReport() {
        if(!options.dev()||session==null||world==null||report==null)return;
        try {report.write(store.directory().resolve("session-summary.json"),session,loop,org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),cam.getWidth(),cam.getHeight(),store.settings().vsync,audioRenderer!=null,audio.voiceCount(),world.bodyCount());}
        catch(IOException e) {Logger.getLogger(getClass().getName()).warning("Cannot write report: "+e.getMessage());}
    }
    private void cleanupMatch() {
        if(audio!=null)audio.stopMatch(); if(combatVisuals!=null){combatVisuals.close();combatVisuals=null;}
        if(runtime!=null){runtime.close();runtime=null;world=null;combat=null;}
        else if(world!=null){world.close();world=null;}
        matchNode.detachAllChildren();vehicleModels.clear();wheels.clear();drivers.clear();wreckTimers.clear();
        session=null;arenaSystems=null;bots=null;content=null;navNode=null;
    }
    private void fail(Exception exception) {
        Logger.getLogger(getClass().getName()).log(Level.SEVERE,"Game failure",exception);
        error=exception.getClass().getSimpleName()+": "+exception.getMessage();
        if(options.smokeSeconds()>0) {System.err.println("GRAPHICS_SMOKE_FAILED: "+error);stop();throw new IllegalStateException(error,exception);}
        if(ui!=null){if(input!=null)input.clear();if(audio!=null)audio.pause();flow.error();}
        else {System.err.println(error);stop();}
    }
    @Override public void destroy() {
        writeReport();cleanupMatch();if(audio!=null)audio.close();if(input!=null)input.close();
        if(fileLog!=null){Logger.getLogger("").removeHandler(fileLog);fileLog.close();} super.destroy();
    }
    private static String percent(float value) {return Math.round(value*100)+"%";}
    private static float nextLevel(float value) {return value>=0.99f?0:Math.min(1,value+0.1f);}
    private static String wrap(String input,int width) {StringBuilder out=new StringBuilder();int column=0;for(String word:input.split(" ")){if(column+word.length()>width){out.append('\n');column=0;}out.append(word).append(' ');column+=word.length()+1;}return out.toString();}
}
