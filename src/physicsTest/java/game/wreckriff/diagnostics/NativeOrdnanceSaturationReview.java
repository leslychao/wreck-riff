package game.wreckriff.diagnostics;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.font.BitmapText;
import com.jme3.light.*;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import game.wreckriff.audio.AudioDirector;
import game.wreckriff.presentation.*;
import game.wreckriff.simulation.MatchSession;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Standalone verification application. Loaded with the immutable game image plus physicsTest classes. */
public final class NativeOrdnanceSaturationReview extends SimpleApplication {
    private enum Phase { FILL, HOLD, DRAIN, EXPIRED }
    private final Path output;
    private final String expectedSource,runId=Long.toUnsignedString(System.nanoTime());
    private final Node range=new Node("native-firing-range");
    private final Map<Integer,Node> cars=new LinkedHashMap<>();
    private final List<String> capturePrefixes=new ArrayList<>();
    private final List<Map<String,Object>> attempts=new ArrayList<>();
    private final Properties buildInfo=new Properties();
    private ScreenshotAppState screenshots;
    private BitmapText heading,caption;
    private NativeOrdnanceSaturationRig rig;
    private CombatVisuals visuals;
    private AudioDirector sound;
    private Phase phase;
    private float accumulator,phaseSeconds;
    private long pausedTick;
    private int attempt,capRenderedFrames,maximumFrustumInstances;
    private boolean phaseCaptured,retryCleared,done;

