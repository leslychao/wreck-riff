package game.wreckriff.combat;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import game.wreckriff.arena.ArenaDefinition.Theme;
import game.wreckriff.presentation.*;
import game.wreckriff.simulation.*;
import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Tools-only A/B evidence: one uniform floor, actual ordnance, actual Freeze wake. */
public final class FreezeTrailReview extends SimpleApplication {
    private static final String[] STAGES={"plain-floor","ordnance-only","freeze-trail","freeze-trail-no-floor-shadow"};
    private final Path output;
    private final CountDownLatch done=new CountDownLatch(1);
    private volatile Throwable failure;
    private boolean complete;
    private SceneLighting.Handle lighting;
    private CombatVisuals visuals;
    private OrdnancePresentation ordnance;
    private ScreenshotAppState screenshots;
    private Geometry floor;
    private int stage,frame;
    private final ProjectileState projectile=new ProjectileState(1,0,"freeze",new Vector3f(0,1.4f,-9.5f),Vector3f.UNIT_Z,120,-1);
    private final List<Map<String,Object>> evidence=new ArrayList<>();

    private FreezeTrailReview(Path output){this.output=output;}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Output directory required");
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("complete.json"));
        var app=new FreezeTrailReview(output);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff - Freeze plain-floor A/B");settings.setResolution(1280,720);
        settings.setSamples(4);settings.setVSync(false);settings.setFrameRate(60);settings.setGammaCorrection(true);
        app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);app.start(JmeContext.Type.Display);
        if(!app.done.await(60,TimeUnit.SECONDS)){app.stop(false);Files.writeString(output.resolve("failure.txt"),"Freeze fixture exceeded 60 seconds");System.exit(1);}
        if(app.failure!=null){app.failure.printStackTrace();System.exit(1);}
        if(!app.complete)throw new IllegalStateException("Freeze fixture closed before all A/B frames");
    }
    @Override public void simpleInitApp() {
        if(context.getType()!=JmeContext.Type.Display)throw new IllegalStateException("Real window required");
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);
        screenshots=new ScreenshotAppState(output+File.separator);stateManager.attach(screenshots);
        floor=new Geometry("untextured-plain-floor",new Box(40,.1f,40));floor.setLocalTranslation(0,-.1f,0);
        floor.setMaterial(SurfaceMaterials.lit(assetManager,new ColorRGBA(.3f,.32f,.34f,1),8,.05f));
        floor.setShadowMode(RenderQueue.ShadowMode.Receive);rootNode.attachChild(floor);
        lighting=SceneLighting.install(assetManager,rootNode,viewPort);lighting.setSamples(4);
        lighting.apply(Theme.CONSTRUCTION,false);lighting.initialize(renderManager);
        cam.setFrustumPerspective(45,1280f/720,.05f,150);cam.setLocation(new Vector3f(8,7,-13));cam.lookAt(new Vector3f(0,1,0),Vector3f.UNIT_Y);
    }
    @Override public void simpleUpdate(float seconds) {
        if(lighting==null)return;
        frame++;
        if(stage>=2&&frame<=18){projectile.previousPosition.set(projectile.position);projectile.position.z+=45f/60;
            visuals.update(List.of(projectile),List.of(),List.of(),List.of(),null,1f/60);}
        int captureFrame=stage==0?100:28;
        if(frame==captureFrame){
            if(cam.getWidth()!=1280||cam.getHeight()!=720)throw new IllegalStateException("Unexpected actual framebuffer size");
            if(visuals!=null&&!Objects.equals(4,visuals.statistics().get("sceneDepthSamples")))throw new IllegalStateException("Expected actual four-sample scene depth");
            screenshots.setFileName(STAGES[stage]);screenshots.takeScreenshot();
            var row=new LinkedHashMap<String,Object>();row.put("stage",STAGES[stage]);row.put("camera",List.of(cam.getWidth(),cam.getHeight()));
            row.put("floorShadow",floor.getShadowMode().toString());if(visuals!=null)row.put("vfx",visuals.statistics());evidence.add(row);
        }
        if(frame>captureFrame+5){
            if(stage==3){finish();return;}
            if(ordnance!=null){ordnance.close();ordnance=null;}
            if(visuals!=null){lighting.bindCombatVisuals(null);visuals.close();visuals=null;}
            stage++;frame=0;
            if(stage==1){projectile.position.set(0,1.4f,4);ordnance=new OrdnancePresentation(assetManager,rootNode);ordnance.update(List.of(projectile),List.of(),cam.getLocation());}
            else {projectile.position.set(0,1.4f,-9.5f);projectile.previousPosition.set(projectile.position);
                if(stage==3)floor.setShadowMode(RenderQueue.ShadowMode.Off);
                visuals=new CombatVisuals(assetManager,rootNode,new PlainFloor());lighting.bindCombatVisuals(visuals);}
        }
    }
    private void finish(){
        try {
            var hashes=new TreeMap<String,String>();
            try(var files=Files.list(output)){for(Path file:files.filter(p->p.toString().endsWith(".png")).toList())hashes.put(file.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));}
            if(hashes.size()!=STAGES.length)throw new IllegalStateException("Missing native screenshots");
            Files.writeString(output.resolve("complete.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("stages",evidence,"pngSha256",hashes,"scope","Native A/B rendering proof; no road textures, decals, vehicles or skid marks. Not a benchmark.")));
            complete=true;stop();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @Override public void handleError(String message,Throwable error){failure=error;try{Files.writeString(output.resolve("failure.txt"),message+"\n"+error);}catch(Exception ignored){}super.handleError(message,error);}
    @Override public void destroy(){try{if(lighting!=null)lighting.bindCombatVisuals(null);if(visuals!=null)visuals.close();if(ordnance!=null)ordnance.close();super.destroy();}finally{done.countDown();}}
    private static final class PlainFloor implements WorldQuery {
        public Vector3f position(int id){return Vector3f.ZERO;}public Vector3f velocity(int id){return Vector3f.ZERO;}
        public Quaternion rotation(int id){return Quaternion.IDENTITY;}public boolean grounded(int id){return true;}public float mass(int id){return 1100;}
        public Hit ray(Vector3f a,Vector3f b,int id){return null;}public Hit sweep(Vector3f a,Vector3f b,float r,int id,float s,float e){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float r){if(a.y>=r&&b.y<r){float t=(a.y-r)/(a.y-b.y);return new Hit(-1,a.clone().interpolateLocal(b,t),Vector3f.UNIT_Y,t);}return null;}
        public boolean visible(Vector3f a,Vector3f b,int id){return true;}public float distanceToHull(int id,Vector3f p){return 0;}
        public Vector3f closestHullPoint(int id,Vector3f from){return Vector3f.ZERO;}
        public void impulse(int id,Vector3f linear,Vector3f torque,float cap){throw new AssertionError("Presentation cannot mutate physics");}
    }
}
