package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.bounding.BoundingBox;
import com.jme3.font.BitmapText;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.Texture;
import game.wreckriff.arena.ArenaDefinition.Theme;
import game.wreckriff.config.*;
import game.wreckriff.presentation.*;
import game.wreckriff.simulation.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Explicit tools-only real-window review. It never starts a match or grants release acceptance. */
public final class VehicleDamageReview extends SimpleApplication {
    private static final float DT=1f/60,MAX_HP=800;
    private static final String[] IDS={"rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee"};
    private static final float[] HEALTH={1,.75f,.5f,.25f,0};
    private record Shot(String name,Runnable setup) {}
    private final Path output;
    private final long startedAt=System.currentTimeMillis();
    private final CountDownLatch done=new CountDownLatch(1);
    private final List<Shot> shots=new ArrayList<>();
    private final List<Map<String,Object>> evidence=new ArrayList<>();
    private final List<Map<String,Object>> assetEvidence=new ArrayList<>();
    private final Node[] cars=new Node[IDS.length];
    private final VehicleProfile[] profiles=new VehicleProfile[IDS.length];
    private final Node display=new Node("vehicle-review-display");
    private final Set<String> captured=new LinkedHashSet<>();
    private final Map<String,String> cleanMasks=new LinkedHashMap<>();
    private SceneLighting.Handle lighting;
    private GaragePresentation garage;
    private ScreenshotAppState screenshots;
    private BitmapText title;
    private int shot=-1,frame;
    private long eventId;
    private boolean tookScreenshot,complete;
    private volatile Throwable failure;

