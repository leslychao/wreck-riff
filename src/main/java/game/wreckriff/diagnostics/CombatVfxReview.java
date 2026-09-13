package game.wreckriff.diagnostics;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.math.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import game.wreckriff.arena.ArenaDefinition.Theme;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.presentation.*;
import game.wreckriff.simulation.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Real OpenGL shader/attachment smoke check. This fixture is not match or performance acceptance. */
public final class CombatVfxReview extends SimpleApplication {
    private final Path output;
    private CombatVisuals visuals;
    private SceneLighting.Handle lighting;
    private ScreenshotAppState screenshots;
    private float elapsed,burstClock;
    private int stage=-1;
    private long id;
    private boolean captured,capturedHot;
    private final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
    private volatile Throwable failure;
    private boolean complete;
    private final MatchSession smokeSession=new MatchSession(42,180);
    private final List<String> observed=new ArrayList<>();
    private static final String[] STAGES={"msaa4-bloom-off","msaa4-bloom-on","msaa4-off-axis","single-sample-bloom-off","msaa2-bloom-off","msaa8-bloom-off","msaa4-resize-4x3","msaa4-rebound"};
    private CombatVfxReview(Path output){this.output=output;}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Output directory required");
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("complete.txt"));
        var evidence=new ArrayList<VerifyAssets.Asset>();CombatVfxAssetsVerifier.verify(evidence);
        System.out.println("VFX_BAKE_VERIFY assets="+evidence.size());
        var app=new CombatVfxReview(output);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff - Combat VFX shader check");settings.setResolution(1280,720);
        settings.setSamples(4);settings.setVSync(false);settings.setFrameRate(60);settings.setGammaCorrection(true);
        app.setSettings(settings);app.setShowSettings(false);app.start(JmeContext.Type.Display);
        if(!app.done.await(90,java.util.concurrent.TimeUnit.SECONDS)){app.stop();throw new IllegalStateException("Native VFX fixture timed out");}
        if(app.failure!=null)throw new IllegalStateException("Native VFX fixture failed",app.failure);
        if(!app.complete)throw new IllegalStateException("Native VFX fixture closed before all stages rendered");
    }
    @Override public void simpleInitApp() {
        if(context.getType()!=JmeContext.Type.Display)throw new IllegalStateException("Real window required");
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);
        screenshots=new ScreenshotAppState(output+File.separator);stateManager.attach(screenshots);
        geometry("floor",new Vector3f(0,-.2f,0),new Vector3f(14,.2f,14),new ColorRGBA(.23f,.25f,.27f,1));
        geometry("wall",new Vector3f(0,1.5f,-1),new Vector3f(.28f,1.5f,2.2f),new ColorRGBA(.35f,.41f,.44f,1));
        geometry("target",new Vector3f(-2,.75f,1),new Vector3f(1,.75f,2),new ColorRGBA(.4f,.15f,.055f,1));
        lighting=SceneLighting.install(assetManager,rootNode,viewPort);lighting.setSamples(4);
        smokeSession.vehicle(0).hp=smokeSession.vehicle(0).maximumHp*.25f;
        lighting.initialize(renderManager);visuals=new CombatVisuals(assetManager,rootNode,new FixtureWorld());
        lighting.bindCombatVisuals(visuals);switchStage(0);
    }
    private void geometry(String name,Vector3f point,Vector3f half,ColorRGBA color) {
        var geometry=new Geometry(name,new Box(half.x,half.y,half.z));geometry.setLocalTranslation(point);
        geometry.setMaterial(SurfaceMaterials.lit(assetManager,color,40,.4f));
        geometry.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);rootNode.attachChild(geometry);
    }
    private void switchStage(int next) {
        stage=next;elapsed=0;burstClock=0;captured=false;capturedHot=false;
        lighting.apply(stage==1?Theme.NEON:Theme.CONSTRUCTION,stage==1);
        lighting.setSamples(switch(stage){case 3->0;case 4->2;case 5->8;default->4;});
        if(stage==6||stage==7)org.lwjgl.glfw.GLFW.glfwSetWindowSize(((com.jme3.system.lwjgl.LwjglWindow)context).getWindowHandle(),stage==6?960:1280,720);
        if(stage==7){lighting.bindCombatVisuals(null);visuals.close();visuals=new CombatVisuals(assetManager,rootNode,new FixtureWorld());lighting.bindCombatVisuals(visuals);}
        cam.setFrustumPerspective(45,1280f/720,.05f,100);cam.setLocation(new Vector3f(9,7,12));cam.lookAt(new Vector3f(0,1,0),Vector3f.UNIT_Y);
        if(stage==2)cam.setFrustum(.05f,100,-.046f,.026f,.02f,-.02f);
    }
    @Override public void simpleUpdate(float wallSeconds) {
        // Fixed presentation frames make captures independent of first shader/texture uploads.
        float dt=1f/60;
        if(visuals==null)return;elapsed+=dt;burstClock-=dt;
        if(burstClock<=0) {
            String[] kinds={"power","mine","cannon","ballistic"};
            for(int i=0;i<4;i++)visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,++id,-1,0,new Vector3f((i-1.5f)*3,1,-2),kinds[i],30,Vector3f.ZERO,Vector3f.UNIT_Y)));
            for(ContactSurface surface:List.of(ContactSurface.METAL,ContactSurface.GLASS,ContactSurface.CONCRETE,ContactSurface.EARTH))
                visuals.acceptPresented(List.of(new GameEvent(GameEvent.Type.IMPACT,++id,-1,0,new Vector3f((surface.ordinal()-3)*.7f,1,3),"machine-gun",5,new Vector3f(0,1,8),Vector3f.UNIT_Z).withContact(surface,null)));
            for(int i=0;i<3;i++)visuals.accept(List.of(new GameEvent(GameEvent.Type.SHOT,++id,0,0,new Vector3f(-2,1,1),"machine-gun",5,new Vector3f(3,1,6),Vector3f.ZERO)));
            burstClock=1.2f;
        }
        var fire=new CombatSystem.FireZoneView(1,0,new Vector3f(2,0,2),Vector3f.UNIT_Y,3,120,List.of(new Vector3f(2,0,2),new Vector3f(3,0,2),new Vector3f(2,0,3)));
        var warning=new CombatSystem.BallisticWarningView(2,0,new Vector3f(-4,0,2),Vector3f.UNIT_Y,2,120);
        visuals.update(List.of(),List.of(),List.of(fire),List.of(warning),smokeSession,dt);
        if(elapsed>.12f&&!capturedHot){screenshots.setFileName(STAGES[stage]+"-hot");screenshots.takeScreenshot();capturedHot=true;}
        if(elapsed>1.45f&&!captured){screenshots.setFileName(STAGES[stage]);screenshots.takeScreenshot();captured=true;
            observed.add(STAGES[stage]+" camera="+cam.getWidth()+"x"+cam.getHeight()+" depth="+visuals.statistics().get("sceneDepthSamples"));
            int expected=switch(stage){case 3->1;case 4->2;case 5->8;default->4;};
            if(!Objects.equals(expected,visuals.statistics().get("sceneDepthSamples")))throw new IllegalStateException("Requested MSAA was not applied: "+observed.getLast());
            if(stage==6&&cam.getWidth()!=960||stage==7&&cam.getWidth()!=1280)throw new IllegalStateException("Real native resize did not reach the render camera");}
        if(elapsed>2.5f) {
            if(stage+1<STAGES.length)switchStage(stage+1);
            else {try{Files.writeString(output.resolve("complete.txt"),"Real display rendered all stages:\n"+String.join("\n",observed)+"\nScene depth, alpha/additive, lit debris, ground, bloom off/on, real native resize, rebind. Not performance acceptance.\n");complete=true;}catch(Exception e){throw new IllegalStateException(e);}stop();}
        }
    }
    @Override public void handleError(String message,Throwable error){failure=error;super.handleError(message,error);}
    @Override public void destroy(){try{if(lighting!=null)lighting.bindCombatVisuals(null);if(visuals!=null)visuals.close();super.destroy();}finally{done.countDown();}}
    private static final class FixtureWorld implements WorldQuery {
        public Vector3f position(int id){return new Vector3f(0,1,5);}public Vector3f velocity(int id){return Vector3f.ZERO;}
        public Quaternion rotation(int id){return Quaternion.IDENTITY;}public boolean grounded(int id){return true;}public float mass(int id){return 1100;}
        public Hit ray(Vector3f a,Vector3f b,int id){return null;}public Hit sweep(Vector3f a,Vector3f b,float r,int id,float s,float e){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float r){
            Hit closest=null;if(a.y>=r&&b.y<r){float t=(a.y-r)/(a.y-b.y);closest=new Hit(-1,a.clone().interpolateLocal(b,t),Vector3f.UNIT_Y,t);}
            for(int side:new int[]{-1,1}) {
                float plane=side*(.28f+r),delta=b.x-a.x;
                if(Math.abs(delta)<.0001f||side*a.x<side*plane)continue;
                float t=(plane-a.x)/delta;if(t<0||t>1)continue;
                Vector3f point=a.clone().interpolateLocal(b,t);
                if(point.y>=-r&&point.y<=3+r&&point.z>=-3.2f-r&&point.z<=1.2f+r&&(closest==null||t<closest.fraction()))
                    closest=new Hit(-1,point,new Vector3f(side,0,0),t);
            }
            return closest;
        }
        public boolean visible(Vector3f a,Vector3f b,int id){return true;}public float distanceToHull(int id,Vector3f p){return 0;}
        public Vector3f closestHullPoint(int id,Vector3f from){return position(id);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float cap){throw new AssertionError("Presentation cannot mutate physics");}
    }
}
