package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.math.Vector3f;
import com.jme3.system.AppSettings;
import com.jme3.system.NativeLibraryLoader;
import game.wreckriff.arena.ArenaFactory;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.config.NativeSetup;
import game.wreckriff.config.Configs;
import game.wreckriff.presentation.ArenaArt;
import game.wreckriff.presentation.SceneLighting;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Offline screenshots of real local arena visuals. Creates a visible window, never a PhysicsSpace or gameplay HUD. */
public final class GenerateArenaPreviews extends SimpleApplication {
    private final Path output;
    private final CountDownLatch complete=new CountDownLatch(1);
    private final List<Map<String,Object>> previews=new ArrayList<>();
    private ArenaRegistry registry;
    private SceneLighting.Handle lighting;
    private ScreenshotAppState screenshots;
    private int arenaIndex,frames;
    private float warmup;
    private boolean requested,captured;
    private volatile Throwable failure;

    private GenerateArenaPreviews(Path output) {this.output=output;}

    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: GenerateArenaPreviews <output-directory>");
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output);NativeSetup.prepare(output);
        var app=new GenerateArenaPreviews(output);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff - arena preview capture");settings.setResolution(1600,900);
        settings.setFullscreen(false);settings.setVSync(false);settings.setFrameRate(60);settings.setSamples(4);
        settings.setGammaCorrection(true);settings.setAudioRenderer(null);settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);app.start();
        if(!app.complete.await(180,TimeUnit.SECONDS)){app.stop(true);throw new IllegalStateException("Arena preview window capture timed out");}
        app.stop(true);
        if(app.failure!=null)throw new IllegalStateException("Arena preview capture failed",app.failure);
        System.out.println("Captured "+app.previews.size()+" clean real arena previews in "+output);
    }

    @Override public void simpleInitApp() {
        try {
            setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);
            // ArenaFactory owns native collision-shape construction; no world, rigid bodies or physics step are created here.
            NativeLibraryLoader.loadNativeLibrary("bulletjme",true);
            registry=ArenaRegistry.load();lighting=SceneLighting.install(assetManager,rootNode,viewPort);lighting.setSamples(4);
            screenshots=new ScreenshotAppState(output+File.separator) {
                @Override protected void writeImageFile(File file) throws IOException {
                    super.writeImageFile(file);
                    try {record(file.toPath());captured=true;}
                    catch(Exception error){fail(error);}
                }
            };
            screenshots.setIsNumbered(false);stateManager.attach(screenshots);showArena();
        } catch(Exception error) {fail(error);}
    }

    @Override public void simpleUpdate(float dt) {
        if(failure!=null||registry==null)return;
        if(captured) {
            captured=false;arenaIndex++;
            if(arenaIndex==registry.entries().size()) {
                try {writeManifest();complete.countDown();}
                catch(Exception error){fail(error);}
                return;
            }
            showArena();
        }
        if(arenaIndex>=registry.entries().size())return;
        warmup+=dt;frames++;
        if(!requested&&warmup>=1.2f&&frames>=30) {requested=true;screenshots.takeScreenshot();}
    }

    private void showArena() {
        rootNode.detachAllChildren();var definition=registry.definition(registry.entries().get(arenaIndex).id());
        rootNode.attachChild(new ArenaFactory(assetManager).build(definition).visual());
        lighting.apply(definition.metadata().theme(),true);
        var bounds=definition.bounds();var centre=new Vector3f((bounds.minX()+bounds.maxX())/2,2,(bounds.minZ()+bounds.maxZ())/2);
        float span=Math.max(bounds.maxX()-bounds.minX(),bounds.maxZ()-bounds.minZ());
        cam.setFrustumPerspective(52,cam.getWidth()/(float)cam.getHeight(),definition.districts().isEmpty()?.1f:.3f,Math.max(650,span*3));
        // Menu cards show a real landmark and its road at a legible scale. The
        // separate route review still inspects every district and interior.
        switch(definition.id()) {
            case "construction_17" -> frame(new Vector3f(1210,18,325),new Vector3f(1110,22,545));
            case "neon_zero" -> frame(new Vector3f(700,15,1000),new Vector3f(465,55,1120));
            case "euphoria_park" -> frame(new Vector3f(1175,10,575),new Vector3f(1300,52,700));
            default -> frame(centre.add(-65,47,-77),centre);
        }
        screenshots.setFileName(definition.id());requested=false;frames=0;warmup=0;
    }
    private void frame(Vector3f position,Vector3f target) {
        cam.setLocation(position);cam.lookAt(target,Vector3f.UNIT_Y);
    }

    private void record(Path path) throws Exception {
        var image=ImageIO.read(path.toFile());
        if(image==null||image.getWidth()!=1600||image.getHeight()!=900)
            throw new IOException("Expected a real 1600x900 window framebuffer, got "+path);
        String id=registry.entries().get(arenaIndex).id();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        var record=new LinkedHashMap<String,Object>();record.put("arenaId",id);record.put("path","textures/ui/arenas/"+id+".png");
        record.put("sourcePath","src/tools/assets/arena-previews/"+id+".png");
        record.put("capturePath",path.toString().replace('\\','/'));record.put("sha256",hash);record.put("sourceSha256",hash);
        var definition=registry.definition(id);
        record.put("layoutRevision",definition.layoutRevision());
        record.put("definitionSha256",definitionHash(Configs.gson().toJson(definition)));
        record.put("artSha256",definitionHash(Configs.gson().toJson(ArenaArt.load(definition))));
        record.put("transformation","Unmodified 1600x900 PNG framebuffer from a real visible jME window; no HUD, no retouching, no resizing");
        previews.add(record);
    }
    private static String definitionHash(String json) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void writeManifest() throws IOException {
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("origin","original-game-render");
        manifest.put("generator","src/tools/java/game/wreckriff/tools/GenerateArenaPreviews.java");
        manifest.put("materials","Existing ArenaFactory/ArenaArt geometry and local SurfaceMaterials; licenses/asset-provenance.json");
        manifest.put("artisticStatus","NEEDS_CREATIVE_REVIEW");manifest.put("previews",previews);
        Files.writeString(output.resolve("provenance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest)+"\n",StandardCharsets.UTF_8);
    }

    private void fail(Throwable error) {failure=error;error.printStackTrace();complete.countDown();}
    @Override public void handleError(String message,Throwable error) {fail(new IllegalStateException(message,error));}
}
