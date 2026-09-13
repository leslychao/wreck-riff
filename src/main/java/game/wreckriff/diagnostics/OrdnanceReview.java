package game.wreckriff.diagnostics;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.font.BitmapText;
import com.jme3.light.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import game.wreckriff.presentation.*;
import game.wreckriff.combat.OrdnanceInspectionScene;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded real-window art inspection. It loads the exact exported game models, never substitute meshes. */
public final class OrdnanceReview extends SimpleApplication {
    private final Path directory;
    private final String runId=Long.toUnsignedString(System.nanoTime());
    private final Node display=new Node("review-models");
    private final List<String> requested=new ArrayList<>();
    private final DirectionalLight key=new DirectionalLight(),rim=new DirectionalLight();
    private final AmbientLight ambient=new AmbientLight();
    private ScreenshotAppState screenshots;
    private BitmapText title,caption;
    private float elapsed;
    private int stage=-1;
    private boolean captured,finished;
    private OrdnanceInspectionScene saturation;
    private final List<Map<String,Object>> saturationRuns=new ArrayList<>();
    private final List<Node> frostVehicles=new ArrayList<>();
    private boolean retryCleanup;
    private static final List<String> STAGES=List.of("01-day-overview","02-neon-overview","03-homing","04-power","05-napalm", "06-ballistic","07-ballistic-fall","08-cannon","09-freeze","10-mine","11-mine-slope","12-saturated-flight","13-retry-saturation","14-frost-day","15-frost-neon");
    public OrdnanceReview(Path directory){this.directory=directory;}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Review output directory required");
        Path directory=Path.of(args[0]).toAbsolutePath();Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve("review.json"));
        OrdnanceReview app=new OrdnanceReview(directory);AppSettings settings=new AppSettings(true);
        settings.setTitle("Wreck Riff - Ordnance material inspection");settings.setResolution(1600,1000);settings.setSamples(4);
        settings.setVSync(false);settings.setFrameRate(60);settings.setGammaCorrection(true);
        app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);app.start(JmeContext.Type.Display);
    }
    @Override public void simpleInitApp() {
        if(context.getType()!=JmeContext.Type.Display)throw new IllegalStateException("Ordnance inspection requires a real window");
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);inputManager.setCursorVisible(true);
        screenshots=new ScreenshotAppState(directory+File.separator);stateManager.attach(screenshots);
        rootNode.attachChild(display);rootNode.addLight(key);rootNode.addLight(rim);rootNode.addLight(ambient);
        key.setDirection(new Vector3f(-.5f,-1,-.35f).normalizeLocal());rim.setDirection(new Vector3f(.75f,-.5f,.9f).normalizeLocal());
        cam.setFrustumPerspective(40,1.6f,.05f,100);
        title=new BitmapText(guiFont);title.setSize(28);title.setLocalTranslation(36,960,0);guiNode.attachChild(title);
        caption=new BitmapText(guiFont);caption.setSize(17);caption.setLocalTranslation(36,924,0);caption.setColor(new ColorRGBA(.68f,.76f,.79f,1));guiNode.attachChild(caption);
        switchStage(0);
    }
    private void switchStage(int next) {
        if(saturation!=null) {
            saturation.close();saturationRuns.add(saturation.evidence());saturation=null;
            retryCleanup=display.getChild("ordnance-models")==null;
            if(!retryCleanup)throw new IllegalStateException("Retry retained the closed presentation root");
        }
        frostVehicles.forEach(VehicleVisual::close);frostVehicles.clear();
        stage=next;captured=false;elapsed=0;display.detachAllChildren();
        boolean night=stage==1||stage==14;
        viewPort.setBackgroundColor(night?new ColorRGBA(.007f,.014f,.034f,1):new ColorRGBA(.035f,.045f,.052f,1));
        key.setColor(night?new ColorRGBA(.15f,.54f,1,1).mult(1.5f):new ColorRGBA(1,.91f,.78f,1).mult(1.35f));
        rim.setColor(night?new ColorRGBA(1,.11f,.45f,1).mult(1.5f):new ColorRGBA(.54f,.7f,1,1).mult(.65f));
        ambient.setColor(new ColorRGBA(.26f,.29f,.31f,1).mult(night?.36f:.85f));
        if(stage<=1) {
            for(int i=0;i<OrdnanceStyle.values().length;i++) {
                var style=OrdnanceStyle.values()[i];Node model=style.load(assetManager);
                model.setLocalScale(2.35f);model.setLocalTranslation((i%4-1.5f)*3.5f,.72f,(i/4-.5f)*4.7f);
                if(style!=OrdnanceStyle.MINE)model.rotate(0,.36f,0);
                display.attachChild(model);platform(model.getLocalTranslation().x,-.08f,model.getLocalTranslation().z,1.4f,.12f,1.8f,new Quaternion());
            }
            cam.setLocation(new Vector3f(7.2f,12.8f,17.4f));cam.lookAt(new Vector3f(0,.3f,0),Vector3f.UNIT_Y);
            title.setText("WRECK RIFF / ORDNANCE     " +(night?"NEON LIGHT":"DAYLIGHT"));
            caption.setText("Homing / Power / Napalm / Ballistic carrier     -     Ballistic charge / Cannon / Freeze / Mine\nOriginal 2048 px diffuse + normal + specular atlases. Identical scene models; inspection scale 2.35x.");
        } else if(stage<=10) {
            var style=OrdnanceStyle.values()[Math.min(stage-2,7)];if(stage==10)style=OrdnanceStyle.MINE;
            Node model=style.load(assetManager);display.attachChild(model);
            if(stage==10) {
                Quaternion slope=OrdnancePresentation.surfaceRotation(new Vector3f(-.37f,1,.21f).normalizeLocal());
                model.setLocalRotation(slope);model.setLocalTranslation(0,.015f,0);platform(0,-.12f,0,.9f,.12f,.9f,slope);
            } else {
                model.setLocalTranslation(0,style==OrdnanceStyle.MINE?.13f:.4f,0);platform(0,0,0,.94f,.10f,1.14f,new Quaternion());
            }
            cam.setLocation(style==OrdnanceStyle.MINE?new Vector3f(1.25f,1.15f,1.70f):new Vector3f(1.20f,1.05f,1.72f));
            cam.lookAt(new Vector3f(0,style==OrdnanceStyle.MINE?.20f:.4f,0),Vector3f.UNIT_Y);
            if(style==OrdnanceStyle.BALLISTIC){cam.setLocation(new Vector3f(1.70f,1.52f,2.6f));cam.lookAt(new Vector3f(0,.4f,0),Vector3f.UNIT_Y);}
            title.setText("WRECK RIFF / "+style.kind().toUpperCase(Locale.ROOT)+(stage==10?" / SLOPED CONTACT":" / MATERIAL DETAIL"));
            caption.setText("Original authored shell, seams, edge wear, fasteners, technical stencil and restrained active signal.\nExact game geometry and material. NEEDS_CREATIVE_REVIEW: capture does not grant owner acceptance.");
        } else if(stage>=13) {
            var rules=VehicleRules.load();String[] ids={"rivet","grinder","spark"};
            for(int i=0;i<ids.length;i++) {
                Node vehicle=VehicleVisual.create(assetManager,VehicleProfile.player(ids[i],rules),i);
                vehicle.setLocalTranslation((i-1)*5.7f,.7f,0);vehicle.rotate(0,.35f,0);
                display.attachChild(vehicle);frostVehicles.add(vehicle);
                VehicleVisual.updateDamage(vehicle,.5f);VehicleVisual.updatePresentation(vehicle,.2f,null);
                VehicleVisual.updateEffects(vehicle,true,false);
                platform((i-1)*5.7f,.1f,0,2.5f,.12f,3.9f,new Quaternion());
            }
            cam.setLocation(new Vector3f(13,14,24));cam.lookAt(new Vector3f(0,1,0),Vector3f.UNIT_Y);
            title.setText("WRECK RIFF / CRYSTALLINE FROST / "+(night?"NEON":"DAYLIGHT"));
            caption.setText("Rivet / Grinder / Spark: exact game meshes at staged 50% HP and Freeze status. No bloom.\nCrystals and cracks share the damaged geometry; real Freeze -> Ballistic commands are recorded in combat showcase.");
        } else {
            saturation=new OrdnanceInspectionScene(assetManager,display);
            platform(0,-.24f,0,10,.12f,9.7f,new Quaternion());
            for(int i=0;i<saturation.mineLimit();i++) {
                var normal=OrdnanceInspectionScene.mineNormal(i);var center=OrdnanceInspectionScene.mineSupport(i).subtract(normal.mult(.12f));
                platform(center.x,center.y,center.z,.78f,.12f,.78f,OrdnancePresentation.surfaceRotation(normal));
            }
            cam.setLocation(new Vector3f(13.2f,22,27.5f));cam.lookAt(new Vector3f(0,.3f,1),Vector3f.UNIT_Y);
            title.setText("WRECK RIFF / "+(stage==11?"SATURATED PRESENTATION":"RETRY / RECREATED PRESENTATION"));
            caption.setText("Configured limits: "+saturation.projectileLimit()+" projectiles / "+saturation.mineLimit()+" mines. Moving fixture poses; alternating armed/safe sensors on slopes.\nExact game presentation. Diagnostic poses only: this is not a CombatSystem emission or performance benchmark.");
            saturation.update(0,cam.getLocation());
        }
    }
    private void platform(float x,float y,float z,float hx,float hy,float hz,Quaternion pose) {
        Geometry platform=new Geometry("inspection-plinth",new Box(hx,hy,hz));
        Material material=SurfaceMaterials.lit(assetManager,new ColorRGBA(.032f,.043f,.055f,1),20,.18f);platform.setMaterial(material);
        platform.setLocalTranslation(x,y,z);platform.setLocalRotation(pose);display.attachChild(platform);
    }
    @Override public void simpleUpdate(float dt) {
        if(finished)return;elapsed+=Math.min(dt,.1f);
        if(saturation!=null)saturation.update(elapsed,cam.getLocation());
        if(!captured&&elapsed>=2.2f) {
            String prefix=STAGES.get(stage)+"-"+runId+"-";
            screenshots.setFileName(prefix);screenshots.takeScreenshot();requested.add(prefix);captured=true;
        }
        if(elapsed>=(stage==11?7:2.8f)) {
            if(stage+1<STAGES.size())switchStage(stage+1);
            else {
                if(saturation!=null){saturation.close();saturationRuns.add(saturation.evidence());saturation=null;}
                frostVehicles.forEach(VehicleVisual::close);frostVehicles.clear();
                finished=true;finish();stop();
            }
        }
    }
    private void finish() {
        try {
            List<String> files;try(var paths=Files.list(directory)){files=paths.filter(p->p.getFileName().toString().endsWith(".png"))
                    .map(p->p.getFileName().toString()).filter(p->requested.stream().anyMatch(p::startsWith)).sorted().toList();}
            for(String expected:requested)if(files.stream().noneMatch(p->p.startsWith(expected)))throw new IllegalStateException("Missing rendered capture "+expected);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("status","CAPTURED");evidence.put("artisticStatus","NEEDS_CREATIVE_REVIEW");
            evidence.put("context",context.getType().name());evidence.put("framebuffer",List.of(cam.getWidth(),cam.getHeight()));evidence.put("renderer",context.getSettings().getRenderer());evidence.put("captures",files);
            evidence.put("saturation",saturationRuns);evidence.put("retryCleanup",retryCleanup);
            evidence.put("bloom",false);evidence.put("frostVehicles",List.of("rivet","grinder","spark"));
            Files.writeString(directory.resolve("review.json"),new GsonBuilder().setPrettyPrinting().create().toJson(evidence)+"\n",StandardCharsets.UTF_8);
        } catch(Exception error){throw new IllegalStateException("Ordnance real-window capture failed",error);}
    }
}
