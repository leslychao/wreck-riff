package game.wreckriff.tools;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
import game.wreckriff.presentation.PickupStyle;
import game.wreckriff.presentation.SurfaceMaterials;
import game.wreckriff.presentation.SurfaceMesh;
import game.wreckriff.ui.VectorIcons;
import jme3tools.optimize.GeometryBatchFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Original authored ammunition meshes, exported at build time; no network or external modelling runtime. */
public final class GeneratePickupModels {
    private final DesktopAssetManager assets=new DesktopAssetManager(true);
    private SurfaceMaterials surfaces;
    private Material metal,black,accent,glass;
    public static void main(String[] args)throws Exception {
        new GeneratePickupModels().generate(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]));
    }
    private void generate(Path source,Path output,Path generatorSource)throws Exception {
        assets.registerLocator(source.toAbsolutePath().toString(),FileLocator.class);
        surfaces=new SurfaceMaterials(assets);
        Path directory=output.resolve("models/pickups");Files.createDirectories(directory);
        List<String> records=new ArrayList<>();
        for(var style:PickupStyle.values()) {
            Node model=model(style);Path file=output.resolve(style.model());
            BinaryExporter.getInstance().save(model,file.toFile());
            records.add("    {\"type\":\""+style.name()+"\",\"asset\":\""+style.model()+"\",\"sha256\":\""+
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))+"\"}");
        }
        Files.writeString(directory.resolve("provenance.json"),"{\n  \"origin\":\"original-java-procedural\",\n"+
                "  \"generator\":\"src/tools/java/game/wreckriff/tools/GeneratePickupModels.java\",\n"+
                "  \"generatorSha256\":\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(generatorSource)))+"\",\n"+
                "  \"materials\":\"Existing locally licensed SurfaceMaterials; original Wreck Riff VectorIcons\",\n"+
                "  \"models\":[\n"+String.join(",\n",records)+"\n  ]\n}\n",StandardCharsets.UTF_8);
        System.out.println("Exported eight original animated-pickup models to "+directory);
    }
    private Node model(PickupStyle style) {
        Node node=new Node("pickup-model-"+style.name().toLowerCase(Locale.ROOT));
        metal=surfaces.material("steel");black=surfaces.material("black");
        accent=SurfaceMaterials.lit(assets,style.color(),28,.3f);
        glass=SurfaceMaterials.lit(assets,new ColorRGBA(.11f,.19f,.23f,1),64,.65f);
        switch(style) {
            case HOMING -> {rocket(node,-.29f,0,.13f,1.0f);rocket(node,.29f,0,.13f,1.0f);box(node,"rack",0,-.49f,0,.56f,.07f,.23f,black);}
            case POWER -> {rocket(node,0,0,.28f,1.25f);for(float y:new float[]{-.25f,.04f})cylinder(node,"reinforced-band",0,y,0,.3f,.1f,accent);}
            case BALLISTIC -> {for(float x:new float[]{-.25f,.25f})for(float z:new float[]{-.21f,.21f})rocket(node,x,z,.14f,.78f);
                box(node,"salvo-cassette",0,-.35f,0,.52f,.17f,.45f,black);box(node,"cassette-band",0,-.35f,.46f,.50f,.06f,.035f,accent);}
            case CANNON -> {for(float x:new float[]{-.32f,.32f})sphere(node,"cannon-round",x,.07f,0,.3f,metal);
                box(node,"heavy-cradle",0,-.28f,0,.72f,.09f,.36f,black);
                for(float x:new float[]{-.67f,0,.67f})box(node,"round-brace",x,-.08f,0,.055f,.26f,.35f,accent);}
            case MINE -> {for(float z:new float[]{-.21f,.21f}) {cylinder(node,"stored-mine",0,0,z,.4f,.14f,metal);
                    cylinder(node,"safe-cap",0,.12f,z,.15f,.07f,accent);}
                box(node,"transport-tray",0,-.14f,0,.54f,.055f,.63f,black);
                for(float x:new float[]{-.49f,.49f})box(node,"safety-latch",x,.04f,0,.05f,.17f,.46f,accent);}
            case NAPALM -> {cylinder(node,"fuel-canister",0,0,0,.31f,.83f,accent);cylinder(node,"valve",0,.48f,0,.12f,.12f,metal);
                box(node,"handle-top",0,.63f,0,.26f,.05f,.08f,black);for(float x:new float[]{-.22f,.22f})box(node,"handle",x,.51f,0,.04f,.12f,.07f,black);
                for(float y:new float[]{-.31f,.29f})cylinder(node,"tank-brace",0,y,0,.34f,.085f,metal);symbol(node,style,0,-.16f,.345f,.36f);}
            case REPAIR -> {box(node,"repair-case",0,0,0,.48f,.3f,.24f,metal);
                box(node,"case-edge",0,0,.255f,.44f,.265f,.025f,black);symbol(node,style,-.22f,-.22f,.285f,.44f);
                box(node,"carry-handle",0,.4f,0,.22f,.055f,.09f,accent);}
            case TURBO -> {cylinder(node,"energy-shell",0,0,0,.30f,.86f,glass);
                for(float y:new float[]{-.43f,.43f})cylinder(node,"terminal-ring",0,y,0,.34f,.12f,metal);
                for(float x:new float[]{-.22f,.22f})box(node,"energy-rail",x,0,-.15f,.05f,.40f,.045f,accent);
                symbol(node,style,-.17f,-.25f,.32f,.4f);}
        }
        node.depthFirstTraversal(s->{if(s instanceof Geometry g&&g.getMaterial().getParam("NormalMap")!=null)
            com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(g.getMesh());});
        node.updateGeometricState();GeometryBatchFactory.optimize(node,false);
        // Presentation positions the visual centre 1.1m above the pad. Recenter exported
        // contents, not the model root whose transform is owned by the pickup animation.
        node.updateGeometricState();Vector3f centre=node.getWorldBound().getCenter().clone();
        for(Spatial child:node.getChildren())child.move(centre.negate());
        node.updateGeometricState();
        node.setUserData("assetOrigin","original-java-procedural");node.setUserData("pickupKind",style.kind());
        return node;
    }
    private void rocket(Node node,float x,float z,float radius,float height) {
        cylinder(node,"rocket-body",x,0,z,radius,height,metal);
        Geometry tip=new Geometry("rocket-nose",new Cylinder(2,12,radius,.005f,height*.35f,true,false));
        tip.rotate(FastMath.HALF_PI,0,0);tip.setLocalTranslation(x,height*.65f,z);tip.setMaterial(accent);node.attachChild(tip);
        for(int i=0;i<4;i++) {Node fin=new Node("stabilizer");fin.setLocalTranslation(x,-height*.36f,z);fin.rotate(0,FastMath.HALF_PI*i,0);
            box(fin,"fin",radius*1.05f,0,0,radius*.65f,height*.15f,.025f,accent);node.attachChild(fin);}
        cylinder(node,"rocket-band",x,height*.25f,z,radius*1.05f,.08f,black);
    }
    private void symbol(Node node,PickupStyle style,float x,float y,float z,float scale) {
        Geometry symbol=new Geometry("weapon-symbol",new VectorIcons().mesh(style.icon()));symbol.setMaterial(accent);
        symbol.setLocalTranslation(x,y,z);symbol.setLocalScale(scale);node.attachChild(symbol);
    }
    private void box(Node node,String id,float x,float y,float z,float hx,float hy,float hz,Material material) {
        Geometry geometry=new Geometry(id,SurfaceMesh.box(hx,hy,hz,1));geometry.setLocalTranslation(x,y,z);geometry.setMaterial(material);node.attachChild(geometry);
    }
    private void cylinder(Node node,String id,float x,float y,float z,float radius,float height,Material material) {
        Geometry geometry=new Geometry(id,new Cylinder(2,16,radius,height,true));geometry.rotate(FastMath.HALF_PI,0,0);
        geometry.setLocalTranslation(x,y,z);geometry.setMaterial(material);node.attachChild(geometry);
    }
    private void sphere(Node node,String id,float x,float y,float z,float radius,Material material) {
        Geometry geometry=new Geometry(id,new Sphere(10,16,radius));geometry.setLocalTranslation(x,y,z);geometry.setMaterial(material);node.attachChild(geometry);
    }
}