    private VehicleDamageReview(Path output){this.output=output;}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Output directory required");
        Path directory=Path.of(args[0]).toAbsolutePath();Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve("complete.json"));Files.deleteIfExists(directory.resolve("failure.txt"));
        var app=new VehicleDamageReview(directory);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff - Prepared vehicle damage review");settings.setResolution(1600,900);
        settings.setSamples(4);settings.setVSync(false);settings.setFrameRate(60);settings.setGammaCorrection(true);
        app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);app.start(JmeContext.Type.Display);
        if(!app.done.await(60,TimeUnit.SECONDS)){app.stop(false);Files.writeString(directory.resolve("failure.txt"),"Vehicle review exceeded 60 seconds");System.exit(1);}
        if(app.failure!=null){app.stop(false);app.failure.printStackTrace();System.exit(1);}
        if(!app.complete)throw new IllegalStateException("Native vehicle review closed before completion");
    }
    @Override public void simpleInitApp() {
        require(context.getType()==JmeContext.Type.Display,"Real display required");
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);
        screenshots=new ScreenshotAppState(output+File.separator);stateManager.attach(screenshots);
        rootNode.attachChild(display);
        var floor=new Geometry("review-floor",new Box(70,.15f,70));floor.setLocalTranslation(0,-.15f,0);
        floor.setMaterial(SurfaceMaterials.lit(assetManager,new ColorRGBA(.16f,.18f,.20f,1),12,.15f));
        floor.setShadowMode(RenderQueue.ShadowMode.Receive);display.attachChild(floor);
        lighting=SceneLighting.install(assetManager,rootNode,viewPort);lighting.setSamples(4);lighting.initialize(renderManager);
        var font=assetManager.loadFont("Interface/Fonts/Default.fnt");title=new BitmapText(font);title.setSize(21);title.setLocalTranslation(25,875,0);guiNode.attachChild(title);
        VehicleRules rules=VehicleRules.load();
        for(int i=0;i<IDS.length;i++) {
            profiles[i]=i<3?VehicleProfile.player(IDS[i],rules):VehicleProfile.boss(IDS[i],rules);
            cars[i]=VehicleVisual.create(assetManager,profiles[i],0);VehicleVisual.configureMaximumHp(cars[i],MAX_HP);
            cars[i].setLocalTranslation((i%3-1)*14,profiles[i].roadOffset(),(i/3)*20-10);display.attachChild(cars[i]);
            rootNode.updateGeometricState();cleanMasks.put(IDS[i],maskHash(cars[i]));
            inspectAsset(i);
        }
        rootNode.updateGeometricState();plan();next();
    }
    private void inspectAsset(int index) {
        Node original=cars[index],twin=VehicleVisual.create(assetManager,profiles[index],0);
        try {
            Map<String,Geometry> first=geometryMap(original),second=geometryMap(twin);
            require(first.keySet().equals(second.keySet()),"Twin geometry differs: "+IDS[index]);
            for(String path:first.keySet())require(first.get(path).getMaterial()!=second.get(path).getMaterial(),"Shared mutable material: "+path);
            Texture firstMask=damageMap(original),secondMask=damageMap(twin);
            require(firstMask.getImage()!=secondMask.getImage(),"Shared damage image: "+IDS[index]);
            String twinBefore=maskHash(twin);contact(index,new Vector3f(1,0,0),20,"machine-gun");
            VehicleVisual.updatePresentation(original,.2f,null);
            require(!maskHash(original).equals(cleanMasks.get(IDS[index])),"Local contact did not update mask: "+IDS[index]);
            require(maskHash(twin).equals(twinBefore),"Local contact contaminated another instance: "+IDS[index]);
            fullRepair(index,.9f);VehicleVisual.updatePresentation(original,.35f,null);
            require(maskHash(original).equals(cleanMasks.get(IDS[index])),"Full repair left a local mask: "+IDS[index]);
            var info=new LinkedHashMap<String,Object>();info.put("profile",IDS[index]);info.put("mutableMaterialsIsolated",true);info.put("damageImagesIsolated",true);
            var levels=new ArrayList<Map<String,Object>>();
            for(int lod=0;lod<3;lod++){var geometry=geometryMap((Node)original.getChild("lod"+lod));var row=new LinkedHashMap<String,Object>();row.put("lod",lod);row.put("bodyGeometries",geometry.size());row.put("bodyVertices",geometry.values().stream().mapToInt(g->g.getMesh().getVertexCount()).sum());row.put("bodyTriangles",geometry.values().stream().mapToInt(g->g.getMesh().getTriangleCount()).sum());row.put("morphTargets",geometry.values().stream().mapToInt(g->g.getMesh().getMorphTargets().length).max().orElse(0));levels.add(row);}
            info.put("preparedLevels",levels);assetEvidence.add(info);
        } finally {VehicleVisual.close(twin);}
    }
    private void plan() {
        for(int stage=0;stage<HEALTH.length;stage++) {
            final int selectedStage=stage;
            for(int profile=0;profile<IDS.length;profile++) {
                final int selected=profile;
                shots.add(new Shot(IDS[profile]+"-hp-"+Math.round(HEALTH[stage]*100),()->{allHealth(HEALTH[selectedStage]);individual(selected,false);}));
            }
            shots.add(new Shot("all-profiles-hp-"+Math.round(HEALTH[stage]*100),()->{allHealth(HEALTH[selectedStage]);overview();}));
        }
        shots.add(new Shot("local-left",()->{for(int i=0;i<cars.length;i++){fullRepair(i,0);VehicleVisual.updateDamage(cars[i],.5f);contact(i,new Vector3f(-1,0,0),35,"machine-gun");}overview();cam.setLocation(new Vector3f(-32,29,48));cam.lookAt(new Vector3f(0,1,0),Vector3f.UNIT_Y);}));
        shots.add(new Shot("local-right",()->{for(int i=0;i<cars.length;i++)contact(i,new Vector3f(1,0,0),35,"cannon");overview();}));
        shots.add(new Shot("local-front",()->{for(int i=0;i<cars.length;i++)contact(i,new Vector3f(0,0,1),35,"power");overview();}));
        shots.add(new Shot("local-rear-thermal",()->{for(int i=0;i<cars.length;i++)contact(i,new Vector3f(0,0,-1),6,"napalm-fire");overview();cam.setLocation(new Vector3f(-24,33,-57));cam.lookAt(new Vector3f(0,1,0),Vector3f.UNIT_Y);}));
        shots.add(new Shot("damaged-frost",()->{for(Node car:cars)VehicleVisual.updateEffects(car,true,false);overview();lighting.apply(Theme.NEON,false);}));
        shots.add(new Shot("damaged-shield-cleanse",()->{for(Node car:cars)VehicleVisual.updateEffects(car,true,true);overview();lighting.apply(Theme.INDUSTRIAL_YARD,false);}));
        shots.add(new Shot("full-repair",()->{for(int i=0;i<cars.length;i++){VehicleVisual.updateEffects(cars[i],false,false);fullRepair(i,.5f);}overview();}));
        shots.add(new Shot("repair-then-new-hit-same-frame",()->{for(int i=0;i<cars.length;i++){contact(i,new Vector3f(-1,0,0),20,"machine-gun");fullRepair(i,.9f);contact(i,new Vector3f(1,0,0),20,"machine-gun");VehicleVisual.updateDamage(cars[i],.95f);}overview();}));
        for(int profile=0;profile<IDS.length;profile++){final int selected=profile;shots.add(new Shot("off-axis-"+IDS[profile],()->individual(selected,true)));}
        for(int lod=0;lod<3;lod++){final int selected=lod;shots.add(new Shot("rivet-active-lod-"+lod,()->{individual(0,false);selectCameraLod(selected);}));}
        for(String id:List.of("rivet","grinder","spark"))shots.add(new Shot("actual-garage-"+id,()->{
            display.setCullHint(Spatial.CullHint.Always);if(garage==null){garage=new GaragePresentation(assetManager,VehicleRules.load());rootNode.attachChild(garage.node());}
            garage.show(id);lighting.applyMenu(false);garage.update(.35f,cam,.32f,.12f);
        }));
    }
    private void allHealth(float hp){for(Node car:cars){VehicleVisual.updateEffects(car,false,false);VehicleVisual.updateDamage(car,hp);}}
    private void normalLighting(){display.setCullHint(Spatial.CullHint.Inherit);for(Node car:cars)car.setCullHint(Spatial.CullHint.Inherit);lighting.apply(Theme.CONSTRUCTION,false);}
    private void overview() {
        normalLighting();cam.setFrustumPerspective(40,16f/9,.1f,400);cam.setLocation(new Vector3f(24,33,57));cam.lookAt(new Vector3f(0,1,0),Vector3f.UNIT_Y);
    }
    private void individual(int index,boolean offAxis) {
        normalLighting();for(int i=0;i<cars.length;i++)cars[i].setCullHint(i==index?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        Node car=cars[index];rootNode.updateGeometricState();BoundingBox box=(BoundingBox)car.getWorldBound();
        Vector3f centre=box.getCenter();float radius=Math.max(box.getXExtent(),Math.max(box.getYExtent(),box.getZExtent()));
        float near=.1f,tanY=FastMath.tan(35*FastMath.DEG_TO_RAD/2),tanX=tanY*16/9;
        if(offAxis){cam.setFrustum(near,400,-near*tanX*1.32f,near*tanX*.68f,near*tanY*.88f,-near*tanY*1.12f);lighting.applyMenu(false);}
        else cam.setFrustumPerspective(35,16f/9,near,400);
        cam.setLocation(centre.add(new Vector3f(.62f,.36f,.82f).normalizeLocal().mult(radius*(offAxis?5.3f:4.3f))));cam.lookAt(centre,Vector3f.UNIT_Y);
    }
    private void selectCameraLod(int target) {
        BoundingBox bounds=(BoundingBox)cars[0].getWorldBound();Vector3f centre=bounds.getCenter(),away=cam.getLocation().subtract(centre).normalizeLocal();
        float distance=target==0?4:target==1?15:120;
        for(int attempt=0;attempt<16;attempt++) {
            cam.setLocation(centre.add(away.mult(distance)));cam.lookAt(centre,Vector3f.UNIT_Y);cam.update();
            VehicleVisual.updatePresentation(cars[0],0,cam);int actual=cars[0].getUserData("vehicleLod");if(actual==target)return;
            distance*=actual<target?1.4f:.7f;
        }
        throw new IllegalStateException("Could not reach active LOD "+target);
    }
    private void contact(int index,Vector3f normal,float damage,String kind) {
        VehicleProfile p=profiles[index];Vector3f local=new Vector3f(normal.x*p.width()*.5f,p.height()*.22f,normal.z*p.length()*.46f);
        Node car=cars[index];Vector3f world=car.getLocalRotation().mult(local).addLocal(car.getLocalTranslation());
        var event=new GameEvent(GameEvent.Type.DAMAGE,++eventId,index,0,world,kind,damage,Vector3f.ZERO,normal)
                .withContact(ContactSurface.METAL,new VehicleContact(local,normal)).withHealthChange(new HealthChange(MAX_HP*.5f,MAX_HP*.5f-damage));
        VehicleVisual.acceptPresented(car,VehicleVisual.refineContact(car,event));
    }
    private void fullRepair(int index,float beforeFraction) {
        VehicleVisual.acceptPresented(cars[index],new GameEvent(GameEvent.Type.REPAIRED,++eventId,index,0,Vector3f.ZERO,"repair",MAX_HP*(1-beforeFraction)).withHealthChange(new HealthChange(MAX_HP*beforeFraction,MAX_HP)));
        VehicleVisual.updateDamage(cars[index],1);
    }
    private void next(){shot++;frame=0;tookScreenshot=false;shots.get(shot).setup.run();title.setText(shots.get(shot).name+"  |  1600x900 MSAA4, bloom off  |  native model review, not a match");}
    @Override public void simpleUpdate(float ignored) {
        if(shot<0)return;frame++;
        if(garage!=null&&shots.get(shot).name.startsWith("actual-garage-"))garage.update(DT,cam,.32f,.12f);
        else for(Node car:cars)VehicleVisual.updatePresentation(car,DT,cam);
        if(frame>=20&&!tookScreenshot){checkAndRecord();screenshots.setFileName(shots.get(shot).name+"-");screenshots.takeScreenshot();tookScreenshot=true;captured.add(shots.get(shot).name);}
        if(frame>=28){if(shot+1<shots.size())next();else finish();}
    }
    private void checkAndRecord() {
        String name=shots.get(shot).name;
        require(cam.getWidth()==1600&&cam.getHeight()==900,"Unexpected native resolution");
        if(name.startsWith("off-axis-")||name.startsWith("actual-garage-"))require(Math.abs(cam.getFrustumLeft()+cam.getFrustumRight())>.0001f,"Off-axis frustum was lost");
        if(name.equals("full-repair"))for(int i=0;i<cars.length;i++)require(maskHash(cars[i]).equals(cleanMasks.get(IDS[i])),"Repair did not clear "+IDS[i]);
        if(name.equals("repair-then-new-hit-same-frame"))for(int i=0;i<cars.length;i++)require(!maskHash(cars[i]).equals(cleanMasks.get(IDS[i])),"Repair erased newer contact on "+IDS[i]);
        if(name.equals("damaged-frost"))for(Node car:cars)require(car.getChild("frost-overlay").getLocalCullHint()!=Spatial.CullHint.Always,"Frost missing");
        if(name.equals("damaged-shield-cleanse"))for(Node car:cars){require(car.getChild("shield-shell").getLocalCullHint()!=Spatial.CullHint.Always,"Shield missing");require(car.getChild("frost-overlay").getLocalCullHint()==Spatial.CullHint.Always,"Shield retained frost");}
        if(!name.startsWith("all-")&&(name.contains("-hp-")||name.startsWith("off-axis-")))for(Node car:cars)if(car.getLocalCullHint()!=Spatial.CullHint.Always)checkFraming(car);
        var row=new LinkedHashMap<String,Object>();row.put("capture",name);row.put("camera",List.of(cam.getLocation().x,cam.getLocation().y,cam.getLocation().z));row.put("frustum",List.of(cam.getFrustumLeft(),cam.getFrustumRight(),cam.getFrustumTop(),cam.getFrustumBottom()));
        var states=new ArrayList<Map<String,Object>>();
        if(name.startsWith("actual-garage-"))states.add(state((Node)garage.node().getChild("garage-vehicle")));
        else for(Node car:cars)if(car.getLocalCullHint()!=Spatial.CullHint.Always)states.add(state(car));
        row.put("vehicles",states);evidence.add(row);
    }
    private static Map<String,Object> state(Node car) {
        var state=new LinkedHashMap<String,Object>();state.put("profile",car.getUserData("profileId"));state.put("hpStage",car.getUserData("damageStage"));state.put("lod",car.getUserData("vehicleLod"));state.put("repairRevision",car.getUserData("repairRevision"));var active=visibleGeometry(car);state.put("visibleGeometries",active.size());state.put("visibleTriangles",active.stream().mapToInt(g->g.getMesh().getTriangleCount()).sum());state.put("localMaskSha256",maskHash(car));return state;
    }
    private void checkFraming(Node car) {
        BoundingBox bounds=(BoundingBox)car.getWorldBound();Vector3f centre=bounds.getCenter();
        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1}) {
            Vector3f screen=cam.getScreenCoordinates(centre.add(x*bounds.getXExtent(),y*bounds.getYExtent(),z*bounds.getZExtent()));
            require(screen.x>=0&&screen.x<=cam.getWidth()&&screen.y>=0&&screen.y<=cam.getHeight(),"Cropped conservative vehicle bounds: "+shots.get(shot).name);
        }
    }
    private void finish() {
        try {
            require(captured.size()==shots.size(),"Incomplete capture matrix");
            for(String name:captured)try(var files=Files.list(output)){boolean found=false;for(Path file:files.toList())if(file.getFileName().toString().startsWith(name+"-")&&file.toString().endsWith(".png")&&Files.getLastModifiedTime(file).toMillis()>=startedAt){found=true;break;}require(found,"Missing fresh captured PNG: "+name);}
            var result=new LinkedHashMap<String,Object>();result.put("realWindow",true);result.put("releaseAcceptance",false);result.put("captures",evidence);result.put("assets",assetEvidence);result.put("captureCount",captured.size());result.put("primaryProfileStageCount",30);result.put("renderer",renderManager.getRenderer().getClass().getName());result.put("java",System.getProperty("java.version"));
            Files.writeString(output.resolve("complete.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));complete=true;stop();
        } catch(Exception error){throw new IllegalStateException(error);}
    }
    private static Map<String,Geometry> geometryMap(Node root){var result=new LinkedHashMap<String,Geometry>();collect(root,"",result);return result;}
    private static void collect(Spatial spatial,String path,Map<String,Geometry> result){String name=path+"/"+spatial.getName();if(spatial instanceof Geometry geometry)result.put(name,geometry);else if(spatial instanceof Node node)for(Spatial child:node.getChildren())collect(child,name,result);}
    private static List<Geometry> visibleGeometry(Spatial spatial){if(spatial.getLocalCullHint()==Spatial.CullHint.Always)return List.of();if(spatial instanceof Geometry g)return List.of(g);var result=new ArrayList<Geometry>();if(spatial instanceof Node node)for(Spatial child:node.getChildren())result.addAll(visibleGeometry(child));return result;}
    private static Texture damageMap(Node car){for(Geometry geometry:geometryMap(car).values()){var param=geometry.getMaterial().getTextureParam("DamageMap");if(param!=null)return param.getTextureValue();}throw new IllegalStateException("Damage map missing");}
    private static String maskHash(Node car){try{ByteBuffer data=damageMap(car).getImage().getData(0).duplicate();data.clear();MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(data);return HexFormat.of().formatHex(hash.digest());}catch(Exception e){throw new IllegalStateException(e);}}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    @Override public void handleError(String message,Throwable error){failure=error==null?new IllegalStateException(message):error;try{Files.writeString(output.resolve("failure.txt"),message+"\n"+failure);}catch(Exception ignored){}stop(false);done.countDown();}
    @Override public void destroy(){try{if(garage!=null)garage.close();for(Node car:cars)if(car!=null)VehicleVisual.close(car);super.destroy();}finally{done.countDown();}}
}