    private NativeOrdnanceSaturationReview(Path output,String expectedSource){this.output=output;this.expectedSource=expectedSource;}
    public static void main(String[] args)throws Exception {
        JmeSystem.setSystemDelegate(new game.wreckriff.audio.DesktopAudioSystem());
        if(args.length!=2)throw new IllegalArgumentException("Usage: output-directory expected-image-source-sha256");
        if(!args[1].matches("(?i)[0-9a-f]{64}"))throw new IllegalArgumentException("Expected exact immutable image source SHA-256");
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output);Files.deleteIfExists(output.resolve("review.json"));
        var app=new NativeOrdnanceSaturationReview(output,args[1]);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff / Native combat ordnance limits");settings.setResolution(1920,1080);settings.setSamples(4);
        settings.setGammaCorrection(true);settings.setVSync(false);settings.setFrameRate(60);
        app.setSettings(settings);app.setShowSettings(false);app.start(JmeContext.Type.Display);
    }
    @Override public void simpleInitApp() {
        if(context.getType()!=JmeContext.Type.Display||audioRenderer==null)throw new IllegalStateException("A real window and audio device are required");
        try(var input=getClass().getResourceAsStream("/build-info.properties")) {
            if(input==null)throw new IllegalStateException("Image build-info missing");buildInfo.load(input);
        } catch(Exception failure){throw new IllegalStateException("Cannot identify the running image",failure);}
        if(!expectedSource.equalsIgnoreCase(buildInfo.getProperty("sourceSha256")))throw new IllegalStateException("Running source hash differs from the requested immutable image");
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);inputManager.setCursorVisible(true);
        screenshots=new ScreenshotAppState(output+File.separator);stateManager.attach(screenshots);
        rootNode.attachChild(range);viewPort.setBackgroundColor(new ColorRGBA(.08f,.12f,.17f,1));
        var sun=new DirectionalLight();sun.setDirection(new Vector3f(-.5f,-1,.35f).normalizeLocal());sun.setColor(new ColorRGBA(1,.9f,.77f,1).mult(1.4f));rootNode.addLight(sun);
        var ambient=new AmbientLight();ambient.setColor(new ColorRGBA(.32f,.38f,.45f,1));rootNode.addLight(ambient);
        cam.setFrustumPerspective(47,1920f/1080,1,700);cam.setLocation(new Vector3f(100,145,185));cam.lookAt(new Vector3f(0,0,52),Vector3f.UNIT_Y);
        heading=new BitmapText(guiFont);heading.setSize(28);heading.setLocalTranslation(32,1040,0);guiNode.attachChild(heading);
        caption=new BitmapText(guiFont);caption.setSize(18);caption.setLocalTranslation(32,1000,0);guiNode.attachChild(caption);
        sound=new AudioDirector(assetManager,audioRenderer,listener,rootNode);sound.setVolumes(.8f,.3f,.8f);
        beginAttempt();
    }
    private void beginAttempt() {
        rig=new NativeOrdnanceSaturationRig();visuals=new CombatVisuals(assetManager,range,rig.world);
        Geometry floor=new Geometry("native-floor",new Box(100,.5f,260));floor.setLocalTranslation(0,-.5f,100);
        floor.setMaterial(SurfaceMaterials.lit(assetManager,new ColorRGBA(.12f,.14f,.16f,1),12,.12f));range.attachChild(floor);
        Geometry apron=new Geometry("native-firing-apron",new Box(100,NativeOrdnanceSaturationRig.FIRING_PLATFORM_HEIGHT/2,6));apron.setLocalTranslation(0,NativeOrdnanceSaturationRig.FIRING_PLATFORM_HEIGHT/2,0);
        apron.setMaterial(SurfaceMaterials.lit(assetManager,new ColorRGBA(.25f,.27f,.28f,1),12,.12f));range.attachChild(apron);
        for(var participant:rig.session.vehicles) {
            Node car=VehicleVisual.create(assetManager,rig.world.profile(participant.id),participant.liveryId);cars.put(participant.id,car);range.attachChild(car);
        }
        sound.startMatch(rig.session.sessionId,rig.rosterArena.metadata().music(),rig.rosterArena.metadata().bossMusic());
        setPhase(Phase.FILL);updatePresentation(0);
    }
    private void setPhase(Phase next){phase=next;phaseSeconds=0;accumulator=0;phaseCaptured=false;}
    @Override public void simpleUpdate(float dt) {
        if(done||rig==null)return;
        sound.updatePresentation(dt);
        phaseSeconds+=Math.min(dt,.25f);
        boolean paused=phase==Phase.HOLD||phase==Phase.EXPIRED;
        if(!paused) {
            accumulator+=Math.min(dt,.25f);
            while(accumulator>=MatchSession.DT) {
                var events=rig.step(phase==Phase.FILL);visuals.accept(events);sound.accept(events);accumulator-=MatchSession.DT;
                if(phase==Phase.FILL&&rig.saturated()) {
                    pausedTick=rig.session.tick;sound.pause();setPhase(Phase.HOLD);break;
                }
                if(phase==Phase.FILL&&rig.scriptTicks()>600)throw new IllegalStateException("Command-only saturation failed: "+rig.evidence());
                if(phase==Phase.DRAIN&&rig.combat.projectiles().isEmpty()){setPhase(Phase.EXPIRED);sound.pause();break;}
            }
        }
        updatePresentation(paused?0:Math.min(dt,.1f));
        heading.setText("NATIVE COMBAT / "+(attempt==0?"INITIAL SESSION":"RETRY SESSION")+" / "+phase);
        caption.setText("Real CombatSystem states: "+rig.combat.projectiles().size()+" / "+rig.rules.maximumProjectiles()+" projectiles; "+rig.combat.mines().size()+" / "+rig.rules.mine().maximumActive()+" mines.\n"
                +"13 native chassis; ordinary placement and Homing commands; bundled ammo, cooldown, TTL and limits. No state injection.\n"
                +"Staged firing range. Pauses hold the full scene for capture. This is a correctness check, not map gameplay or performance acceptance.");
        if(phase==Phase.HOLD) {
            if(rig.session.tick!=pausedTick)throw new IllegalStateException("Paused render spent combat time");
            assertRenderedCap();capRenderedFrames++;
            if(!phaseCaptured&&phaseSeconds>.4f){capture(attempt==0?"01-native-64-10":"03-native-retry-64-10");phaseCaptured=true;}
            if(phaseSeconds>2) {
                if(attempt==1){attempts.add(rig.evidence());finish();}
                else {sound.resume();setPhase(Phase.DRAIN);}
            }
        } else if(phase==Phase.EXPIRED) {
            if(!phaseCaptured&&phaseSeconds>.25f){capture("02-native-projectiles-expired");phaseCaptured=true;}
            if(phaseSeconds>1) {
                attempts.add(rig.evidence());visuals.close();rig.combat.clear();
                retryCleared=range.getChild("ordnance-models")==null&&rig.combat.projectiles().isEmpty()&&rig.combat.mines().isEmpty();
                if(!retryCleared)throw new IllegalStateException("Retry retained authoritative states or presentation nodes");
                sound.stopMatch();rig.close();range.detachAllChildren();cars.clear();attempt++;beginAttempt();
            }
        }
    }
    private void updatePresentation(float dt) {
        for(var entry:cars.entrySet()){entry.getValue().setLocalTranslation(rig.world.position(entry.getKey()));entry.getValue().setLocalRotation(rig.world.rotation(entry.getKey()));}
        visuals.update(rig.combat.projectiles(),rig.combat.mines(),rig.combat.fireZones(),rig.combat.ballisticWarnings(),rig.session,dt);
        // Update from the light-owning parent before reading bounds; updating the child first caches an empty inherited light list.
        sound.update(rig.session,rig.world,dt);rootNode.updateGeometricState();
    }
    private void assertRenderedCap() {
        var models=(Node)range.getChild("ordnance-models");int expected=rig.rules.maximumProjectiles()+rig.rules.mine().maximumActive();
        if(visuals.projectileCount()!=rig.rules.maximumProjectiles()||models==null||models.getQuantity()!=expected)
            throw new IllegalStateException("Presentation omitted authoritative capped states");
        int visible=0,previous=cam.getPlaneState();
        for(var model:models.getChildren()){cam.setPlaneState(0);if(cam.contains(model.getWorldBound())!=Camera.FrustumIntersect.Outside)visible++;}
        cam.setPlaneState(previous);maximumFrustumInstances=Math.max(maximumFrustumInstances,visible);
        if(visible!=expected)throw new IllegalStateException("The inspection camera crops "+(expected-visible)+" live ordnance instances");
    }
    private void capture(String name){String prefix=name+"-"+runId+"-";screenshots.setFileName(prefix);screenshots.takeScreenshot();capturePrefixes.add(prefix);}
    private void finish() {
        done=true;
        try {
            List<String> captures;try(var files=Files.list(output)) {captures=files.map(p->p.getFileName().toString()).filter(n->n.endsWith(".png")&&capturePrefixes.stream().anyMatch(n::startsWith)).sorted().toList();}
            for(String prefix:capturePrefixes)if(captures.stream().noneMatch(n->n.startsWith(prefix)))throw new IllegalStateException("Missing native rendered capture "+prefix);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("status","PASS");evidence.put("sourceSha256",buildInfo.getProperty("sourceSha256"));
            evidence.put("context",context.getType().name());evidence.put("renderer",context.getSettings().getRenderer());evidence.put("resolution",List.of(cam.getWidth(),cam.getHeight()));
            evidence.put("msaa",context.getSettings().getSamples());evidence.put("audioEnabled",audioRenderer!=null);evidence.put("attempts",attempts);
            evidence.put("capRenderedFrames",capRenderedFrames);evidence.put("maximumFrustumInstances",maximumFrustumInstances);evidence.put("retryCleared",retryCleared);
            evidence.put("captures",captures);evidence.put("artisticStatus","NEEDS_CREATIVE_REVIEW");
            Files.writeString(output.resolve("review.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence)+"\n",StandardCharsets.UTF_8);
            System.out.println("NATIVE_ORDNANCE_SATURATION PASS: "+output.resolve("review.json"));
        } catch(Exception failure){throw new IllegalStateException("Native saturation evidence failed",failure);}
        stop();
    }
    @Override public void destroy(){if(visuals!=null)visuals.close();if(rig!=null)rig.close();if(sound!=null)sound.close();super.destroy();}
    @Override public void handleError(String message,Throwable failure) {
        System.err.println(message);failure.printStackTrace();
        try {
            var evidence=new LinkedHashMap<String,Object>();evidence.put("status","FAIL");evidence.put("runId",runId);
            evidence.put("expectedSourceSha256",expectedSource);evidence.put("sourceSha256",buildInfo.getProperty("sourceSha256"));
            evidence.put("phase",phase==null?"INITIALIZING":phase.name());evidence.put("message",message);evidence.put("failure",failure.toString());
            Files.writeString(output.resolve("failure-"+runId+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence)+"\n",StandardCharsets.UTF_8);
        } catch(Exception reportingFailure){System.err.println("Could not retain failure report: "+reportingFailure);}
        stop();System.exit(2);
    }
}
