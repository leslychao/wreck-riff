package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.app.state.VideoRecorderAppState;
import com.jme3.math.Vector3f;
import com.jme3.system.AppSettings;
import com.jme3.system.NativeLibraryLoader;
import game.wreckriff.arena.*;
import game.wreckriff.config.NativeSetup;
import game.wreckriff.config.BuildInfo;
import game.wreckriff.presentation.SceneLighting;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Moving views through authored districts and interiors; visual evidence, not a gameplay benchmark. */
public final class ArenaRouteReview extends SimpleApplication {
    public record Route(String arenaId,String id,String kind,List<ArenaDefinition.Vec3> points) {}
    public record Review(List<Route> routes) {}
    private final Path output;
    private final List<Route> routes;
    private final boolean video;
    private final CountDownLatch complete=new CountDownLatch(1);
    private final List<Map<String,Object>> captures=new ArrayList<>();
    private SceneLighting.Handle lighting;
    private ScreenshotAppState screenshots;
    private ArenaRegistry registry;
    private String loadedArena="";
    private int routeIndex,shot;
    private float seconds;
    private boolean waiting;
    private volatile Throwable failure;

    private ArenaRouteReview(List<Route> routes,Path output,boolean video) {this.routes=routes;this.output=output;this.video=video;}
    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>4)throw new IllegalArgumentException("ArenaRouteReview <routes.json> <output> [arenaId] [video]");
        var gson=new GsonBuilder().create();var review=gson.fromJson(Files.readString(Path.of(args[0])),Review.class);
        String arena=args.length>2?args[2]:"";
        var routes=review.routes().stream().filter(r->arena.isEmpty()||r.arenaId().equals(arena)).toList();
        if(routes.isEmpty())throw new IllegalArgumentException("No review routes");
        for(var route:routes)if(route.points().size()<3||!route.id().matches("[a-z0-9-]+"))throw new IllegalArgumentException("Invalid review route "+route.id());
        Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);NativeSetup.prepare(output);
        var app=new ArenaRouteReview(routes,output,args.length>3&&args[3].equals("video"));
        var settings=new AppSettings(true);settings.setTitle("Wreck Riff — moving map inspection");
        settings.setResolution(1920,1080);settings.setFullscreen(true);settings.setSamples(4);settings.setVSync(false);
        settings.setGammaCorrection(true);settings.setAudioRenderer(null);settings.setFrameRate(30);settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);app.start();
        if(!app.complete.await(120+routes.size()*30L,TimeUnit.SECONDS)) {
            app.stop(false);System.err.println("Moving map inspection timed out");System.exit(1);
        }
        // An uncaught render-thread failure cannot service stop(true)'s shutdown latch.
        if(app.failure!=null){app.stop(false);app.failure.printStackTrace();System.exit(1);}
        app.stop(true);
        System.out.println("Inspected "+routes.size()+" authored routes with "+app.captures.size()+" captured views: "+output);
    }
    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);setDisplayFps(false);setDisplayStatView(false);
            NativeLibraryLoader.loadNativeLibrary("bulletjme",true);registry=ArenaRegistry.load();
            lighting=SceneLighting.install(assetManager,rootNode,viewPort);lighting.setSamples(4);
            screenshots=new ScreenshotAppState(output+File.separator) {
                @Override protected void writeImageFile(File file) throws IOException {
                    super.writeImageFile(file);var route=routes.get(routeIndex);
                    captures.add(Map.of("arenaId",route.arenaId(),"route",route.id(),"kind",route.kind(),"shot",shot,
                            "path",file.getName(),"eye",List.of(cam.getLocation().x,cam.getLocation().y,cam.getLocation().z)));
                    waiting=false;
                }
            };
            screenshots.setIsNumbered(false);stateManager.attach(screenshots);
            if(video)stateManager.attach(new VideoRecorderAppState(output.resolve("moving-review.avi").toFile(),.75f,30));
            nextRoute();
        } catch(Throwable error){fail(error);}
    }
    private void nextRoute() {
        var route=routes.get(routeIndex);
        if(!route.arenaId().equals(loadedArena)) {
            rootNode.detachAllChildren();var arena=registry.definition(route.arenaId());
            rootNode.attachChild(new ArenaFactory(assetManager).build(arena).visual());lighting.apply(arena.metadata().theme(),true);
            loadedArena=route.arenaId();
            cam.setFrustumPerspective(65,cam.getWidth()/(float)cam.getHeight(),.3f,
                    (float)Math.hypot(arena.bounds().maxX()-arena.bounds().minX(),arena.bounds().maxZ()-arena.bounds().minZ())+200);
        }
        seconds=0;shot=0;waiting=false;
    }
    @Override public void simpleUpdate(float dt) {
        if(registry==null||failure!=null||routeIndex>=routes.size())return;
        try {
            seconds+=dt;var route=routes.get(routeIndex);float progress=Math.clamp((seconds-1)/8,0,1);
            Vector3f point=sample(route.points(),progress),ahead=sample(route.points(),Math.min(1,progress+.035f));
            if(ahead.distanceSquared(point)<.001f)ahead=point.add(point.subtract(sample(route.points(),Math.max(0,progress-.035f))));
            cam.setLocation(point.add(0,3.5f,0));cam.lookAt(ahead.add(0,2.5f,0),Vector3f.UNIT_Y);
            if(!waiting&&shot<3&&seconds>=1.5f+shot*3.5f) {
                screenshots.setFileName(route.arenaId()+"--"+route.kind()+"--"+route.id()+"--"+(++shot));waiting=true;screenshots.takeScreenshot();
            }
            if(seconds>=10&&!waiting) {
                routeIndex++;
                if(routeIndex==routes.size()) {
                    Files.writeString(output.resolve("review.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                            "status","VISUAL_CAPTURE_COMPLETE","acceptance","OWNER_REVIEW_PENDING","performanceEvidence",false,
                            "build",BuildInfo.current(),"audio",false,"camera","authored route inspection",
                            "resolution",List.of(cam.getWidth(),cam.getHeight()),"routes",routes,"captures",captures)));
                    complete.countDown();
                } else nextRoute();
            }
        } catch(Throwable error){fail(error);}
    }
    private static Vector3f sample(List<ArenaDefinition.Vec3> points,float progress) {
        float length=0;for(int i=1;i<points.size();i++)length+=points.get(i).vector().distance(points.get(i-1).vector());
        float remaining=length*progress;
        for(int i=1;i<points.size();i++) {
            var a=points.get(i-1).vector();var b=points.get(i).vector();float segment=a.distance(b);
            if(remaining<=segment&&segment>0)return a.interpolateLocal(b,remaining/segment);
            remaining-=segment;
        }
        return points.getLast().vector();
    }
    private void fail(Throwable error){failure=error;error.printStackTrace();complete.countDown();}
    @Override public void handleError(String message,Throwable error){fail(new IllegalStateException(message,error));}
}
